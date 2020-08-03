package net.semlang.refill

import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.util.*

/*
 * Refill: A Kotlin/JVM library for defining asynchronous tasks in terms of each
 * other, going beyond RxJava by adding support for minimal recomputation when portions of the input change.
 *
 */

// TODO: For onCatch methods, should outputs of parent nodes be added to their inputs?

// TODO: Add the ability to skip computations based on the equality of certain inputs (and other inputs remaining at the timestamp
// they were last time)

// TODO: It might be nice to be able to compose keylists within keylists, on the general principle that composability is good
// (Say e.g. one keylist represents directories, a composed keylist could represent a list of files within each of those
// directories; and then as opposed to a flat keylist of all files, this would allow per-file computations that could then
// be filtered back into a per-directory view before being brought back to a global list.)

// TODO: Probably out of scope, but I think we could get typings on the input setters if code were either generated from
// a spec or based on an annotation processor

// TODO: Should we use names as the arguments to builders instead of the outputs of previous builder calls? Still use
// the topological-ordering-as-you-go, but rely on runtime checks of inputs instead of compile-time?

// TODO: Add optional "payloads" for key lists, to replace the removed keyed inputs feature

sealed class GenericNodeName
class NodeName<T>(val name: String): GenericNodeName() {
    override fun toString(): String {
        return name
    }
}
class KeyListNodeName<T>(val name: String): GenericNodeName() {
    override fun toString(): String {
        return name
    }
}
// TODO: Should the source key list be defined here?
class KeyedNodeName<K, T>(val name: String): GenericNodeName() {
    override fun toString(): String {
        return name
    }
}


// TODO: Internal or retain as public?
sealed class ValueId {
    data class Nonkeyed(val nodeName: NodeName<*>): ValueId()
    data class FullKeyList(val nodeName: KeyListNodeName<*>): ValueId()
    // Note: Stored values associated with KeyListKeys are booleans
    data class KeyListKey(val nodeName: KeyListNodeName<*>, val key: Any?): ValueId()
    data class Keyed(val nodeName: KeyedNodeName<*, *>, val key: Any?): ValueId()
    data class FullKeyedList(val nodeName: KeyedNodeName<*, *>): ValueId()
}

internal class KeyList<T> private constructor(val list: List<T>, val set: Set<T>) {
    companion object {
        fun <T> copyOf(collection: Collection<T>): KeyList<T> {
            val list = ArrayList<T>()
            val set = HashSet<T>()
            for (item in collection) {
                if (!set.contains(item)) {
                    list.add(item)
                    set.add(item)
                }
            }
            return KeyList(list, set)
        }
        fun <T> empty(): KeyList<T> {
            return KeyList(emptyList(), emptySet())
        }
    }

    fun add(key: T): KeyList<T> {
        if (set.contains(key)) {
            return this
        }

        val listCopy = ArrayList<T>(list.size + 1)
        listCopy.addAll(list)
        listCopy.add(key)
        val setCopy = HashSet<T>(set.size + 1)
        setCopy.addAll(set)
        setCopy.add(key)
        return KeyList(listCopy, setCopy)
    }

    // TODO: This could be much more efficient
    fun addAll(keys: Collection<T>): KeyList<T> {
        if (keys.all { set.contains(it) }) {
            return this
        }

        val listCopy = ArrayList<T>(list.size + keys.size)
        listCopy.addAll(list)
        val setCopy = HashSet<T>(set.size + keys.size)
        setCopy.addAll(list)
        for (key in keys) {
            if (!setCopy.contains(key)) {
                listCopy.add(key)
                setCopy.add(key)
            }
        }
        return KeyList(listCopy, setCopy)
    }

    fun remove(key: T): KeyList<T> {
        if (!set.contains(key)) {
            return this
        }

        val listCopy = ArrayList<T>(list.size - 1)
        val setCopy = HashSet<T>(set.size - 1)
        for (element in list) {
            if (element != key) {
                listCopy.add(element)
                setCopy.add(element)
            }
        }
        return KeyList(listCopy, setCopy)
    }

    fun removeAll(keys: Collection<T>): KeyList<T> {
        if (!keys.any { set.contains(it) }) {
            return this
        }
        val keysSet = keys as? Set<T> ?: keys.toSet()

        val listCopy = ArrayList<T>()
        val setCopy = HashSet<T>()
        for (element in list) {
            if (!keysSet.contains(element)) {
                listCopy.add(element)
                setCopy.add(element)
            }
        }
        return KeyList(listCopy, setCopy)
    }

    // Used to expose to users; TODO: but maybe we should also expose Set.contains somehow? Return a Collection or an immutable KeyList (vs. internal MutableKeyList)?
    // TODO: Should probably just do this at creation time...
    fun asList(): List<T> {
        return Collections.unmodifiableList(list)
    }

    fun contains(key: T): Boolean {
        return set.contains(key)
    }

    override fun toString(): String {
        return list.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (other is KeyList<*>) {
            return list.equals(other.list)
        } else {
            return false
        }
    }

    override fun hashCode(): Int {
        return list.hashCode()
    }

}

private data class TimestampedValue(private var timestamp: Long, private var latestConsistentTimestamp: Long, private var value: Any?, private var failure: RefillFailure?) {
    fun set(newTimestamp: Long, newValue: Any?, newFailure: RefillFailure?) {
        this.timestamp = newTimestamp
        this.latestConsistentTimestamp = newTimestamp
        this.value = newValue
        this.failure = newFailure
    }

    fun set(newTimestamp: Long, newValue: Any?) {
        this.timestamp = newTimestamp
        this.latestConsistentTimestamp = newTimestamp
        this.value = newValue
        this.failure = null
    }

    fun setFailure(newTimestamp: Long, newFailure: RefillFailure) {
        this.timestamp = newTimestamp
        this.latestConsistentTimestamp = newTimestamp
        this.value = null
        this.failure = newFailure
    }

    fun setLatestConsistentTimestamp(latestConsistentTimestamp: Long) {
        this.latestConsistentTimestamp = latestConsistentTimestamp
    }

    fun getValue(): Any? {
        return value
    }

    fun getTimestamp(): Long {
        return timestamp
    }

    fun getLatestConsistentTimestamp(): Long {
        return latestConsistentTimestamp
    }

    fun getFailure(): RefillFailure? {
        return failure
    }
}

interface RefillInputReceiver {
    fun setInputs(changes: List<RefillInputChange>): Long
    fun <T> setInput(nodeName: NodeName<T>, value: T): Long
    fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long
    fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long
    fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long
    fun <T> editKeys(nodeName: KeyListNodeName<T>, keysAdded: List<T>, keysRemoved: List<T>): Long
    fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long
    fun <K, T> setKeyedInputs(nodeName: KeyedNodeName<K, T>, map: Map<K, T>): Long
}

sealed class RefillInputChange {
    abstract val nodeName: GenericNodeName
    data class SetBasic<T>(override val nodeName: NodeName<T>, val value: T): RefillInputChange()
    data class SetKeys<T>(override val nodeName: KeyListNodeName<T>, val value: List<T>): RefillInputChange()
    // TODO: Add to fuzz testing
    // Note: Removals happen before additions. If a key is in both lists, it will be removed and then readded to the list (thus ending up in a different position).
    data class EditKeys<T>(override val nodeName: KeyListNodeName<T>, val keysAdded: List<T>, val keysRemoved: List<T>): RefillInputChange()
    data class SetKeyed<K, T>(override val nodeName: KeyedNodeName<K, T>, val map: Map<K, T>): RefillInputChange()
}

