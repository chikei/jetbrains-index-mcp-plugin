package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import junit.framework.TestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Integration tests for [KtorMcpServer] over the official Kotlin MCP SDK transports.
 *
 * These exercise the SDK-backed routing: stateless Streamable HTTP (primary + legacy alias),
 * DNS-rebinding/origin protection, and the legacy SSE handshake. Protocol-level behavior
 * (batching, error codes, session lifecycle) is owned by the SDK and is not re-asserted here.
 *
 * The server factory builds a bare SDK [Server] (no tools): `ping`/`initialize` are handled by
 * the SDK itself, so transport wiring can be verified without the IntelliJ platform's settings
 * service. Tool registration and dispatch are covered by their own tests.
 */
class KtorMcpServerUnitTest : TestCase() {

    private val httpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val mcpSessionIdHeader = "Mcp-Session-Id"

    private lateinit var server: KtorMcpServer
    private var port: Int = 0

    override fun setUp() {
        super.setUp()
        port = findFreePort()
        server = createServer(port)
        assertEquals(KtorMcpServer.StartResult.Success, server.start())
    }

    override fun tearDown() {
        server.stop()
        super.tearDown()
    }

    fun testStreamableInitializeOmitsSessionHeaderAndReturns2025ProtocolVersion() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = initializeRequestBody("2025-03-26")
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())
        assertFalse(
            "Streamable HTTP should not return an Mcp-Session-Id header in stateless mode",
            response.headers().firstValue(mcpSessionIdHeader).isPresent
        )

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals(
            "2025-03-26",
            responseBody["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testLegacyPostInitializeReturns2024ProtocolVersion() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.MCP_ENDPOINT_PATH,
            body = initializeRequestBody("2024-11-05")
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals(
            "2024-11-05",
            responseBody["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testStreamablePingReturnsResult() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("1", responseBody["id"]!!.jsonPrimitive.content)
        assertNotNull(responseBody["result"])
    }

    fun testStreamableGetReturnsMethodNotAllowed() {
        val response = sendRequest(
            method = "GET",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH
        )

        assertEquals(HttpStatusCode.MethodNotAllowed.value, response.statusCode())
        assertEquals("POST", response.headers().firstValue("allow").orElse(""))
    }

    fun testStreamableDeleteReturnsMethodNotAllowedInStatelessMode() {
        val response = sendRequest(
            method = "DELETE",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH
        )

        assertEquals(HttpStatusCode.MethodNotAllowed.value, response.statusCode())
        assertEquals("POST", response.headers().firstValue("allow").orElse(""))
    }

    fun testRejectsNonLocalOrigin() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = initializeRequestBody("2025-03-26"),
            headers = mapOf("Origin" to "https://evil.example")
        )

        assertEquals(HttpStatusCode.Forbidden.value, response.statusCode())
    }

    fun testAcceptsIpv6LoopbackOrigin() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = initializeRequestBody("2025-03-26"),
            headers = mapOf("Origin" to "http://[::1]:3000")
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())
    }

    fun testLegacySseHandshakeAdvertisesEndpointAndStreamsResponses() {
        val response = openSseStream(McpConstants.SSE_ENDPOINT_PATH)
        response.body().use {
            assertEquals(HttpStatusCode.OK.value, response.statusCode())
            assertTrue(
                response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream")
            )

            val reader = it.bufferedReader()
            val endpointEvent = readSseEvent(reader)
            assertEquals("endpoint", endpointEvent.eventType())

            val sessionId = endpointEvent.data().substringAfter("sessionId=")
            assertTrue("Expected sessionId in endpoint event", sessionId.isNotBlank())

            val postResponse = sendRequest(
                method = "POST",
                path = "${McpConstants.SSE_ENDPOINT_PATH}?${McpConstants.SESSION_ID_PARAM}=$sessionId",
                body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
            )

            assertEquals(HttpStatusCode.Accepted.value, postResponse.statusCode())

            val messageEvent = readSseEvent(reader)
            assertEquals("message", messageEvent.eventType())

            val messageBody = json.parseToJsonElement(messageEvent.data()).jsonObject
            assertEquals("1", messageBody["id"]!!.jsonPrimitive.content)
            assertNotNull(messageBody["result"])
        }
    }

    fun testStreamableRequestStillWorksAfterRestart() {
        server.stop()

        port = findFreePort()
        server = createServer(port)
        assertEquals(KtorMcpServer.StartResult.Success, server.start())

        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())
    }

    private fun createServer(port: Int): KtorMcpServer {
        return KtorMcpServer(
            port = port,
            serverFactory = {
                Server(
                    serverInfo = Implementation(name = "test-server", version = "1.0.0"),
                    options = ServerOptions(capabilities = ServerCapabilities()),
                )
            }
        )
    }

    private fun sendRequest(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Accept", "application/json, text/event-stream")

        headers.forEach { (name, value) -> builder.header(name, value) }

        if (body != null) {
            builder.header("Content-Type", "application/json")
        }

        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
            "DELETE" -> builder.DELETE()
            else -> error("Unsupported method: $method")
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun openSseStream(
        path: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<InputStream> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Accept", "text/event-stream")
            .GET()

        headers.forEach { (name, value) -> builder.header(name, value) }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun readSseEvent(reader: BufferedReader, timeoutMillis: Long = 5_000): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!reader.ready() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertTrue("Timed out waiting for SSE event", reader.ready())

        val lines = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                if (lines.isNotEmpty()) {
                    break
                }
                continue
            }
            lines += line
        }

        return lines
    }

    private fun List<String>.eventType(): String = first { it.startsWith("event:") }.substringAfter("event:").trim()

    private fun List<String>.data(): String = filter { it.startsWith("data:") }
        .joinToString("\n") { it.substringAfter("data:").trim() }

    private fun initializeRequestBody(protocolVersion: String) = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "$protocolVersion",
            "clientInfo": {
              "name": "test-client",
              "version": "1.0.0"
            },
            "capabilities": {}
          }
        }
    """.trimIndent()

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
