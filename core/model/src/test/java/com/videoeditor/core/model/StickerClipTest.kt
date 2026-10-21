package com.videoeditor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class StickerClipTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun stickerClipSerializesRoundTrip() {
        val sticker = StickerClip(
            id = "s1", assetKey = "heart",
            timelineStartMicros = 1_250_000L, timelineDurationMicros = 2_500_000L,
            positionX = 0.25f, positionY = 0.75f, scale = 1.5f,
            rotationDegrees = 45f, opacity = 0.8f, zIndex = 2,
        )
        val project = Project(id = "p", name = "t", createdAtMillis = 0, updatedAtMillis = 0,
            timeline = Timeline(stickers = listOf(sticker)))
        val decoded = json.decodeFromString<Project>(json.encodeToString(Project.serializer(), project))
        assertEquals(listOf(sticker), decoded.timeline.stickers)
        assertEquals(3_750_000L, decoded.timeline.stickers[0].timelineEndMicros)
    }

    @Test
    fun legacyProjectDocumentWithoutStickersLoadsWithEmptyList() {
        // A version-1 document predating stickers has no "stickers" key at all.
        val legacy = """{"id":"p","name":"t","createdAtMillis":0,"updatedAtMillis":0,"timeline":{"videoClips":[]}}"""
        val decoded = json.decodeFromString<Project>(legacy)
        assertTrue(decoded.timeline.stickers.isEmpty())
    }

    @Test
    fun unknownFutureStickerFieldsAreIgnored() {
        val future = """{"id":"p","name":"t","createdAtMillis":0,"updatedAtMillis":0,"timeline":{"stickers":[{"id":"s1","assetKey":"star","futureField":42}]}}"""
        val decoded = json.decodeFromString<Project>(future)
        assertEquals("star", decoded.timeline.stickers[0].assetKey)
        assertEquals(0.5f, decoded.timeline.stickers[0].positionX)
    }

    @Test
    fun exportQualityPreservesSerializedCompatibility() {
        // Existing documents carry "Standard" and "High"; both must still parse.
        val doc = """{"id":"p","name":"t","createdAtMillis":0,"updatedAtMillis":0,"exportPreferences":{"quality":"High"}}"""
        val decoded = json.decodeFromString<Project>(doc)
        assertEquals(ExportQuality.High, decoded.exportPreferences.quality)
    }
}
