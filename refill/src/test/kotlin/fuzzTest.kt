package net.semlang.refill

import org.awaitility.Awaitility
import org.awaitility.Awaitility.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class TrickleFuzzTests {

    @Test
    fun specificTest1() {
        for (i in 0..100) {
            runSpecificTest(121, 0)
        }
        System.out.flush()
    }


    private fun runSpecificTest(definitionSeed: Int, operationsSeed: Int) {
        val definition = getFuzzedDefinition(definitionSeed)

        try {
            val descriptionBeforeGen = definition.toMultiLineString()
            val script = getOperationsScript(definition, operationsSeed)
            val descriptionAfterGen = definition.toMultiLineString()
            if (descriptionBeforeGen != descriptionAfterGen) {
                throw RuntimeException("The description changed; before:\n$descriptionBeforeGen\nAfter:\n$descriptionAfterGen")
            }

            try {
                checkReferenceInstance(ReferenceInstance(definition), script.operations)
                checkRawInstance1(definition.instantiateRaw(), script.operations)
                checkRawInstance2(definition.instantiateRaw(), script.operations)
                checkSyncInstance(definition.instantiateSync(), script.operations)
                checkAsyncInstance1(definition.instantiateAsync(Executors.newFixedThreadPool(4)), script.operations)
                checkAsyncInstance2(definition.instantiateAsync(Executors.newFixedThreadPool(4)), script.operations)
                checkAsyncInstanceWithListeners(definition.instantiateAsync(Executors.newFixedThreadPool(4)), script.operations)
                checkAsyncInstanceWithListeners2(definition.instantiateAsync(Executors.newFixedThreadPool(4)), script.operations)
                checkAsyncInstance1(definition.instantiateAsync(Executors.newFixedThreadPool(2)), script.operations)
                checkAsyncInstance2(definition.instantiateAsync(Executors.newFixedThreadPool(2)), script.operations)
                checkAsyncInstanceWithListeners(definition.instantiateAsync(Executors.newFixedThreadPool(2)), script.operations)
                checkAsyncInstanceWithListeners2(definition.instantiateAsync(Executors.newFixedThreadPool(2)), script.operations)
                checkAsyncInstance1(definition.instantiateAsync(Executors.newSingleThreadExecutor()), script.operations)
                checkAsyncInstance2(definition.instantiateAsync(Executors.newSingleThreadExecutor()), script.operations)
                checkAsyncInstanceWithListeners(definition.instantiateAsync(Executors.newSingleThreadExecutor()), script.operations)
                checkAsyncInstanceWithListeners2(definition.instantiateAsync(Executors.newSingleThreadExecutor()), script.operations)


                // TODO: Maybe multiple rounds of this?
                // TODO: Document this as a principle of the design
                val inputsReorderedOperations = reorderInputs(script.operations, definition, Random(0L))
                try {
                    checkReferenceInstance(ReferenceInstance(definition), inputsReorderedOperations)
                    checkRawInstance1(definition.instantiateRaw(), inputsReorderedOperations)
                    checkRawInstance2(definition.instantiateRaw(), inputsReorderedOperations)
                    checkSyncInstance(definition.instantiateSync(), inputsReorderedOperations)
                } catch (t: Throwable) {
                    throw RuntimeException("Reordered operations script: \n${inputsReorderedOperations.withIndex().joinToString("\n")}", t)
                }
            } catch (t: Throwable) {
                System.out.flush()
                throw RuntimeException(
                    "Operations script: \n${script.operations.withIndex().joinToString("\n")}",
                    t
                )
            }
        } catch (t: Throwable) {
            throw RuntimeException("Definition:\n" + definition.toMultiLineString(), t)
        }
    }

    @Test
    fun runFuzzTests() {
        for (definitionSeed in 0..99) {
            val definition = getFuzzedDefinition(definitionSeed)

            try {
                for (operationsSeed in 0..4) {
                    try {
                        val descriptionBeforeGen = definition.toMultiLineString()
                        val script = getOperationsScript(definition, operationsSeed)
                        val descriptionAfterGen = definition.toMultiLineString()
                        if (descriptionBeforeGen != descriptionAfterGen) {
                            throw RuntimeException("The description changed; before:\n$descriptionBeforeGen\nAfter:\n$descriptionAfterGen")
                        }

                        try {
                            checkReferenceInstance(ReferenceInstance(definition), script.operations)
                            checkRawInstance1(definition.instantiateRaw(), script.operations)
                            checkRawInstance2(definition.instantiateRaw(), script.operations)
                            checkSyncInstance(definition.instantiateSync(), script.operations)
                            checkAsyncInstance1(definition.instantiateAsync(Executors.newFixedThreadPool(4)), script.operations)
                            checkAsyncInstance2(definition.instantiateAsync(Executors.newFixedThreadPool(4)), script.operations)
                            checkAsyncInstanceWithListeners(definition.instantiateAsync(Executors.newFixedThreadPool(4)), script.operations)
                            checkAsyncInstanceWithListeners2(definition.instantiateAsync(Executors.newFixedThreadPool(4)), script.operations)
                            checkAsyncInstance1(definition.instantiateAsync(Executors.newFixedThreadPool(2)), script.operations)
                            checkAsyncInstance2(definition.instantiateAsync(Executors.newFixedThreadPool(2)), script.operations)
                            checkAsyncInstanceWithListeners(definition.instantiateAsync(Executors.newFixedThreadPool(2)), script.operations)
                            checkAsyncInstanceWithListeners2(definition.instantiateAsync(Executors.newFixedThreadPool(2)), script.operations)
                            checkAsyncInstance1(definition.instantiateAsync(Executors.newSingleThreadExecutor()), script.operations)
                            checkAsyncInstance2(definition.instantiateAsync(Executors.newSingleThreadExecutor()), script.operations)
                            checkAsyncInstanceWithListeners(definition.instantiateAsync(Executors.newSingleThreadExecutor()), script.operations)
                            checkAsyncInstanceWithListeners2(definition.instantiateAsync(Executors.newSingleThreadExecutor()), script.operations)

                            // TODO: Maybe multiple rounds of this?
                            // TODO: Document this as a principle of the design
                            val inputsReorderedOperations = reorderInputs(script.operations, definition, Random(0L))
                            try {
                                checkReferenceInstance(ReferenceInstance(definition), inputsReorderedOperations)
                                checkRawInstance1(definition.instantiateRaw(), inputsReorderedOperations)
                                checkRawInstance2(definition.instantiateRaw(), inputsReorderedOperations)
                                checkSyncInstance(definition.instantiateSync(), inputsReorderedOperations)
                            } catch (t: Throwable) {
                                throw RuntimeException("Reordered operations script: \n${inputsReorderedOperations.withIndex().joinToString("\n")}", t)
                            }
                        } catch (t: Throwable) {
                            throw RuntimeException(
                                "Operations script: \n${script.operations.withIndex().joinToString("\n")}",
                                t
                            )
                        }
                    } catch (t: Throwable) {
                        throw RuntimeException(
                            "Error with definitionSeed $definitionSeed and operationsSeed $operationsSeed",
                            t
                        )
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("Definition:\n" + definition.toMultiLineString(), t)
            }
        }
    }

    private fun reorderInputs(script: List<FuzzOperation>, definition: TrickleDefinition, random: Random): List<FuzzOperation> {
        val reordered = ArrayList<FuzzOperation>()

        val curGroup = ArrayList<TrickleInputChange>()
        fun flushCurGroup() {
            if (curGroup.isEmpty()) {
                return
            }
            // We want to preserve the order within each NodeName, but shuffle things otherwise
            // Slight caveat: Group keyed inputs with their key source key input
            fun getRelevantNodeName(change: TrickleInputChange): GenericNodeName {
                if (change is TrickleInputChange.SetKeyed<*, *>) {
                    return definition.keyedNodes[change.nodeName]!!.keySourceName
                }
                return change.nodeName
            }
            val namesOnlyOrdering = curGroup.map(::getRelevantNodeName)
            val perNameOrdering = curGroup.groupBy(::getRelevantNodeName)
            val newNamesOrdering = namesOnlyOrdering.shuffled(random)
            val perNameIterators = perNameOrdering.mapValues { (_, list) -> list.iterator() }
            val newChangeOrdering = ArrayList<TrickleInputChange>()
            for (name in newNamesOrdering) {
                newChangeOrdering.add(perNameIterators.getValue(name).next())
            }

            if (random.nextBoolean()) {
                // Add as separate inputs
                for (change in newChangeOrdering) {
                    reordered.add(toOperation(change))
                }
            } else {
                // Add as a single input
                reordered.add(FuzzOperation.SetMultiple(newChangeOrdering))
            }

            curGroup.clear()
        }
        for (operation in script) {
            val unused: Any = when (operation) {
                is FuzzOperation.SetBasic -> curGroup.add(TrickleInputChange.SetBasic(operation.name, operation.value))
                is FuzzOperation.AddKey -> curGroup.add(TrickleInputChange.EditKeys(operation.name, listOf(operation.key), listOf()))
                is FuzzOperation.RemoveKey -> curGroup.add(TrickleInputChange.EditKeys(operation.name, listOf(), listOf(operation.key)))
                is FuzzOperation.SetKeyList -> curGroup.add(TrickleInputChange.SetKeys(operation.name, operation.value))
                is FuzzOperation.EditKeys -> curGroup.add(TrickleInputChange.EditKeys(operation.name, operation.keysAdded, operation.keysRemoved))
                is FuzzOperation.SetKeyed -> curGroup.add(TrickleInputChange.SetKeyed(operation.name, operation.map))
                is FuzzOperation.SetMultiple -> curGroup.addAll(operation.changes)
                is FuzzOperation.CheckBasic -> {
                    flushCurGroup()
                    reordered.add(operation)
                }
                is FuzzOperation.CheckKeyList -> {
                    flushCurGroup()
                    reordered.add(operation)
                }
                is FuzzOperation.CheckKeyedList -> {
                    flushCurGroup()
                    reordered.add(operation)
                }
                is FuzzOperation.CheckKeyedValue -> {
                    flushCurGroup()
                    reordered.add(operation)
                }
            }
        }
        flushCurGroup()
        return reordered
    }

    private fun checkReferenceInstance(instance: ReferenceInstance, operations: List<FuzzOperation>) {
        for ((opIndex, op) in operations.withIndex()) {
            try {
                val unused: Any = when (op) {
                    is FuzzOperation.SetBasic -> {
                        instance.setInput(op.name, op.value)
                    }
                    is FuzzOperation.AddKey -> {
                        instance.addKeyInput(op.name, op.key)
                    }
                    is FuzzOperation.RemoveKey -> {
                        instance.removeKeyInput(op.name, op.key)
                    }
                    is FuzzOperation.SetKeyList -> {
                        instance.setInput(op.name, op.value)
                    }
                    is FuzzOperation.EditKeys -> {
                        instance.editKeys(op.name, op.keysAdded, op.keysRemoved)
                    }
                    is FuzzOperation.SetKeyed -> {
                        instance.setKeyedInputs(op.name, op.map)
                    }
                    is FuzzOperation.SetMultiple -> {
                        instance.setInputs(op.changes)
                    }
                    is FuzzOperation.CheckBasic -> {
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyList -> {
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedList -> {
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedValue -> {
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name, op.key))
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("Failed on operation #$opIndex: #$op", t)
            }
        }
    }

    private fun checkRawInstance1(instance: TrickleInstance, operations: List<FuzzOperation>) {
        // Handle cases where a node we query depends only on key list inputs
        instance.completeSynchronously()
        for ((opIndex, op) in operations.withIndex()) {
            try {
                val unused: Any = when (op) {
                    is FuzzOperation.SetBasic -> {
                        instance.setInput(op.name, op.value)
                        instance.completeSynchronously()
                    }
                    is FuzzOperation.AddKey -> {
                        instance.addKeyInput(op.name, op.key)
                        instance.completeSynchronously()
                    }
                    is FuzzOperation.RemoveKey -> {
                        instance.removeKeyInput(op.name, op.key)
                        instance.completeSynchronously()
                    }
                    is FuzzOperation.SetKeyList -> {
                        instance.setInput(op.name, op.value)
                        instance.completeSynchronously()
                    }
                    is FuzzOperation.EditKeys -> {
                        instance.editKeys(op.name, op.keysAdded, op.keysRemoved)
                        instance.completeSynchronously()
                    }
                    is FuzzOperation.SetKeyed -> {
                        instance.setKeyedInputs(op.name, op.map)
                        instance.completeSynchronously()
                    }
                    is FuzzOperation.SetMultiple -> {
                        instance.setInputs(op.changes)
                        instance.completeSynchronously()
                    }
                    is FuzzOperation.CheckBasic -> {
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyList -> {
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedList -> {
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedValue -> {
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name, op.key))
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("Failed on operation #$opIndex: #$op", t)
            }
        }
    }

    private fun checkRawInstance2(instance: TrickleInstance, operations: List<FuzzOperation>) {
        for ((opIndex, op) in operations.withIndex()) {
            try {
                val unused: Any = when (op) {
                    is FuzzOperation.SetBasic -> {
                        instance.setInput(op.name, op.value)
                    }
                    is FuzzOperation.AddKey -> {
                        instance.addKeyInput(op.name, op.key)
                    }
                    is FuzzOperation.RemoveKey -> {
                        instance.removeKeyInput(op.name, op.key)
                    }
                    is FuzzOperation.SetKeyList -> {
                        instance.setInput(op.name, op.value)
                    }
                    is FuzzOperation.EditKeys -> {
                        instance.editKeys(op.name, op.keysAdded, op.keysRemoved)
                    }
                    is FuzzOperation.SetKeyed -> {
                        instance.setKeyedInputs(op.name, op.map)
                    }
                    is FuzzOperation.SetMultiple -> {
                        instance.setInputs(op.changes)
                    }
                    is FuzzOperation.CheckBasic -> {
                        instance.completeSynchronously()
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyList -> {
                        instance.completeSynchronously()
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedList -> {
                        instance.completeSynchronously()
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedValue -> {
                        instance.completeSynchronously()
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name, op.key))
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("Failed on operation #$opIndex: #$op", t)
            }
        }
    }

    private fun checkSyncInstance(instance: TrickleSyncInstance, operations: List<FuzzOperation>) {
        for ((opIndex, op) in operations.withIndex()) {
            try {
                val unused: Any = when (op) {
                    is FuzzOperation.SetBasic -> {
                        instance.setInput(op.name, op.value)
                    }
                    is FuzzOperation.AddKey -> {
                        instance.addKeyInput(op.name, op.key)
                    }
                    is FuzzOperation.RemoveKey -> {
                        instance.removeKeyInput(op.name, op.key)
                    }
                    is FuzzOperation.SetKeyList -> {
                        instance.setInput(op.name, op.value)
                    }
                    is FuzzOperation.EditKeys -> {
                        instance.editKeys(op.name, op.keysAdded, op.keysRemoved)
                    }
                    is FuzzOperation.SetKeyed -> {
                        instance.setKeyedInputs(op.name, op.map)
                    }
                    is FuzzOperation.SetMultiple -> {
                        instance.setInputs(op.changes)
                    }
                    is FuzzOperation.CheckBasic -> {
                        assertEquals(op.outcome, instance.getOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyList -> {
                        assertEquals(op.outcome, instance.getOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedList -> {
                        assertEquals(op.outcome, instance.getOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedValue -> {
                        assertEquals(op.outcome, instance.getOutcome(op.name, op.key))
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("Failed on operation #$opIndex: #$op", t)
            }
        }
    }


    private fun checkAsyncInstance1(instance: TrickleAsyncInstance, operations: List<FuzzOperation>) {
        // TODO: I think this approach just barely works because we're submitting everything to the queue from the same
        // thread and the queue preserves order so that the timestamps end up "later". It may be better to be able to
        // submit a set or list of timestamps and require that it be after all of them.
        // TODO: Revive the "single-timestamp" type as well
//        var lastTimestamp: TrickleAsyncTimestamp? = null
        try {
            val timestamps = ArrayList<TrickleAsyncTimestamp>()
            for ((opIndex, op) in operations.withIndex()) {
                try {
                    val unused: Any = when (op) {
                        is FuzzOperation.SetBasic -> {
                            timestamps.add(instance.setInput(op.name, op.value))
                        }
                        is FuzzOperation.AddKey -> {
                            timestamps.add(instance.addKeyInput(op.name, op.key))
                        }
                        is FuzzOperation.RemoveKey -> {
                            timestamps.add(instance.removeKeyInput(op.name, op.key))
                        }
                        is FuzzOperation.SetKeyList -> {
                            timestamps.add(instance.setInput(op.name, op.value))
                        }
                        is FuzzOperation.EditKeys -> {
                            timestamps.add(instance.editKeys(op.name, op.keysAdded, op.keysRemoved))
                        }
                        is FuzzOperation.SetKeyed -> {
                            timestamps.add(instance.setKeyedInputs(op.name, op.map))
                        }
                        is FuzzOperation.SetMultiple -> {
                            timestamps.add(instance.setInputs(op.changes))
                        }
                        is FuzzOperation.CheckBasic -> {
                            assertEquals(op.outcome, instance.getOutcome(op.name, 10L, TimeUnit.SECONDS, *timestamps.toTypedArray()))
                        }
                        is FuzzOperation.CheckKeyList -> {
                            assertEquals(op.outcome, instance.getOutcome(op.name, 10L, TimeUnit.SECONDS, *timestamps.toTypedArray()))
                        }
                        is FuzzOperation.CheckKeyedList -> {
                            assertEquals(op.outcome, instance.getOutcome(op.name, 10L, TimeUnit.SECONDS, *timestamps.toTypedArray()))
                        }
                        is FuzzOperation.CheckKeyedValue -> {
                            assertEquals(op.outcome, instance.getOutcome(op.name, op.key, 10L, TimeUnit.SECONDS, *timestamps.toTypedArray()))
                        }
                    }
                } catch (t: Throwable) {
                    throw RuntimeException("Failed on operation #$opIndex: #$op", t)
                }
            }
        } finally {
            instance.shutdown()
        }
    }


    private fun checkAsyncInstance2(instance: TrickleAsyncInstance, operations: List<FuzzOperation>) {
        // TODO: I think this approach just barely works because we're submitting everything to the queue from the same
        // thread and the queue preserves order so that the timestamps end up "later". It may be better to be able to
        // submit a set or list of timestamps and require that it be after all of them.
        // TODO: Revive the "single-timestamp" type as well
//        var lastTimestamp: TrickleAsyncTimestamp? = null
        try {
            var timestamp: TrickleAsyncTimestamp? = null
            for ((opIndex, op) in operations.withIndex()) {
                try {
                    val unused: Any = when (op) {
                        is FuzzOperation.SetBasic -> {
                            timestamp = instance.setInput(op.name, op.value)
                        }
                        is FuzzOperation.AddKey -> {
                            timestamp = instance.addKeyInput(op.name, op.key)
                        }
                        is FuzzOperation.RemoveKey -> {
                            timestamp = instance.removeKeyInput(op.name, op.key)
                        }
                        is FuzzOperation.SetKeyList -> {
                            timestamp = instance.setInput(op.name, op.value)
                        }
                        is FuzzOperation.EditKeys -> {
                            timestamp = instance.editKeys(op.name, op.keysAdded, op.keysRemoved)
                        }
                        is FuzzOperation.SetKeyed -> {
                            timestamp = instance.setKeyedInputs(op.name, op.map)
                        }
                        is FuzzOperation.SetMultiple -> {
                            timestamp = instance.setInputs(op.changes)
                        }
                        is FuzzOperation.CheckBasic -> {
                            val outcome = if (timestamp == null) {
                                instance.getOutcome(op.name, 10L, TimeUnit.SECONDS)
                            } else {
                                instance.getOutcome(op.name, 10L, TimeUnit.SECONDS, timestamp)
                            }
                            assertEquals(op.outcome, outcome)
                        }
                        is FuzzOperation.CheckKeyList -> {
                            val outcome = if (timestamp == null) {
                                instance.getOutcome(op.name, 10L, TimeUnit.SECONDS)
                            } else {
                                instance.getOutcome(op.name, 10L, TimeUnit.SECONDS, timestamp)
                            }
                            assertEquals(op.outcome, outcome)
                        }
                        is FuzzOperation.CheckKeyedList -> {
                            val outcome = if (timestamp == null) {
                                instance.getOutcome(op.name, 10L, TimeUnit.SECONDS)
                            } else {
                                instance.getOutcome(op.name, 10L, TimeUnit.SECONDS, timestamp)
                            }
                            assertEquals(op.outcome, outcome)
                        }
                        is FuzzOperation.CheckKeyedValue -> {
                            val outcome = if (timestamp == null) {
                                instance.getOutcome(op.name, op.key, 10L, TimeUnit.SECONDS)
                            } else {
                                instance.getOutcome(op.name, op.key, 10L, TimeUnit.SECONDS, timestamp)
                            }
                            assertEquals(op.outcome, outcome)
                        }
                    }
                } catch (t: Throwable) {
                    throw RuntimeException("Failed on operation #$opIndex: #$op", t)
                }
            }
        } finally {
            instance.shutdown()
        }
    }

    private fun checkAsyncInstanceWithListeners(instance: TrickleAsyncInstance, operations: List<FuzzOperation>) {
        val collectedErrors = Collections.synchronizedList(ArrayList<Exception>())
        val listenedEvents = ConcurrentHashMap<ValueId, TrickleEvent<*>>()
        Awaitility.setDefaultPollDelay(0, TimeUnit.MILLISECONDS)
        Awaitility.setDefaultPollInterval(10, TimeUnit.MILLISECONDS)
        try {
            for ((opIndex, op) in operations.withIndex()) {
                try {
                    val unused: Any = when (op) {
                        is FuzzOperation.SetBasic -> instance.setInput(op.name, op.value)
                        is FuzzOperation.AddKey -> instance.addKeyInput(op.name, op.key)
                        is FuzzOperation.RemoveKey -> instance.removeKeyInput(op.name, op.key)
                        is FuzzOperation.SetKeyList -> instance.setInput(op.name, op.value)
                        is FuzzOperation.EditKeys -> instance.editKeys(op.name, op.keysAdded, op.keysRemoved)
                        is FuzzOperation.SetKeyed -> instance.setKeyedInputs(op.name, op.map)
                        is FuzzOperation.SetMultiple -> instance.setInputs(op.changes)
                        is FuzzOperation.CheckBasic -> {
                            // TODO: Only add one listener per name
                            instance.addBasicListener(op.name, TrickleEventListener { event ->
                                listenedEvents.merge(event.valueId, event, { event1, event2 ->
                                    if (event2.timestamp > event1.timestamp) {
                                        event2
                                    } else if (event2.timestamp == event1.timestamp && event1.timestamp >= 0 && event2 != event1) {
                                        // Note that receiving the same event (with the same timestamp) twice is expected sometimes.
                                        val e = IllegalStateException("Should not be receiving two different events for the same timestamp; events were $event1 and $event2")
                                        collectedErrors.add(e)
                                        throw e
                                    } else {
                                        event1
                                    }
                                })
                            })
                            await().untilAsserted {
                                val event = listenedEvents[ValueId.Nonkeyed(op.name)]
                                when (op.outcome) {
                                    is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                                    is NodeOutcome.NoSuchKey -> TODO()
                                    is NodeOutcome.Computed -> {
                                        assertEquals(op.outcome.value, (event as? TrickleEvent.Computed)?.value)
                                    }
                                    is NodeOutcome.Failure -> {
                                        assertEquals(op.outcome.failure, (event as? TrickleEvent.Failure)?.failure)
                                    }
                                }
                            }
                        }
                        is FuzzOperation.CheckKeyList -> {
                            // TODO: Only add one listener per name
                            instance.addKeyListListener(op.name, TrickleEventListener { event ->
                                listenedEvents.merge(event.valueId, event, { event1, event2 ->
                                    if (event2.timestamp > event1.timestamp) {
                                        event2
                                    } else if (event2.timestamp == event1.timestamp && event1.timestamp >= 0 && event2 != event1) {
                                        // Note that receiving the same event (with the same timestamp) twice is expected sometimes.
                                        val e = IllegalStateException("Should not be receiving two different events for the same timestamp; events were $event1 and $event2")
                                        collectedErrors.add(e)
                                        throw e
                                    } else {
                                        event1
                                    }
                                })
                            })
                            await().untilAsserted {
                                val event = listenedEvents[ValueId.FullKeyList(op.name)]
                                when (op.outcome) {
                                    is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                                    is NodeOutcome.NoSuchKey -> TODO()
                                    is NodeOutcome.Computed -> {
                                        assertEquals(op.outcome.value, (event as? TrickleEvent.Computed)?.value)
                                    }
                                    is NodeOutcome.Failure -> {
                                        assertEquals(op.outcome.failure, (event as? TrickleEvent.Failure)?.failure)
                                    }
                                }
                            }
                        }
                        is FuzzOperation.CheckKeyedList -> {
                            // TODO: Only add one listener per name
                            instance.addKeyedListListener(op.name, TrickleEventListener { event ->
                                listenedEvents.merge(event.valueId, event, { event1, event2 ->
                                    if (event2.timestamp > event1.timestamp) {
                                        event2
                                    } else if (event2.timestamp == event1.timestamp && event1.timestamp >= 0 && event2 != event1) {
                                        // Note that receiving the same event (with the same timestamp) twice is expected sometimes.
                                        val e = IllegalStateException("Should not be receiving two different events for the same timestamp; events were $event1 and $event2")
                                        collectedErrors.add(e)
                                        throw e
                                    } else {
                                        event1
                                    }
                                })
                            })
                            await().untilAsserted {
                                val event = listenedEvents[ValueId.FullKeyedList(op.name)]
                                when (op.outcome) {
                                    is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                                    is NodeOutcome.NoSuchKey -> TODO()
                                    is NodeOutcome.Computed -> {
                                        assertEquals(op.outcome.value, (event as? TrickleEvent.Computed)?.value)
                                    }
                                    is NodeOutcome.Failure -> {
                                        assertEquals(op.outcome.failure, (event as? TrickleEvent.Failure)?.failure)
                                    }
                                }
                            }
                        }
                        is FuzzOperation.CheckKeyedValue -> {
                            // TODO: Only add one listener per name
                            instance.addPerKeyListener(op.name, TrickleEventListener { event ->
                                listenedEvents.merge(event.valueId, event, { event1, event2 ->
                                    if (event2.timestamp > event1.timestamp) {
                                        event2
                                    } else if (event2.timestamp == event1.timestamp && event1.timestamp >= 0 && event2 != event1) {
                                        // Note that receiving the same event (with the same timestamp) twice is expected sometimes.
                                        val e = IllegalStateException("Should not be receiving two different events for the same timestamp; events were $event1 and $event2")
                                        collectedErrors.add(e)
                                        throw e
                                    } else {
                                        event1
                                    }
                                })
                            })
                            await().untilAsserted {
                                val event = listenedEvents[ValueId.Keyed(op.name, op.key)]
                                when (op.outcome) {
                                    is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                                    is NodeOutcome.NoSuchKey -> {
                                        assertTrue(event == null || event is TrickleEvent.KeyRemoved)
                                    }
                                    is NodeOutcome.Computed -> {
                                        assertEquals(op.outcome.value, (event as? TrickleEvent.Computed)?.value)
                                    }
                                    is NodeOutcome.Failure -> {
                                        assertEquals(op.outcome.failure, (event as? TrickleEvent.Failure)?.failure)
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    throw RuntimeException("Failed on operation #$opIndex: #$op", t)
                }
            }
        } finally {
            instance.shutdown()
        }
        if (collectedErrors.isNotEmpty()) {
            val exception = IllegalStateException("Duplicate-event errors were found")
            for (e in collectedErrors) {
                exception.addSuppressed(e)
            }
            throw exception
        }
    }

    private fun checkAsyncInstanceWithListeners2(instance: TrickleAsyncInstance, operations: List<FuzzOperation>) {
        val collectedErrors = Collections.synchronizedList(ArrayList<Exception>())
        val listenedEvents = ConcurrentHashMap<ValueId, TrickleEvent<*>>()
        Awaitility.setDefaultPollDelay(0, TimeUnit.MILLISECONDS)
        Awaitility.setDefaultPollInterval(10, TimeUnit.MILLISECONDS)

        val listenedValueIds = operations.flatMap { op -> when (op) {
            is FuzzOperation.SetBasic -> listOf()
            is FuzzOperation.AddKey -> listOf()
            is FuzzOperation.RemoveKey -> listOf()
            is FuzzOperation.EditKeys -> listOf()
            is FuzzOperation.SetKeyList -> listOf()
            is FuzzOperation.SetKeyed -> listOf()
            is FuzzOperation.SetMultiple -> listOf()
            is FuzzOperation.CheckBasic -> listOf(ValueId.Nonkeyed(op.name))
            is FuzzOperation.CheckKeyList -> listOf(ValueId.FullKeyList(op.name))
            is FuzzOperation.CheckKeyedList -> listOf(ValueId.FullKeyedList(op.name))
            is FuzzOperation.CheckKeyedValue -> listOf(ValueId.Keyed(op.name, "fake key")) // we only want one listener per node
        } }.toSet()
        for (valueId in listenedValueIds) {
            val unused = when (valueId) {
                is ValueId.Nonkeyed -> {
                    instance.addBasicListener(valueId.nodeName, TrickleEventListener { event ->
                        listenedEvents.merge(event.valueId, event, { event1, event2 ->
                            if (event2.timestamp > event1.timestamp) {
                                event2
                            } else if (event2.timestamp == event1.timestamp && event2 != event1) {
                                val e = IllegalStateException("Should not be receiving two different events for the same timestamp; events were $event1 and $event2")
                                collectedErrors.add(e)
                                throw e
                            } else {
                                event1
                            }
                        })
                    })
                }
                is ValueId.FullKeyList -> {
                    instance.addKeyListListener(valueId.nodeName, TrickleEventListener { event ->
                        listenedEvents.merge(event.valueId, event, { event1, event2 ->
                            if (event2.timestamp > event1.timestamp) {
                                event2
                            } else if (event2.timestamp == event1.timestamp && event2 != event1) {
                                val e = IllegalStateException("Should not be receiving two different events for the same timestamp; events were $event1 and $event2")
                                collectedErrors.add(e)
                                throw e
                            } else {
                                event1
                            }
                        })
                    })
                }
                is ValueId.KeyListKey -> TODO()
                is ValueId.Keyed -> {
                    instance.addPerKeyListener(valueId.nodeName, TrickleEventListener { event ->
                        listenedEvents.merge(event.valueId, event, { event1, event2 ->
                            if (event2.timestamp > event1.timestamp) {
                                event2
                            } else if (event2.timestamp == event1.timestamp && event2 != event1) {
                                val e = IllegalStateException("Should not be receiving two different events for the same timestamp; events were $event1 and $event2")
                                collectedErrors.add(e)
                                throw e
                            } else {
                                event1
                            }
                        })
                    })
                }
                is ValueId.FullKeyedList -> {
                    instance.addKeyedListListener(valueId.nodeName, TrickleEventListener { event ->
                        listenedEvents.merge(event.valueId, event, { event1, event2 ->
                            if (event2.timestamp > event1.timestamp) {
                                event2
                            } else if (event2.timestamp == event1.timestamp && event2 != event1) {
                                val e = IllegalStateException("Should not be receiving two different events for the same timestamp; events were $event1 and $event2")
                                collectedErrors.add(e)
                                throw e
                            } else {
                                event1
                            }
                        })
                    })
                }
            }
        }

        try {
            for ((opIndex, op) in operations.withIndex()) {
                try {
                    val unused: Any = when (op) {
                        is FuzzOperation.SetBasic -> instance.setInput(op.name, op.value)
                        is FuzzOperation.AddKey -> instance.addKeyInput(op.name, op.key)
                        is FuzzOperation.RemoveKey -> instance.removeKeyInput(op.name, op.key)
                        is FuzzOperation.SetKeyList -> instance.setInput(op.name, op.value)
                        is FuzzOperation.EditKeys -> instance.editKeys(op.name, op.keysAdded, op.keysRemoved)
                        is FuzzOperation.SetKeyed -> instance.setKeyedInputs(op.name, op.map)
                        is FuzzOperation.SetMultiple -> instance.setInputs(op.changes)
                        is FuzzOperation.CheckBasic -> {
                            await().untilAsserted {
                                val event = listenedEvents[ValueId.Nonkeyed(op.name)]
                                when (op.outcome) {
                                    is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                                    is NodeOutcome.NoSuchKey -> TODO()
                                    is NodeOutcome.Computed -> {
                                        assertEquals(op.outcome.value, (event as? TrickleEvent.Computed)?.value)
                                    }
                                    is NodeOutcome.Failure -> {
                                        assertEquals(op.outcome.failure, (event as? TrickleEvent.Failure)?.failure)
                                    }
                                }
                            }
                        }
                        is FuzzOperation.CheckKeyList -> {
                            await().untilAsserted {
                                val event = listenedEvents[ValueId.FullKeyList(op.name)]
                                when (op.outcome) {
                                    is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                                    is NodeOutcome.NoSuchKey -> TODO()
                                    is NodeOutcome.Computed -> {
                                        assertEquals(op.outcome.value, (event as? TrickleEvent.Computed)?.value)
                                    }
                                    is NodeOutcome.Failure -> {
                                        assertEquals(op.outcome.failure, (event as? TrickleEvent.Failure)?.failure)
                                    }
                                }
                            }
                        }
                        is FuzzOperation.CheckKeyedList -> {
                            await().untilAsserted {
                                val event = listenedEvents[ValueId.FullKeyedList(op.name)]
                                when (op.outcome) {
                                    is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                                    is NodeOutcome.NoSuchKey -> TODO()
                                    is NodeOutcome.Computed -> {
                                        assertEquals(op.outcome.value, (event as? TrickleEvent.Computed)?.value)
                                    }
                                    is NodeOutcome.Failure -> {
                                        assertEquals(op.outcome.failure, (event as? TrickleEvent.Failure)?.failure)
                                    }
                                }
                            }
                        }
                        is FuzzOperation.CheckKeyedValue -> {
                            await().untilAsserted {
                                val event = listenedEvents[ValueId.Keyed(op.name, op.key)]
                                when (op.outcome) {
                                    is NodeOutcome.NotYetComputed -> { /* Do nothing */ }
                                    is NodeOutcome.NoSuchKey -> {
                                        assertTrue(event == null || event is TrickleEvent.KeyRemoved)
                                    }
                                    is NodeOutcome.Computed -> {
                                        assertEquals(op.outcome.value, (event as? TrickleEvent.Computed)?.value)
                                    }
                                    is NodeOutcome.Failure -> {
                                        assertEquals(op.outcome.failure, (event as? TrickleEvent.Failure)?.failure)
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    throw RuntimeException("Failed on operation #$opIndex: #$op", t)
                }
            }
        } finally {
            instance.shutdown()
        }
        if (collectedErrors.isNotEmpty()) {
            val exception = IllegalStateException("Duplicate-event errors were found")
            for (e in collectedErrors) {
                exception.addSuppressed(e)
            }
            throw exception
        }
    }
}

sealed class FuzzOperation {
    data class SetBasic(val name: NodeName<Int>, val value: Int) : FuzzOperation()
    data class AddKey(val name: KeyListNodeName<Int>, val key: Int) : FuzzOperation()
    data class RemoveKey(val name: KeyListNodeName<Int>, val key: Int) : FuzzOperation()
    data class EditKeys(val name: KeyListNodeName<Int>, val keysAdded: List<Int>, val keysRemoved: List<Int>) : FuzzOperation()
    data class SetKeyList(val name: KeyListNodeName<Int>, val value: List<Int>) : FuzzOperation()
    data class SetKeyed(val name: KeyedNodeName<Int, Int>, val map: Map<Int, Int>) : FuzzOperation()
    data class SetMultiple(val changes: List<TrickleInputChange>): FuzzOperation()
    data class CheckBasic(val name: NodeName<Int>, val outcome: NodeOutcome<Int>) : FuzzOperation()
    data class CheckKeyList(val name: KeyListNodeName<Int>, val outcome: NodeOutcome<List<Int>>) : FuzzOperation()
    data class CheckKeyedList(val name: KeyedNodeName<Int, Int>, val outcome: NodeOutcome<List<Int>>) : FuzzOperation()
    data class CheckKeyedValue(val name: KeyedNodeName<Int, Int>, val key: Int, val outcome: NodeOutcome<Int>) : FuzzOperation()
}

class FuzzOperationScript(val operations: List<FuzzOperation>, val states: List<Map<ValueId, Any>>) {
}

private fun getOperationsScript(definition: TrickleDefinition, operationsSeed: Int): FuzzOperationScript {
    val rawInstance = definition.instantiateRaw()
    // Handle cases where a node we query depends only on key list inputs
    rawInstance.completeSynchronously()
    val random = Random(operationsSeed.toLong())
    val operations = ArrayList<FuzzOperation>()
    val states = ArrayList<Map<ValueId, Any>>()
    for (i in 1..20) {
        operations.add(getRandomOperation(rawInstance, definition, random))
        rawInstance.completeSynchronously()
        states.add(getState(rawInstance))
    }
    return FuzzOperationScript(operations, states)
}

fun getState(instance: TrickleInstance): Map<ValueId, Any> {
    // TODO: Implement (these full states will be used in evaluating
    return mapOf()
}

private fun getRandomOperation(rawInstance: TrickleInstance, definition: TrickleDefinition, random: Random): FuzzOperation {
    val roll = random.nextDouble()
    if (roll < 0.15) {
        val numberOfInputs = random.nextInt(6)
        val inputOperations = (0..numberOfInputs).map { getRandomInputChange(definition, random, rawInstance) }
        return FuzzOperation.SetMultiple(inputOperations)
    } else if (roll < 0.5) {
        // Do a random input operation
        return toOperation(getRandomInputChange(definition, random, rawInstance))
    } else {
        // Do a random observation of a node outcome
        val randomNode = definition.topologicalOrdering.getAtRandom(random, { error("This shouldn't be empty") })
        when (randomNode) {
            is NodeName<*> -> {
                val nodeName = randomNode as NodeName<Int>
                val outcome = rawInstance.getNodeOutcome(nodeName)
                return FuzzOperation.CheckBasic(nodeName, outcome)
            }
            is KeyListNodeName<*> -> {
                val nodeName = randomNode as KeyListNodeName<Int>
                val outcome = rawInstance.getNodeOutcome(nodeName)
                return FuzzOperation.CheckKeyList(nodeName, outcome)
            }
            is KeyedNodeName<*, *> -> {
                val nodeName = randomNode as KeyedNodeName<Int, Int>
                val checkTypeRoll = random.nextDouble()
                if (checkTypeRoll < 0.5) {
                    // Check all values
                    val outcome = rawInstance.getNodeOutcome(nodeName)
                    return FuzzOperation.CheckKeyedList(nodeName, outcome)
                } else {
                    // Check one value
                    val keySourceName = definition.keyedNodes[nodeName]!!.keySourceName
                    val keySourceOutcome = rawInstance.getNodeOutcome(keySourceName)
                    val key = when (keySourceOutcome) {
                        is NodeOutcome.NotYetComputed -> 0
                        is NodeOutcome.Computed -> keySourceOutcome.value.getAtRandom(random, { 2 }) as Int
                        is NodeOutcome.Failure -> 1
                        is NodeOutcome.NoSuchKey -> 3
                    }
                    val outcome = rawInstance.getNodeOutcome(nodeName, key)
                    return FuzzOperation.CheckKeyedValue(nodeName, key, outcome)
                }
            }
        }
    }
}

// Note: These aren't the same type because TrickleInputChange has generics, but those make it difficult to call the
// implementations when we run the operations
fun toOperation(change: TrickleInputChange): FuzzOperation {
    return when (change) {
        is TrickleInputChange.SetBasic<*> -> FuzzOperation.SetBasic(change.nodeName as NodeName<Int>, change.value as Int)
        is TrickleInputChange.SetKeys<*> -> FuzzOperation.SetKeyList(change.nodeName as KeyListNodeName<Int>, change.value as List<Int>)
        is TrickleInputChange.EditKeys<*> -> FuzzOperation.EditKeys(change.nodeName as KeyListNodeName<Int>,
            change.keysAdded as List<Int>,
            change.keysRemoved as List<Int>
        )
        is TrickleInputChange.SetKeyed<*, *> -> FuzzOperation.SetKeyed(change.nodeName as KeyedNodeName<Int, Int>, change.map as Map<Int, Int>)
    }
}

private fun getRandomInputChange(definition: TrickleDefinition, random: Random, rawInstance: TrickleInstance): TrickleInputChange {
    val allInputNames = getAllInputNodes(definition)
    val inputName = allInputNames.getAtRandom(random, { error("This shouldn't be empty") })
    // TODO: Add multi-setting when that's a thing
    when (inputName) {
        is NodeName<*> -> {
            val nodeName = inputName as NodeName<Int>
            val valueToSet = random.nextInt(100)
            rawInstance.setInput(nodeName, valueToSet)
            return TrickleInputChange.SetBasic(nodeName, valueToSet)
        }
        is KeyListNodeName<*> -> {
            val nodeName = inputName as KeyListNodeName<Int>
            val keyListRoll = random.nextDouble()
            if (keyListRoll < 0.2) {
                val valueToAdd = random.nextInt(100)
                rawInstance.addKeyInput(nodeName, valueToAdd)
                return TrickleInputChange.EditKeys(nodeName, listOf(valueToAdd), listOf())
            } else if (keyListRoll < 0.35) {
                // Pick an existing key to delete
                val existingListOutcome = rawInstance.getNodeOutcome(nodeName)
                val valueToRemove = when (existingListOutcome) {
                    is NodeOutcome.NotYetComputed -> 0
                    is NodeOutcome.Computed -> existingListOutcome.value.getAtRandom(random, { 2 })
                    is NodeOutcome.Failure -> 1
                    is NodeOutcome.NoSuchKey -> 3
                }
                rawInstance.removeKeyInput(nodeName, valueToRemove)
                return TrickleInputChange.EditKeys(nodeName, listOf(), listOf(valueToRemove))
            } else if (keyListRoll < 0.4) {
                // Pick a random key to maybe delete if it's there
                val valueToRemove = random.nextInt(100)
                rawInstance.removeKeyInput(nodeName, valueToRemove)
                return TrickleInputChange.EditKeys(nodeName, listOf(), listOf(valueToRemove))
            } else if (keyListRoll < 0.7) {
                // Add some keys, remove some keys
                val existingListOutcome = rawInstance.getNodeOutcome(nodeName)
                val numToAdd = random.nextInt(8)
                val valuesToAdd = (0 until numToAdd).map { random.nextInt(100) }
                val numToRemove = random.nextInt(5)
                val valuesToRemove = when (existingListOutcome) {
                    is NodeOutcome.NotYetComputed -> listOf(0)
                    is NodeOutcome.Computed -> (0 until numToRemove).map { existingListOutcome.value.getAtRandom(random, { 2 }) }
                    is NodeOutcome.Failure -> listOf(1)
                    is NodeOutcome.NoSuchKey -> listOf(3)
                }
                rawInstance.editKeys(nodeName, valuesToAdd, valuesToRemove)
                return TrickleInputChange.EditKeys(nodeName, valuesToAdd, valuesToRemove)
            } else {
                val newList = createRandomListProducingFunction(random)(listOf(random.nextInt(100)))
                rawInstance.setInput(nodeName, newList)
                return TrickleInputChange.SetKeys(nodeName, newList)
            }
        }
        is KeyedNodeName<*, *> -> {
            // TODO: Tweak this to possibly return multiple entries in the same map
            val nodeName = inputName as KeyedNodeName<Int, Int>
            val keySourceName = definition.keyedNodes.getValue(nodeName).keySourceName
            val curKeyListOutcome = rawInstance.getNodeOutcome(keySourceName)
            val curKeyList = when (curKeyListOutcome) {
                is NodeOutcome.NotYetComputed -> listOf(0)
                is NodeOutcome.Computed -> curKeyListOutcome.value as List<Int>
                is NodeOutcome.Failure -> listOf(-1)
                is NodeOutcome.NoSuchKey -> listOf(2)
            }
            // Look at the _ to find a thing to set
            val keyedRoll = random.nextDouble()
            val key = if (keyedRoll < 0.05) {
                // Use a random key value
                random.nextInt(100)
            } else {
                // Look up an existing key
                curKeyList.getAtRandom(random, { 1 })
            }

            val valueToSet = random.nextInt(100)
            rawInstance.setKeyedInput(nodeName, key, valueToSet)
            return TrickleInputChange.SetKeyed(nodeName, mapOf(key to valueToSet))
        }
        else -> error("Unexpected situation")
    }
}

private fun getAllInputNodes(definition: TrickleDefinition): List<GenericNodeName> {
    val result = ArrayList<GenericNodeName>()
    // Use topologicalOrdering so nodes end up in the same order in each run
    for (nodeName in definition.topologicalOrdering) {
        val operation = when (nodeName) {
            is NodeName<*> -> definition.nonkeyedNodes.getValue(nodeName).operation
            is KeyListNodeName<*> -> definition.keyListNodes.getValue(nodeName).operation
            is KeyedNodeName<*, *> -> definition.keyedNodes.getValue(nodeName).operation
        }
        if (operation == null) {
            result.add(nodeName)
        }
    }
    return result
}

private fun getFuzzedDefinition(seed: Int): TrickleDefinition {
    return FuzzedDefinitionBuilder(seed).run()
}

private class FuzzedDefinitionBuilder(seed: Int) {
    val random = Random(seed.toLong())
    val numNodes = 5 + random.nextInt(6)

    val builder = TrickleDefinitionBuilder()
    val existingNodes = ArrayList<GenericNodeName>()
    val existingKeyListNodes = ArrayList<TrickleBuiltKeyListNode<Int>>()
    val existingKeyedNodes = HashMap<KeyListNodeName<*>, ArrayList<TrickleInput<*>>>()

    val unkeyedInputs = ArrayList<TrickleInput<*>>()

    fun run(): TrickleDefinition {
        for (i in 1..numNodes) {
            val curNodeRoll = random.nextDouble()
            if (existingNodes.size < 2) {
                makeInputNode(curNodeRoll, i)
            } else {
                if (curNodeRoll < 0.2) {
                    makeInputNode(random.nextDouble(), i)
                } else if (curNodeRoll < 0.5) {
                    makeBasicNode(i)
                } else if (curNodeRoll < 0.7) {
                    makeKeyListNode(i)
                } else {
                    if (existingKeyListNodes.isEmpty()) {
                        makeKeyListNode(i)
                    } else {
                        makeKeyedNode(i)
                    }
                }
            }
        }
        if (existingNodes.size != numNodes) {
            error("I probably screwed something up here")
        }
        return builder.build()
    }

    private fun makeBasicNode(i: Int) {
        val name = NodeName<Int>("basic$i")

        // Decide how many inputs to have (between one and four)
        val maxInputs = Math.min(unkeyedInputs.size, 4)
        val numInputs = random.nextInt(maxInputs) + 1
        val inputs = unkeyedInputs.randomSubset(random, numInputs)

        val fn = createRandomIntProducingFunction(random)
        val onCatch = null // TODO: Create throwing methods and onCatch methods

        val node = builder.createNode(name, inputs, fn, onCatch)
        existingNodes.add(name)
        unkeyedInputs.add(node)
    }

    private fun makeKeyListNode(i: Int) {
        val name = KeyListNodeName<Int>("list$i")

        // Decide how many inputs to have (between one and two)
        val maxInputs = Math.min(unkeyedInputs.size, 2)
        val numInputs = random.nextInt(maxInputs) + 1
        val inputs = unkeyedInputs.randomSubset(random, numInputs)

        val fn = createRandomListProducingFunction(random)
        val onCatch = null // TODO: Create throwing methods and onCatch methods

        val node = builder.createKeyListNode(name, inputs, fn, onCatch)
        existingNodes.add(name)
        unkeyedInputs.add(node.listOutput())
        existingKeyListNodes.add(node)
        existingKeyedNodes[name] = ArrayList()
    }

    private fun makeKeyedNode(i: Int) {
        val keySource = existingKeyListNodes.getAtRandom(random, { error("This shouldn't be empty") })

        // We put input generation here instead of with the other inputs because it relied on key lists already existing.
        val makeInput = if (keySource.name.name.contains("Input")) {
            random.nextDouble() < 0.5
        } else {
            // The test is slightly hacky, but keyed inputs can only have input key lists as their key sources
            false
        }
        val name = KeyedNodeName<Int, Int>(if (makeInput) "keyedInput$i" else "keyed$i")

        if (makeInput) {
            val node = builder.createKeyedInputNode(name, keySource)
            existingNodes.add(name)
            existingKeyedNodes[keySource.name]!!.add(node.keyedOutput())
            unkeyedInputs.add(node.fullOutput())
            return
        }

        val possibleInputs = ArrayList<TrickleInput<*>>()
        possibleInputs.addAll(unkeyedInputs)
        possibleInputs.addAll(existingKeyedNodes[keySource.name]!!)
        // Decide how many inputs to have (between zero and four)
        val maxInputs = Math.min(unkeyedInputs.size, 5)
        val numInputs = random.nextInt(maxInputs)
        val inputs = possibleInputs.randomSubset(random, numInputs)

        val listOnlyFn = createRandomIntProducingFunction(random)
        val fn = { key: Int, list: List<*> ->
            listOnlyFn(list + key)
        }
        val onCatch = null // TODO: Create throwing methods and onCatch methods

        val node = builder.createKeyedNode(name, keySource, inputs, fn, onCatch)
        existingNodes.add(name)
        existingKeyedNodes[keySource.name]!!.add(node.keyedOutput())
        unkeyedInputs.add(node.fullOutput())
    }

    private fun makeInputNode(curNodeRoll: Double, i: Int) {
        if (curNodeRoll < 0.6) {
            val name = NodeName<Int>("basicInput$i")
            val node = builder.createInputNode(name)
            existingNodes.add(name)
            unkeyedInputs.add(node)
        } else {
            val name = KeyListNodeName<Int>("listInput$i")
            val node = builder.createKeyListInputNode(name)
            existingNodes.add(name)
            existingKeyListNodes.add(node)
            unkeyedInputs.add(node.listOutput())
            existingKeyedNodes[name] = ArrayList()
        }
    }
}

private fun createRandomIntProducingFunction(random: Random): (List<*>) -> Int {
    val inputsToInts: (List<*>) -> List<Int> = { incomingValues ->
        incomingValues.map { value ->
            if (value is List<*>) {
                value.hashCode()
            } else {
                value as Int
            }
        }
    }

    val dieRoll = random.nextDouble()
    if (dieRoll < 0.7) {
        val toAdd = -50 + random.nextInt(101)
        return { list ->
            val ints = inputsToInts(list)
            ints.hashCode() + toAdd
        }
    } else {
        val toMultiply = 2 + random.nextInt(5)
        return { list ->
            val ints = inputsToInts(list)
            ints.hashCode() * toMultiply
        }
    }
}

private fun createRandomListProducingFunction(random: Random): (List<*>) -> List<Int> {
    // Start by reducing its inputs to a number
    val turnToInt = createRandomIntProducingFunction(random)
    val dieRoll = random.nextDouble()

    if (dieRoll < 0.5) {
        // Low numbers
        return {
            val int = turnToInt(it)
            val bounded = (int % 10)
            (0 until bounded).toList()
        }
    } else {
        // More numbers...
        val defaultStart = random.nextInt(100)
        return {
            val int = turnToInt(it)
            val listRng = Random(int.toLong())
            val start = defaultStart - 2 + listRng.nextInt(5)
            val end = defaultStart + 6 + listRng.nextInt(5)
            val numbers = (start until end).toList()
            numbers.randomSubset(listRng, listRng.nextInt(numbers.size))
        }
    }

}

private fun <E> List<E>.getAtRandom(random: Random, ifZero: () -> E): E {
    if (this.size == 0) {
        return ifZero()
    }
    if (this.size == 1) {
        return this[0]
    }
    val index = random.nextInt(this.size)
    return this[index]
}

private fun <E> List<E>.randomSubset(random: Random, n: Int): List<E> {
    if (n >= this.size) {
        return ArrayList(this)
    }
    if (n == 0) {
        return listOf()
    }
    val indices = LinkedHashSet<Int>()
    while (indices.size < n) {
        indices.add(random.nextInt(this.size))
    }
    val subset = ArrayList<E>(n)
    for (index in indices) {
        subset.add(this[index])
    }
    return subset
}
