package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class McpToolDispatcher(
    private val recordHistory: (Project, CommandEntry) -> Unit = { project, entry ->
        CommandHistoryService.getInstance(project).recordCommand(entry)
    },
    private val updateHistory: (Project, CommandEntry, CommandStatus, String?, Long) -> Unit =
        { project, entry, status, result, duration ->
            CommandHistoryService.getInstance(project).updateCommandStatus(entry.id, status, result, duration)
        },
) {

    companion object {
        private val LOG = logger<McpToolDispatcher>()
    }

    /**
     * Dispatches a tool call within an IDE modality context so tools that rely on the ambient
     * [ModalityState] (read actions, `invokeLater` without an explicit modality) behave correctly
     * regardless of which transport coroutine the SDK runs the call on.
     *
     * The dispatch logic lives in [dispatchInternal] so it can be exercised in tests without an
     * IDE application present.
     */
    suspend fun dispatch(tool: McpTool, arguments: JsonObject): CallToolResult =
        withIdeModality { dispatchInternal(tool, arguments) }

    internal suspend fun dispatchInternal(tool: McpTool, arguments: JsonObject): CallToolResult {
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
            recordHistory(project, commandEntry)
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
            updateHistory(project, commandEntry, status, result, duration)
        } catch (e: Exception) {
            LOG.warn("Failed to update command history for ${commandEntry.toolName}", e)
        }
    }

    private suspend fun <T> withIdeModality(block: suspend () -> T): T {
        val application = ApplicationManager.getApplication() ?: return block()
        return withContext(ModalityState.any().asContextElement()) { block() }
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
