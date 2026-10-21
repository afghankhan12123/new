package com.videoeditor.feature.home

import com.videoeditor.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ProjectCoverTest {
    private val media = MediaRef("source", "file:///actual.mp4", MediaType.Video, "Actual", durationMicros = 10_000_000)
    private val clip = Clip("clip",media.id,sourceStartMicros = 2_000_000,sourceEndMicros = 6_000_000)
    private val project = Project("project","Cover",0,0,mediaRefs = listOf(media),timeline = Timeline(videoClips = listOf(clip)))

    @Test fun empty_or_missing_source_uses_fallback() {
        assertNull(projectCover(project.copy(timeline = Timeline())))
        assertNull(projectCover(project.copy(mediaRefs = emptyList())))
    }
    @Test fun cover_uses_actual_trimmed_source_and_refreshes_on_change() {
        val cover = projectCover(project)!!
        assertEquals(media.uri,cover.uri)
        assertEquals(3_000_000L,cover.sourceMicros)
        assertTrue(cover.isVideo)
        assertNotEquals(cover.key,projectCover(project.copy(updatedAtMillis = 1))!!.key)
        assertNotEquals(cover.key,projectCover(project.copy(timeline = Timeline(videoClips = listOf(clip.copy(sourceStartMicros = 3_000_000)))))!!.key)
    }
    @Test fun photo_cover_uses_image_decoder() {
        assertFalse(projectCover(project.copy(mediaRefs = listOf(media.copy(type = MediaType.Photo))))!!.isVideo)
    }
}
