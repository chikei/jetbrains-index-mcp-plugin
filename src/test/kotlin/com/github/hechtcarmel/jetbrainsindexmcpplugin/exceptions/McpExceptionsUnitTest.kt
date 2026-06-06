package com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions

import junit.framework.TestCase

class McpExceptionsUnitTest : TestCase() {

    fun testIndexNotReadyException() {
        val exception = IndexNotReadyException("IDE is currently indexing")

        assertEquals("IDE is currently indexing", exception.message)
        assertEquals(JsonRpcErrorCodes.INDEX_NOT_READY, exception.errorCode)
        assertEquals(-32001, exception.errorCode)
    }

    fun testCustomErrorCodeIsInCustomRange() {
        val code = JsonRpcErrorCodes.INDEX_NOT_READY

        assertTrue("Custom error code $code should be negative", code < 0)
        assertTrue(
            "Custom error code $code should be in custom range -32099 to -32001",
            code in -32099..-32001
        )
    }
}
