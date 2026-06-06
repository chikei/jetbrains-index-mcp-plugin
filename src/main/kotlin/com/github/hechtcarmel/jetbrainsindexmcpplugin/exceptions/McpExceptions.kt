package com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions

object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Custom error codes for MCP tools
    const val INDEX_NOT_READY = -32001
    const val FILE_NOT_FOUND = -32002
    const val SYMBOL_NOT_FOUND = -32003
    const val REFACTORING_CONFLICT = -32004
}

sealed class McpException(
    message: String,
    val errorCode: Int
) : Exception(message)

class ParseErrorException(message: String) :
    McpException(message, JsonRpcErrorCodes.PARSE_ERROR)

class InvalidRequestException(message: String) :
    McpException(message, JsonRpcErrorCodes.INVALID_REQUEST)

class MethodNotFoundException(method: String) :
    McpException("Method not found: $method", JsonRpcErrorCodes.METHOD_NOT_FOUND)

class InvalidParamsException(message: String) :
    McpException(message, JsonRpcErrorCodes.INVALID_PARAMS)

class InternalErrorException(message: String) :
    McpException(message, JsonRpcErrorCodes.INTERNAL_ERROR)

class IndexNotReadyException(message: String) :
    McpException(message, JsonRpcErrorCodes.INDEX_NOT_READY)

class FileNotFoundException(path: String) :
    McpException("File not found: $path", JsonRpcErrorCodes.FILE_NOT_FOUND)

class SymbolNotFoundException(message: String) :
    McpException(message, JsonRpcErrorCodes.SYMBOL_NOT_FOUND)

class RefactoringConflictException(message: String) :
    McpException(message, JsonRpcErrorCodes.REFACTORING_CONFLICT)
