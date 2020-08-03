package com.github.alexlandau.refill

import java.lang.IllegalArgumentException
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.ExecutorService
import java.util.function.Predicate


class RefillDefinition internal constructor(internal val nonkeyedNodes: Map<NodeName<*>, RefillNode<*>>,
                                            internal val keyListNodes: Map<KeyListNodeName<*>, RefillKeyListNode<*>>,
                                            internal val keyedNodes: Map<KeyedNodeName<*, *>, RefillKeyedNode<*, *>>,
                                            internal val topologicalOrdering: List<GenericNodeName>) {
    fun instantiateRaw(): RefillInstance {
        return RefillInstance(this)
    }
    fun instantiateSync(): RefillSyncInstance {
        return RefillSyncInstance(RefillInstance(this))
    }

    // TODO: This actually wants to be a Predicate, because we might want all keys within a group
    // TODO: This might benefit from caching
    internal fun getRelevantValuesPredicate(valueId: ValueId): Predicate<ValueId> {
        val relevantValues = HashSet<ValueId>()
        val keyedNamesWithAllKeysRelevant = HashSet<KeyedNodeName<*, *>>()
        val toAdd = HashSet<ValueId>()
        toAdd.add(valueId)

        while (toAdd.isNotEmpty()) {
            val curId = toAdd.first()
            toAdd.remove(curId)
            if (relevantValues.contains(curId)) {
                continue
            }
            relevantValues.add(curId)

            val inputs = when (curId) {
                is ValueId.Nonkeyed -> nonkeyedNodes.getValue(curId.nodeName).inputs
                is ValueId.FullKeyList -> keyListNodes.getValue(curId.nodeName).inputs
                is ValueId.KeyListKey -> keyListNodes.getValue(curId.nodeName).inputs
                is ValueId.Keyed -> keyedNodes.getValue(curId.nodeName).inputs
                is ValueId.FullKeyedList -> keyedNodes.getValue(curId.nodeName).inputs
            }
            for (input in inputs) {
                when (input) {
                    is RefillBuiltNode -> toAdd.add(ValueId.Nonkeyed(input.name))
                    is RefillInput.KeyList<*> -> toAdd.add(ValueId.FullKeyList(input.name))
                    is RefillInput.Keyed<*, *> -> {
                        if (curId is ValueId.Keyed) {
                            toAdd.add(ValueId.Keyed(input.name, curId.key))
                        } else if (curId is ValueId.FullKeyedList) {
                            toAdd.add(ValueId.FullKeyedList(input.name))
                        } else {
                            error("This shouldn't happen")
                        }
                    }
                    is RefillInput.FullKeyedList<*, *> -> toAdd.add(ValueId.FullKeyedList(input.name))
                }
            }
            // TODO: Also add a section for "keyed nodes rely on their inputs"
            if (curId is ValueId.FullKeyedList) {
                keyedNamesWithAllKeysRelevant.add(curId.nodeName)
                val keySourceName = keyedNodes.getValue(curId.nodeName).keySourceName
                toAdd.add(ValueId.FullKeyList(keySourceName))
            }
            if (curId is ValueId.Keyed) {
                val keySourceName = keyedNodes.getValue(curId.nodeName).keySourceName
                toAdd.add(ValueId.FullKeyList(keySourceName))
            }
        }

        return Predicate {
            relevantValues.contains(it) || (it is ValueId.Keyed && keyedNamesWithAllKeysRelevant.contains(it.nodeName))
        }
    }

    fun toMultiLineString(): String {
        val sb = StringBuilder()
        for (nodeName in topologicalOrdering) {
            when (nodeName) {
                is NodeName<*> -> {
                    val node = nonkeyedNodes.getValue(nodeName)
                    sb.append("Basic   ")
                    sb.append(nodeName)
                    if (node.operation != null) {
                        sb.append("(")
                        sb.append(node.inputs.map { it.toString() }.joinToString(", "))
                        sb.append(")")
                    }
                }
                is KeyListNodeName<*> -> {
                    val node = keyListNodes.getValue(nodeName)
                    sb.append("KeyList ")
                    sb.append(nodeName)
                    if (node.operation != null) {
                        sb.append("(")
                        sb.append(node.inputs.map { it.toString() }.joinToString(", "))
                        sb.append(")")
                    }
                }
                is KeyedNodeName<*, *> -> {
                    val node = keyedNodes.getValue(nodeName)
                    sb.append("Keyed   ")
                    sb.append(nodeName)
                    sb.append("<")
                    sb.append(node.keySourceName)
                    sb.append(">(")
                    sb.append(node.inputs.map { it.toString() }.joinToString(", "))
                    sb.append(")")
                }
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    fun instantiateAsync(executorService: ExecutorService): RefillAsyncInstance {
        return RefillAsyncInstance(RefillInstance(this), executorService)
    }
}
class RefillDefinitionBuilder {
    private val usedNodeNames = HashSet<String>()
    private val nodes = HashMap<NodeName<*>, RefillNode<*>>()
    // TODO: Give this its own sealed class covering all the node name types
    private val topologicalOrdering = ArrayList<GenericNodeName>()
    private val keyListNodes = HashMap<KeyListNodeName<*>, RefillKeyListNode<*>>()
    private val keyedNodes = HashMap<KeyedNodeName<*, *>, RefillKeyedNode<*, *>>()

    // Used to ensure all node inputs we receive originated from this builder
    class Id internal constructor()
    private val builderId = Id()

    fun <T> createInputNode(name: NodeName<T>): RefillBuiltNode<T> {
        checkNameNotUsed(name.name)
        val node = RefillNode<T>(name, listOf(), null, null)
        nodes[name] = node
        topologicalOrdering.add(name)
        return RefillBuiltNode(name, builderId)
    }

    private fun checkNameNotUsed(name: String) {
        if (usedNodeNames.contains(name)) {
            error("Cannot create two nodes with the same name '$name'")
        }
        usedNodeNames.add(name)
    }

    fun <T, I1> createNode(name: NodeName<T>, input1: RefillInput<I1>, fn: (I1) -> T, onCatch: ((RefillFailure) -> T)? = null): RefillBuiltNode<T> {
        return createNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
    fun <T, I1, I2> createNode(name: NodeName<T>, input1: RefillInput<I1>, input2: RefillInput<I2>, fn: (I1, I2) -> T, onCatch: ((RefillFailure) -> T)? = null): RefillBuiltNode<T> {
        return createNode(name, listOf(input1, input2), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) }, onCatch)
    }
    fun <T, I1, I2, I3> createNode(name: NodeName<T>, input1: RefillInput<I1>, input2: RefillInput<I2>, input3: RefillInput<I3>, fn: (I1, I2, I3) -> T, onCatch: ((RefillFailure) -> T)? = null): RefillBuiltNode<T> {
        return createNode(name, listOf(input1, input2, input3), { inputs -> fn(inputs[0] as I1, inputs[1] as I2, inputs[2] as I3) }, onCatch)
    }
    fun <T> createNode(name: NodeName<T>, inputs: List<RefillInput<*>>, fn: (List<*>) -> T, onCatch: ((RefillFailure) -> T)?): RefillBuiltNode<T> {
        if (inputs.isEmpty()) {
            // TODO: We might want to allow this as long as fn and onCatch are null
            error("Use createInputNode to create a node with no inputs.")
        }
        checkNameNotUsed(name.name)
        validateBuilderIds(inputs)
        val node = RefillNode<T>(name, inputs, fn, onCatch)
        nodes[name] = node
        topologicalOrdering.add(name)
        return RefillBuiltNode(name, builderId)
    }

    private fun validateBuilderIds(inputs: List<RefillInput<*>>) {
        for (input in inputs) {
            if (builderId != input.builderId) {
                throw IllegalArgumentException("Cannot reuse nodes or inputs across builders")
            }
        }
    }

    fun <T> createKeyListInputNode(name: KeyListNodeName<T>): RefillBuiltKeyListNode<T> {
        checkNameNotUsed(name.name)

        val node = RefillKeyListNode<T>(name, listOf(), null, null)
        keyListNodes[name] = node
        topologicalOrdering.add(name)
        return RefillBuiltKeyListNode(name, builderId)
    }
    fun <T, I1> createKeyListNode(name: KeyListNodeName<T>, input1: RefillInput<I1>, fn: (I1) -> List<T>, onCatch: ((RefillFailure) -> List<T>)? = null): RefillBuiltKeyListNode<T> {
        return createKeyListNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
    fun <T, I1, I2> createKeyListNode(name: KeyListNodeName<T>, input1: RefillInput<I1>, input2: RefillInput<I2>, fn: (I1, I2) -> List<T>, onCatch: ((RefillFailure) -> List<T>)? = null): RefillBuiltKeyListNode<T> {
        return createKeyListNode(name, listOf(input1, input2), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) }, onCatch)
    }
    fun <T> createKeyListNode(name: KeyListNodeName<T>, inputs: List<RefillInput<*>>, fn: (List<*>) -> List<T>, onCatch: ((RefillFailure) -> List<T>)?): RefillBuiltKeyListNode<T> {
        checkNameNotUsed(name.name)
        validateBuilderIds(inputs)
        val node = RefillKeyListNode<T>(name, inputs, fn, onCatch)
        keyListNodes[name] = node
        topologicalOrdering.add(name)
        return RefillBuiltKeyListNode(name, builderId)
    }

    fun <K, T> createKeyedInputNode(name: KeyedNodeName<K, T>, keySource: RefillBuiltKeyListNode<K>): RefillBuiltKeyedNode<K, T> {
        checkNameNotUsed(name.name)

        if (keyListNodes[keySource.name]!!.operation != null) {
            error("Keyed input nodes can only use input key lists as their key sources, but ${keySource.name} is not an input.")
        }

        val node = RefillKeyedNode(name, keySource.name, listOf(), null, null)
        keyedNodes[name] = node
        topologicalOrdering.add(name)
        return RefillBuiltKeyedNode(name, builderId)
    }
    fun <K, T> createKeyedNode(name: KeyedNodeName<K, T>, keySource: RefillBuiltKeyListNode<K>, fn: (K) -> T, onCatch: ((RefillFailure) -> T)? = null): RefillBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(), { key, list -> fn(key) }, onCatch)
    }
    fun <K, T, I1> createKeyedNode(name: KeyedNodeName<K, T>, keySource: RefillBuiltKeyListNode<K>, input1: RefillInput<I1>, fn: (K, I1) -> T, onCatch: ((RefillFailure) -> T)? = null): RefillBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1), { key, list -> fn(key, list[0] as I1) }, onCatch)
    }
    fun <T, K, I1, I2> createKeyedNode(name: KeyedNodeName<K, T>, keySource: RefillBuiltKeyListNode<K>, input1: RefillInput<I1>, input2: RefillInput<I2>, fn: (K, I1, I2) -> T, onCatch: ((RefillFailure) -> T)? = null): RefillBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1, input2), { key, list -> fn(key, list[0] as I1, list[1] as I2) }, onCatch)
    }
    fun <T, K, I1, I2, I3> createKeyedNode(name: KeyedNodeName<K, T>, keySource: RefillBuiltKeyListNode<K>, input1: RefillInput<I1>, input2: RefillInput<I2>, input3: RefillInput<I3>, fn: (K, I1, I2, I3) -> T, onCatch: ((RefillFailure) -> T)? = null): RefillBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1, input2, input3), { key, list -> fn(key, list[0] as I1, list[1] as I2, list[2] as I3) }, onCatch)
    }
    fun <K, T> createKeyedNode(name: KeyedNodeName<K, T>, keySource: RefillBuiltKeyListNode<K>, inputs: List<RefillInput<*>>, fn: (K, List<*>) -> T, onCatch: ((RefillFailure) -> T)?): RefillBuiltKeyedNode<K, T> {
        checkNameNotUsed(name.name)
        val node = RefillKeyedNode(name, keySource.name, inputs, fn, onCatch)
        keyedNodes[name] = node
        topologicalOrdering.add(name)
        return RefillBuiltKeyedNode(name, builderId)
    }

    fun build(): RefillDefinition {
        return RefillDefinition(nodes, keyListNodes, keyedNodes, topologicalOrdering)
    }

}

