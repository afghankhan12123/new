package com.videoeditor.editing

import com.videoeditor.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectCreatorTest {
    private fun source(rotation: Int = 0) = MediaRef("v", "file:///v.mp4", MediaType.Video, "Portrait", durationMicros = 1_000_000, width = 720, height = 1280, rotationDegrees = rotation)
    @Test fun originalCanvasUsesSourceDimensionsAndRotation() {
        val creator = ProjectCreator()
        assertEquals(CanvasSettings(width = 720, height = 1280), creator.create("Portrait", listOf(source())).canvas)
        assertEquals(CanvasSettings(width = 1280, height = 720), creator.create("Rotated", listOf(source(90))).canvas)
    }
    @Test fun explicitCanvasRemainsAuthoritative() {
        val canvas = CanvasSettings(aspectRatio = AspectRatioPreset.Square1x1, width = 1080, height = 1080)
        assertEquals(canvas, ProjectCreator().create("Square", listOf(source()), canvas = canvas).canvas)
    }
}
