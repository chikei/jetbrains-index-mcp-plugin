package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema

import junit.framework.TestCase
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Unit tests for [toToolSchema], the adapter that converts a raw `{type, properties, required}`
 * JSON schema (as emitted by `SchemaBuilder`) into the SDK's `ToolSchema`.
 */
class ToolSchemaAdapterUnitTest : TestCase() {

    fun testExtractsPropertiesAndRequired() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file") { put("type", "string") }
                putJsonObject("line") { put("type", "integer") }
            }
            putJsonArray("required") {
                add("file")
            }
        }

        val toolSchema = toToolSchema(schema)

        assertNotNull("properties should be carried over", toolSchema.properties)
        assertTrue("properties should contain 'file'", toolSchema.properties!!.containsKey("file"))
        assertTrue("properties should contain 'line'", toolSchema.properties!!.containsKey("line"))
        assertEquals(listOf("file"), toolSchema.required)
    }

    fun testMissingRequiredDefaultsToEmptyList() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string") }
            }
        }

        val toolSchema = toToolSchema(schema)

        assertNotNull("properties should be carried over", toolSchema.properties)
        assertEquals(emptyList<String>(), toolSchema.required)
    }

    fun testMissingPropertiesYieldsNull() {
        val schema = buildJsonObject {
            put("type", "object")
        }

        val toolSchema = toToolSchema(schema)

        assertNull("properties should be null when absent", toolSchema.properties)
        assertEquals(emptyList<String>(), toolSchema.required)
    }

    fun testEmptyRequiredArrayYieldsEmptyList() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonArray("required") {}
        }

        val toolSchema = toToolSchema(schema)

        assertEquals(emptyList<String>(), toolSchema.required)
    }

    fun testEmptyPropertiesObjectIsPreservedNonNull() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        val toolSchema = toToolSchema(schema)

        assertNotNull("Empty properties object should be preserved, not nulled", toolSchema.properties)
        assertTrue("Properties should be empty", toolSchema.properties!!.isEmpty())
    }

    fun testMultipleRequiredPreservedInOrder() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonArray("required") {
                add("a")
                add("b")
                add("c")
            }
        }

        val toolSchema = toToolSchema(schema)

        assertEquals(listOf("a", "b", "c"), toolSchema.required)
    }
}
