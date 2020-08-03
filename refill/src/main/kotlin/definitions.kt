package net.semlang.refill

import java.lang.IllegalArgumentException
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.ExecutorService
import java.util.function.Predicate


class TrickleDefinition internal constructor(internal val nonkeyedNodes: Map<NodeName<*>, TrickleNode<*>>,
                                             internal val keyListNodes: Map<KeyListNodeName<*>, TrickleKeyListNode<*>>,
                                             internal val keyedNodes: Map<KeyedNodeName<*, *>, TrickleKeyedNode<*, *>>,
                                             internal val topologicalOrdering: List<GenericNodeName>) {
    fun instantiateRaw(): TrickleInstance {
        return TrickleInstance(this)
    }
    fun instantiateSync(): TrickleSyncInstance {
        return TrickleSyncInstance(TrickleInstance(this))
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
                    is TrickleBuiltNode -> toAdd.add(ValueId.Nonkeyed(input.name))
                    is TrickleInput.KeyList<*> -> toAdd.add(ValueId.FullKeyList(input.name))
                    is TrickleInput.Keyed<*, *> -> {
                        if (curId is ValueId.Keyed) {
                            toAdd.add(ValueId.Keyed(input.name, curId.key))
                        } else if (curId is ValueId.FullKeyedList) {
                            toAdd.add(ValueId.FullKeyedList(input.name))
                        } else {
                            error("This shouldn't happen")
                        }
                    }
                    is TrickleInput.FullKeyedList<*, *> -> toAdd.add(ValueId.FullKeyedList(input.name))
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

    fun instantiateAsync(executorService: ExecutorService): TrickleAsyncInstance {
        return TrickleAsyncInstance(TrickleInstance(this), executorService)
    }
}
class TrickleDefinitionBuilder {
    private val usedNodeNames = HashSet<String>()
    private val nodes = HashMap<NodeName<*>, TrickleNode<*>>()
    // TODO: Give this its own sealed class covering all the node name types
    private val topologicalOrdering = ArrayList<GenericNodeName>()
    private val keyListNodes = HashMap<KeyListNodeName<*>, TrickleKeyListNode<*>>()
    private val keyedNodes = HashMap<KeyedNodeName<*, *>, TrickleKeyedNode<*, *>>()

    // Used to ensure all node inputs we receive originated from this builder
    class Id internal constructor()
    private val builderId = Id()

    fun <T> createInputNode(name: NodeName<T>): TrickleBuiltNode<T> {
        checkNameNotUsed(name.name)
        val node = TrickleNode<T>(name, listOf(), null, null)
        nodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltNode(name, builderId)
    }

    private fun checkNameNotUsed(name: String) {
        if (usedNodeNames.contains(name)) {
            error("Cannot create two nodes with the same name '$name'")
        }
        usedNodeNames.add(name)
    }

    fun <T, I1> createNode(name: NodeName<T>, input1: TrickleInput<I1>, fn: (I1) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltNode<T> {
        return createNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
    fun <T, I1, I2> createNode(name: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (I1, I2) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltNode<T> {
        return createNode(name, listOf(input1, input2), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) }, onCatch)
    }
    fun <T, I1, I2, I3> createNode(name: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, input3: TrickleInput<I3>, fn: (I1, I2, I3) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltNode<T> {
        return createNode(name, listOf(input1, input2, input3), { inputs -> fn(inputs[0] as I1, inputs[1] as I2, inputs[2] as I3) }, onCatch)
    }
    fun <T> createNode(name: NodeName<T>, inputs: List<TrickleInput<*>>, fn: (List<*>) -> T, onCatch: ((TrickleFailure) -> T)?): TrickleBuiltNode<T> {
        if (inputs.isEmpty()) {
            // TODO: We might want to allow this as long as fn and onCatch are null
            error("Use createInputNode to create a node with no inputs.")
        }
        checkNameNotUsed(name.name)
        validateBuilderIds(inputs)
        val node = TrickleNode<T>(name, inputs, fn, onCatch)
        nodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltNode(name, builderId)
    }

    private fun validateBuilderIds(inputs: List<TrickleInput<*>>) {
        for (input in inputs) {
            if (builderId != input.builderId) {
                throw IllegalArgumentException("Cannot reuse nodes or inputs across builders")
            }
        }
    }

    fun <T> createKeyListInputNode(name: KeyListNodeName<T>): TrickleBuiltKeyListNode<T> {
        checkNameNotUsed(name.name)

        val node = TrickleKeyListNode<T>(name, listOf(), null, null)
        keyListNodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltKeyListNode(name, builderId)
    }
    fun <T, I1> createKeyListNode(name: KeyListNodeName<T>, input1: TrickleInput<I1>, fn: (I1) -> List<T>, onCatch: ((TrickleFailure) -> List<T>)? = null): TrickleBuiltKeyListNode<T> {
        return createKeyListNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
    fun <T, I1, I2> createKeyListNode(name: KeyListNodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (I1, I2) -> List<T>, onCatch: ((TrickleFailure) -> List<T>)? = null): TrickleBuiltKeyListNode<T> {
        return createKeyListNode(name, listOf(input1, input2), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) }, onCatch)
    }
    fun <T> createKeyListNode(name: KeyListNodeName<T>, inputs: List<TrickleInput<*>>, fn: (List<*>) -> List<T>, onCatch: ((TrickleFailure) -> List<T>)?): TrickleBuiltKeyListNode<T> {
        checkNameNotUsed(name.name)
        validateBuilderIds(inputs)
        val node = TrickleKeyListNode<T>(name, inputs, fn, onCatch)
        keyListNodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltKeyListNode(name, builderId)
    }

    fun <K, T> createKeyedInputNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>): TrickleBuiltKeyedNode<K, T> {
        checkNameNotUsed(name.name)

        if (keyListNodes[keySource.name]!!.operation != null) {
            error("Keyed input nodes can only use input key lists as their key sources, but ${keySource.name} is not an input.")
        }

        val node = TrickleKeyedNode(name, keySource.name, listOf(), null, null)
        keyedNodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltKeyedNode(name, builderId)
    }
    fun <K, T> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, fn: (K) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(), { key, list -> fn(key) }, onCatch)
    }
    fun <K, T, I1> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, input1: TrickleInput<I1>, fn: (K, I1) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1), { key, list -> fn(key, list[0] as I1) }, onCatch)
    }
    fun <T, K, I1, I2> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (K, I1, I2) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1, input2), { key, list -> fn(key, list[0] as I1, list[1] as I2) }, onCatch)
    }
    fun <T, K, I1, I2, I3> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, input3: TrickleInput<I3>, fn: (K, I1, I2, I3) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1, input2, input3), { key, list -> fn(key, list[0] as I1, list[1] as I2, list[2] as I3) }, onCatch)
    }
    fun <K, T> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, inputs: List<TrickleInput<*>>, fn: (K, List<*>) -> T, onCatch: ((TrickleFailure) -> T)?): TrickleBuiltKeyedNode<K, T> {
        checkNameNotUsed(name.name)
        val node = TrickleKeyedNode(name, keySource.name, inputs, fn, onCatch)
        keyedNodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltKeyedNode(name, builderId)
    }

    fun build(): TrickleDefinition {
        return TrickleDefinition(nodes, keyListNodes, keyedNodes, topologicalOrdering)
    }

}

