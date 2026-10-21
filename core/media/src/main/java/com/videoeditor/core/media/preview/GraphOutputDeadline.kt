package com.videoeditor.core.media.preview

/** Main-thread generation ownership for a bounded preview startup recovery. */
internal class GraphOutputDeadline(
    val owner: Any,
    val targetUs: Long,
    val retry: Int,
    private val startedMs: Long,
    private val timeoutMs: Long = 4_000,
    val toleranceUs: Long = DEFAULT_FRAME_TOLERANCE_US,
) {
    companion object {
        const val DEFAULT_FRAME_TOLERANCE_US = 34_000L
    }

    enum class Result { Waiting, Delivered, Retry, Failed, Retired }

    fun observe(activeOwner: Any?, submittedUs: Long, nowMs: Long): Result = when {
        activeOwner !== owner -> Result.Retired
        // Player positions, READY and first-frame callbacks cannot prove that
        // this generation submitted the requested decoded composition frame.
        submittedUs != androidx.media3.common.C.TIME_UNSET &&
            submittedUs >= (targetUs - toleranceUs).coerceAtLeast(0L) -> Result.Delivered
        nowMs - startedMs < timeoutMs -> Result.Waiting
        retry == 0 -> Result.Retry
        else -> Result.Failed
    }
}
