package net.semlang.refill

import org.awaitility.Awaitility.await
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import java.lang.IllegalStateException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// TODO: Test that getting keyedInput() fails at some point if the key lists involved aren't the same (or when used
// as the input to a basic or key list node)

// TODO: Test multi-input setting and consistency
// (regression test: short-circuiting or in the wrong spot)

class RefillTests {
    private val A = NodeName<Int>("a")
    private val B = NodeName<Int>("b")
    private val C = NodeName<Int>("c")
    private val D = NodeName<Int>("d")
    private val E = NodeName<Int>("e")

    private val A_KEYS = KeyListNodeName<Int>("aKeys")
    private val B_KEYS = KeyListNodeName<Int>("bKeys")
    private val C_KEYS = KeyListNodeName<Int>("cKeys")
    private val D_KEYS = KeyListNodeName<Int>("dKeys")
    private val E_KEYS = KeyListNodeName<Int>("eKeys")

    private val B_KEYED = KeyedNodeName<Int, Int>("bKeyed")
    private val C_KEYED = KeyedNodeName<Int, Int>("cKeyed")
    private val D_KEYED = KeyedNodeName<Int, Int>("dKeyed")
    private val E_KEYED = KeyedNodeName<Int, Int>("eKeyed")
    private val F_KEYED = KeyedNodeName<Int, Int>("fKeyed")

    @Test
    fun testRefillBasic() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        // TODO: It would be nice if we could pass in the NodeName as an input
        val bNode = builder.createNode(B, aNode, { it + 1 })
        val cNode = builder.createNode(C, aNode, { it + 3 })
        builder.createNode(D, bNode, cNode, { b, c -> b * c })

        val definition = builder.build()
        val instance = definition.instantiateRaw()

        instance.setInput(A, 0)
        instance.completeSynchronously()
        assertEquals(3, instance.getNodeValue(D))
        assertEquals(1, instance.getNodeValue(B))
        assertEquals(3, instance.getNodeValue(C))
        assertEquals(0, instance.getNodeValue(A))

