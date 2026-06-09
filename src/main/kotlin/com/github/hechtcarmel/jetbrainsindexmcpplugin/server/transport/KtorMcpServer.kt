package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import java.net.BindException
import java.net.URI

/**
 * Embedded Ktor CIO server hosting the MCP protocol via the official Kotlin MCP SDK transports.
 *
 * Supports two transport modes, both backed by SDK-provided transports:
 * 1. Streamable HTTP Transport (2025-03-26 spec) — PRIMARY:
 *    - POST   /index-mcp/streamable-http → Stateless JSON-RPC over HTTP
 *    - GET    /index-mcp/streamable-http → 405 Method Not Allowed
 *    - DELETE /index-mcp/streamable-http → 405 Method Not Allowed
 *    - POST   /index-mcp                 → Stateless alias (backward compatibility)
 *
 * 2. Legacy SSE Transport (2024-11-05 spec):
 *    - GET  /index-mcp/sse → Opens SSE stream, advertises POST URL via the `endpoint` event
 *    - POST /index-mcp/sse → JSON-RPC messages, response sent over the stream
 *
 * A fresh [Server] is built per connection via [serverFactory] so that the advertised tool set
 * always reflects the current settings.
 */
class KtorMcpServer(
    private val port: Int,
    private val host: String = McpConstants.DEFAULT_SERVER_HOST,
    private val serverFactory: () -> Server,
) : Disposable {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    companion object {
        private val LOG = logger<KtorMcpServer>()

        /** Localhost hostnames accepted by DNS-rebinding protection (port/scheme ignored). */
        private val LOCALHOST_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

        /** Same localhost origins for the CORS plugin: host → allowed schemes. */
        private val CORS_HOSTS = listOf(
            "localhost" to listOf("http"),
            "127.0.0.1" to listOf("http"),
            "[::1]" to listOf("http"),
        )
    }

    /**
     * Result of attempting to start the server.
     */
    sealed class StartResult {
        data object Success : StartResult()
        data class PortInUse(val port: Int) : StartResult()
        data class Error(val message: String, val cause: Throwable? = null) : StartResult()
    }

    /**
     * Starts the server.
     *
     * @return StartResult indicating success or failure with details
     */
    fun start(): StartResult {
        return try {
            server = embeddedServer(CIO, port = port, host = host) {
                configureRouting()
            }
            server?.start(wait = false)

            LOG.info("MCP Server started on http://$host:$port")
            StartResult.Success
        } catch (e: BindException) {
            LOG.warn("Port $port is already in use", e)
            StartResult.PortInUse(port)
        } catch (e: Exception) {
            if (e is CancellationException) {
                val cause = e.cause
                if (cause is BindException) {
                    LOG.warn("Failed to start server on $host:$port: ${cause.message}", cause)
                    return StartResult.Error("Failed to bind to $host:$port. ${cause.message}", cause)
                }
                throw e
            }
            LOG.error("Failed to start MCP server", e)
            StartResult.Error(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Stops the server gracefully.
     */
    fun stop() {
        try {
            server?.stop(1000, 2000)
            server = null
            LOG.info("MCP Server stopped")
        } catch (e: Exception) {
            LOG.warn("Error stopping MCP server", e)
        }
    }

    /**
     * Returns whether the server is currently running.
     */
    fun isRunning(): Boolean = server != null

    override fun dispose() = stop()

    private fun Application.configureRouting() {
        install(SSE)
        install(ContentNegotiation) { json(McpJson) }
        // CORS for browser-based MCP clients. Locked to the same localhost origins enforced by
        // DnsRebindingProtection below — no anyHost().
        install(CORS) {
            CORS_HOSTS.forEach { (host, schemes) -> allowHost(host, schemes = schemes) }
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowNonSimpleContentTypes = true
            allowHeader("Mcp-Session-Id")
            allowHeader("Mcp-Protocol-Version")
            exposeHeader("Mcp-Session-Id")
            exposeHeader("Mcp-Protocol-Version")
        }

        routing {
            // All transports live under /index-mcp. Origin protection is enforced in
            // handleStreamableHttp (the 0.10.0 SDK has no route-level DnsRebindingProtection
            // plugin); the CORS plugin above plus localhost-only binding cover the rest.
            route(McpConstants.MCP_ENDPOINT_PATH) {
                // === Streamable HTTP Transport (2025-03-26 spec) ===
                route("/streamable-http") {
                    post { handleStreamableHttp(call) }
                    get { respondMethodNotAllowed(call) }
                    delete { respondMethodNotAllowed(call) }
                }

                // Backward-compatible stateless alias on the legacy POST path.
                post { handleStreamableHttp(call) }

                // === Legacy SSE Transport (2024-11-05 spec) ===
                // The SDK owns the session lifecycle and advertises its POST URL via the
                // `endpoint` event.
                mcp(path = "/sse") {
                    serverFactory()
                }
            }
        }
    }

    /**
     * Handles a stateless Streamable HTTP POST request by bridging it to an SDK
     * [StreamableHttpServerTransport]. Mirrors the SDK's private stateless endpoint.
     *
     * Origin protection is enforced here rather than via the SDK's built-in DNS-rebinding
     * guard: the 0.10.0 transport compares the full Origin string (scheme + host + port) and
     * rejects requests with no Origin header, which blocks legitimate non-browser clients and
     * loopback origins on non-default ports. We compare hostnames only and allow a missing
     * Origin (non-browser callers), matching the localhost-only binding policy.
     */
    private suspend fun handleStreamableHttp(call: ApplicationCall) {
        if (!isOriginAllowed(call.request.headers[HttpHeaders.Origin])) {
            call.respond(HttpStatusCode.Forbidden)
            return
        }

        val transport = StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
        ).also { it.setSessionIdGenerator(null) }

        val mcpServer = serverFactory()
        mcpServer.createSession(transport)

        transport.handleRequest(null, call)
    }

    /** Allows a missing Origin (non-browser clients) or any localhost hostname, port-agnostic. */
    private fun isOriginAllowed(origin: String?): Boolean {
        if (origin == null) return true
        val host = runCatching { URI(origin).host }.getOrNull() ?: return false
        return host.lowercase() in LOCALHOST_HOSTS
    }

    private suspend fun respondMethodNotAllowed(call: ApplicationCall) {
        call.response.header(HttpHeaders.Allow, "POST")
        call.respond(HttpStatusCode.MethodNotAllowed)
    }
}
