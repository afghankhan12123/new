package com.videoeditor.core.media.preview

/** Progress means a newly submitted picture, independent of the optimistic player clock. */
internal class PlaybackOutputWatchdog(private val timeoutMs: Long = 4_000L) {
    private var owner: Any? = null
    private var lastOutputUs = Long.MIN_VALUE
    private var lastProgressMs = 0L

    fun reset() { owner = null }

    fun stalled(activeOwner: Any, outputUs: Long, nowMs: Long): Boolean {
        if (owner !== activeOwner || outputUs != lastOutputUs) {
            owner = activeOwner
            lastOutputUs = outputUs
            lastProgressMs = nowMs
        }
        return nowMs - lastProgressMs >= timeoutMs
    }
}