interface RefillRawInstance {
    val definition: RefillDefinition
    fun setInputs(changes: List<RefillInputChange>): Long
    fun <T> setInput(nodeName: NodeName<T>, value: T): Long
    fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long
    fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long
    fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long
    fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long
    fun <K, T> setKeyedInputs(nodeName: KeyedNodeName<K, T>, map: Map<K, T>): Long
    fun getNextSteps(): List<RefillStep>
    fun completeSynchronously()
    fun reportResult(result: RefillStepResult)
    fun <T> getNodeValue(nodeName: NodeName<T>): T
    fun <T> getNodeValue(nodeName: KeyListNodeName<T>): List<T>
    fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>, key: K): V
    fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>): List<V>
    fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T>
    fun <T> getNodeOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>>
    fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V>
    fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>>
    // TODO: Maybe remove this if unused?
    fun getLastUpdateTimestamp(valueId: ValueId): Long
    fun getLatestTimestampWithValue(valueId: ValueId): Long
}

// TODO: Synchronize stuff
// TODO: Add methods to ask for a given result, but only after a given timestamp (block until available?)
class RefillInstance internal constructor(override val definition: RefillDefinition): RefillRawInstance {
    // Used to ensure all results we receive originated from this instance
    class Id internal constructor()
    private val instanceId = Id()

    private val values = LinkedHashMap<ValueId, TimestampedValue>()
    private var valueListener: ((RefillEvent<*>) -> Unit)? = null

    private var curTimestamp = 0L

    init {
        synchronized(this) {
            for (unkeyedNode in definition.nonkeyedNodes.values) {
                if (unkeyedNode.operation == null) {
                    val valueId = ValueId.Nonkeyed(unkeyedNode.name)
                    setValue(valueId, 0L, null, RefillFailure(mapOf(), setOf(valueId)))
                }
            }
            // Key list input nodes start out as empty lists
            for (keyListNode in definition.keyListNodes.values) {
                if (keyListNode.operation == null) {
                    setValue(ValueId.FullKeyList(keyListNode.name), 0L, KeyList.empty<Any>(), null)
                }
            }
        }
    }

    @Synchronized
    private fun setValueSuppressingListener(valueId: ValueId, newTimestamp: Long, newValue: Any?, newFailure: RefillFailure?) {
        var valueHolder = values[valueId]
        if (valueHolder == null) {
            valueHolder = TimestampedValue(-1L, -1L, null, null)
            values[valueId] = valueHolder
        }
        valueHolder.set(newTimestamp, newValue, newFailure)
    }

    @Synchronized
    private fun setValue(valueId: ValueId, newTimestamp: Long, newValue: Any?, newFailure: RefillFailure?) {
        setValueSuppressingListener(valueId, newTimestamp, newValue, newFailure)
        if (valueListener != null) {
            val event: RefillEvent<*> = if (newFailure == null) {
                if (valueId is ValueId.FullKeyList) {
                    RefillEvent.Computed.of(valueId, (newValue as KeyList<Any?>).asList(), newTimestamp)
                } else {
                    RefillEvent.Computed.of(valueId, newValue, newTimestamp)
                }
            } else {
                RefillEvent.Failure.of<Any?>(valueId, newFailure, newTimestamp)
            }
            valueListener!!(event)
        }
    }

    @Synchronized
    fun setValueListener(listener: ((RefillEvent<*>) -> Unit)?) {
        this.valueListener = listener
    }

    // TODO: Only invoke the listener for the final values of inputs after a set of changes
    @Synchronized
    override fun setInputs(changes: List<RefillInputChange>): Long {
        for (change in changes) {
            checkHasValidInputNode(change)
        }

        /*
         * Coalesce related changes together so there is only one change per input node.
         *
         * The benefits of coalescing include making sure there is only one listener invocation (and therefore only one
         * externally visible value) of a given input node per timestamp.
         */
//        val coalescedChanges = coalesceChanges(changes, definition)

        val prospectiveNewTimestamp = curTimestamp + 1
//        var somethingChanged = false
        val eventsFromChanges = HashMap<ValueId, RefillEvent<*>>()
        for (change in changes) {
            eventsFromChanges.putAll(applyChange(change, prospectiveNewTimestamp))
        }
        if (eventsFromChanges.isNotEmpty()) {
            curTimestamp = prospectiveNewTimestamp
            if (valueListener != null) {
                for (event in eventsFromChanges.values) {
                    valueListener!!(event)
                }
            }
        }
        return curTimestamp
    }

    /**
     * Returns true iff something changed. (This may be the case if, e.g., a key is added to a key list that is already
     * in the list.)
     */
    @Synchronized
    private fun applyChange(change: RefillInputChange, newTimestamp: Long): Map<ValueId, RefillEvent<*>> {
        return when (change) {
            is RefillInputChange.SetBasic<*> -> applySetBasicChange(change, newTimestamp)
            is RefillInputChange.SetKeys<*> -> applySetKeysChange(change, newTimestamp)
            is RefillInputChange.EditKeys<*> -> applyEditKeysChange(change, newTimestamp)
            is RefillInputChange.SetKeyed<*, *> -> applySetKeyedChange(change, newTimestamp)
        }
    }

    @Synchronized
    private fun checkHasValidInputNode(change: RefillInputChange) {
        val unused = when (change) {
            is RefillInputChange.SetBasic<*> -> {
                val node = definition.nonkeyedNodes[change.nodeName]
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly set the value of a non-input node ${change.nodeName}")
                }
                null
            }
            is RefillInputChange.SetKeys<*> -> {
                val node = definition.keyListNodes[change.nodeName]
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly set the value of a non-input node ${change.nodeName}")
                }
                null
            }
            is RefillInputChange.EditKeys<*> -> {
                val node = definition.keyListNodes[change.nodeName]
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly set the value of a non-input node ${change.nodeName}")
                }
                null
            }
            is RefillInputChange.SetKeyed<*, *> -> {
                val node = definition.keyedNodes[change.nodeName]
                // TODO: Write tests for these error cases
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly modify the value of a non-input node ${change.nodeName}")
                }
                null
            }
        }
    }

    // TODO: Switch these over to calling the function for a list of changes to reduce duplicate code paths
    @Synchronized
    override fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