        instance.setInput(A, 1)
        instance.completeSynchronously()
        assertEquals(8, instance.getNodeValue(D))
    }

    @Test
    fun testApplyingResultsAfterMultipleInputs() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 10)
        val olderStep = instance.getNextSteps().single()
        instance.setInput(A, 20)
        val newerStep = instance.getNextSteps().single()

        // Compute and apply the steps in order
        val olderResult = olderStep.execute()
        assertEquals(11, olderResult.result)
        instance.reportResult(olderResult)
        // TODO: What should we return if we ask for B here?
        val newerResult = newerStep.execute()
        assertEquals(21, newerResult.result)
        instance.reportResult(newerResult)

        assertEquals(0, instance.getNextSteps().size)
        // The result based on the newer input is returned
        assertEquals(21, instance.getNodeValue(B))
    }

    @Test
    fun testApplyingResultsOutOfOrderDoesNotMatter() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 10)
        val olderStep = instance.getNextSteps().single()
        instance.setInput(A, 20)
        val newerStep = instance.getNextSteps().single()

        // Compute and apply the steps out of order
        val newerResult = newerStep.execute()
        assertEquals(21, newerResult.result)
        instance.reportResult(newerResult)

        if (instance.getNextSteps().size > 0) {
            println("**** " + instance.getNextSteps())
        }
        assertEquals(0, instance.getNextSteps().size)

        val olderResult = olderStep.execute()
        assertEquals(11, olderResult.result)
        instance.reportResult(olderResult)

        if (instance.getNextSteps().size > 0) {
            println("**** " + instance.getNextSteps())
        }
        assertEquals(0, instance.getNextSteps().size)
        // The B node has the result based on the input set later, even though the result for the older input was the
        // most recently reported
        assertEquals(21, instance.getNodeValue(B))
    }

    @Test
    fun testCaseTryingToTrickTimestamps() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })
        val cNode = builder.createInputNode(C)
        val dNode = builder.createNode(D, cNode, { it + 2 })
        val eNode = builder.createNode(E, bNode, dNode, { left, right -> left * right } )

        val instance = builder.build().instantiateRaw()

        // Prepare two updates to B followed by one to D
        instance.setInput(A, 0)
        instance.setInput(C, 0)
        val firstBChange = instance.getNextSteps().filter { (it.valueId as ValueId.Nonkeyed).nodeName == B }.single()
        instance.setInput(A, 10)
        val secondBChange = instance.getNextSteps().filter { (it.valueId as ValueId.Nonkeyed).nodeName == B }.single()
        instance.setInput(C, 99)
        val dChange = instance.getNextSteps().filter { (it.valueId as ValueId.Nonkeyed).nodeName == D }.single()

        // Apply the first B change; no E computation should be suggested yet, and a B update should still be requested
        instance.reportResult(firstBChange.execute())
        assertEquals(setOf(B, D), instance.getNextSteps().map { (it.valueId as ValueId.Nonkeyed).nodeName }.toSet())
        // After reporting on D, we should still be waiting on the newer B
        instance.reportResult(dChange.execute())
        assertEquals(setOf(B), instance.getNextSteps().map { (it.valueId as ValueId.Nonkeyed).nodeName }.toSet())
        // Reporting the more recent version of B finally unlocks E
        instance.reportResult(secondBChange.execute())
        assertEquals(setOf(E), instance.getNextSteps().map { (it.valueId as ValueId.Nonkeyed).nodeName }.toSet())
        // Adding a new input value to C will retract the request for an update to E
        instance.setInput(C, 100)
        assertEquals(setOf(D), instance.getNextSteps().map { (it.valueId as ValueId.Nonkeyed).nodeName }.toSet())

        instance.completeSynchronously()
        assertTrue(instance.getNextSteps().isEmpty())
        assertEquals(1122, instance.getNodeValue(E))
    }

    fun testCanGetStepsWithInputsUndefined() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getNodeOutcome(B))
    }

    private fun inputsMissingOutcome(vararg valueIds: ValueId): NodeOutcome.Failure<Int> {
        return NodeOutcome.Failure(RefillFailure(mapOf(), valueIds.toSet()))
    }

    fun testCannotGetStepsWithInputsUndefined2() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })
        val cNode = builder.createInputNode(C)

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        assertEquals(NodeOutcome.Computed(2), instance.getNodeOutcome(B))
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(C)), instance.getNodeOutcome(C))
    }

    @Test
    fun testUncaughtExceptionInCalculation() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val bOutcome = instance.getNodeOutcome(B)
        if (bOutcome !is NodeOutcome.Failure) {
            fail()
        } else {
            val errors = bOutcome.failure.errors
            assertEquals(setOf(ValueId.Nonkeyed(B)), errors.keys)
            assertTrue(errors.values.single().message!!.contains("custom exception message"))
        }
    }

    @Test
    fun testUpstreamUncaughtException() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })
        val cNode = builder.createNode(C, bNode, { it * 3 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val cOutcome = instance.getNodeOutcome(C)
        if (cOutcome !is NodeOutcome.Failure) {
            fail()
        } else {
            val errors = cOutcome.failure.errors
            assertEquals(setOf(ValueId.Nonkeyed(B)), errors.keys)
            assertTrue(errors.values.single().message!!.contains("custom exception message"))
        }
    }

    @Test
    fun testUpstreamMixedMissingAndException() {
        val builder = RefillDefinitionBuilder()

        val exception = java.lang.RuntimeException("simulated failure")

        val aNode = builder.createInputNode(A)
        val bNode = builder.createInputNode(B)
        val cNode = builder.createNode(C, aNode, { throw exception })
        val dNode = builder.createNode(D, bNode, cNode, { b, c -> b * c })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val outcome = instance.getNodeOutcome(D)
        assertEquals(
                NodeOutcome.Failure<Int>(RefillFailure(
                        mapOf(ValueId.Nonkeyed(C) to exception),
                        setOf(ValueId.Nonkeyed(B)))),
                outcome)
    }

    @Test
    fun testCatchExceptionInNode() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") }, { failures -> -1 })
        val cNode = builder.createNode(C, bNode, { it * 3 }, { failures -> -1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val bOutcome = instance.getNodeOutcome(B)
        if (bOutcome !is NodeOutcome.Computed) {
            fail()
        } else {
            assertEquals(-1, bOutcome.value)
        }
        assertEquals(-3, instance.getNodeValue(C))
    }

    // TODO: Should a catch block also apply to the node's own computation? Also, what if the node fails itself?
    @Test
    fun testCatchUpstreamException() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })
        val cNode = builder.createNode(C, bNode, { it * 3 }, { failures -> -1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val cOutcome = instance.getNodeOutcome(C)
        if (cOutcome !is NodeOutcome.Computed) {
            fail()
        } else {
            assertEquals(-1, cOutcome.value)
        }
        assertEquals(-1, instance.getNodeValue(C))
    }

    @Test
    fun testCatchUpstreamException2() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })
        val cNode = builder.createNode(C, bNode, { it + 1 })
        val dNode = builder.createNode(D, cNode, { it * 3 }, { failures -> -1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val dOutcome = instance.getNodeOutcome(D)
        if (dOutcome !is NodeOutcome.Computed) {
            fail()
        } else {
            assertEquals(-1, dOutcome.value)
        }
        assertEquals(-1, instance.getNodeValue(D))
    }

    @Test
    fun testKeyListNode1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)

        val instance = builder.build().instantiateRaw()

        assertEquals(listOf<Int>(), instance.getNodeValue(A_KEYS))
        instance.addKeyInput(A_KEYS, 4)
        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 5)
        assertEquals(listOf(4, 3, 5), instance.getNodeValue(A_KEYS))
    }

    @Test
    fun testKeyListNode2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        // Note: This is not a realistic example; summing over a set (vs. a list) is usually not useful
        val bNode = builder.createNode(B, aKeys.listOutput(), { ints -> ints.sum() })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(0, instance.getNodeValue(B))
        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 4)
        instance.completeSynchronously()
        assertEquals(7, instance.getNodeValue(B))
    }

    @Test
    fun testKeyListNode3() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bKeys = builder.createKeyListNode(B_KEYS, aNode, { (1..it).toList() })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 4)
        instance.completeSynchronously()
        assertEquals(listOf(1, 2, 3, 4), instance.getNodeValue(B_KEYS))
        instance.setInput(A, 2)
        instance.completeSynchronously()
        assertEquals(listOf(1, 2), instance.getNodeValue(B_KEYS))
    }

    @Test
    fun testCatchingKeyListNode() {
        val builder = RefillDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { error("Something went wrong") })
        val cKeys = builder.createKeyListNode(C_KEYS, bNode, { (1..it).toList() }, { _ -> listOf(-1) })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 4)
        instance.completeSynchronously()
        assertEquals(listOf(-1), instance.getNodeValue(C_KEYS))
    }

    @Test
    fun testCatchingKeyedNode1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { error("Something went wrong") }, { _ -> -1})

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2))
        instance.completeSynchronously()
        val outcome1 = instance.getNodeOutcome(B_KEYED, 1)
        if (outcome1 !is NodeOutcome.Computed) {
            fail()
        } else {
            assertEquals(-1, outcome1.value)
        }
        assertEquals(listOf(-1, -1), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testCatchingKeyedNode2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, {
            if (it >= 0) it else error("Something went wrong")
        }, { _ -> -1})

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(20, -30, 40))
        instance.completeSynchronously()
        assertEquals(listOf(20, -1, 40), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testCatchingKeyedNode3() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { if (it >= 0) it else error("Something went wrong") })
        val cKeyed = builder.createKeyedNode(C_KEYED, aKeys, bKeyed.keyedOutput(), { _, b -> b * 2 }, { _ -> -1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(20, -30, 40))
        instance.completeSynchronously()

        assertEquals(20, instance.getNodeValue(B_KEYED, 20))
        val bOutcome = instance.getNodeOutcome(B_KEYED, -30)
        assertTrue(bOutcome is NodeOutcome.Failure)
        val bListOutcome = instance.getNodeOutcome(B_KEYED)
        assertTrue(bListOutcome is NodeOutcome.Failure)

        assertEquals(40, instance.getNodeValue(C_KEYED, 20))
        assertEquals(-1, instance.getNodeValue(C_KEYED, -30))
        assertTrue(instance.getNodeOutcome(C_KEYED, -30) is NodeOutcome.Computed)
        assertTrue(instance.getNodeOutcome(C_KEYED) is NodeOutcome.Computed)
        assertEquals(listOf(40, -1, 80), instance.getNodeValue(C_KEYED))
    }

    @Test
    fun testCatchingKeyedNodeErrors4() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { if (it >= 0) it else error("Something went wrong") })
        val cNode = builder.createNode(C, bKeyed.fullOutput(), { b -> b.sum() * 2 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, -2, 3))
        instance.completeSynchronously()
        val cOutcome = instance.getNodeOutcome(C)
        if (cOutcome !is NodeOutcome.Failure) {
            fail("Expected a NodeOutcome.Failure")
        } else {
            assertEquals(setOf(ValueId.Keyed(B_KEYED, -2)), cOutcome.failure.errors.keys)
        }
    }

    @Test
    fun testCatchingKeyedNodeErrors5() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { if (it >= 0) it else error("Something went wrong") }, { _ -> -1 })
        val cNode = builder.createNode(C, bKeyed.fullOutput(), { b -> b.sum() * 2 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, -2, 3))
        instance.completeSynchronously()
        assertEquals(6, instance.getNodeValue(C))
    }

    @Test
    fun testKeyListInputBehavior() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)

        val instance = builder.build().instantiateRaw()

        // It's a set
        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 3)
        val result1 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3), result1)
        // It maintains order
        instance.addKeyInput(A_KEYS, 4)
        instance.addKeyInput(A_KEYS, 6)
        instance.addKeyInput(A_KEYS, 5)
        instance.addKeyInput(A_KEYS, 6) //ignored
        val result2 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3, 4, 6, 5), result2)
        // Keys can be removed, and lose their place in the ordering
        instance.removeKeyInput(A_KEYS, 4)
        val result3 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3, 6, 5), result3)
        instance.addKeyInput(A_KEYS, 4)
        val result4 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3, 6, 5, 4), result4)
        instance.removeKeyInput(A_KEYS, 8)
        val result7 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3, 6, 5, 4), result7)
        // And the list can be replaced entirely, which resets the order
        instance.setInput(A_KEYS, listOf(4, 3, 5, 6))
        val result5 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(4, 3, 5, 6), result5)
        instance.setInput(A_KEYS, listOf())
        val result6 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf<Int>(), result6)
        // When setting the value, duplicates are lost
        instance.setInput(A_KEYS, listOf(1, 3, 2, 3, 4, 4, 1))
        assertEquals(listOf<Int>(1, 3, 2, 4), instance.getNodeValue(A_KEYS))

        // All results are defensive copies of the list, not views of the list
        assertEquals(listOf(3), result1)
        assertEquals(listOf(3, 4, 6, 5), result2)
        assertEquals(listOf(3, 6, 5), result3)
        assertEquals(listOf(3, 6, 5, 4), result4)
        assertEquals(listOf(3, 6, 5, 4), result7)
        assertEquals(listOf(4, 3, 5, 6), result5)
        assertEquals(listOf<Int>(), result6)
    }

    @Test
    fun testKeyedNode1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 1)
        instance.addKeyInput(A_KEYS, 2)
        instance.completeSynchronously()
        assertEquals(2, instance.getNodeValue(B_KEYED, 1))
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(6, instance.getNodeValue(B_KEYED, 3))
        assertEquals(listOf(6, 2, 4), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testKeyedNode2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(listOf<Int>(), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testKeyedNode3() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bNode = builder.createInputNode(B)
        val cKeyed = builder.createKeyedNode(C_KEYED, aKeys, bNode, { a, b -> a*2 + b })

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 1)
        instance.addKeyInput(A_KEYS, 2)
        instance.setInput(B, 1)
        instance.completeSynchronously()
        assertEquals(3, instance.getNodeValue(C_KEYED, 1))
        assertEquals(5, instance.getNodeValue(C_KEYED, 2))
        assertEquals(7, instance.getNodeValue(C_KEYED, 3))
        assertEquals(listOf(7, 3, 5), instance.getNodeValue(C_KEYED))
    }

    @Test
    fun testKeyedInputNode1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(A_KEYS, 1)
        instance.addKeyInput(A_KEYS, 2)
        instance.addKeyInput(A_KEYS, 3)
        instance.setKeyedInput(B_KEYED, 1, 6)
        instance.setKeyedInput(B_KEYED, 2, 4)
        instance.setKeyedInput(B_KEYED, 3, 5)
        instance.completeSynchronously()
        assertEquals(6, instance.getNodeValue(B_KEYED, 1))
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(5, instance.getNodeValue(B_KEYED, 3))
        assertEquals(listOf(6, 4, 5), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testKeyedInputNode2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)
        val cKeyed = builder.createKeyedNode(C_KEYED, aKeys, bKeyed.keyedOutput(), { key, b -> key * b })

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(A_KEYS, 1)
        instance.addKeyInput(A_KEYS, 2)
        instance.addKeyInput(A_KEYS, 3)
        instance.setKeyedInput(B_KEYED, 1, 6)
        instance.setKeyedInput(B_KEYED, 2, 4)
        instance.setKeyedInput(B_KEYED, 3, 5)
        instance.completeSynchronously()
        assertEquals(6, instance.getNodeValue(C_KEYED, 1))
        assertEquals(8, instance.getNodeValue(C_KEYED, 2))
        assertEquals(15, instance.getNodeValue(C_KEYED, 3))
        assertEquals(listOf(6, 8, 15), instance.getNodeValue(C_KEYED))
        instance.setKeyedInput(B_KEYED, 1, 3)
        instance.completeSynchronously()
        assertEquals(3, instance.getNodeValue(C_KEYED, 1))
        assertEquals(listOf(3, 8, 15), instance.getNodeValue(C_KEYED))
    }

    @Test
    fun testKeyedValuesNotRecomputedWhenKeyOrderChanges() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        assertEquals(3, instance.getNextSteps().size)
        instance.completeSynchronously()
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        assertEquals(0, instance.getNextSteps().size)
        instance.setInput(A_KEYS, listOf(3, 2, 1))
        assertEquals(0, instance.getNextSteps().size)
        assertEquals(listOf(6, 4, 2), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testKeyedValuesNotRecomputedWhenSingleKeyAdded() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        assertEquals(3, instance.getNextSteps().size)
        instance.completeSynchronously()
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        assertEquals(0, instance.getNextSteps().size)
        instance.addKeyInput(A_KEYS, 4)
        assertEquals(1, instance.getNextSteps().size)
        assertEquals(ValueId.Keyed(B_KEYED, 4), instance.getNextSteps().single().valueId)
    }

    @Test
    fun testKeyedNodeOutputs1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})
        val c = builder.createNode(C, bKeyed.fullOutput(), { it.sum() })

        val instance = builder.build().instantiateRaw()
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(2 + 4 + 6, instance.getNodeValue(C))
    }

    @Test
    fun testKeyedNodeOutputs2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})
        val cKeyed = builder.createKeyedNode(C_KEYED, aKeys, bKeyed.keyedOutput(), { key, bValue -> bValue + 1 })

        val instance = builder.build().instantiateRaw()
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(listOf(3, 5, 7), instance.getNodeValue(C_KEYED))
    }

    @Test
    fun testRefillSyncIsLazy1() {
        val builder = RefillDefinitionBuilder()

        val didUnnecessaryWork = AtomicBoolean(false)

        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, { it + 4 })
        val c = builder.createNode(C, a, { didUnnecessaryWork.set(true); -1 })

        val instance = builder.build().instantiateSync()

        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getOutcome(B))
        instance.setInput(A, 3)
        assertEquals(7, instance.getValue(B))
        assertFalse(didUnnecessaryWork.get())
    }

    @Test
    fun testRefillSyncIsLazy2() {
        val builder = RefillDefinitionBuilder()

        val didUnnecessaryWork = AtomicBoolean(false)

        val a = builder.createKeyListInputNode(A_KEYS)
        val b = builder.createKeyedNode(B_KEYED, a, {
            if (it == 1) {
                didUnnecessaryWork.set(true)
                -1
            } else {
                0
            }
        })

        val instance = builder.build().instantiateSync()

        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getOutcome(B_KEYED, 1))
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getOutcome(B_KEYED, 2))
        instance.setInput(A_KEYS, listOf(1, 2))
        assertEquals(0, instance.getValue(B_KEYED, 2))
        assertFalse(didUnnecessaryWork.get())
    }

    @Test
    fun testBehaviorWhenNodeDependsOnlyOnKeyListInputs() {
        // Key lists default to true, so values can be computed without setting any inputs
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val b = builder.createNode(B, aKeys.listOutput(), { it.sum() })

        val instance = builder.build().instantiateRaw()

        assertEquals(NodeOutcome.NotYetComputed.get<Int>(), instance.getNodeOutcome(B))
        assertEquals(1, instance.getNextSteps().size)
        instance.completeSynchronously()
        assertEquals(0, instance.getNodeValue(B))
    }

    @Test
    fun testGettingKeyedValuesForNonexistentKey() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getNodeOutcome(B_KEYED, 10))
    }

    @Test
    fun testGettingKeyedValuesForRemovedKey1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        instance.removeKeyInput(A_KEYS, 2)
        instance.completeSynchronously()
        assertEquals(listOf(2, 6), instance.getNodeValue(B_KEYED))
        // Reverts to "no such key"
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getNodeOutcome(B_KEYED, 2))
    }

    @Test
    fun testGettingKeyedValuesForRemovedKey2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        instance.setInput(A_KEYS, listOf(1, 3))
        instance.completeSynchronously()
        assertEquals(listOf(2, 6), instance.getNodeValue(B_KEYED))
        // Reverts to "no such key"
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getNodeOutcome(B_KEYED, 2))
    }

    @Test
    fun testFullOutputsFromKeyedNodeWhenKeyListIsSometimesEmpty() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val bKeys = builder.createKeyListInputNode(B_KEYS)
        val cKeyed = builder.createKeyedNode(C_KEYED, bKeys, a, { key, bVal -> key + bVal + 1 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(NodeOutcome.Computed(listOf<Int>()), instance.getNodeOutcome(C_KEYED))
        instance.addKeyInput(B_KEYS, 42)
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getNodeOutcome(C_KEYED, 42))
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getNodeOutcome(C_KEYED))
    }

    @Test
    fun testNodeGetsHungUpByOtherNodeBecomingNonempty() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val bKeys = builder.createKeyListInputNode(B_KEYS)
        val cKeyed = builder.createKeyedNode(C_KEYED, bKeys, a, { key, bVal -> key + bVal + 1 })
        val d = builder.createInputNode(D)
        val e = builder.createNode(E, cKeyed.fullOutput(), d, { cList, d -> cList.hashCode() + d })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(NodeOutcome.Computed(listOf<Int>()), instance.getNodeOutcome(C_KEYED))
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(D)), instance.getNodeOutcome(E))
        instance.setInput(D, 100)
        instance.completeSynchronously()
        assertEquals(101, instance.getNodeValue(E))

        instance.setInput(D, 1000)
        instance.addKeyInput(B_KEYS, 42)
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getNodeOutcome(E))
    }

    @Test
    fun testNodeGetsHungUpByKeyedInputNode() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)
        val c = builder.createNode(C, bKeyed.fullOutput(), { it.sum() + 1 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(1, instance.getNodeValue(C))
        instance.addKeyInput(A_KEYS, 1)
        instance.setKeyedInput(B_KEYED, 1, 30)
        instance.completeSynchronously()
        assertEquals(31, instance.getNodeValue(C))
        instance.addKeyInput(A_KEYS, 2)
        instance.setKeyedInput(B_KEYED, 2, 60)
        instance.addKeyInput(A_KEYS, 3)
        instance.completeSynchronously()
        // TODO: Also not good! If the addition of <2, 60> had been registered, the value would be 91, not 31.
//        assertEquals(31, instance.getNodeValue(C))
        // This would be preferable:
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 3)), instance.getNodeOutcome(C))
    }

    @Test
    fun testSettingKeyedInputBeforeKeyExists1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        // This should be ignored since that key doesn't exist currently
        instance.setKeyedInput(B_KEYED, 2, 10)
        instance.completeSynchronously()
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 2)), instance.getNodeOutcome(B_KEYED, 2))
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 1), ValueId.Keyed(B_KEYED, 2), ValueId.Keyed(B_KEYED, 3)), instance.getNodeOutcome(B_KEYED))
    }

    @Test
    fun testSettingKeyedInputBeforeKeyExists2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        // This should be ignored since that key doesn't exist currently
        instance.setKeyedInput(B_KEYED, 2, 10)
