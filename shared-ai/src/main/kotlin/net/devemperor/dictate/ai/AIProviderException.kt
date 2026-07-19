package net.devemperor.dictate.ai

/**
 * Unified exception for all AI provider errors.
 * Maps typed SDK exceptions (OpenAI + Anthropic) to a common ErrorType enum.
 * NO string parsing – uses instanceof on the SDK exception hierarchies:
 *   OpenAI:    com.openai.errors.{UnauthorizedException, RateLimitException, NotFoundException, ...}
 *   Anthropic: com.anthropic.errors.{UnauthorizedException, RateLimitException, ...}
 */
class AIProviderException(
    val errorType: ErrorType,
    message: String,
    cause: Throwable? = null,
    val modelName: String? = null,   // For MODEL_NOT_FOUND: which model is missing
    val provider: AIProvider? = null  // Which provider caused the error
) : RuntimeException(message, cause) {

    enum class ErrorType {
        INVALID_API_KEY,       // UnauthorizedException (401)
        RATE_LIMITED,          // RateLimitException (429) – incl. quota
        MODEL_NOT_FOUND,       // NotFoundException (404) – model no longer exists
        BAD_REQUEST,           // BadRequestException (400) – invalid parameters
        SERVER_ERROR,          // InternalServerException (5xx)
        NETWORK_ERROR,         // IO error, connectivity
        CANCELLED,             // User cancelled
        UNKNOWN
    }

    /**
     * Produces the string key carried through
     * `PipelineCallback.onPipelineError(errorInfoKey, …)`.
     *
     * The IME-side consumer parses it back into the typed
     * [net.devemperor.dictate.state.PipelineErrorKind] via
     * `PipelineErrorKind.fromInfoKey` (ADR-0006 completion,
     * 2026-07-02). Note `"cancelled"` deliberately parses to `null`
     * there — user-initiated cancellation never surfaces an error bar
     * (F-076); today `PipelineOrchestrator.isCancellation` already
     * short-circuits before `onPipelineError`, so the mapping below is
     * a defensive belt for future runners that report CANCELLED.
     */
    fun toInfoKey(): String = when (errorType) {
        ErrorType.INVALID_API_KEY -> "invalid_api_key"
        ErrorType.RATE_LIMITED -> "quota_exceeded"
        ErrorType.MODEL_NOT_FOUND -> "model_not_found"
        ErrorType.BAD_REQUEST -> "bad_request"
        ErrorType.SERVER_ERROR -> "internet_error"
        ErrorType.NETWORK_ERROR -> "internet_error"
        ErrorType.CANCELLED -> "cancelled"
        ErrorType.UNKNOWN -> "internet_error"
    }
}
