package net.semlang.refill

import java.util.ArrayList

/**
 * This may be useful when debugging tests; it wraps a RefillInstance and makes it possible to see the various calls
 * made to its methods and their results.
 */
internal class RecordingRawInstance(val delegate: RefillInstance): RefillRawInstance {
    override val definition: RefillDefinition
        get() = delegate.definition
    // It would be nice to record these as actual objects, but that would take some effort
    val records = ArrayList<String>()

    @Synchronized
    override fun setInputs(changes: List<RefillInputChange>): Long {
        records.add("Calling setInputs")
        records.add("  changes: $changes")
        val result = delegate.setInputs(changes)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        records.add("Calling setInput")
        records.add("  nodeName: $nodeName")
        records.add("  value: $value")
        val result = delegate.setInput(nodeName, value)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long {
        records.add("Calling setInput")
        records.add("  nodeName: $nodeName")
        records.add("  list: $list")
        val result = delegate.setInput(nodeName, list)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        records.add("Calling addKeyInput")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        val result = delegate.addKeyInput(nodeName, key)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        records.add("Calling removeKeyInput")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        val result = delegate.removeKeyInput(nodeName, key)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long {
        records.add("Calling setKeyedInput")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        records.add("  value: $value")
        val result = delegate.setKeyedInput(nodeName, key, value)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, T> setKeyedInputs(nodeName: KeyedNodeName<K, T>, map: Map<K, T>): Long {
        records.add("Calling setKeyedInputs")
        records.add("  nodeName: $nodeName")
        records.add("  map: $map")
        val result = delegate.setKeyedInputs(nodeName, map)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun getNextSteps(): List<RefillStep> {
        records.add("Calling getNextSteps")
        val result = delegate.getNextSteps()
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun completeSynchronously() {
        records.add("Calling completeSynchronously")
        delegate.completeSynchronously()
        records.add("Done with completeSynchronously")
    }

    @Synchronized
    override fun reportResult(result: RefillStepResult) {
        records.add("Calling reportResult")
        records.add("  result (arg): $result")
        delegate.reportResult(result)
    }

    @Synchronized
    override fun <T> getNodeValue(nodeName: NodeName<T>): T {
        records.add("Calling getNodeValue")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeValue(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> getNodeValue(nodeName: KeyListNodeName<T>): List<T> {
        records.add("Calling getNodeValue")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeValue(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>, key: K): V {
        records.add("Calling getNodeValue")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        val result = delegate.getNodeValue(nodeName, key)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>): List<V> {
        records.add("Calling getNodeValue")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeValue(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
        records.add("Calling getNodeOutcome")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeOutcome(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> getNodeOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>> {
        records.add("Calling getNodeOutcome")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeOutcome(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V> {
        records.add("Calling getNodeOutcome")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        val result = delegate.getNodeOutcome(nodeName, key)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>> {
        records.add("Calling getNodeOutcome")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeOutcome(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun getLastUpdateTimestamp(valueId: ValueId): Long {
        records.add("Calling getLastUpdateTimestamp")
        records.add("  valueId: $valueId")
        val result = delegate.getLastUpdateTimestamp(valueId)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun getLatestTimestampWithValue(valueId: ValueId): Long {
        records.add("Calling getLatestTimestampWithValue")
        records.add("  valueId: $valueId")
        val result = delegate.getLatestTimestampWithValue(valueId)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    fun getRecording(): List<String> {
        return records
    }
}