//        val node = definition.nonkeyedNodes[nodeName]
//        if (node == null) {
//            throw IllegalArgumentException("Unrecognized node name $nodeName")
//        }
//        if (node.operation != null) {
//            throw IllegalArgumentException("Cannot directly set the value of a non-input node $nodeName")
//        }
//        curTimestamp++
//        setValue(ValueId.Nonkeyed(nodeName), curTimestamp, value, null)
//        return curTimestamp
        return setInputs(listOf(RefillInputChange.SetBasic(nodeName, value)))
    }

    // TODO: Currently we don't check equality here but we may want to in the future
    @Synchronized
    private fun <T> applySetBasicChange(change: RefillInputChange.SetBasic<T>, newTimestamp: Long): Map<ValueId, RefillEvent<*>> {
        val valueId = ValueId.Nonkeyed(change.nodeName)
        setValueSuppressingListener(valueId, newTimestamp, change.value, null)
        return mapOf(valueId to RefillEvent.Computed.of(valueId, change.value, newTimestamp))
    }

    @Synchronized
    override fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long {
        return setInputs(listOf(RefillInputChange.SetKeys(nodeName, list)))
    }

    @Synchronized
    private fun <T> applySetKeysChange(change: RefillInputChange.SetKeys<T>, newTimestamp: Long): Map<ValueId, RefillEvent<*>> {
        val (nodeName, value) = change
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>
        val newList = KeyList.copyOf(value)

        if (oldList.list != newList.list) {
            setValueSuppressingListener(listValueId, newTimestamp, newList, null)

            val additions = HashSet(newList.set)
            additions.removeAll(oldList.set)
            val removals = HashSet(oldList.set)
            removals.removeAll(newList.set)

            for (addition in additions) {
                val keyValueId = ValueId.KeyListKey(nodeName, addition)
                setValueSuppressingListener(keyValueId, newTimestamp, true, null)
            }
            for (removal in removals) {
                val keyValueId = ValueId.KeyListKey(nodeName, removal)
                values.remove(keyValueId)
            }
            val pruneEvents = pruneKeyedInputsForRemovedKeysSuppressingListener(nodeName, removals, newTimestamp)

            return mapOf(listValueId to RefillEvent.Computed.of(listValueId, newList.asList(), newTimestamp)) + pruneEvents
        }
        return mapOf()
    }

    @Synchronized
    override fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        return setInputs(listOf(RefillInputChange.EditKeys(nodeName, listOf(key), listOf())))
    }

    @Synchronized
    private fun <T> applyEditKeysChange(change: RefillInputChange.EditKeys<T>, newTimestamp: Long): Map<ValueId, RefillEvent<*>> {
        val (nodeName, keysToAdd, keysToRemove) = change
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>

        val newList = oldList.removeAll(keysToRemove).addAll(keysToAdd)

        if (oldList != newList) {
            setValueSuppressingListener(listValueId, newTimestamp, newList, null)

            val actuallyRemoved = HashSet<T>()
            for (key in keysToRemove) {
                if (oldList.contains(key) && !newList.contains(key)) {
                    val keyValueId = ValueId.KeyListKey(nodeName, key)
                    values.remove(keyValueId)
                    actuallyRemoved.add(key)
                }
            }
            for (key in keysToAdd) {
                if (!oldList.contains(key) && newList.contains(key)) {
                    val keyValueId = ValueId.KeyListKey(nodeName, key)
                    setValueSuppressingListener(keyValueId, newTimestamp, true, null)
                }
            }
            val pruneEvents = pruneKeyedInputsForRemovedKeysSuppressingListener(nodeName, actuallyRemoved, newTimestamp)
            return mapOf(listValueId to RefillEvent.Computed.of(listValueId, newList.asList(), newTimestamp)) + pruneEvents
        }
        return mapOf()
    }

    @Synchronized
    override fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        return setInputs(listOf(RefillInputChange.EditKeys(nodeName, listOf(), listOf(key))))
    }

    @Synchronized
    fun <T> editKeys(nodeName: KeyListNodeName<T>, keysToAdd: List<T>, keysToRemove: List<T>): Long {
        return setInputs(listOf(RefillInputChange.EditKeys(nodeName, keysToAdd, keysToRemove)))
    }

    // TODO: Reconcile with the other/refactor
    @Synchronized
    private fun <T> pruneKeyedInputsForRemovedKeysSuppressingListener(nodeName: KeyListNodeName<T>, keysRemoved: Set<T>, timestamp: Long): Map<ValueId, RefillEvent<*>> {
        val events = HashMap<ValueId, RefillEvent<*>>()
        // Prune values for keys that no longer exist in the key list
        // These will now return "NoSuchKey"
        for (valueId in values.keys.toList()) {
            if (valueId is ValueId.Keyed && keysRemoved.contains(valueId.key)) {
                // Check that the key is correct
                // TODO: We can store or memoize things so this is easier to determine...
                val keyedNodeName = valueId.nodeName
                val keySourceName = definition.keyedNodes.getValue(keyedNodeName).keySourceName
                if (keySourceName == nodeName) {
                    values.remove(valueId)
                    events.put(valueId, RefillEvent.KeyRemoved.of<Any?>(valueId, timestamp))
                }
            }
        }
        return events
    }

    @Synchronized
    private fun <T> pruneKeyedInputsForRemovedKeys(nodeName: KeyListNodeName<T>, keysRemoved: Set<T>, timestamp: Long) {
        // Prune values for keys that no longer exist in the key list
        // These will now return "NoSuchKey"
        for (valueId in values.keys.toList()) {
            if (valueId is ValueId.Keyed && keysRemoved.contains(valueId.key)) {
                // Check that the key is correct
                // TODO: We can store or memoize things so this is easier to determine...
                val keyedNodeName = valueId.nodeName
                val keySourceName = definition.keyedNodes.getValue(keyedNodeName).keySourceName
                if (keySourceName == nodeName) {
                    values.remove(valueId)
                    if (valueListener != null) {
                        val event = RefillEvent.KeyRemoved.of<Any?>(valueId, timestamp)
                        valueListener!!(event)
                    }
                }
            }
        }
    }

    @Synchronized
    override fun <K, T> setKeyedInputs(nodeName: KeyedNodeName<K, T>, map: Map<K, T>): Long {
        return setInputs(listOf(RefillInputChange.SetKeyed(nodeName, map)))
    }

        // TODO: Add a setKeyedInputs that takes a map as an argument
    @Synchronized
    override fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long {
//        val node = definition.keyedNodes[nodeName]
//        // TODO: Write tests for these error cases
//        if (node == null) {
//            throw IllegalArgumentException("Unrecognized node name $nodeName")
//        }
//        if (node.operation != null) {
//            throw IllegalArgumentException("Cannot directly modify the value of a non-input node $nodeName")
//        }
//
//        // If the key doesn't exist, ignore this
//        val keyListValueId = ValueId.FullKeyList(node.keySourceName)
//        val keyListValueHolder = values[keyListValueId]!!
//
//        if (keyListValueHolder.getValue() == null) {
//            error("Internal error: The key list should be an input and therefore should already have a value holder")
//        } else if (!(keyListValueHolder.getValue() as KeyList<K>).contains(key)) {
//            // Ignore this input
//            return curTimestamp
//        }
//
//        val keyedValueId = ValueId.Keyed(nodeName, key)
//
//        curTimestamp++
//        setValue(keyedValueId, curTimestamp, value, null)
//        return curTimestamp
        return setInputs(listOf(RefillInputChange.SetKeyed(nodeName, mapOf(key to value))))
    }

    @Synchronized
    private fun <K, T> applySetKeyedChange(change: RefillInputChange.SetKeyed<K, T>, newTimestamp: Long): Map<ValueId, RefillEvent<*>> {
        val (nodeName, map) = change
        val node = definition.keyedNodes[nodeName]!!

        // If the key doesn't exist, ignore this
        val keyListValueId = ValueId.FullKeyList(node.keySourceName)
        val keyListValueHolder = values[keyListValueId]!!

        if (keyListValueHolder.getValue() == null) {
            error("Internal error: The key list should be an input and therefore should already have a value holder")
        }

        val events = HashMap<ValueId, RefillEvent<*>>()
//        var anythingChanged = false
        for ((key, value) in map) {
            // Only apply if the key currently exists
            if ((keyListValueHolder.getValue() as KeyList<K>).contains(key)) {
//                anythingChanged = true

                val keyedValueId = ValueId.Keyed(nodeName, key)

                // TODO: Maybe don't register a change in some cases if the new value is the same or value-equals
                setValueSuppressingListener(keyedValueId, newTimestamp, value, null)

                events.put(keyedValueId, RefillEvent.Computed.of(keyedValueId, value, newTimestamp))
            }
        }
        return events
    }

    /*
    Go through each node in topological order
    For each one, we should have recorded the timestamp associated with its current value

    Next we look at the node's inputs (if no inputs, it is up-to-date unless its timestamp is -1 for uninitialized)
    For each input, we want to know 1) if that input is up-to-date, and 2) if so, its timestamp
    If any input is not up-to-date, the node is not ready to be recomputed
    If all inputs are up-to-date, then the maximum of the inputs' timestamps is the desired timestamp of this node
    So either this node is that timestamp (do nothing) or it is not (create a RefillStep for that node with that timestamp)
    Then we record that this node is up-to-date or its timestamp

    How does this differ for keylist nodes and keyed nodes? A keylist node can have multiple timestamps for its constituent
    keys; keyed nodes only look at the keyed portions of the appropriate inputs. Nothing that fundamentally alters the
    above algorithm.

    This also has the job of propagating errors into failures throughout the graph.

    This also has the job of updating "latest consistent" timestamps for values due to unrelated inputs changing.
     */
    // TODO: Propagating "input is missing" should be similar to propagating errors. But should they be different concepts
    // or a combined notion of failure? One difference is that "catch" should only apply to errors, not missing inputs...
    // TODO: Update all nodes's "consistent" timestamps to curTimestamp if they're up-to-date
    @Synchronized
    override fun getNextSteps(): List<RefillStep> {
        val nextSteps = ArrayList<RefillStep>()
        val timeStampIfUpToDate = HashMap<ValueId, Long>()
        fun updateLatestConsistentTimestamp(valueId: ValueId) {
            values[valueId]!!.setLatestConsistentTimestamp(curTimestamp)
        }
        for (nodeName in definition.topologicalOrdering) {
            when (nodeName) {
                is NodeName<*> -> {
                    val node = definition.nonkeyedNodes[nodeName]!!
                    val nodeValueId = ValueId.Nonkeyed(nodeName)
                    if (node.operation == null) {
                        val timestamp = values[nodeValueId]?.getTimestamp() ?: -1L
                        if (timestamp >= 0L) {
                            timeStampIfUpToDate[nodeValueId] = timestamp
                            updateLatestConsistentTimestamp(nodeValueId)
                        }
                    } else {
                        var anyInputNotUpToDate = false
                        var maximumInputTimestamp = -1L
                        val inputValues = ArrayList<Any?>()
                        val inputFailures = ArrayList<RefillFailure>()
                        for (input in node.inputs) {
                            val inputValueId = getValueIdFromInput(input)
                            val timeStampMaybe = timeStampIfUpToDate[inputValueId]
                            if (timeStampMaybe == null) {
                                anyInputNotUpToDate = true
                            } else {
                                maximumInputTimestamp = Math.max(maximumInputTimestamp, timeStampMaybe)
                                val contents = values[inputValueId]!!
                                val failure = contents.getFailure()
                                if (failure != null) {
                                    inputFailures.add(failure)
                                } else {
                                    // Transform KeyLists into Lists
                                    if (input is RefillInput.KeyList<*>) {
                                        inputValues.add((contents.getValue() as KeyList<*>).asList())
                                    } else {
                                        inputValues.add(contents.getValue())
                                    }
                                }
                            }
                        }
                        if (!anyInputNotUpToDate) {
                            // All inputs are up-to-date
                            if (inputFailures.isNotEmpty()) {
                                // Aggregate the failures for reporting
                                val curValueTimestamp = values[nodeValueId]?.getTimestamp()
                                if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                    val newFailure = combineFailures(inputFailures)

                                    val onCatch = node.onCatch
                                    if (onCatch != null) {
                                        nextSteps.add(
                                            RefillStep(nodeValueId, maximumInputTimestamp, instanceId, { onCatch(newFailure) }, null)
                                        )
                                    } else {
                                        setValue(nodeValueId, maximumInputTimestamp, null, newFailure)
                                        timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                        updateLatestConsistentTimestamp(nodeValueId)
                                    }
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                    updateLatestConsistentTimestamp(nodeValueId)
                                }
                            } else {
                                val curValueTimestamp = values[nodeValueId]?.getTimestamp()
                                if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                    // We should compute this (pass in the maximumInputTimestamp and the appropriate input values)

                                    val operation = node.operation ?: error("This was supposed to be an input node, I guess")
                                    nextSteps.add(
                                        RefillStep(
                                            nodeValueId,
                                            maximumInputTimestamp,
                                            instanceId,
                                            { operation(inputValues) },
                                            node.onCatch)
                                    )
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    // Report this as being up-to-date for future things
                                    timeStampIfUpToDate[nodeValueId] = curValueTimestamp
                                    updateLatestConsistentTimestamp(nodeValueId)
                                }
                            }
                        }
                    }
                }
                is KeyListNodeName<*> -> {
                    val node = definition.keyListNodes[nodeName]!!
                    val nodeValueId = ValueId.FullKeyList(nodeName)
                    // TODO: We can store things differently to make this more efficient
                    val keyValueIds = values.keys.filter { it is ValueId.KeyListKey && it.nodeName == nodeName }
                    if (node.operation == null) {
                        val timestamp = values[nodeValueId]?.getTimestamp() ?: -1L
                        if (timestamp >= 0L) {
                            timeStampIfUpToDate[nodeValueId] = timestamp
                            updateLatestConsistentTimestamp(nodeValueId)
                            for (keyValueId in keyValueIds) {
                                timeStampIfUpToDate[keyValueId] = values[keyValueId]!!.getTimestamp()
                                updateLatestConsistentTimestamp(keyValueId)
                            }
                        } else {
                            // Key lists are initialized to be empty
                            error("Key lists should have been initialized to be empty")
                        }
                    } else {
                        var anyInputNotUpToDate = false
                        var maximumInputTimestamp = -1L
                        val inputValues = ArrayList<Any?>()
                        val inputFailures = ArrayList<RefillFailure>()
                        for (input in node.inputs) {
                            val unkeyedInputValueId = getValueIdFromInput(input)
                            val timeStampMaybe = timeStampIfUpToDate[unkeyedInputValueId]
                            if (timeStampMaybe == null) {
                                anyInputNotUpToDate = true
                            } else {
                                maximumInputTimestamp = Math.max(maximumInputTimestamp, timeStampMaybe)
                                val contents = values[unkeyedInputValueId]!!
                                val failure = contents.getFailure()
                                if (failure != null) {
                                    inputFailures.add(failure)
                                } else {
                                    // Transform KeyLists into Lists
                                    if (input is RefillInput.KeyList<*>) {
                                        inputValues.add((contents.getValue() as KeyList<*>).asList())
                                    } else {
                                        inputValues.add(contents.getValue())
                                    }
                                }
                            }
                        }
                        if (!anyInputNotUpToDate) {
                            // All inputs are up-to-date
                            if (inputFailures.isNotEmpty()) {
                                // Aggregate the failures for reporting
                                val curValueTimestamp = values[nodeValueId]?.getTimestamp()
                                if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                    val newFailure = combineFailures(inputFailures)

                                    val onCatch = node.onCatch
                                    if (onCatch != null) {
                                        nextSteps.add(
                                            RefillStep(nodeValueId, maximumInputTimestamp, instanceId, { onCatch(newFailure) }, null)
                                        )
                                    } else {
                                        setValue(nodeValueId, maximumInputTimestamp, null, newFailure)
                                        timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                        updateLatestConsistentTimestamp(nodeValueId)
                                    }
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                    updateLatestConsistentTimestamp(nodeValueId)
                                }
                            } else {
                                val curValueTimestamp = values[nodeValueId]?.getTimestamp()
                                if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                    // We should compute this (pass in the maximumInputTimestamp and the appropriate input values)

                                    val operation = node.operation ?: error("This was supposed to be an input node, I guess")
                                    nextSteps.add(
                                        RefillStep(
                                            nodeValueId,
                                            maximumInputTimestamp,
                                            instanceId,
                                            { operation(inputValues) },
                                            node.onCatch)
                                    )
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    // Report this as being up-to-date for future things
                                    timeStampIfUpToDate[nodeValueId] = curValueTimestamp
                                    updateLatestConsistentTimestamp(nodeValueId)
                                }
                            }
                        }
                    }
                }
                is KeyedNodeName<*, *> -> {
                    val node = definition.keyedNodes.getValue(nodeName)
                    val keySourceName = node.keySourceName
                    val fullKeyListId = ValueId.FullKeyList(keySourceName)

                    val isKeySourceUpToDate = timeStampIfUpToDate.containsKey(fullKeyListId)
                    if (isKeySourceUpToDate) {
                        var anyKeyedValueNotUpToDate = false
                        var maximumInputTimestampAcrossAllKeys = timeStampIfUpToDate[fullKeyListId]!!
                        val allInputFailuresAcrossAllKeys = ArrayList<RefillFailure>()

                        val keyListHolder = values[fullKeyListId]!!
                        val keyList = if (keyListHolder.getFailure() != null) {
                            allInputFailuresAcrossAllKeys.add(keyListHolder.getFailure()!!)
                            KeyList.empty<Any?>()
                        } else {
                            keyListHolder.getValue() as KeyList<*>
                        }

                        for (key in keyList.list) {
                            if (node.operation == null) {
                                // This keyed node is an input
                                val keyedInputValueId = ValueId.Keyed(nodeName, key)

                                val timestamp = values[keyedInputValueId]?.getTimestamp() ?: -1L
                                if (timestamp >= 0L) {
                                    timeStampIfUpToDate[keyedInputValueId] = timestamp
                                    updateLatestConsistentTimestamp(keyedInputValueId)
                                    val failure = values[keyedInputValueId]!!.getFailure()
                                    if (failure != null) {
                                        allInputFailuresAcrossAllKeys.add(failure)
                                    }
                                } else {
                                    val failure = RefillFailure(mapOf(), setOf(keyedInputValueId))

                                    setValue(keyedInputValueId, keyListHolder.getTimestamp(), null, failure)
                                    timeStampIfUpToDate[keyedInputValueId] = keyListHolder.getTimestamp()
                                    updateLatestConsistentTimestamp(keyedInputValueId)

                                    allInputFailuresAcrossAllKeys.add(failure)
                                }
                                maximumInputTimestampAcrossAllKeys = Math.max(maximumInputTimestampAcrossAllKeys, timestamp)
                            } else {
                                var anyInputNotUpToDate = false
                                var maximumInputTimestamp =
                                    values.getValue(ValueId.KeyListKey(keySourceName, key)).getTimestamp()
                                val inputValues = ArrayList<Any?>()
                                val inputFailures = ArrayList<RefillFailure>()
                                for (input in node.inputs) {
                                    val unkeyedInputValueId = getValueIdFromInput(input, key)
                                    val timeStampMaybe = timeStampIfUpToDate[unkeyedInputValueId]
                                    if (timeStampMaybe == null) {
                                        anyInputNotUpToDate = true
                                        anyKeyedValueNotUpToDate = true
                                    } else {
                                        maximumInputTimestamp = Math.max(maximumInputTimestamp, timeStampMaybe)
                                        val contents = values[unkeyedInputValueId]!!
                                        val failure = contents.getFailure()
                                        if (failure != null) {
                                            inputFailures.add(failure)
                                        } else {
                                            // Transform KeyLists into Lists
                                            if (input is RefillInput.KeyList<*>) {
                                                inputValues.add((contents.getValue() as KeyList<*>).asList())
                                            } else {
                                                inputValues.add(contents.getValue())
                                            }
                                        }
                                    }
                                }
                                maximumInputTimestampAcrossAllKeys =
                                    Math.max(maximumInputTimestampAcrossAllKeys, maximumInputTimestamp)
                                val keyedValueId = ValueId.Keyed(nodeName, key)
                                if (!anyInputNotUpToDate) {
                                    // All inputs are up-to-date
                                    if (inputFailures.isNotEmpty()) {
                                        // Aggregate the failures for reporting
                                        val curValueTimestamp = values[keyedValueId]?.getTimestamp()
                                        if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                            val newFailure = combineFailures(inputFailures)

                                            val onCatch = node.onCatch
                                            if (onCatch != null) {
                                                nextSteps.add(
                                                    RefillStep(
                                                        keyedValueId,
                                                        maximumInputTimestamp,
                                                        instanceId,
                                                        { onCatch(newFailure) },
                                                        null)
                                                )
                                                anyKeyedValueNotUpToDate = true
                                            } else {
                                                setValue(keyedValueId, maximumInputTimestamp, null, newFailure)
                                                timeStampIfUpToDate[keyedValueId] = maximumInputTimestamp
                                                updateLatestConsistentTimestamp(keyedValueId)
                                                allInputFailuresAcrossAllKeys.addAll(inputFailures)
                                            }
                                        } else if (curValueTimestamp > maximumInputTimestamp) {
                                            error("This should never happen")
                                        } else {
                                            timeStampIfUpToDate[keyedValueId] = maximumInputTimestamp
                                            updateLatestConsistentTimestamp(keyedValueId)
                                            // Note: I am very doubtful that I got the following logic just right...
                                            if (node.onCatch == null || values[keyedValueId]!!.getFailure() != null) {
                                                allInputFailuresAcrossAllKeys.addAll(inputFailures)
                                            } else if (values[keyedValueId]!!.getFailure() != null) {
                                                allInputFailuresAcrossAllKeys.add(values[keyedValueId]!!.getFailure()!!)
                                            }
                                        }
                                    } else {
                                        val curValueTimestamp = values[keyedValueId]?.getTimestamp()
                                        if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                            // We should compute this (pass in the maximumInputTimestamp and the appropriate input values)

                                            val operation = node.operation as (Any?, List<*>) -> Any?
                                            nextSteps.add(
                                                RefillStep(
                                                    keyedValueId,
                                                    maximumInputTimestamp,
                                                    instanceId,
                                                    { operation(key, inputValues) },
                                                    node.onCatch)
                                            )
                                            anyKeyedValueNotUpToDate = true
                                        } else if (curValueTimestamp > maximumInputTimestamp) {
                                            error("This should never happen")
                                        } else {
                                            // Report this as being up-to-date for future things
                                            timeStampIfUpToDate[keyedValueId] = curValueTimestamp
                                            updateLatestConsistentTimestamp(keyedValueId)
                                        }
                                    }
                                }
                            }
                        }
                        // TODO: Remove values for non-up-to-date keys

                        // If every key value is up-to-date, but the full keyed list is not, update the keyed list and prune old values
                        if (!anyKeyedValueNotUpToDate) {
                            // All keyed values are up-to-date; update the full list
                            val fullListValueId = ValueId.FullKeyedList(nodeName)
                            if (allInputFailuresAcrossAllKeys.isNotEmpty()) {
                                val combinedFailure = combineFailures(allInputFailuresAcrossAllKeys)
                                setValue(fullListValueId, maximumInputTimestampAcrossAllKeys, null, combinedFailure)
                                timeStampIfUpToDate[fullListValueId] = maximumInputTimestampAcrossAllKeys
                                updateLatestConsistentTimestamp(fullListValueId)
                            } else {
                                val newList = ArrayList<Any?>()
                                val failures = ArrayList<RefillFailure>()
                                for (key in keyList.list) {
                                    val value = values[ValueId.Keyed(nodeName, key)]!!
                                    val failure = value.getFailure()
                                    if (failure != null) {
                                        failures.add(failure)
                                    } else {
                                        newList.add(value.getValue())
                                    }
                                }
                                if (failures.isEmpty()) {
                                    setValue(fullListValueId, maximumInputTimestampAcrossAllKeys, newList, null)
                                } else {
                                    val combinedFailure = combineFailures(failures)
                                    setValue(fullListValueId, maximumInputTimestampAcrossAllKeys, null, combinedFailure)
                                }
                                timeStampIfUpToDate[fullListValueId] = maximumInputTimestampAcrossAllKeys
                                updateLatestConsistentTimestamp(fullListValueId)
                            }

                        }
                    }
                }
            }

        }

        return nextSteps
    }

    @Synchronized
    private fun combineFailures(inputFailures: List<RefillFailure>): RefillFailure {
        val allErrors = LinkedHashMap<ValueId, Throwable>()
        val allMissingInputs = LinkedHashSet<ValueId>()
        for (failure in inputFailures) {
            allErrors.putAll(failure.errors)
            allMissingInputs.addAll(failure.missingInputs)
        }
        return RefillFailure(allErrors, allMissingInputs)
    }

    // TODO: This should probably just be a function on RefillInput
    // TODO: This doesn't really work for keyed node outputs: we also need to be able to get both things tied to a key
    // and the list containing all the keys
    @Synchronized
    private fun getValueIdFromInput(input: RefillInput<*>, key: Any? = null): ValueId {
        return when (input) {
            is RefillBuiltNode -> ValueId.Nonkeyed(input.name)
            is RefillInput.KeyList<*> -> ValueId.FullKeyList(input.name)
            is RefillInput.Keyed<*, *> -> ValueId.Keyed(input.name, key)
            is RefillInput.FullKeyedList<*, *> -> ValueId.FullKeyedList(input.name)
        }
    }

    // Explicitly not synchronized
    override fun completeSynchronously() {
        while (true) {
            val nextSteps = getNextSteps()
            if (nextSteps.isEmpty()) {
                return
            }
            for (step in nextSteps) {
                val result = step.execute()
                this.reportResult(result)
            }
        }
    }

    @Synchronized
    override fun reportResult(result: RefillStepResult) {
        if (instanceId != result.instanceId) {
            throw IllegalArgumentException("Received a result that did not originate from this instance")
        }

        val valueId = result.valueId
        when (valueId) {
            is ValueId.Nonkeyed -> {
                reportBasicResult(valueId, result.timestamp, result.result, result.error)
            }
            is ValueId.FullKeyList -> {
                reportKeyListResult(valueId, result.timestamp, result.result, result.error)
            }
            is ValueId.KeyListKey -> TODO()
            is ValueId.Keyed -> {
                reportKeyedResult(valueId, result.timestamp, result.result, result.error)
            }
            is ValueId.FullKeyedList -> TODO()
            else -> error("Unhandled valueId type")
        }
    }

    @Synchronized
    private fun reportBasicResult(valueId: ValueId.Nonkeyed, timestamp: Long, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || timestamp > valueHolder.getTimestamp()) {
            val failure = error?.let { RefillFailure(mapOf(valueId to it), setOf()) }
            setValue(valueId, timestamp, result, failure)
        }
    }

    @Synchronized
    private fun reportKeyedResult(valueId: ValueId.Keyed, timestamp: Long, result: Any?, error: Throwable?) {
        // If the key doesn't still exist, ignore the result (don't store, don't trigger listeners)
        val keySourceName = definition.keyedNodes[valueId.nodeName]!!.keySourceName
        val keyListHolder = values[ValueId.FullKeyList(keySourceName)]
        if (keyListHolder == null) {
            return
        }
        val keyExists = (keyListHolder.getValue() as KeyList<Any?>).contains(valueId.key)

        if (!keyExists) {
            return
        }

        val valueHolder = values[valueId]
        if (valueHolder == null || timestamp > valueHolder.getTimestamp()) {
            val failure = error?.let { RefillFailure(mapOf(valueId to it), setOf()) }
            setValue(valueId, timestamp, result, failure)
        }
        // TODO: For keyed nodes, also adjust the values/timestamps of the whole list (?)
    }

    @Synchronized
    private fun reportKeyListResult(valueId: ValueId.FullKeyList, timestamp: Long, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || timestamp > valueHolder.getTimestamp()) {
            val failure = error?.let { RefillFailure(mapOf(valueId to it), setOf()) }
            if (result == null) {
                setValue(valueId, timestamp, null, failure)
            } else {
                val newList = KeyList.copyOf(result as List<*>)
                val oldList = valueHolder?.getValue() as? KeyList<*> ?: KeyList.empty<Any?>()
                setValue(valueId, timestamp, newList, failure)

                val additions = HashSet(newList.set)
                additions.removeAll(oldList.set)
                val removals = HashSet(oldList.set)
                removals.removeAll(newList.set)

                for (addition in additions) {
                    val keyValueId = ValueId.KeyListKey(valueId.nodeName, addition)
                    setValue(keyValueId, timestamp, true, failure)
                }
                for (removal in removals) {
                    val keyValueId = ValueId.KeyListKey(valueId.nodeName, removal)
                    setValue(keyValueId, timestamp, false, failure)
                }
                pruneKeyedInputsForRemovedKeys(valueId.nodeName as KeyListNodeName<Any?>, removals, timestamp)
            }
        }
    }

    @Synchronized
    override fun <T> getNodeValue(nodeName: NodeName<T>): T {
        val outcome = getNodeOutcome(nodeName)
        when (outcome) {
            is NodeOutcome.Computed -> {
                return outcome.value
            }
            is NodeOutcome.NotYetComputed -> {
                throw IllegalStateException("Value for node $nodeName has not yet been computed")
            }
            is NodeOutcome.Failure -> {
                outcome.failure.errors.values
                val exception = IllegalStateException("Value for node $nodeName was not computed successfully: ${outcome.failure}")
                for (e in outcome.failure.errors.values) {
                    exception.addSuppressed(e)
                }
                throw exception
            }
            is NodeOutcome.NoSuchKey -> {
                error("This shouldn't happen in this particular function")
            }
        }
    }

    // TODO: Descriptive exceptions
    @Synchronized
    override fun <T> getNodeValue(nodeName: KeyListNodeName<T>): List<T> {
        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    // TODO: Descriptive exceptions
    @Synchronized
    override fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>, key: K): V {
        return (getNodeOutcome(nodeName, key) as NodeOutcome.Computed).value
    }

    // TODO: Descriptive exceptions
    @Synchronized
    override fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>): List<V> {
        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    @Synchronized
    override fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
        if (!definition.nonkeyedNodes.containsKey(nodeName)) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val value = values[ValueId.Nonkeyed(nodeName)]
        if (value == null) {
            return NodeOutcome.NotYetComputed.get()
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure(failure)
        }
        return NodeOutcome.Computed(value.getValue() as T)
    }

    @Synchronized
    override fun <T> getNodeOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>> {
        if (!definition.keyListNodes.containsKey(nodeName)) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val value = values[ValueId.FullKeyList(nodeName)]
        if (value == null) {
            return NodeOutcome.NotYetComputed.get()
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure(failure)
        }
        return NodeOutcome.Computed((value.getValue() as KeyList<T>).asList())
    }

    @Synchronized
    override fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V> {
        val nodeDefinition = definition.keyedNodes[nodeName]
        if (nodeDefinition == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val keyListId = ValueId.FullKeyList(nodeDefinition.keySourceName)
        val keyListValueHolder = values[keyListId]
        if (keyListValueHolder == null || keyListValueHolder.getValue() == null) {
            // The key list itself has not been successfully computed
            return NodeOutcome.NotYetComputed.get()
        }
        if (!(keyListValueHolder.getValue() as KeyList<K>).contains(key)) {
            // TODO: We may not need to check the key list directly at this point... (?)
            return NodeOutcome.NoSuchKey.get()
        }
        val value = values[ValueId.Keyed(nodeName, key)]
        if (value == null) {
            return NodeOutcome.NotYetComputed.get()
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure(failure)
        }
        return NodeOutcome.Computed(value.getValue() as V)
    }

    @Synchronized
    override fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>> {
        val nodeDefinition = definition.keyedNodes[nodeName]
        if (nodeDefinition == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }

        val value = values[ValueId.FullKeyedList(nodeName)]
        if (value == null) {
            return NodeOutcome.NotYetComputed.get()
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure(failure)
        }
        return NodeOutcome.Computed(value.getValue() as List<V>)
    }

    internal fun getValueDirectlyForTesting(valueId: ValueId): Any? {
        return values[valueId]?.getValue()
    }

    @Synchronized
    internal fun printStoredState() {
        println("*** Raw instance state ***")
        println("  Current timestamp: $curTimestamp")
        val sortedValueIds = ArrayList(values.keys)
        sortedValueIds.sortBy { it.toString() }
        for (valueId in sortedValueIds) {
            val valueHolder = values[valueId]
            if (valueHolder != null) {
                println("  ${valueId}: ${valueHolder.getValue()}, ${valueHolder.getFailure()} (${valueHolder.getTimestamp()})")
            }
        }
        println("**************************")
    }

    /**
     * This returns the last timestamp for which the given value ID had an update due to an input changing.
     *
     * This is the timestamp at which the current value was computed. (TODO: How will this work with equality-checking)
     *
     * This does not update as unrelated inputs nodes change.
     */
    @Synchronized
    override fun getLastUpdateTimestamp(valueId: ValueId): Long {
        val value = values[valueId]
        return if (value == null) {
            -1L
        } else {
            value.getTimestamp()
        }
    }

    /**
     * This returns the last timestamp that is consistent with the value ID's current value.
     *
     * This can update as unrelated input nodes change.
     */
    @Synchronized
    override fun getLatestTimestampWithValue(valueId: ValueId): Long {
        val value = values[valueId]
        return if (value == null) {
            -1L
        } else {
            value.getLatestConsistentTimestamp()
        }
    }
}

