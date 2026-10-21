package com.videoeditor.core.media.export

import java.io.File
import javax.inject.Inject

/** Storage probe kept separate so exhaustion can be tested without filling a device. */
open class ExportStorageBudget @Inject constructor() {
    open fun availableBytes(directory: File): Long = directory.usableSpace
    fun requireCapacity(directory: File, estimatedBytes: Long) {
        check(availableBytes(directory) >= estimatedBytes + 16L * 1024 * 1024) {
            "Not enough storage to export. Free space and try again."
        }
    }
}
