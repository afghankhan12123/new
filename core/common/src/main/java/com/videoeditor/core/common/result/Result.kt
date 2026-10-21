package com.videoeditor.core.common.result

/**
 * A lightweight result type for operations that can fail with a typed error.
 * Kept dependency-free so it can be used across all layers including the media
 * engine.
 */
sealed interface Result<out T, out E> {
    data class Success<T>(val value: T) : Result<T, Nothing>
    data class Failure<E>(val error: E) : Result<Nothing, E>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}

fun <T> Result<T, *>.getOrNull(): T? = (this as? Result.Success)?.value

fun <E> Result<*, E>.errorOrNull(): E? = (this as? Result.Failure)?.error

inline fun <T, E, R> Result<T, E>.map(transform: (T) -> R): Result<R, E> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Failure -> this
}

inline fun <T, E, R> Result<T, E>.flatMap(transform: (T) -> Result<R, E>): Result<R, E> =
    when (this) {
        is Result.Success -> transform(value)
        is Result.Failure -> this
    }

inline fun <T, E> Result<T, E>.onSuccess(action: (T) -> Unit): Result<T, E> {
    if (this is Result.Success) action(value)
    return this
}

inline fun <T, E> Result<T, E>.onFailure(action: (E) -> Unit): Result<T, E> {
    if (this is Result.Failure) action(error)
    return this
}

fun <T, E> Result<T, E>.getOrElse(default: T): T = when (this) {
    is Result.Success -> value
    is Result.Failure -> default
}

/**
 * Runs [block], capturing any thrown [Throwable] into a [Result.Failure].
 * Named distinctly from Kotlin's stdlib `runCatching` (which returns `kotlin.Result`)
 * to avoid ambiguity at call sites.
 */
inline fun <T> runCatchingResult(block: () -> T): Result<T, Throwable> = try {
    Result.Success(block())
} catch (t: Throwable) {
    Result.Failure(t)
}