class RefillBuiltNode<T>(val name: NodeName<T>, override val builderId: RefillDefinitionBuilder.Id): RefillInput<T>() {
    override fun toString(): String {
        return name.toString()
    }
}
class RefillBuiltKeyListNode<T>(val name: KeyListNodeName<T>, val builderId: RefillDefinitionBuilder.Id) {
    fun listOutput(): RefillInput<List<T>> {
        return RefillInput.KeyList<T>(name, builderId)
    }
    override fun toString(): String {
        return name.toString()
    }
}
class RefillBuiltKeyedNode<K, T>(val name: KeyedNodeName<K, T>, val builderId: RefillDefinitionBuilder.Id) {
    fun keyedOutput(): RefillInput<T> {
        return RefillInput.Keyed(name, builderId)
    }
    fun fullOutput(): RefillInput<List<T>> {
        return RefillInput.FullKeyedList(name, builderId)
    }
    override fun toString(): String {
        return name.toString()
    }
}
sealed class RefillInput<T> {
    abstract val builderId: RefillDefinitionBuilder.Id
    data class KeyList<T>(val name: KeyListNodeName<T>, override val builderId: RefillDefinitionBuilder.Id): RefillInput<List<T>>() {
        override fun toString(): String {
            return name.toString()
        }
    }
    data class Keyed<K, T>(val name: KeyedNodeName<K, T>, override val builderId: RefillDefinitionBuilder.Id): RefillInput<T>() {
        override fun toString(): String {
            return "$name (keyed)"
        }
    }
    data class FullKeyedList<K, T>(val name: KeyedNodeName<K, T>, override val builderId: RefillDefinitionBuilder.Id): RefillInput<List<T>>() {
        override fun toString(): String {
            return "$name (full list)"
        }
    }
}

internal class RefillNode<T> internal constructor(
        val name: NodeName<T>,
        val inputs: List<RefillInput<*>>,
        val operation: ((List<*>) -> T)?,
        val onCatch: ((RefillFailure) -> T)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}

internal class RefillKeyListNode<T>(
        val name: KeyListNodeName<T>,
        val inputs: List<RefillInput<*>>,
        val operation: ((List<*>) -> List<T>)?,
        val onCatch: ((RefillFailure) -> List<T>)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}

internal class RefillKeyedNode<K, T>(
        val name: KeyedNodeName<K, T>,
        val keySourceName: KeyListNodeName<K>,
        val inputs: List<RefillInput<*>>,
        val operation: ((K, List<*>) -> T)?,
        val onCatch: ((RefillFailure) -> T)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}

