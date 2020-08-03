package net.semlang.refill

import java.util.function.Predicate


// TODO: The RefillInputReceiver methods here should probably just not return the timestamps to avoid confusion
// TODO: Should the Outcome methods here be a different type? I guess if inputs are not filled in, uncomputed is fine...
// TODO: Separate into an interface (to be returned by the RefillDefinition) and a class
class RefillSyncInstance(private val instance: RefillInstance): RefillInputReceiver {
    override fun setInputs(changes: List<RefillInputChange>): Long {
        return instance.setInputs(changes)
    }

    override fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        return instance.setInput(nodeName, value)
    }

    override fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long {
        return instance.setInput(nodeName, list)
    }

    override fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        return instance.addKeyInput(nodeName, key)
    }

    override fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        return instance.removeKeyInput(nodeName, key)
    }

    override fun <T> editKeys(nodeName: KeyListNodeName<T>, keysAdded: List<T>, keysRemoved: List<T>): Long {
        return instance.editKeys(nodeName, keysAdded, keysRemoved)
    }

    override fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long {
        return instance.setKeyedInput(nodeName, key, value)
    }

    override fun <K, T> setKeyedInputs(nodeName: KeyedNodeName<K, T>, map: Map<K, T>): Long {
        return instance.setKeyedInputs(nodeName, map)
    }

    private fun <T> doComputationsFor(nodeName: NodeName<T>) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.Nonkeyed(nodeName))
        computeRelevantValues(relevantValuesPred)
    }

    private fun <T> doComputationsFor(nodeName: KeyListNodeName<T>) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.FullKeyList(nodeName))
        computeRelevantValues(relevantValuesPred)
    }

    private fun <K, V> doComputationsFor(nodeName: KeyedNodeName<K, V>, key: K) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.Keyed(nodeName, key))
        computeRelevantValues(relevantValuesPred)
    }

    private fun <K, V> doComputationsFor(nodeName: KeyedNodeName<K, V>) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.FullKeyedList(nodeName))
        computeRelevantValues(relevantValuesPred)
    }

    private fun computeRelevantValues(relevantValueIds: Predicate<ValueId>) {
        while (true) {
            val nextSteps = instance.getNextSteps()
            val relevantNextSteps = nextSteps.filter { relevantValueIds.test(it.valueId) }
            if (relevantNextSteps.isEmpty()) {
                return
            }
            for (step in relevantNextSteps) {
                instance.reportResult(step.execute())
            }
        }
    }

    fun <T> getValue(nodeName: NodeName<T>): T {
        doComputationsFor(nodeName)
        return instance.getNodeValue(nodeName)
    }

    fun <T> getValue(nodeName: KeyListNodeName<T>): List<T> {
        doComputationsFor(nodeName)
        return instance.getNodeValue(nodeName)
    }

    fun <K, V> getValue(nodeName: KeyedNodeName<K, V>, key: K): V {
        doComputationsFor(nodeName, key)
        return instance.getNodeValue(nodeName, key)
    }

    fun <K, V> getValue(nodeName: KeyedNodeName<K, V>): List<V> {
        doComputationsFor(nodeName)
        return instance.getNodeValue(nodeName)
    }

    fun <T> getOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
        doComputationsFor(nodeName)
        return instance.getNodeOutcome(nodeName)
    }

    fun <T> getOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>> {
        doComputationsFor(nodeName)
        return instance.getNodeOutcome(nodeName)
    }

    fun <K, V> getOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V> {
        doComputationsFor(nodeName, key)
        return instance.getNodeOutcome(nodeName, key)
    }

    fun <K, V> getOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>> {
        doComputationsFor(nodeName)
        return instance.getNodeOutcome(nodeName)
    }
}
