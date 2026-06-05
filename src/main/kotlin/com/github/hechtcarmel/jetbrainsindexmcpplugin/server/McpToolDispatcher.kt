package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class McpToolDispatcher {

    companion object {
        private val LOG = logger<McpToolDispatcher>()
    }

    suspend fun dispatch(tool: McpTool, arguments: JsonObject): CallToolResult {
        val projectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.contentOrNull

        val projectResult = ProjectResolver.resolve(projectPath)
        if (projectResult.isError) {
            return toCallToolResult(projectResult.errorResult!!)
        }

        val project = projectResult.project!!
        val commandEntry = CommandEntry(toolName = tool.name, parameters = arguments)
        recordHistorySafely(project, commandEntry)

        val startTime = System.currentTimeMillis()

        return try {
            val result = tool.execute(project, arguments)
            val duration = System.currentTimeMillis() - startTime

            updateHistorySafely(
                project = project,
                commandEntry = commandEntry,
                status = if (result.isError) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result = result.content.firstOrNull()?.let {
                    when (it) {
                        is ContentBlock.Text -> it.text
                        is ContentBlock.Image -> "[Image]"
                    }
                },
                duration = duration
            )

            toCallToolResult(result)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            LOG.error("Tool execution failed: ${tool.name}", e)

            updateHistorySafely(
                project = project,
                commandEntry = commandEntry,
                status = CommandStatus.ERROR,
                result = e.message,
                duration = duration
            )

            CallToolResult(
                content = listOf(TextContent(e.message ?: ErrorMessages.UNKNOWN_ERROR)),
                isError = true
            )
        }
    }

    private fun recordHistorySafely(project: Project, commandEntry: CommandEntry) {
        try {
            CommandHistoryService.getInstance(project).recordCommand(commandEntry)
        } catch (e: Exception) {
            LOG.warn("Failed to record command history for ${commandEntry.toolName}", e)
        }
    }

    private fun updateHistorySafely(
        project: Project,
        commandEntry: CommandEntry,
        status: CommandStatus,
        result: String?,
        duration: Long
    ) {
        try {
            CommandHistoryService.getInstance(project).updateCommandStatus(commandEntry.id, status, result, duration)
        } catch (e: Exception) {
            LOG.warn("Failed to update command history for ${commandEntry.toolName}", e)
        }
    }
}

fun toCallToolResult(result: ToolCallResult): CallToolResult = CallToolResult(
    content = result.content.map { block ->
        when (block) {
            is ContentBlock.Text -> TextContent(block.text)
            is ContentBlock.Image -> ImageContent(block.data, block.mimeType)
        }
    },
    isError = result.isError,
)
