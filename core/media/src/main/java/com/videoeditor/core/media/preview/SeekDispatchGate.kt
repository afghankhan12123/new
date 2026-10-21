package com.videoeditor.core.media.preview

/** Give an unfinished decode a small work budget while retaining only the newest request.
 * Frame delivery admits the next request immediately; lift bypasses the interactive budget.
 * This controls reset frequency, never the timeline clock or frame-success reporting.
 */
internal class SeekDispatchGate(private val workBudgetMs: Long = 80L) {
    private var dispatchedMs: Long? = null
    fun dispatched(nowMs: Long) { dispatchedMs = nowMs }
    fun canSupersede(nowMs: Long, settling: Boolean): Boolean = settling ||
        dispatchedMs?.let { nowMs - it >= workBudgetMs } != false
    fun reset() { dispatchedMs = null }
}
