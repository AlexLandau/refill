package net.semlang.refill

import org.junit.Test
import java.lang.IllegalArgumentException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * This test class covers how Trickle handles misuse of its API by the coder using it, as opposed to how it handles tasks
 * failing (which is part of its expected behavior).
 */
class ErrorHandlingTests {
    private val A = NodeName<Int>("a")
    private val B = NodeName<Int>("b")
    private val C = NodeName<Int>("c")

    private val B_KEYS = KeyListNodeName<Int>("bKeys")
    private val C_KEYS = KeyListNodeName<Int>("cKeys")

    private val B_KEYED = KeyedNodeName<Int, Int>("bKeyed")

    @Test
    fun testCannotShareNodesBetweenBuilders() {
        val builder1 = TrickleDefinitionBuilder()
        val builder2 = TrickleDefinitionBuilder()

        val aNode1 = builder1.createInputNode(A)
        assertThrows(IllegalArgumentException::class.java) {
            val bNode2 = builder2.createNode(B, aNode1, { it + 1 })
        }
    }

    @Test
    fun testCannotShareResultsBetweenInstances() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val definition = builder.build()
        val instance1 = definition.instantiateRaw()
        val instance2 = definition.instantiateRaw()

        instance1.setInput(A, 3)
        val step = instance1.getNextSteps().single()
        val result = step.execute()

        assertThrows(IllegalArgumentException::class.java) {
            instance2.reportResult(result)
        }
    }

    @Test
    fun testCannotSetUnrecognizedNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        assertThrows(IllegalArgumentException::class.java) {
            instance.setInput(C, 2)
        }
    }

    @Test
    fun testCannotSetUnrecognizedKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        assertThrows(IllegalArgumentException::class.java) {
            instance.setInput(C_KEYS, listOf(1, 2, 3))
        }
    }

    @Test
    fun testCannotAddToUnrecognizedKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        assertThrows(IllegalArgumentException::class.java) {
            instance.addKeyInput(C_KEYS, 1)
        }
    }

    @Test
    fun testCannotRemoveFromUnrecognizedKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        assertThrows(IllegalArgumentException::class.java) {
            instance.removeKeyInput(C_KEYS, 1)
        }
    }

    @Test
    fun testCannotSetNonInputNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        assertThrows(IllegalArgumentException::class.java) {
            instance.setInput(B, 2)
        }
    }

    @Test
    fun testCannotSetNonInputKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createKeyListNode(B_KEYS, aNode, { (1..it).toList() })

        val instance = builder.build().instantiateRaw()

        assertThrows(IllegalArgumentException::class.java) {
            instance.setInput(B_KEYS, listOf(1, 2, 3))
        }
    }

    @Test
    fun testCannotAddToNonInputKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createKeyListNode(B_KEYS, aNode, { (1..it).toList() })

        val instance = builder.build().instantiateRaw()

        assertThrows(IllegalArgumentException::class.java) {
            instance.addKeyInput(B_KEYS, 1)
        }
    }

    @Test
    fun testCannotRemoveFromNonInputKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createKeyListNode(B_KEYS, aNode, { (1..it).toList() })

        val instance = builder.build().instantiateRaw()

        assertThrows(IllegalArgumentException::class.java) {
            instance.removeKeyInput(B_KEYS, 1)
        }
    }

    @Test
    fun testPassingUnusedNamesToAsyncInstance() {
        val builder = TrickleDefinitionBuilder()

        val a = builder.createInputNode(A)

        val instance = builder.build().instantiateAsync(Executors.newCachedThreadPool())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                instance.getOutcome(B, 1, TimeUnit.SECONDS)
            }
            assertThrows(IllegalArgumentException::class.java) {
                instance.getOutcome(B_KEYS, 1, TimeUnit.SECONDS)
            }
            assertThrows(IllegalArgumentException::class.java) {
                instance.getOutcome(B_KEYED, 1, TimeUnit.SECONDS)
            }
            assertThrows(IllegalArgumentException::class.java) {
                instance.getOutcome(B_KEYED, 7, 1, TimeUnit.SECONDS)
            }
            assertThrows(IllegalArgumentException::class.java) {
                instance.setInput(B, 7)
            }
            assertThrows(IllegalArgumentException::class.java) {
                instance.setInput(B_KEYS, listOf(7))
            }
            assertThrows(IllegalArgumentException::class.java) {
                instance.addKeyInput(B_KEYS, 1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                instance.removeKeyInput(B_KEYS, 7)
            }

        } finally {
            instance.shutdown()
        }
    }

}

fun assertThrows(exceptionClass: Class<out Throwable>, action: () -> Unit) {
    try {
        action()
    } catch (t: Throwable) {
        if (t.javaClass == exceptionClass) {
            return
        }
        throw AssertionError("Expected a thrown ${exceptionClass.name}, but was a ${t.javaClass}", t)
    }
    throw AssertionError("Expected this code to throw a ${exceptionClass.name}, but it did not throw anything")
}