//        instance.completeSynchronously()
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 2)), instance.getNodeOutcome(B_KEYED, 2))
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 1), ValueId.Keyed(B_KEYED, 2), ValueId.Keyed(B_KEYED, 3)), instance.getNodeOutcome(B_KEYED))
    }

    @Test(expected = IllegalStateException::class)
    fun testCannotUseNonInputKeyListAsKeyedInputSource() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val bKeys = builder.createKeyListNode(B_KEYS, a, { (0..it).toList() })
        val cKeyed = builder.createKeyedInputNode(C_KEYED, bKeys)
    }

    @Test
    fun testKeyedInputsGiveOutOfDateKeyedOutputsWhenKeysChange1() {
        val builder = RefillDefinitionBuilder()

        val bKeys = builder.createKeyListInputNode(B_KEYS)
        val dKeyed = builder.createKeyedInputNode(D_KEYED, bKeys)
        val eKeyed = builder.createKeyedNode(E_KEYED, bKeys, dKeyed.keyedOutput(), { k, e1 -> k + e1 })
        // TODO: Does it ever make sense for a keyed node to take its key source as an input? Are there problems that causes?
        // It must be accepting the full key list as the input here...

        val instance = builder.build().instantiateRaw()

        instance.setInput(B_KEYS, listOf(1, 2)) // op 3
        instance.setKeyedInput(D_KEYED, 1, 10) // op 4
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(D_KEYED, 2)), instance.getNodeOutcome(E_KEYED))

        instance.setKeyedInput(D_KEYED, 2, 20)
        instance.completeSynchronously()
        instance.addKeyInput(B_KEYS, 3)
        instance.completeSynchronously()

        assertEquals(inputsMissingOutcome(ValueId.Keyed(D_KEYED, 3)), instance.getNodeOutcome(E_KEYED)) // op 8
    }

    @Test
    fun testKeyedInputsGiveOutOfDateKeyedOutputsWhenKeysChange2() {
        val builder = RefillDefinitionBuilder()

        val bKeys = builder.createKeyListInputNode(B_KEYS)
        val dKeyed = builder.createKeyedInputNode(D_KEYED, bKeys)
        val eKeyed = builder.createKeyedNode(E_KEYED, bKeys, dKeyed.keyedOutput(), { k, e1 -> k + e1 })
        // TODO: Does it ever make sense for a keyed node to take its key source as an input? Are there problems that causes?
        // It must be accepting the full key list as the input here...

        val instance = builder.build().instantiateRaw()

        instance.setInput(B_KEYS, listOf(1, 2)) // op 3
        instance.setKeyedInput(D_KEYED, 1, 10) // op 4
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(D_KEYED, 2)), instance.getNodeOutcome(E_KEYED))

        instance.setKeyedInput(D_KEYED, 2, 20)
        instance.addKeyInput(B_KEYS, 3)
        instance.completeSynchronously()

        assertEquals(inputsMissingOutcome(ValueId.Keyed(D_KEYED, 3)), instance.getNodeOutcome(E_KEYED)) // op 8
    }

    @Test
    fun testSyncKeyedInputsBug() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateSync()

        instance.setInput(A_KEYS, listOf(5, 10, 6))
        instance.setKeyedInput(B_KEYED, 5, 10)
        assertEquals(NodeOutcome.Computed(10), instance.getOutcome(B_KEYED, 5))
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 10), ValueId.Keyed(B_KEYED, 6)), instance.getOutcome(B_KEYED))
    }

    @Test
    fun testRawKeyedInputsBug() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(5, 10, 6))
        instance.setKeyedInput(B_KEYED, 5, 10)
        instance.getNextSteps()
        instance.getNextSteps()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 10), ValueId.Keyed(B_KEYED, 6)), instance.getNodeOutcome(B_KEYED))
    }

    @Test
    fun testKeyedInputsRemovedAfterKeyRemoved1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.getNodeOutcome(A_KEYS)
        instance.addKeyInput(A_KEYS, 0)
        instance.setKeyedInput(B_KEYED, 0, 57)
        instance.removeKeyInput(A_KEYS, 0)
        instance.completeSynchronously()
        instance.setInput(A_KEYS, listOf(0, 1))
        instance.setKeyedInput(B_KEYED, 1, 58)
        instance.getNextSteps()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 0)), instance.getNodeOutcome(B_KEYED, 0))
    }

    @Test
    fun testKeyedInputsRemovedAfterKeyRemoved2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.getNodeOutcome(A_KEYS)
        instance.addKeyInput(A_KEYS, 0)
        instance.setKeyedInput(B_KEYED, 0, 57)
        instance.removeKeyInput(A_KEYS, 0)
        instance.setInput(A_KEYS, listOf(0, 1))
        instance.setKeyedInput(B_KEYED, 1, 58)
        instance.getNextSteps()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 0)), instance.getNodeOutcome(B_KEYED, 0))
    }

    @Test
    fun testKeyedValuesClearedWhenInputListsInvalidated1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val b = builder.createInputNode(B)
        val c = builder.createNode(C, b, { it + 1 })
        val dKeyed = builder.createKeyedInputNode(D_KEYED, aKeys)
        val eKeys = builder.createKeyListNode(E_KEYS, c, dKeyed.fullOutput(), { cVal, dList -> dList.map { it + cVal } })
        val fKeyed = builder.createKeyedNode(F_KEYED, eKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        instance.setInput(B, 6)
        instance.completeSynchronously()
        instance.addKeyInput(A_KEYS, 23)
        instance.completeSynchronously()

        instance.addKeyInput(A_KEYS, 29)
        instance.setKeyedInput(D_KEYED, 29, 20)
        instance.setKeyedInput(D_KEYED, 23, 31)
        instance.completeSynchronously()

        instance.setInput(A_KEYS, listOf(0))
        instance.completeSynchronously()
        assertEquals(NodeOutcome.NotYetComputed.get<Int>(), instance.getNodeOutcome(F_KEYED, 38))
    }


    @Test
    fun testKeyedValuesClearedWhenInputListsInvalidated2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val b = builder.createInputNode(B)
        val c = builder.createNode(C, b, { it + 1 })
        val dKeyed = builder.createKeyedInputNode(D_KEYED, aKeys)
        val eKeys = builder.createKeyListNode(E_KEYS, c, dKeyed.fullOutput(), { cVal, dList -> dList.map { it + cVal } })
        val fKeyed = builder.createKeyedNode(F_KEYED, eKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        instance.setInput(B, 6)
        instance.completeSynchronously()
        instance.addKeyInput(A_KEYS, 23)
        instance.completeSynchronously()

        instance.addKeyInput(A_KEYS, 29)
        instance.setKeyedInput(D_KEYED, 29, 20)
        instance.setKeyedInput(D_KEYED, 23, 31)
//        instance.completeSynchronously()

        instance.setInput(A_KEYS, listOf(0))
        instance.completeSynchronously()
        assertEquals(NodeOutcome.NotYetComputed.get<Int>(), instance.getNodeOutcome(F_KEYED, 38))
    }

    @Test
    fun testErrorPropagationPastKeyedNodeOfKeyListFailure() {
        val builder = RefillDefinitionBuilder()

        val exception = RuntimeException("error creating list")
        val failure = RefillFailure(mapOf(ValueId.FullKeyList(A_KEYS) to exception), setOf())

        val aKeys = builder.createKeyListNode(A_KEYS, listOf(), { throw exception }, null)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it + 1 })
        val c = builder.createNode(C, bKeyed.fullOutput(), { it.sum() })
        val d = builder.createNode(D, bKeyed.fullOutput(), { it.sum() }, { -1 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(NodeOutcome.Failure<List<Int>>(failure), instance.getNodeOutcome(A_KEYS))
        assertEquals(NodeOutcome.Failure<List<Int>>(failure), instance.getNodeOutcome(B_KEYED))
//        assertEquals(NodeOutcome.Failure<Int>(failure), instance.getNodeOutcome(B_KEYED, 1))
        // TODO: This feels wrong
        assertEquals(NodeOutcome.NotYetComputed.get<Int>(), instance.getNodeOutcome(B_KEYED, 1))
        assertEquals(NodeOutcome.Failure<Int>(failure), instance.getNodeOutcome(C))
        assertEquals(NodeOutcome.Computed(-1), instance.getNodeOutcome(D))
    }

    @Ignore("I'll come back to this later")
    @Test
    fun testArgumentEqualityCheck1() {
        val builder = RefillDefinitionBuilder()

        var computationCount = 0
        // TODO: Next step is to figure out the right way to pass in "If this is equal, don't recompute outputs" to A
        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, {
            computationCount++
            it + 1
        })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 10)
        assertEquals(0, computationCount)
        instance.completeSynchronously()
        assertEquals(1, computationCount)
        assertEquals(11, instance.getNodeValue(B))
        instance.setInput(A, 10)
        // There is a step for determining B, but it does not end up running the operation
        assertEquals(1, instance.getNextSteps().size)
        instance.completeSynchronously()
        assertEquals(1, computationCount)
        instance.setInput(A, 20)
        instance.setInput(A, 10)
        instance.completeSynchronously()
        assertEquals(1, computationCount)
    }

    @Test
    fun testBasicAsync1() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, { it + 1 })
        val c = builder.createNode(C, a, { it * 2 })
        val d = builder.createNode(D, b, c, { left, right -> left * right })

        val executor = Executors.newCachedThreadPool()
        val instance = builder.build().instantiateAsync(executor)

        try {
            val timestamp1 = instance.setInput(A, 1)
            assertEquals(NodeOutcome.Computed(1), instance.getOutcome(A, timestamp1))
            assertEquals(NodeOutcome.Computed(2), instance.getOutcome(B, timestamp1))
            assertEquals(NodeOutcome.Computed(2), instance.getOutcome(C, timestamp1))
            assertEquals(NodeOutcome.Computed(4), instance.getOutcome(D, timestamp1))
            val timestamp2 = instance.setInput(A, 2)
            assertEquals(NodeOutcome.Computed(2), instance.getOutcome(A, timestamp2))
            assertEquals(NodeOutcome.Computed(3), instance.getOutcome(B, timestamp2))
            assertEquals(NodeOutcome.Computed(4), instance.getOutcome(C, timestamp2))
            assertEquals(NodeOutcome.Computed(12), instance.getOutcome(D, timestamp2))
        } finally {
            instance.shutdown()
        }
    }


    @Test
    fun testBasicAsync2() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, { it + 1 })
        val c = builder.createInputNode(C)

        val executor = Executors.newCachedThreadPool()
        val instance = builder.build().instantiateAsync(executor)

        try {
            val timestamp1 = instance.setInput(A, 1)
            assertEquals(NodeOutcome.Computed(1), instance.getOutcome(A, timestamp1))
            assertEquals(NodeOutcome.Computed(2), instance.getOutcome(B, timestamp1))
            val timestamp2 = instance.setInput(C, 1)
            assertEquals(NodeOutcome.Computed(1), instance.getOutcome(A, 2, TimeUnit.SECONDS, timestamp2))
            assertEquals(NodeOutcome.Computed(2), instance.getOutcome(B, 2, TimeUnit.SECONDS, timestamp2))
        } finally {
            instance.shutdown()
        }
    }

    @Test
    fun testAsyncTimeout1() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, { Thread.sleep(100); it + 1 })

        val executor = Executors.newCachedThreadPool()
        val instance = builder.build().instantiateAsync(executor)

        try {
            val timestamp1 = instance.setInput(A, 1)
            assertEquals(NodeOutcome.Computed(2), instance.getOutcome(B, 1, TimeUnit.SECONDS, timestamp1))
        } finally {
            instance.shutdown()
        }
    }

    @Test
    fun testAsyncTimeout2() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, { Thread.sleep(1000); it + 1 })

        val executor = Executors.newCachedThreadPool()
        val instance = builder.build().instantiateAsync(executor)

        try {
            val timestamp1 = instance.setInput(A, 1)
            assertThrows(TimeoutException::class.java) {
                assertEquals(NodeOutcome.Computed(2), instance.getOutcome(B, 100, TimeUnit.MILLISECONDS, timestamp1))
            }
        } finally {
            instance.shutdown()
        }
    }

    @Test
    fun testAsyncNoSuchKey() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val syncInstance = builder.build().instantiateSync()
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), syncInstance.getOutcome(B_KEYED, 1))

        val executor = Executors.newCachedThreadPool()
        val instance = builder.build().instantiateAsync(executor)

        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getOutcome(B_KEYED, 1, 1, TimeUnit.SECONDS))
    }

    @Test
    fun testAsyncMissingInputBasic() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, { it + 1 })

        val instance = builder.build().instantiateAsync(Executors.newCachedThreadPool())

        try {
            assertEquals(NodeOutcome.Failure<Int>(RefillFailure(mapOf(), setOf(ValueId.Nonkeyed(A)))), instance.getOutcome(A, 1, TimeUnit.SECONDS))
            assertEquals(NodeOutcome.Failure<Int>(RefillFailure(mapOf(), setOf(ValueId.Nonkeyed(A)))), instance.getOutcome(B, 1, TimeUnit.SECONDS))
        } finally {
            instance.shutdown()
        }
    }

    @Test
    fun testKeyUpdatesUpdateKeyedTimestamps() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1))
        val (step1) = instance.getNextSteps()
        assertEquals(ValueId.Keyed(B_KEYED, 1), step1.valueId)
        val result1 = step1.execute()
        instance.reportResult(result1)
        assertEquals(0, instance.getNextSteps().size)
        instance.setInput(A_KEYS, listOf())
        instance.setInput(A_KEYS, listOf(1))
        val (newStep) = instance.getNextSteps()
        // These timestamps should not be the same
        assertNotEquals(step1.timestamp, newStep.timestamp)
    }

    @Test
    fun testAsyncMissingKeyedInput1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateAsync(Executors.newCachedThreadPool())

        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getOutcome(B_KEYED, 1, 1, TimeUnit.SECONDS))