class TrickleBuiltNode<T>(val name: NodeName<T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<T>() {
    override fun toString(): String {
        return name.toString()
    }
}
class TrickleBuiltKeyListNode<T>(val name: KeyListNodeName<T>, val builderId: TrickleDefinitionBuilder.Id) {
    fun listOutput(): TrickleInput<List<T>> {
        return TrickleInput.KeyList<T>(name, builderId)
    }
    override fun toString(): String {
        return name.toString()
    }
}
class TrickleBuiltKeyedNode<K, T>(val name: KeyedNodeName<K, T>, val builderId: TrickleDefinitionBuilder.Id) {
    fun keyedOutput(): TrickleInput<T> {
        return TrickleInput.Keyed(name, builderId)
    }
    fun fullOutput(): TrickleInput<List<T>> {
        return TrickleInput.FullKeyedList(name, builderId)
    }
    override fun toString(): String {
        return name.toString()
    }
}
sealed class TrickleInput<T> {
    abstract val builderId: TrickleDefinitionBuilder.Id
    data class KeyList<T>(val name: KeyListNodeName<T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<List<T>>() {
        override fun toString(): String {
            return name.toString()
        }
    }
    data class Keyed<K, T>(val name: KeyedNodeName<K, T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<T>() {
        override fun toString(): String {
            return "$name (keyed)"
        }
    }
    data class FullKeyedList<K, T>(val name: KeyedNodeName<K, T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<List<T>>() {
        override fun toString(): String {
            return "$name (full list)"
        }
    }
}

internal class TrickleNode<T> internal constructor(
        val name: NodeName<T>,
        val inputs: List<TrickleInput<*>>,
        val operation: ((List<*>) -> T)?,
        val onCatch: ((TrickleFailure) -> T)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}

internal class TrickleKeyListNode<T>(
        val name: KeyListNodeName<T>,
        val inputs: List<TrickleInput<*>>,
        val operation: ((List<*>) -> List<T>)?,
        val onCatch: ((TrickleFailure) -> List<T>)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}

internal class TrickleKeyedNode<K, T>(
        val name: KeyedNodeName<K, T>,
        val keySourceName: KeyListNodeName<K>,
        val inputs: List<TrickleInput<*>>,
        val operation: ((K, List<*>) -> T)?,
        val onCatch: ((TrickleFailure) -> T)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}