sealed class NodeOutcome<T> {
    class NotYetComputed<T> private constructor(): NodeOutcome<T>() {
        companion object {
            private val INSTANCE = NotYetComputed<Any>()
            fun <T> get(): NotYetComputed<T> {
                return INSTANCE as NotYetComputed<T>
            }
        }
        override fun toString(): String {
            return "NotYetComputed"
        }
        override fun equals(other: Any?): Boolean {
            return other is NotYetComputed<*>
        }
        override fun hashCode(): Int {
            return 309803249
        }
    }
    class NoSuchKey<T> private constructor(): NodeOutcome<T>() {
        companion object {
            private val INSTANCE = NoSuchKey<Any>()
            fun <T> get(): NoSuchKey<T> {
                return INSTANCE as NoSuchKey<T>
            }
        }
        override fun toString(): String {
            return "NoSuchKey"
        }
        override fun equals(other: Any?): Boolean {
            return other is NoSuchKey<*>
        }
        override fun hashCode(): Int {
            return 892753869
        }
    }
    data class Computed<T>(val value: T): NodeOutcome<T>()
    data class Failure<T>(val failure: RefillFailure): NodeOutcome<T>()
}

// For use with listeners
/*
 * Note: The weird timestamp manipulation covers the following case:
 * - We have input keylist node A and non-input keyed node B sourced by A
 * - A change gets applied to A that removes and re-adds a key (i.e. an EditKeys with the key in both the removes and adds list)
 * - One event will get triggered for the key getting removed from B, and one from it being recomputed; the Refill instance will
 *   give these both the same timestamp, so we differentiate the timestamps at the event level by giving KeyRemoved events lower
 *   timestamps than Computed or Failure events
 *
 * TODO: Shift this timestamp change to inside the instance so these can just be instantiated normally again
 */
