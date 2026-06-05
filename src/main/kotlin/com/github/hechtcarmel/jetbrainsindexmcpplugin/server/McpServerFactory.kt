package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.toToolSchema
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

fun buildServer(toolRegistry: ToolRegistry, dispatcher: McpToolDispatcher): Server {
    val server = Server(
        serverInfo = Implementation(
            name = McpConstants.getServerName(),
            version = McpConstants.SERVER_VERSION,
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    for (def in toolRegistry.getToolDefinitions()) {
        val tool = toolRegistry.getTool(def.name) ?: continue
        server.addTool(
            name = def.name,
            description = def.description,
            inputSchema = toToolSchema(def.inputSchema),
        ) { request ->
            dispatcher.dispatch(tool, request.arguments ?: EmptyJsonObject)
        }
    }

    return server
}
