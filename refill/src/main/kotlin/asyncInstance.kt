package net.semlang.refill

import java.lang.Exception
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/*

Basic design thoughts:

1) Two input interfaces here: One is to get the timestamp return values from the setters and pass them into the
getOutcome/getValue calls, which will return something at least as recent as that timestamp. The other is to add a
listener that will get called with any values generated for a given ValueId.
2) Ideally we make the setter calls not block on the whole getNextSteps() process and instead use a BlockingQueue or
something to queue up inputs for processing. That does make the use of timestamps a bit trickier, since that *does*
seem to require some centralization, and also means the timestamps from the raw instance maybe aren't suitable.
Maybe we use a different type of timestamp? Just a specialty object type or something? Make one in the setter and put
it in the queue alongside the input, so later things can correlate an external timestamp to an internal one?
3) Part of the benefit of managing the inputs in a queue at this level is that we can actually implement fairness this
way, just by requiring the getNextSteps() to clear out before submitting the next batch of input changes. This should
be configurable by the user: choose faster response to user actions vs. no starvation if there are constant inputs.
4) So what are the threads/runnables here actually going to look like?

- It would be nice if nothing were running when things are in a quiescent state. This implies that in that case, the
setters are going to need to submit a management job to the executor.
- The management job is what runs getNextSteps() and decides which steps to execute. The runnables we execute should
not only compute values and report their results, but make sure the management job gets resubmitted if it needs to be.
- All of this should work on a single-threaded executor while also taking advantage of multithreading the execute() calls
when more threads are available.

5) How will we track timestamps and doneness for the synchronous getters? Maybe there's some kind of latch or barrier
system per-value; clearly we want the call to be waiting. That wouldn't be part of the raw instance, so that's something
else the internal "execute something" job is going to need to do.

6) We really ought to include a limit on how long to wait.

 */

// This is probably not a great name, but needs to be public API
// TODO: Explain how this works, because it's unintuitive
class TrickleAsyncTimestamp

data class TimestampedInput(val inputs: List<TrickleInputChange>, val timestamp: TrickleAsyncTimestamp)

// TODO: Should we add a finalizer on this to shut down the executor when the instance itself gets GCed?
class TrickleAsyncInstance(private val instance: TrickleInstance, private val executor: ExecutorService) {
    // Use with care; note the use of synchronizedMap.
    // Keys should be added by the instantiator of the async timestamp.
    // Keys will be removed by garbage collection only.
    private val asyncToRawTimestampMap = Collections.synchronizedMap(WeakHashMap<TrickleAsyncTimestamp, CompletableFuture<Long>>())
    // Unguarded; BlockingQueue handles multithreaded access
    private val inputsQueue: BlockingQueue<TimestampedInput> = LinkedBlockingQueue<TimestampedInput>()

    /*
    So these two bits work in conjunction to ensure we run management jobs whenever we need to while making sure no more
    than one management job works at a time...

    When we run a management job, we do the following:
    1) Flip the "should run management" boolean to true
    2) Try grabbing the lock; end if we fail
    If we succeed at grabbing the lock:
    3) Flip the "should run management" boolean to false
    4) Run management
    5) Release the lock
    6) If the "should run management" boolean is true, go to 2

    This ensures that for each job, either 1) we run the management job ourselves (when the lock is available), or 2)
    the job that does have the lock either will see that it needs to rerun in step 6, or hasn't run step 3 yet and therefore
    will run management after the set of changes we care about have been made.

    This lets us enqueue management jobs whenever we want without thinking about optimizations on the caller's part.
     */
    private val shouldRunManagement = AtomicBoolean(false)
    private val managementJobLock = ReentrantLock()

    // This is guarded by the management job lock; it should only be used by the management job
    private val enqueuedJobTasks = HashMap<TrickleStep, Future<*>>()

    // Unguarded; operations are synchronized
    private val timestampBarriers = TimestampBarrierMultimap()