sealed class RefillEvent<T> {
    abstract val valueId: ValueId
    abstract val timestamp: Long
    data class Computed<T> private constructor(override val valueId: ValueId, val value: T, override val timestamp: Long): RefillEvent<T>() {
        companion object {
            fun <T> of(valueId: ValueId, value: T, timestamp: Long): Computed<T> {
                return Computed(valueId, value, timestamp*2 + 1)
            }
        }
    }
    data class Failure<T> private constructor(override val valueId: ValueId, val failure: RefillFailure, override val timestamp: Long): RefillEvent<T>() {
        companion object {
            fun <T> of(valueId: ValueId, failure: RefillFailure, timestamp: Long): Failure<T> {
                return Failure(valueId, failure, timestamp*2 + 1)
            }
        }
    }
    data class KeyRemoved<T> private constructor(override val valueId: ValueId.Keyed, override val timestamp: Long): RefillEvent<T>() {
        companion object {
            fun <T> of(valueId: ValueId.Keyed, timestamp: Long): KeyRemoved<T> {
                return KeyRemoved(valueId, timestamp*2)
            }
        }
    }
}

class RefillStep internal constructor(
        val valueId: ValueId,
        val timestamp: Long,
        val instanceId: RefillInstance.Id,
        val operation: () -> Any?,
        val onCatch: ((RefillFailure) -> Any?)?
) {
    fun execute(): RefillStepResult {
        try {
            val result = operation()
            return RefillStepResult(valueId, timestamp, instanceId, result, null)
        } catch (t: Throwable) {
            if (t is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (onCatch != null) {
                try {
                    val result = onCatch!!(RefillFailure(mapOf(valueId to t), setOf()))
                    return RefillStepResult(valueId, timestamp, instanceId, result, null)
                } catch (t2: Throwable) {
                    t.addSuppressed(t2)
                    return RefillStepResult(valueId, timestamp, instanceId, null, t)
                }
            }
            return RefillStepResult(valueId, timestamp, instanceId, null, t)
        }
    }

    override fun toString(): String {
        return "RefillStep($valueId, $timestamp)"
    }

    // Note: equals() and hashCode() ignore the operation
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RefillStep

        if (valueId != other.valueId) return false
        if (timestamp != other.timestamp) return false
        if (instanceId != other.instanceId) return false

        return true
    }

    // Note: equals() and hashCode() ignore the operation
    override fun hashCode(): Int {
        var result = valueId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + instanceId.hashCode()
        return result
    }
}

