package com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions

object JsonRpcErrorCodes {
    const val INDEX_NOT_READY = -32001
}

sealed class McpException(
    message: String,
    val errorCode: Int
) : Exception(message)

class IndexNotReadyException(message: String) :
    McpException(message, JsonRpcErrorCodes.INDEX_NOT_READY)