//        assertEquals(NodeOutcome.Failure<Int>(RefillFailure(mapOf(), setOf(ValueId.Nonkeyed(A)))), instance.getOutcome(B_KEYED, 1, 1, TimeUnit.SECONDS))
    }

    @Test
    fun testAsyncMissingKeyedInput2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateAsync(Executors.newCachedThreadPool())

        val ts = instance.addKeyInput(A_KEYS, 1)
        assertEquals(NodeOutcome.Failure<Int>(RefillFailure(mapOf(), setOf(ValueId.Keyed(B_KEYED, 1)))), instance.getOutcome(B_KEYED, 1, 1, TimeUnit.SECONDS, ts))
    }

    @Test
    fun testAsyncMissingKeyedInput3() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateAsync(Executors.newCachedThreadPool())

        instance.removeKeyInput(A_KEYS, 77)
        instance.setKeyedInput(B_KEYED, 1, 37)
        instance.setKeyedInput(B_KEYED, 1, 25)
        instance.setKeyedInput(B_KEYED, 1, 30)
        instance.setInput(A_KEYS, listOf(14, 6, 4, 13, 11, 8))
        instance.addKeyInput(A_KEYS, 18)
        var timestamp = instance.setKeyedInput(B_KEYED, 13, 26)
        assertEquals(NodeOutcome.Failure<Int>(RefillFailure(mapOf(), setOf(ValueId.Keyed(B_KEYED, 4)))), instance.getOutcome(B_KEYED, 4, 5, TimeUnit.SECONDS, timestamp))
        instance.setInput(A_KEYS, listOf(104, 98, 101))
        instance.addKeyInput(A_KEYS, 34)
        instance.setKeyedInput(B_KEYED, 98, 84)
        instance.setKeyedInput(B_KEYED, 98, 53)
        timestamp = instance.removeKeyInput(A_KEYS, 101)
        assertEquals(NodeOutcome.Failure<Int>(RefillFailure(mapOf(), setOf(ValueId.Keyed(B_KEYED, 34)))), instance.getOutcome(B_KEYED, 34, timestamp))
    }

    @Test
    fun testAsyncListeners1() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, {it + 1})

        val instance = builder.build().instantiateAsync(Executors.newCachedThreadPool())

        try {
            instance.setInput(A, 3)
            val listenedBValue = AtomicReference<RefillEvent<Int>>()
            // Try adding the listener after the input is already set
            instance.addBasicListener(B, RefillEventListener { event ->
                listenedBValue.set(event)
            })
            await().untilAsserted {
                val event = listenedBValue.get()
                assertTrue(event is RefillEvent.Computed)
                assertEquals(4, (event as RefillEvent.Computed).value)
            }
            // Now try a change after the listener was added
            instance.setInput(A, 10)
            await().untilAsserted {
                val event = listenedBValue.get()
                assertTrue(event is RefillEvent.Computed)
                assertEquals(11, (event as RefillEvent.Computed).value)
            }
        } finally {
            instance.shutdown()
        }
    }

    @Test
    fun testListenerNotInvokedForNonfinalValuesInInputs() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)

        val instance = builder.build().instantiateRaw()

        val saw2 = AtomicBoolean(false)
        val saw3Latch = CountDownLatch(1)

        instance.setValueListener { event ->
            if (event is RefillEvent.Computed) {
                if (event.value == 2) {
                    saw2.set(true)
                } else if (event.value == 3) {
                    saw3Latch.countDown()
                }
            }
        }

        instance.setInputs(listOf(
            RefillInputChange.SetBasic(A, 2),
            RefillInputChange.SetBasic(A, 3)
        ))

        val foundSuccessfully = saw3Latch.await(10, TimeUnit.SECONDS)
        assertTrue(foundSuccessfully)

        assertFalse("Should not have seen the intermediate value of the input", saw2.get())
    }

    @Test
    fun testKeyedValuesGetRemovedWhenInputKeysRemovedViaSetting() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(B_KEYED, 3))
        instance.setInput(A_KEYS, listOf(1, 2))
        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.Keyed(B_KEYED, 3)))
    }

    @Test
    fun testKeyedValuesGetRemovedWhenInputKeysRemovedViaEditing() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(B_KEYED, 3))
        instance.editKeys(A_KEYS, listOf(), listOf(3))
        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.Keyed(B_KEYED, 3)))
    }


    @Test
    fun testKeyedValuesGetRemovedWhenNonInputKeysRemoved() {
        val builder = RefillDefinitionBuilder()

        val a = builder.createInputNode(A)
        val bKeys = builder.createKeyListNode(B_KEYS, a, { (1..it).toList() })
        val cKeyed = builder.createKeyedNode(C_KEYED, bKeys, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 3)
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(C_KEYED, 3))
        instance.setInput(A, 2)
        // Recompute the keys
        instance.completeSynchronously()
        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.Keyed(C_KEYED, 3)))
    }

    @Test
    fun testGettingSingleKeyValues() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)

        val instance = builder.build().instantiateRaw()

        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        assertEquals(true, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
        instance.setInput(A_KEYS, listOf(2, 3))
        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
    }

    @Test
    fun testGettingSingleKeyValues2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)

        val instance = builder.build().instantiateRaw()

        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        assertEquals(true, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
        instance.removeKeyInput(A_KEYS, 1)
        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
    }

    @Test
    fun testGettingSingleKeyValues3() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)

        val instance = builder.build().instantiateRaw()

        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        assertEquals(true, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
        instance.editKeys(A_KEYS, listOf(), listOf(1))
        assertEquals(null, instance.getValueDirectlyForTesting(ValueId.KeyListKey(A_KEYS, 1)))
    }

    @Test
    fun testErrorFromKeyedInputsListOutput() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        // This propagates the error
        instance.getNextSteps()
        assertEquals(NodeOutcome.Failure<List<Int>>(RefillFailure(mapOf(), setOf(ValueId.Keyed(B_KEYED, 1), ValueId.Keyed(B_KEYED, 2), ValueId.Keyed(B_KEYED, 3)))), instance.getNodeOutcome(B_KEYED))
    }

    @Test
    fun testSettingKeyedInputSimultaneouslyWithKeyAddition1() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.setInputs(listOf(
            RefillInputChange.SetKeys(A_KEYS, listOf(1)),
            RefillInputChange.SetKeyed(B_KEYED, mapOf(1 to 10))
        ))
        assertEquals(NodeOutcome.Computed(10), instance.getNodeOutcome(B_KEYED, 1))
    }

    // Note the reversed order compared with the previous test
    @Test
    fun testSettingKeyedInputSimultaneouslyWithKeyAddition2() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.setInputs(listOf(
            RefillInputChange.SetKeyed(B_KEYED, mapOf(1 to 10)),
            RefillInputChange.SetKeys(A_KEYS, listOf(1))
        ))
        instance.getNextSteps()
        assertEquals(NodeOutcome.Failure<Int>(RefillFailure(mapOf(), setOf(ValueId.Keyed(B_KEYED, 1)))), instance.getNodeOutcome(B_KEYED, 1))
    }

    // Coalescing shouldn't mess this up
    @Test
    fun testSettingKeyedInputSimultaneouslyWithKeyAddition3() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.setInputs(listOf(
            RefillInputChange.SetKeyed(B_KEYED, mapOf(2 to 20)),
            RefillInputChange.SetKeys(A_KEYS, listOf(1)),
            RefillInputChange.SetKeyed(B_KEYED, mapOf(1 to 10))
        ))
        instance.getNextSteps()
        assertEquals(NodeOutcome.Computed(10), instance.getNodeOutcome(B_KEYED, 1))
    }

    // Coalescing shouldn't mess this up
    @Test
    fun testSettingKeyedInputSimultaneouslyWithKeyAddition4() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.setInputs(listOf(
            RefillInputChange.SetKeyed(B_KEYED, mapOf(1 to 10)),
            RefillInputChange.SetKeys(A_KEYS, listOf(1)),
            RefillInputChange.SetKeyed(B_KEYED, mapOf(2 to 20))
        ))
        instance.getNextSteps()
        assertEquals(NodeOutcome.Failure<Int>(RefillFailure(mapOf(), setOf(ValueId.Keyed(B_KEYED, 1)))), instance.getNodeOutcome(B_KEYED, 1))
    }

    @Test
    fun testRemovingAndReaddingKeyGivesDifferentKeyedEventTimestamps() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it + 1 })

        val instance = builder.build().instantiateAsync(Executors.newCachedThreadPool())

        try {
            val initialComputationEvent = CompletableFuture<RefillEvent<Int>>()
            val removalEvent = CompletableFuture<RefillEvent<Int>>()
            val recomputedEvent = CompletableFuture<RefillEvent<Int>>()
            instance.addPerKeyListener(B_KEYED, RefillEventListener<Int> { event: RefillEvent<Int> ->
                if ((event.valueId as? ValueId.Keyed)?.key?.equals(1) == true) {
                    if (!initialComputationEvent.isDone()) {
                        initialComputationEvent.complete(event)
                    } else if (!removalEvent.isDone()) {
                        removalEvent.complete(event)
                    } else if (!recomputedEvent.isDone()) {
                        recomputedEvent.complete(event)
                    } else {
                        error("More events than expected: $event")
                    }
                }
            })
            instance.setInput(A_KEYS, listOf(1, 2, 3))
            val event1 = initialComputationEvent.get(10, TimeUnit.SECONDS)
            assertTrue(event1 is RefillEvent.Computed && event1.value == 2)
            instance.setInputs(listOf(
                RefillInputChange.SetKeys(A_KEYS, listOf(2, 3)),
                RefillInputChange.SetKeys(A_KEYS, listOf(1, 2))
            ))
            val event2 = removalEvent.get(10, TimeUnit.SECONDS)
            assertTrue(event2 is RefillEvent.KeyRemoved)
            val event3 = recomputedEvent.get(10, TimeUnit.SECONDS)
            assertTrue(event3 is RefillEvent.Computed && event3.value == 2)
            assertTrue(event1.timestamp < event2.timestamp)
            assertTrue(event2.timestamp < event3.timestamp)
        } finally {
            instance.shutdown()
        }
    }

    @Test
    fun testRemovingAndReaddingKeyWorksWithAsyncGetters() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it + 1 })

        val instance = builder.build().instantiateAsync(Executors.newCachedThreadPool())

        try {
            instance.setInput(A_KEYS, listOf(1, 2, 3))
            val timestamp = instance.setInputs(listOf(
                RefillInputChange.SetKeys(A_KEYS, listOf(2, 3)),
                RefillInputChange.SetKeys(A_KEYS, listOf(1, 2))
            ))
            val outcome = instance.getOutcome(B_KEYED, 1, 10, TimeUnit.SECONDS, timestamp)
            assertEquals(NodeOutcome.Computed(2), outcome)
        } finally {
            instance.shutdown()
        }
    }

    @Test
    fun testReceivingObsoleteComputedKeyEventDoesntTriggerListener() {
        val builder = RefillDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it + 1 })

        val instance = builder.build().instantiateRaw()

        var sawComputedResult = false
        instance.setValueListener { event ->
            if (event is RefillEvent.Computed<*> && event.valueId == ValueId.Keyed(B_KEYED, 1)) {
                sawComputedResult = true
            }
        }

        instance.setInput(A_KEYS, listOf(1))
        val computeBStep = instance.getNextSteps().single()
        assertEquals(ValueId.Keyed(B_KEYED, 1), computeBStep.valueId)
        instance.setInput(A_KEYS, listOf())
        instance.reportResult(computeBStep.execute())
        assertNull(instance.getValueDirectlyForTesting(ValueId.Keyed(B_KEYED, 1)))
        assertFalse(sawComputedResult)
    }
}
