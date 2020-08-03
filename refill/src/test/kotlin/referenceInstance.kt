package com.github.alexlandau.refill

import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * A simple "reference" reimplementation of a basic instance API for use in fuzz testing. This recomputes all outputs
 * after every input change, instead of trying to minimize work in any way.
 *
 * Only the KeyList is shared with the main instance implementation.
 */
// TODO: Catching errors in nodes is not implemented in the fuzz tests and thus not implemented here
internal class ReferenceInstance(private val definition: RefillDefinition) {
    private val basicInputs = HashMap<NodeName<Int>, Int?>()
    private val keyListInputs = HashMap<KeyListNodeName<Int>, KeyList<Int>>()
    private val keyedInputs = HashMap<KeyedNodeName<Int, Int>, HashMap<Int, Int>>()
    private val basicOutputs = HashMap<NodeName<Int>, ValueOrFailure<Int>>()
    private val keyListOutputs = HashMap<KeyListNodeName<Int>, ValueOrFailure<KeyList<Int>>>()
    private val keyedOutputs = HashMap<KeyedNodeName<Int, Int>, HashMap<Int, ValueOrFailure<Int>>>()

    init {
        for (keyListNode in definition.keyListNodes.values) {
            if (keyListNode.operation == null) {
                keyListInputs[keyListNode.name as KeyListNodeName<Int>] = KeyList.empty()
            }
        }
        for (keyedNode in definition.keyedNodes.values) {
            if (keyedNode.operation == null) {
                keyedInputs[keyedNode.name as KeyedNodeName<Int, Int>] = HashMap()
            }
        }
        recomputeState()
    }