    // Listeners
    // TODO: Guard
    private val basicListeners = HashMap<NodeName<*>, ArrayList<TrickleEventListener<*>>>()
    private val keyListListeners = HashMap<KeyListNodeName<*>, ArrayList<TrickleEventListener<*>>>()
    private val fullKeyedListListeners = HashMap<KeyedNodeName<*, *>, ArrayList<TrickleEventListener<*>>>()
    private val keyedValueListeners = HashMap<KeyedNodeName<*, *>, ArrayList<TrickleEventListener<*>>>()

    init {
        instance.setValueListener { event ->
            val valueId = event.valueId
            when (valueId) {
                is ValueId.Nonkeyed -> {
                    synchronized(basicListeners) {
                        for (listener in basicListeners[valueId.nodeName] ?: listOf<TrickleEventListener<*>>()) {
                            executor.submit {
                                (listener as TrickleEventListener<Any?>).receive(event as TrickleEvent<Any?>)
                            }
                        }
                    }
                }
                is ValueId.FullKeyList -> {
                    synchronized(keyListListeners) {
                        for (listener in keyListListeners[valueId.nodeName] ?: listOf<TrickleEventListener<*>>()) {
                            executor.submit {
                                (listener as TrickleEventListener<Any?>).receive(event as TrickleEvent<Any?>)
                            }
                        }
                    }
                }
                is ValueId.KeyListKey -> {
                    // Do nothing
                }
                is ValueId.Keyed -> {
                    synchronized(keyedValueListeners) {
                        for (listener in keyedValueListeners[valueId.nodeName] ?: listOf<TrickleEventListener<*>>()) {
                            executor.submit {
                                (listener as TrickleEventListener<Any?>).receive(event as TrickleEvent<Any?>)
                            }
                        }
                    }
                }
                is ValueId.FullKeyedList -> {
                    synchronized(fullKeyedListListeners) {
                        for (listener in fullKeyedListListeners[valueId.nodeName] ?: listOf<TrickleEventListener<*>>()) {
                            executor.submit {
                                (listener as TrickleEventListener<Any?>).receive(event as TrickleEvent<Any?>)
                            }
                        }
                    }
                }
            }
        }
        // Run initial management before users can start asking for values
        getManagementJob().run()
    }

    /*
When do we want to trigger the management job?
Current resolution: Trigger frequently, collapse redundant jobs
At some point, we may want to improve how this handles for single-threaded executors, or just have some different model for that
     */
    private fun getManagementJob(): Runnable {
        return Runnable runnable@{
/*
            Coordination between management jobs:
            1) Flip the "should run management" boolean to true
            2) Try grabbing the lock; end if we fail
                If we succeed at grabbing the lock:
            3) Flip the "should run management" boolean to false
            4) Run management
            5) Release the lock
            6) If the "should run management" boolean is true, go to 2
 */

            shouldRunManagement.set(true)
            while (true) {
                val acquiredLock = managementJobLock.tryLock()
                if (!acquiredLock) {
                    return@runnable
                }
                try {
                    shouldRunManagement.set(false)

                    runManagement()
                } finally {
                    managementJobLock.unlock()
                }
                if (!shouldRunManagement.get()) {
                    return@runnable
                }
            }
        }
    }

