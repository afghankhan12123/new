package com.videoeditor.core.media

import android.content.Context
import android.os.SystemClock
import java.io.File

// A fresh instrumentation process owns a distinct directory. Active decoder
// inputs and completed exports are evidence, not disposable cache entries.
private val retainedMediaRun = "run-${SystemClock.elapsedRealtimeNanos()}"

internal fun retainedMediaTestDirectory(context: Context): File =
    File(context.filesDir, "retained-media-tests/$retainedMediaRun").apply {
        check(isDirectory || mkdirs() || isDirectory) { "Cannot retain test media in $this" }
    }
