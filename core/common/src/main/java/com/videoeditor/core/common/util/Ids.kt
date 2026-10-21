package com.videoeditor.core.common.util

import java.util.UUID

/** Generates stable, collision-resistant identifiers for projects and clips. */
object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