    private fun runManagement() {
        // TODO: This needs to take things from the queue, pass them to the instance, and track their timestamps

        // TODO:
        val inputsToAdd = ArrayList<TimestampedInput>()
        inputsQueue.drainTo(inputsToAdd)
        // TODO: Try just doing separate timestamps for the separate groups

        // Note: We do this instead of flattening into a single set of inputs to deal with some issues around event
        // timestamps that could otherwise make the listener approach less reliable
        val inputRawTimestamps = ArrayList<Long>()
        for (input in inputsToAdd) {
            val newRawTimestamp = instance.setInputs(input.inputs)
            asyncToRawTimestampMap[input.timestamp]!!.complete(newRawTimestamp)
            inputRawTimestamps.add(newRawTimestamp)
        }

        val nextSteps = instance.getNextSteps()

        // Note: It may be unintuitive that we have to wait until after getNextSteps() to unlock these, but when we're
        // checking a keyed input that is absent that is related to a present key, we rely on getNextSteps() to populate
        // the TrickleFailure that says it's absent but expected. That in turn uses a FullKeyedList ValueId (for some
        // reason I forget at the moment) that can get triggered for the given timestamp by an unrelated input.
        // Also, querying your instance for an input value is not a great look to begin with.
        for ((inputList, rawTimestamp) in inputsToAdd.zip(inputRawTimestamps)) {
            for (input in inputList.inputs) {
                updateInputTimestampBarrier(input, rawTimestamp)
            }
        }

        // In some cases, getNextSteps() will cause the timestamps for certain ValueIds to advance, so we need to check
        // all the ones we're waiting on
        for ((valueId, barrier) in timestampBarriers.getAll()) {
            val curTimestamp = instance.getLatestTimestampWithValue(valueId)
            if (curTimestamp >= barrier.minTimestampWanted) {
                barrier.latch.countDown()
                timestampBarriers.remove(valueId, barrier)
            }
        }

        val alreadyEnqueuedSteps = enqueuedJobTasks.keys.toSet()
        for (step in nextSteps) {
            if (!alreadyEnqueuedSteps.contains(step)) {
                val future = executor.submit(getExecuteJob(step))
                enqueuedJobTasks[step] = future
            }
        }
        // TODO: What if something is getting removed? I guess the easy way is just to have management do the removal
        // TODO: Also cancel tasks that are no longer in nextSteps
        val nextStepsSet = nextSteps.toSet()
        for (step in alreadyEnqueuedSteps) {
            if (!nextStepsSet.contains(step)) {
                enqueuedJobTasks[step]!!.cancel(true)
                enqueuedJobTasks.remove(step)
            }
        }

        // TODO: This needs to make runnables for those steps and pass them to the executor (done)
    }

    private fun updateInputTimestampBarrier(input: TrickleInputChange, newTimestamp: Long) {
        // TODO: Is this done elsewhere? Should this be a utility method on TIC?
        val valueIds = when (input) {
            is TrickleInputChange.SetBasic<*> -> listOf(ValueId.Nonkeyed(input.nodeName))
            // TODO: Can/should we also update ValueId.KeyListKey values? SetKeys doesn't list keys that would get removed...
            is TrickleInputChange.SetKeys<*> -> listOf(ValueId.FullKeyList(input.nodeName))
            is TrickleInputChange.EditKeys<*> -> listOf(ValueId.FullKeyList(input.nodeName))
            is TrickleInputChange.SetKeyed<*, *> -> input.map.keys.map { ValueId.Keyed(input.nodeName, it) } +
                    listOf(ValueId.FullKeyedList(input.nodeName))
        }
        for (valueId in valueIds) {
            unblockWaitingTimestampBarriers(valueId, newTimestamp)
        }
    }

    private fun unblockWaitingTimestampBarriers(valueId: ValueId, newTimestamp: Long) {
        for (barrier in timestampBarriers.get(valueId)) {
            if (barrier.minTimestampWanted <= newTimestamp) {
                barrier.latch.countDown()
                timestampBarriers.remove(valueId, barrier)
            }
        }
    }

    private fun getExecuteJob(step: TrickleStep): Runnable {
        return Runnable {
            val result = step.execute()

            instance.reportResult(result)
            unblockWaitingTimestampBarriers(result.valueId, result.timestamp)

            // Unblock things waiting on this result at this timestamp
            /*
            TODO: Design flaw here...
            There are certain cases (e.g. failure outcomes where something upstream threw) where timestamps update during
            getNextSteps(), and we'll need to check those somehow
            One approach is to just iterate through the listeners in the management thread after we run getNextSteps()
            and look at the relevant ValueIds
             */

            // Trigger the management job in case new steps are available
            enqueueManagementJob()
        }
    }


    fun setInputs(changes: List<TrickleInputChange>): TrickleAsyncTimestamp {
        for (change in changes) {
            if (!(change.nodeName is NodeName<*> && instance.definition.nonkeyedNodes.containsKey(change.nodeName as NodeName<*>))
                    && !(change.nodeName is KeyListNodeName<*> && instance.definition.keyListNodes.containsKey(change.nodeName as KeyListNodeName<*>))
                    && !(change.nodeName is KeyedNodeName<*, *> && instance.definition.keyedNodes.containsKey(change.nodeName as KeyedNodeName<*, *>))) {
                throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
            }
        }
        val timestamp = TrickleAsyncTimestamp()
        asyncToRawTimestampMap[timestamp] = CompletableFuture()
        inputsQueue.put(TimestampedInput(changes, timestamp))
        enqueueManagementJob()
        return timestamp
    }