data class RefillStepResult internal constructor(
        val valueId: ValueId,
        val timestamp: Long,
        val instanceId: RefillInstance.Id,
        val result: Any?,
        val error: Throwable?
)

// TODO: Add a method for turning this into a single Exception with a reasonable human-friendly summary
data class RefillFailure(val errors: Map<ValueId, Throwable>, val missingInputs: Set<ValueId>)

// General idea: For any possible state the input nodes could be in, the input and output set of changes should result
// in the same end state, with the difference that the output set of changes contains only one entry per input node
//private fun coalesceChanges(changes: List<RefillInputChange>, definition: RefillDefinition): List<RefillInputChange> {
//    val changePerNode = LinkedHashMap<GenericNodeName, RefillInputChange>()
//
//    for (curChange in changes) {
//        val existingChange = changePerNode[curChange.nodeName]
//        if (existingChange == null) {
//            changePerNode[curChange.nodeName] = curChange
//        } else {
//            val modifiedChange: RefillInputChange = when (curChange) {
//                is RefillInputChange.SetBasic<*> -> {
//                    // New change overwrites the old
//                    curChange
//                }
//                is RefillInputChange.SetKeys<*> -> {
//                    // New change overwrites the old
//                    curChange
//                }
//                is RefillInputChange.EditKeys<*> -> {
//                    val nodeName = curChange.nodeName as KeyListNodeName<Any?>
//                    when (existingChange) {
//                        is RefillInputChange.SetBasic<*> -> error("Refill internal error")
//                        is RefillInputChange.SetKeys<*> -> {
//                            var keys = KeyList.copyOf(existingChange.value)
//                            keys = keys.removeAll(curChange.keysRemoved)
//                            keys = keys.addAll(curChange.keysAdded)
//                            RefillInputChange.SetKeys(nodeName, keys.asList())
//                        }
//                        is RefillInputChange.EditKeys<*> -> {
//                            val keysRemoved = existingChange.keysRemoved + curChange.keysRemoved
//                            val keysAdded = (existingChange.keysAdded - curChange.keysRemoved) + curChange.keysAdded
//                            RefillInputChange.EditKeys(nodeName, keysAdded, keysRemoved)
//                        }
//                        is RefillInputChange.SetKeyed<*, *> -> TODO()
//                    }
//                }
//                is RefillInputChange.SetKeyed<*, *> -> {
//                    when (existingChange) {
//                        is RefillInputChange.SetBasic<*> -> TODO()
//                        is RefillInputChange.SetKeys<*> -> TODO()
//                        is RefillInputChange.EditKeys<*> -> TODO()
//                        is RefillInputChange.SetKeyed<*, *> -> {
//                            val map = HashMap<Any?, Any?>()
//                            map.putAll(existingChange.map)
//                            map.putAll(curChange.map)
//                            RefillInputChange.SetKeyed(curChange.nodeName as KeyedNodeName<Any?, Any?>, map)
//                        }
//                    }
//                }
//            }
//            // Remove and re-add to force it to come last (relevant for interactions between key and keyed inputs)
//            changePerNode.remove(curChange.nodeName)
//            changePerNode[curChange.nodeName] = modifiedChange
//        }
//
//        // TODO: Also deal with the interaction between key changes and keyed input changes
//        // TODO: The coalescing approach can't actually deal with the following case...
//        // Set B_KEYED to {1=10}, set A_KEYS to [1, 2], set B_KEYED to {2=20}
//        // If 1 was already in A_KEYS, this should set B_KEYED[1] to 10, but it shouldn't if it wasn't already in
//        // A_KEYS, so we can't drop the B_KEYED[1] setter and we can't move it after the A_KEYS setting.
//        // But we also can't move the B_KEYED[2] setter before A_KEYS. So we have to set in multiple operations.
//        // We could still do this if we split up the keyed changes by their keys, but that's kinda hacky and still
//        // results in listeners triggering on otherwise invisible values.
//        // So... switch to a listener suppression/aggregation approach elsewhere?
//        if (curChange is RefillInputChange.SetKeys<*> || curChange is RefillInputChange.EditKeys<*>) {
//            for ((keyedName, change) in changePerNode.entries.toList()) {
//                if (keyedName is KeyedNodeName<*, *>) {
//                    if (curChange.nodeName == definition.keyedNodes[keyedName]!!.keySourceName) {
//                        change as RefillInputChange.SetKeyed<*, *>
//                        // Remove any key-setting in keys being removed or not included
//                        val modifiedMap = if (curChange is RefillInputChange.SetKeys<*>) {
//                            val keysAsSet = curChange.value.toSet()
//                            change.map.filterKeys { keysAsSet.contains(it) }
//                        } else {
//                            curChange as RefillInputChange.EditKeys<*>
//                            val keysRemovedAsSet = curChange.keysRemoved.toSet()
//                            change.map.filterKeys { !keysRemovedAsSet.contains(it) }
//                        }
//                        changePerNode[keyedName] = RefillInputChange.SetKeyed(keyedName as KeyedNodeName<Any?, Any?>, modifiedMap)
//                    }
//                }
//            }
//        }
//    }
//    println("Coalesce changes result:")
//    println("  Input:  $changes")
//    println("  Output: ${changePerNode.values.toList()}")
//    return changePerNode.values.toList()
//}