    private fun recomputeState() {
        // TODO: Don't forget to pare keyed inputs down to the right set of keys
        for (nodeName in definition.topologicalOrdering) {
            when (nodeName) {
                is NodeName<*> -> {
                    nodeName as NodeName<Int>
                    if (isInput(nodeName)) {
                        // Do nothing
                    } else {
                        // Compute and store new value
                        val node = definition.nonkeyedNodes[nodeName]!!
                        val inputResults = getInputValuesOrCombinedFailure(definition.nonkeyedNodes[nodeName]!!.inputs, null)
                        when (inputResults) {
                            is ValueOrFailure.Value -> {
                                val newValue = node.operation!!(inputResults.value) as Int
                                basicOutputs[nodeName] = ValueOrFailure.Value(newValue)
                            }
                            is ValueOrFailure.Failure -> {
                                basicOutputs[nodeName] = ValueOrFailure.Failure(inputResults.failure)
                            }
                        }
                    }
                }
                is KeyListNodeName<*> -> {
                    nodeName as KeyListNodeName<Int>
                    if (isInput(nodeName)) {
                        // Do nothing
                    } else {
                        // Compute and store new value
                        val node = definition.keyListNodes[nodeName]!!
                        val inputResults = getInputValuesOrCombinedFailure(definition.keyListNodes[nodeName]!!.inputs, null)
                        when (inputResults) {
                            is ValueOrFailure.Value -> {
                                val newValue = node.operation!!(inputResults.value) as List<Int>
                                keyListOutputs[nodeName] = ValueOrFailure.Value(KeyList.copyOf(newValue))
                            }
                            is ValueOrFailure.Failure -> {
                                keyListOutputs[nodeName] = ValueOrFailure.Failure(inputResults.failure)
                            }
                        }

                    }
                }
                is KeyedNodeName<*, *> -> {
                    nodeName as KeyedNodeName<Int, Int>
                    if (isInput(nodeName)) {
                        // Prune inputs
                        if (keyedInputs[nodeName] != null) {
                            val keyListName = definition.keyedNodes[nodeName]!!.keySourceName
                            val currentKeyList = keyListInputs[keyListName]!!
                            for (key in keyedInputs[nodeName]!!.keys.toList()) {
                                if (!currentKeyList.contains(key)) {
                                    keyedInputs[nodeName]!!.remove(key)
                                }
                            }
                        }
                    } else {
                        // TODO: Compute and store new value
                        if (keyedOutputs[nodeName] == null) {
                            keyedOutputs[nodeName] = HashMap()
                        }
                        val node = definition.keyedNodes[nodeName]!!
                        val curKeyListOutcome = getNodeOutcome(node.keySourceName as KeyListNodeName<Int>)
                        when (curKeyListOutcome) {
                            is NodeOutcome.NotYetComputed -> TODO()
                            is NodeOutcome.NoSuchKey -> TODO()
                            is NodeOutcome.Computed -> {
                                val keys = curKeyListOutcome.value
                                for (key in keys) {
                                    val inputResults =
                                        getInputValuesOrCombinedFailure(definition.keyedNodes[nodeName]!!.inputs, key)
                                    when (inputResults) {
                                        is ValueOrFailure.Value -> {
                                            val newValue =
                                                (node.operation!! as (Int, List<*>) -> Int)(key, inputResults.value)
                                            keyedOutputs[nodeName]!!.put(key, ValueOrFailure.Value(newValue))
                                        }
                                        is ValueOrFailure.Failure -> {
                                            keyedOutputs[nodeName]!!.put(
                                                key,
                                                ValueOrFailure.Failure(inputResults.failure)
                                            )
                                        }
                                    }
                                }
                            }
                            is NodeOutcome.Failure -> {
                                // TODO: Do nothing??? Propagating the failure might be better
                                // Do nothing; error propagation will happen when we try to get these results (?)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getInputValuesOrCombinedFailure(inputs: List<RefillInput<*>>, curKey: Int?): ValueOrFailure<List<*>> {
        val allInputs = ArrayList<Any?>()
        val allFailures = ArrayList<RefillFailure>()
        perInputLoop@for (input in inputs) {
            when (input) {
                is RefillBuiltNode -> {
                    val name = input.name
                    if (isInput(name)) {
                        val valueMaybe = basicInputs[name]
                        if (valueMaybe == null) {
                            allFailures.add(RefillFailure(mapOf(), setOf(ValueId.Nonkeyed(name))))
                        } else {
                            allInputs.add(valueMaybe)
                        }
                    } else {
                        when (val output = basicOutputs[name]!!) {
                            is ValueOrFailure.Value -> {
                                allInputs.add(output.value)
                            }
                            is ValueOrFailure.Failure -> {
                                allFailures.add(output.failure)
                            }
                        }
                    }
                }
                is RefillInput.KeyList<*> -> {
                    val name = input.name
                    if (isInput(name)) {
                        allInputs.add(keyListInputs[name]!!.list)
                    } else {
                        when (val output = keyListOutputs[name]!!) {
                            is ValueOrFailure.Value -> {
                                allInputs.add(output.value.list)
                            }
                            is ValueOrFailure.Failure -> {
                                allFailures.add(output.failure)
                            }
                        }
                    }
                }
                is RefillInput.Keyed<*, *> -> {
                    curKey ?: error("Expected a key to be specified")
                    val name = input.name
                    if (isInput(name)) {
                        val result = keyedInputs[name]!![curKey]
                        if (result == null) {
                            allFailures.add(RefillFailure(mapOf(), setOf(ValueId.Keyed(name, curKey))))
                        } else {
                            allInputs.add(result)
                        }
                    } else {
                        when (val output = keyedOutputs[name]!![curKey]!!) {
                            is ValueOrFailure.Value -> {
                                allInputs.add(output.value)
                            }
                            is ValueOrFailure.Failure -> {
                                allFailures.add(output.failure)
                            }
                        }
                    }
                }
                is RefillInput.FullKeyedList<*, *> -> {
                    val name = input.name
                    val keySourceName = definition.keyedNodes[name]!!.keySourceName
                    val keyList: List<Int> = if (isInput(keySourceName)) {
                        keyListInputs[keySourceName]!!.list
                    } else {
                        when (val result = keyListOutputs[keySourceName]) {
                            is ValueOrFailure.Value -> {
                                result.value.list
                            }
                            is ValueOrFailure.Failure -> {
                                allFailures.add(result.failure)
                                continue@perInputLoop
                            }
                            null -> TODO()
                        }
                    }
                    val allKeyedValues = ArrayList<Int>()
                    val allKeyedFailures = ArrayList<RefillFailure>()
                    if (isInput(name)) {
                        for (key in keyList) {
                            val valueMaybe = keyedInputs[name]!![key]
                            if (valueMaybe != null) {
                                allKeyedValues.add(valueMaybe)
                            } else {
                                allKeyedFailures.add(RefillFailure(mapOf(), setOf(ValueId.Keyed(name, key))))
                            }
                        }
                    } else {
                        for (key in keyList) {
                            when (val result = keyedOutputs[name]!![key]) {
                                is ValueOrFailure.Value -> {
                                    allKeyedValues.add(result.value)
                                }
                                is ValueOrFailure.Failure -> {
                                    allKeyedFailures.add(result.failure)
                                }
                                null -> error("I don't think this should happen")
                            }
                        }
                    }
                    if (allKeyedFailures.isNotEmpty()) {
                        allFailures.addAll(allKeyedFailures)
                    } else {
                        allInputs.add(allKeyedValues)
                    }
                }
            }
        }
        if (allFailures.isNotEmpty()) {
            return ValueOrFailure.Failure(combineFailures(allFailures))
        }
        return ValueOrFailure.Value(allInputs)
    }

    fun setInput(name: NodeName<Int>, value: Int) {
        basicInputs[name] = value
        recomputeState()
    }

    fun addKeyInput(name: KeyListNodeName<Int>, key: Int) {
        keyListInputs[name] = keyListInputs[name]!!.add(key)
        recomputeState()
    }

    fun removeKeyInput(name: KeyListNodeName<Int>, key: Int) {
        keyListInputs[name] = keyListInputs[name]!!.remove(key)
        recomputeState()
    }

    fun editKeys(name: KeyListNodeName<Int>, keysAdded: List<Int>, keysRemoved: List<Int>) {
        keyListInputs[name] = keyListInputs[name]!!.removeAll(keysRemoved).addAll(keysAdded)
        recomputeState()
    }

    fun setInput(name: KeyListNodeName<Int>, value: List<Int>) {
        keyListInputs[name] = KeyList.copyOf(value)
        recomputeState()
    }

    fun setKeyedInput(name: KeyedNodeName<Int, Int>, key: Int, value: Int) {
        keyedInputs[name]!!.put(key, value)
        recomputeState()
    }

    fun setKeyedInputs(name: KeyedNodeName<Int, Int>, map: Map<Int, Int>) {
        for ((key, value) in map) {
            keyedInputs[name]!!.put(key, value)
        }
        recomputeState()
    }

    fun setInputs(changes: List<RefillInputChange>) {
        for (change in changes) {
            when (change) {
                is RefillInputChange.SetBasic<*> -> setInput(change.nodeName as NodeName<Int>, change.value as Int)
                is RefillInputChange.SetKeys<*> -> setInput(change.nodeName as KeyListNodeName<Int>, change.value as List<Int>)
                is RefillInputChange.EditKeys<*> -> editKeys(change.nodeName as KeyListNodeName<Int>, change.keysAdded as List<Int>, change.keysRemoved as List<Int>)
                is RefillInputChange.SetKeyed<*, *> -> setKeyedInputs(change.nodeName as KeyedNodeName<Int, Int>, change.map as Map<Int, Int>)
            }
        }
    }

    fun getNodeOutcome(name: NodeName<Int>): NodeOutcome<Int> {
        if (isInput(name)) {
            return basicInputs[name]?.let { NodeOutcome.Computed(it) } ?: NodeOutcome.Failure(RefillFailure(mapOf(), setOf(ValueId.Nonkeyed(name))))
        } else {
            when (val result = basicOutputs[name]) {
                null -> {
                    return NodeOutcome.NotYetComputed.get()
                }
                is ValueOrFailure.Value -> {
                    return NodeOutcome.Computed(result.value)
                }
                is ValueOrFailure.Failure -> {
                    return NodeOutcome.Failure(result.failure)
                }
            }
        }
    }

    fun getNodeOutcome(name: KeyListNodeName<Int>): NodeOutcome<List<Int>> {
        if (isInput(name)) {
            return NodeOutcome.Computed(keyListInputs[name]!!.list)
        } else {
            when (val result = keyListOutputs[name]) {
                is ValueOrFailure.Value -> {
                    return NodeOutcome.Computed(result.value.list)
                }
                is ValueOrFailure.Failure -> {
                    return NodeOutcome.Failure(result.failure)
                }
                null -> {
                    return NodeOutcome.NotYetComputed.get()
                }
            }
        }
    }

    fun getNodeOutcome(name: KeyedNodeName<Int, Int>): NodeOutcome<List<Int>> {
        val keySourceName = definition.keyedNodes[name]!!.keySourceName as KeyListNodeName<Int>
        when (val keyListOutcome = getNodeOutcome(keySourceName)) {
            is NodeOutcome.NotYetComputed -> {
                return NodeOutcome.NotYetComputed.get()
            }
            is NodeOutcome.Failure -> {
                return NodeOutcome.Failure(keyListOutcome.failure)
            }
            is NodeOutcome.NoSuchKey -> TODO()
            is NodeOutcome.Computed -> {
                val keyList = keyListOutcome.value
                val allKeyedValues = ArrayList<Int>()
                val allFailures = ArrayList<RefillFailure>()
                for (key in keyList) {
                    if (isInput(name)) {
                        val valueMaybe = keyedInputs[name]!![key]
                        if (valueMaybe != null) {
                            allKeyedValues.add(valueMaybe)
                        } else {
                            allFailures.add(RefillFailure(mapOf(), setOf(ValueId.Keyed(name, key))))
                        }
                    } else {
                        val resultsMap = keyedOutputs[name]
                        if (resultsMap == null) {
                            return NodeOutcome.NotYetComputed.get()
                        }
                        when (val result = resultsMap[key]) {
                            is ValueOrFailure.Value -> {
                                allKeyedValues.add(result.value)
                            }
                            is ValueOrFailure.Failure -> {
                                allFailures.add(result.failure)
                            }
                            null -> {
                                error("I don't expect this case to happen...?")
                            }
                        }
                    }
                }
                if (allFailures.isNotEmpty()) {
                    return NodeOutcome.Failure(combineFailures(allFailures))
                }
                return NodeOutcome.Computed(allKeyedValues)
            }
        }
    }

    fun getNodeOutcome(name: KeyedNodeName<Int, Int>, key: Int): NodeOutcome<Int> {
        val keySourceName = definition.keyedNodes[name]!!.keySourceName as KeyListNodeName<Int>
        when (val keyListOutcome = getNodeOutcome(keySourceName)) {
            is NodeOutcome.NotYetComputed -> {
                return NodeOutcome.NotYetComputed.get()
            }
            is NodeOutcome.Failure -> {
//                return NodeOutcome.Failure(keyListOutcome.failure)
                // TODO: Do we want this? Failure might be better
                return NodeOutcome.NotYetComputed.get()
            }
            is NodeOutcome.NoSuchKey -> TODO()
            is NodeOutcome.Computed -> {
                if (!keyListOutcome.value.contains(key)) {
                    return NodeOutcome.NoSuchKey.get()
                }
                if (isInput(name)) {
                    val result = keyedInputs[name]!![key]
                    if (result == null) {
                        return NodeOutcome.Failure(RefillFailure(mapOf(), setOf(ValueId.Keyed(name, key))))
                    } else {
                        return NodeOutcome.Computed(result)
                    }
                } else {
                    val resultsMap = keyedOutputs[name]
                    if (resultsMap == null) {
                        return NodeOutcome.NotYetComputed.get()
                    }
                    when (val result = resultsMap[key]) {
                        is ValueOrFailure.Value -> {
                            return NodeOutcome.Computed(result.value)
                        }
                        is ValueOrFailure.Failure -> {
                            return NodeOutcome.Failure(result.failure)
                        }
                        null -> {
                            error("Not sure this case should occur...")
                        }
                    }
                }
            }
        }
    }

    private fun isInput(name: NodeName<*>): Boolean {
        return definition.nonkeyedNodes[name]!!.operation == null
    }

    private fun isInput(name: KeyListNodeName<*>): Boolean {
        return definition.keyListNodes[name]!!.operation == null
    }

    private fun isInput(name: KeyedNodeName<*, *>): Boolean {
        return definition.keyedNodes[name]!!.operation == null
    }
}

private sealed class ValueOrFailure<T> {
    data class Value<T>(val value: T): ValueOrFailure<T>()
    data class Failure<T>(val failure: RefillFailure): ValueOrFailure<T>()
}

private fun combineFailures(inputFailures: List<RefillFailure>): RefillFailure {
    val allErrors = LinkedHashMap<ValueId, Throwable>()
    val allMissingInputs = LinkedHashSet<ValueId>()
    for (failure in inputFailures) {
        allErrors.putAll(failure.errors)
        allMissingInputs.addAll(failure.missingInputs)
    }
    return RefillFailure(allErrors, allMissingInputs)
}