    private fun enqueueManagementJob() {
        executor.submit(getManagementJob())
    }

    fun <T> setInput(nodeName: NodeName<T>, value: T): TrickleAsyncTimestamp {
        return setInputs(listOf(TrickleInputChange.SetBasic(nodeName, value)))
    }

    fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): TrickleAsyncTimestamp {
        return setInputs(listOf(TrickleInputChange.SetKeys(nodeName, list)))
    }

    fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): TrickleAsyncTimestamp {
        return setInputs(listOf(TrickleInputChange.EditKeys(nodeName, listOf(key), listOf())))
    }

    fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): TrickleAsyncTimestamp {
        return setInputs(listOf(TrickleInputChange.EditKeys(nodeName, listOf(), listOf(key))))
    }

    fun <T> editKeys(nodeName: KeyListNodeName<T>, keysAdded: List<T>, keysRemoved: List<T>): TrickleAsyncTimestamp {
        return setInputs(listOf(TrickleInputChange.EditKeys(nodeName, keysAdded, keysRemoved)))
    }

    fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): TrickleAsyncTimestamp {
        return setInputs(listOf(TrickleInputChange.SetKeyed(nodeName, mapOf(key to value))))
    }

    fun <K, T> setKeyedInputs(nodeName: KeyedNodeName<K, T>, map: Map<K, T>): TrickleAsyncTimestamp {
        return setInputs(listOf(TrickleInputChange.SetKeyed(nodeName, map)))
    }

    // TODO: Add a variant that waits a limited amount of time
    // TODO: We actually want to accept a variable number of these timestamps
    fun <T> getOutcome(name: NodeName<T>, vararg minTimestamps: TrickleAsyncTimestamp): NodeOutcome<T> {
        if (!instance.definition.nonkeyedNodes.containsKey(name)) {
            throw IllegalArgumentException("Unrecognized node name $name")
        }
        waitForTimestamp(ValueId.Nonkeyed(name), minTimestamps.asList())

        return instance.getNodeOutcome(name)
    }

    fun <T> getOutcome(name: NodeName<T>, timeToWait: Long, timeUnits: TimeUnit, vararg minTimestamps: TrickleAsyncTimestamp): NodeOutcome<T> {
        if (!instance.definition.nonkeyedNodes.containsKey(name)) {
            throw IllegalArgumentException("Unrecognized node name $name")
        }
        waitForTimestamp(ValueId.Nonkeyed(name), minTimestamps.asList(), timeToWait, timeUnits)

        return instance.getNodeOutcome(name)
    }

    private fun waitForTimestamp(valueId: ValueId, minTimestamps: Collection<TrickleAsyncTimestamp>) {
        waitForTimestamp(valueId, minTimestamps, Long.MAX_VALUE, TimeUnit.MILLISECONDS)
    }

    private fun waitForTimestamp(initialValueId: ValueId, minTimestamps: Collection<TrickleAsyncTimestamp>, timeToWait: Long, timeUnits: TimeUnit) {
        val millisToWait = timeUnits.toMillis(timeToWait)
        val startTime = System.currentTimeMillis()

        // TODO: The new problem here is that we might be waiting on a value ID for a keyed value (or, in theory, a key list value?)
        // for which the key doesn't exist. Ideally we'd wait on both the keyed value and the full-key value and accept
        // either as a prompt to continue.
        // TODO: Can we find a way to not have to wait?
        // I think we'd have to first wait for the key list timestamp, then check the key list, then use that to figure
        // out which ValueId to wait for.
        // For now, try the simpler version of just waiting for the full keyed list.

        // TODO: Problem here!!! Real problem here. Apparently.
        val valueId = when (initialValueId) {
            is ValueId.Nonkeyed -> initialValueId
            is ValueId.FullKeyList -> initialValueId
            is ValueId.KeyListKey -> ValueId.FullKeyList(initialValueId.nodeName)
            is ValueId.Keyed -> ValueId.FullKeyedList(initialValueId.nodeName)
            is ValueId.FullKeyedList -> initialValueId
        }

        // We always need to wait until at least time 0, for calculations based on empty key lists
        var rawMinTimestamp = 0L
        for (minTimestamp in minTimestamps) {
            val curRawMin = asyncToRawTimestampMap[minTimestamp]!!.get(millisToWait - (System.currentTimeMillis() - startTime), TimeUnit.MILLISECONDS)
            if (curRawMin > rawMinTimestamp) {
                rawMinTimestamp = curRawMin
            }
        }

        /*
        TODO: This whole approach is inconsistent with how the raw instance uses its timestamps and will result in hanging

        One hack to work around this for now is to find all the input nodes upstream of the thing and look at their timestamps,
        getting the most recent versions I guess, or this rawMinTimestamp if it's smaller... but that's still unsatisfying
        in the case where that causes us to wait longer than necessary

        What does the ideal solution for this actually look like? When do we associate timestamp-for-one-input to timestamps
        across all inputs? When a new timestamp is added to one part of the graph, when and how do we associate the "current"
        values elsewhere in the graph to say they're also up-to-date with respect to that new timestamp?



        Okay, so let's say we have the getNextSteps() function be responsible for propagating along the graph the values
        of timestamps that are actually relevant to a given location.

        Value id of what we query -> list of timestamps that are actually relevant

        With some pruning mechanism for sufficiently old timestamps.

        So then we want to get a value of some node at a given timestamp...

        - If the timestamp is in the list for that node, wait for that exact timestamp
        - If the timestamp is not in the list but some greater timestamp is, we wait for the timestamp preceding the
          greater one
        - If the timestamp is not in the list and no greater timestamps are in the list... well, that could mean multiple
          things and we sort of have to sort them out by ironing out the details of when this list is populated and what
          it means

        In particular, we may want to associate this timestamp list with an additional value, that of the latest timestamp
        that was known when the list was made. In that case, we can differentiate between "the timestamp is newer than the
        list" and "use the last value in the list though it's lower than the value you're asking for". But this also
        depends on how the list is synchronized; it's possible that the former case wouldn't come up at all if the
        synchronization is held for both the creation of timestamps and the subsequent updating of lists.

        So what's the other thread that's going to be in contention with getNextSteps() (run by the management thread)?
        That would be here. I guess one way to think about this is to ask not "what's the current timestamp of this value?",
        but "what's the latest timestamp that this value is up-to-date for?" That makes sense for the original question
        and could be tracked as easily as the other thing. But I guess we could also phrase the question as "what timestamps
        do we need to wait for between this value and ___?"

        The big issue is that the current approach to timestamps when we have to wait is to wait for the corresponding
        Result object, and that's not great when it only returns one timestamp-shaped thing. On the other hand, we could
        maybe arrange so that that triggers a new query about which timestamps we need to wait for (?).

         */

        if (instance.getLatestTimestampWithValue(valueId) < rawMinTimestamp) {
            // TODO: Do something to wait longer until the new min timestamp is satisfied
            // This wants to be a map where the keys are ValueIds, so when execute tasks finish they look for these barriers and update them
            // However, we also want the values to be per-getOutcome and not shared for simplicity, right? So a multimap, but an actual
            // proper one that removes its key when all associated values are removed
            // Though I'd rather not add Guava as a dependency...
            val timestampBarrier = timestampBarriers.add(valueId, rawMinTimestamp)
            // Now that it's been added, double-check before waiting, in case the timestamp was updated to a new value before the barrier
            // was created
            if (instance.getLatestTimestampWithValue(valueId) < rawMinTimestamp) {
                // General case, we need to wait on the barrier
                // TODO: Do we need to handle interruption here?
                val gotLatch = timestampBarrier.latch.await(millisToWait - (System.currentTimeMillis() - startTime), TimeUnit.MILLISECONDS)
                if (!gotLatch) {
                    throw TimeoutException()
                }
            } else {
                // Rare case, don't bother waiting
                timestampBarriers.remove(valueId, timestampBarrier)
            }
        }
    }

    fun <T> getOutcome(name: KeyListNodeName<T>, vararg minTimestamps: TrickleAsyncTimestamp): NodeOutcome<List<T>> {
        if (!instance.definition.keyListNodes.containsKey(name)) {
            throw IllegalArgumentException("Unrecognized node name $name")
        }
        waitForTimestamp(ValueId.FullKeyList(name), minTimestamps.asList())

        return instance.getNodeOutcome(name)
    }

    fun <T> getOutcome(name: KeyListNodeName<T>, timeToWait: Long, timeUnits: TimeUnit, vararg minTimestamps: TrickleAsyncTimestamp): NodeOutcome<List<T>> {
        if (!instance.definition.keyListNodes.containsKey(name)) {
            throw IllegalArgumentException("Unrecognized node name $name")
        }
        waitForTimestamp(ValueId.FullKeyList(name), minTimestamps.asList(), timeToWait, timeUnits)

        return instance.getNodeOutcome(name)
    }

    fun <K, T> getOutcome(name: KeyedNodeName<K, T>, vararg minTimestamps: TrickleAsyncTimestamp): NodeOutcome<List<T>> {
        if (!instance.definition.keyedNodes.containsKey(name)) {
            throw IllegalArgumentException("Unrecognized node name $name")
        }
        waitForTimestamp(ValueId.FullKeyedList(name), minTimestamps.asList())

        return instance.getNodeOutcome(name)
    }

    fun <K, T> getOutcome(name: KeyedNodeName<K, T>, timeToWait: Long, timeUnits: TimeUnit, vararg minTimestamps: TrickleAsyncTimestamp): NodeOutcome<List<T>> {
        if (!instance.definition.keyedNodes.containsKey(name)) {
            throw IllegalArgumentException("Unrecognized node name $name")
        }
        waitForTimestamp(ValueId.FullKeyedList(name), minTimestamps.asList(), timeToWait, timeUnits)

        return instance.getNodeOutcome(name)
    }

    fun <K, T> getOutcome(name: KeyedNodeName<K, T>, key: K, vararg minTimestamps: TrickleAsyncTimestamp): NodeOutcome<T> {
        if (!instance.definition.keyedNodes.containsKey(name)) {
            throw IllegalArgumentException("Unrecognized node name $name")
        }
        waitForTimestamp(ValueId.Keyed(name, key), minTimestamps.asList())

        return instance.getNodeOutcome(name, key)
    }

    fun <K, T> getOutcome(name: KeyedNodeName<K, T>, key: K, timeToWait: Long, timeUnits: TimeUnit, vararg minTimestamps: TrickleAsyncTimestamp): NodeOutcome<T> {
        if (!instance.definition.keyedNodes.containsKey(name)) {
            throw IllegalArgumentException("Unrecognized node name $name")
        }
        waitForTimestamp(ValueId.Keyed(name, key), minTimestamps.asList(), timeToWait, timeUnits)

        return instance.getNodeOutcome(name, key)
    }

    // TODO: Add some way to unsubscribe (?)
    // TODO: How do we make sure listeners run in order when that's important? Is that something the instance promises
    // to account for when calling listeners, or do we just pass timestamps and make the user take care of it?
    fun <T> addBasicListener(name: NodeName<T>, listener: TrickleEventListener<T>) {
        synchronized(basicListeners) {
            basicListeners.getOrPut(name, { ArrayList() }).add(listener)
        }
        // Also do the initial output with the current value
        executor.submit {
            val valueId = ValueId.Nonkeyed(name)
            val outcome = instance.getNodeOutcome(name) // Don't block
            when (outcome) {
                is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                is NodeOutcome.NoSuchKey -> TODO()
                is NodeOutcome.Computed -> listener.receive(TrickleEvent.Computed.of(valueId, outcome.value, -1L))
                is NodeOutcome.Failure -> listener.receive(TrickleEvent.Failure.of(valueId, outcome.failure, -1L))
            }
        }
    }
    fun <T> addKeyListListener(name: KeyListNodeName<T>, listener: TrickleEventListener<List<T>>) {
        synchronized(keyListListeners) {
            keyListListeners.getOrPut(name, { ArrayList() }).add(listener)
        }
        // Also do the initial output with the current value
        executor.submit {
            val valueId = ValueId.FullKeyList(name)
            val outcome = getOutcome(name, 0, TimeUnit.MILLISECONDS) // Don't block
            when (outcome) {
                is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                is NodeOutcome.NoSuchKey -> TODO()
                is NodeOutcome.Computed -> listener.receive(TrickleEvent.Computed.of(valueId, outcome.value, -1L))
                is NodeOutcome.Failure -> listener.receive(TrickleEvent.Failure.of(valueId, outcome.failure, -1L))
            }
        }
    }
    fun <K, T> addKeyedListListener(name: KeyedNodeName<K, T>, listener: TrickleEventListener<List<T>>) {
        synchronized(fullKeyedListListeners) {
            fullKeyedListListeners.getOrPut(name, { ArrayList() }).add(listener)
        }
        // Also do the initial output with the current value
        executor.submit {
            val valueId = ValueId.FullKeyedList(name)
            val outcome = getOutcome(name, 0, TimeUnit.MILLISECONDS) // Don't block
            when (outcome) {
                is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                is NodeOutcome.NoSuchKey -> TODO()
                is NodeOutcome.Computed -> listener.receive(TrickleEvent.Computed.of(valueId, outcome.value, -1L))
                is NodeOutcome.Failure -> listener.receive(TrickleEvent.Failure.of(valueId, outcome.failure, -1L))
            }
        }
    }
    fun <K, T> addPerKeyListener(name: KeyedNodeName<K, T>, listener: TrickleEventListener<T>) {
        synchronized(keyedValueListeners) {
            keyedValueListeners.getOrPut(name, { ArrayList() }).add(listener)
        }
        // Also do the initial outputs with the current values
        executor.submit {
            val keySourceName = instance.definition.keyedNodes[name]!!.keySourceName
            // Get the current values of the keys and the keyed values as a single operation on the raw instance
            synchronized(instance) {
                val keyListOutcome = instance.getNodeOutcome(keySourceName)
                if (keyListOutcome is NodeOutcome.Computed) {
                    for (key in keyListOutcome.value) {
                        val valueId = ValueId.Keyed(name, key as K)
                        val outcome = instance.getNodeOutcome(name, key)
                        when (outcome) {
                            is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                            is NodeOutcome.NoSuchKey -> TODO()
                            is NodeOutcome.Computed -> listener.receive(
                                TrickleEvent.Computed.of(
                                    valueId,
                                    outcome.value,
                                    -1L
                                )
                            )
                            is NodeOutcome.Failure -> listener.receive(
                                TrickleEvent.Failure.of(
                                    valueId,
                                    outcome.failure,
                                    -1L
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun shutdown() {
        // TODO: Any reason to do a soft shutdown instead?
        executor.shutdownNow()
    }
}


// Note: This should explicitly *not* implement hashCode/equals
class TimestampBarrier(val minTimestampWanted: Long) {
    val latch = CountDownLatch(1)
}

class TimestampBarrierMultimap {
    private val map = HashMap<ValueId, HashSet<TimestampBarrier>>()

    // TODO: Reconsider if this is the best way to guard all these properly
    @Synchronized
    fun add(valueId: ValueId, minTimestampWanted: Long): TimestampBarrier {
        val existingSet = map[valueId]
        val set = if (existingSet != null) {
            existingSet
        } else {
            val newSet = HashSet<TimestampBarrier>()
            map[valueId] = newSet
            newSet
        }
        val newBarrier = TimestampBarrier(minTimestampWanted)
        set.add(newBarrier)
        return newBarrier
    }

    @Synchronized
    fun remove(valueId: ValueId, barrier: TimestampBarrier) {
        val set = map[valueId]
        if (set != null) {
            set.remove(barrier)
            if (set.isEmpty()) {
                map.remove(valueId)
            }
        }
    }

    @Synchronized
    fun get(valueId: ValueId): Collection<TimestampBarrier> {
        return map[valueId]?.toList() ?: emptyList()
    }

    @Synchronized
    fun getAll(): Collection<Pair<ValueId, TimestampBarrier>> {
        return map.flatMap { (valueId, barriers) ->
            barriers.map { valueId to it }
        }
    }
}
