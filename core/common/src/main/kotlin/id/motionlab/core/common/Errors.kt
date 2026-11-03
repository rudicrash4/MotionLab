package id.motionlab.core.common

import java.util.concurrent.CancellationException

enum class ErrorCode {
    UNKNOWN,
    INVALID_ARGUMENT,
    NOT_FOUND,
    IO,
    UNSUPPORTED,
    CANCELLED,
    NATIVE,
    GPU,
    DECODE,
    ENCODE,
    CORRUPT_DATA,
    INITIALIZATION,
}

/** The single exception type that crosses module boundaries. */
class MlException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String = "MlException(code=$code, message=$message)"
}

sealed interface MlResult<out T> {
    data class Success<out T>(val value: T) : MlResult<T>
    data class Failure(val error: MlException) : MlResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error
    }

    fun <R> map(transform: (T) -> R): MlResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }
}

/**
 * Runs [block] and converts any [Exception] into an [MlResult.Failure].
 * [MlException]s keep their own code; cancellations become [ErrorCode.CANCELLED].
 */
inline fun <T> mlRunCatching(defaultCode: ErrorCode = ErrorCode.UNKNOWN, block: () -> T): MlResult<T> =
    try {
        MlResult.Success(block())
    } catch (e: MlException) {
        MlResult.Failure(e)
    } catch (e: CancellationException) {
        MlResult.Failure(MlException(ErrorCode.CANCELLED, e.message ?: "cancelled", e))
    } catch (e: Exception) {
        MlResult.Failure(MlException(defaultCode, e.message ?: e.javaClass.simpleName, e))
    }
