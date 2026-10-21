package com.videoeditor.feature.editor.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timeline
import com.videoeditor.editing.timeline.TimelineHit
import com.videoeditor.editing.timeline.TimelineSelection
import com.videoeditor.editing.timeline.TimelineLayout
import com.videoeditor.feature.editor.EditorUiState
import com.videoeditor.feature.editor.TimelineAssets
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pointer-level verification of the timeline's gesture parser — the layer the
 * ViewModel-driven device tests cannot reach, because they call gesture methods
 * directly rather than synthesizing real touch events.
 *
 * The parser must disambiguate the things a single finger does on this
 * timeline: a quick tap selects, a prompt drag scrubs, a held press on a clip
 * reorders, and two fingers zoom. Callbacks are recorded into a list, so this
 * tests pointer parsing in isolation from the ViewModel and media engine.
 */
@RunWith(AndroidJUnit4::class)
class TimelineGestureUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Recorded callback names, in order, for assertion. */
    private val calls = CopyOnWriteArrayList<String>()
    private var forceRecomposition: () -> Unit = {}

    /** 15s project: 6s + 5s + 4s, matching the device-test fixtures. */
    private val project = Project(
        id = "p",
        name = "Gesture test",
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        mediaRefs = listOf(
            MediaRef("r1", "file:///1.mp4", MediaType.Video, "one.mp4"),
            MediaRef("r2", "file:///2.mp4", MediaType.Video, "two.mp4"),
            MediaRef("r3", "file:///3.mp4", MediaType.Video, "three.mp4"),
        ),
        timeline = Timeline(
            videoClips = listOf(
                Clip("c1", "r1", 0L, 6_000_000L, timelineStartMicros = 0L),
                Clip("c2", "r2", 0L, 5_000_000L, timelineStartMicros = 6_000_000L),
                Clip("c3", "r3", 0L, 4_000_000L, timelineStartMicros = 11_000_000L),
            ),
        ),
    )

    @Before
    fun setUp() {
        calls.clear()
    }

    /**
     * A wide zoom (whole 45s span fits the viewport, as the app initializes)
     * and scroll 0: time zero sits under the centered playhead. Clip c1
     * occupies x=0.5..0.633 of the measured canvas width.
     */
    private fun testState(): EditorUiState {
        // Initial seed; the measured Canvas size replaces it on first layout.
        val widthPx = 1080f
        val zoom = widthPx / 45_000_000f
        return EditorUiState(
            project = project,
            isLoading = false,
            viewportWidthPx = widthPx,
            playheadXpx = widthPx / 2f,
            zoomPixelsPerMicro = zoom,
            scrollXPx = 0f,
        )
    }

    private fun callbacks(onSize: (Float, Float) -> Unit) = TimelineCallbacks(
        onViewportSize = onSize,
        onTap = { hit -> calls += "tap:${hitName(hit)}" },
        onScrubBegin = { calls += "scrubBegin"; true },
        onScrubBy = { calls += "scrubBy" },
        onScrollEnd = { calls += "scrubEnd" },
        onZoomBegin = { calls += "zoomBegin"; true },
        onZoomBy = { _, _ -> calls += "zoomBy" },
        onZoomEnd = { calls += "zoomEnd" },
        onTrimBegin = { _, edge, isAudio -> calls += "trimBegin:$edge:$isAudio"; true },
        onTrimBy = { _, _ -> calls += "trimBy" },
        onTrimEnd = { commit, _ -> calls += "trimEnd:$commit" },
        onReorderBegin = { calls += "reorderBegin"; true },
        onReorderTo = { calls += "reorderTo" },
        onReorderEnd = { commit -> calls += "reorderEnd:$commit" },
        onFreeMoveBegin = { _, id -> calls += "freeMoveBegin:$id"; true },
        onFreeMoveBy = { calls += "freeMoveBy" },
        onFreeMoveEnd = { commit -> calls += "freeMoveEnd:$commit" },
    )

    private fun hitName(hit: TimelineHit): String = when (hit) {
        is TimelineHit.VideoClip -> "video:${hit.clipId}:handle=${hit.handle}"
        is TimelineHit.AudioClip -> "audio:${hit.audioClipId}"
        is TimelineHit.TextClip -> "text:${hit.overlayId}"
        TimelineHit.Ruler -> "ruler"
        TimelineHit.Empty -> "empty"
    }

    private fun setContent(selected: Boolean = false) {
        composeRule.setContent {
            // Stable across recompositions, exactly as EditorScreen holds it.
            var state by remember { mutableStateOf(testState().copy(selection = if (selected) TimelineSelection.Video("c1") else TimelineSelection.None)) }
            forceRecomposition = { state = state.copy(userMessage = "recompose during touch") }
            val cbs = remember { callbacks { width, playhead ->
                state = state.copy(viewportWidthPx = width, playheadXpx = playhead,
                    zoomPixelsPerMicro = width / 45_000_000f)
            } }
            Box(modifier = Modifier.fillMaxSize()) {
                TimelineView(
                    state = state,
                    assets = TimelineAssets(),
                    onRefreshAssets = { _, _ -> },
                    callbacks = cbs,
                    modifier = Modifier,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** y of a layout row's center, as a fraction of the canvas height. */
    private fun rowCenterFraction(row: Int): Float =
        (TimelineLayout.rowTop(row) + TimelineLayout.rowHeight(row) / 2f) /
            TimelineLayout.TOTAL_HEIGHT

    private val videoRowY get() = rowCenterFraction(TimelineLayout.ROW_VIDEO)
    private val rulerY get() = rowCenterFraction(TimelineLayout.ROW_RULER)

    // -- tap selects --

    @Test
    fun quickTapOnVideoClipSelectsItWithoutDragging() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            // t=3s → screen x = 540 + 3s*24px/s = 612 ≈ 0.567 of width.
            click(position = Offset(width * 0.567f, height * videoRowY))
        }
        composeRule.waitForIdle()

        assertTrue("expected a tap callback, got $calls",
            calls.contains("tap:video:c1:handle=null"))
        assertTrue("a tap must not begin a drag gesture, got $calls",
            calls.none { it.startsWith("reorder") || it.startsWith("scrub") || it.startsWith("trim") })
    }

    @Test
    fun quickTapOnEmptyAreaClearsSelection() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            // t=25s → x = 540 + 600 = 1140 > 1080: off the right edge is
            // beyond the project; use the text row at t=3s instead, which
            // holds no text overlays → Empty.
            click(position = Offset(width * 0.567f, height * rowCenterFraction(TimelineLayout.ROW_TEXT)))
        }
        composeRule.waitForIdle()
        assertTrue("expected an empty-area tap, got $calls", calls.contains("tap:empty"))
    }

    // -- drag scrubs --

    @Test
    fun promptDragOnClipScrubsInsteadOfReordering() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            swipe(
                start = Offset(width * 0.6f, height * videoRowY),
                end = Offset(width * 0.3f, height * videoRowY),
            )
        }
        composeRule.waitForIdle()

        assertTrue("expected scrub to begin, got $calls", calls.contains("scrubBegin"))
        assertTrue("expected scrub motion, got $calls", calls.contains("scrubBy"))
        assertTrue("expected scrub to end, got $calls", calls.contains("scrubEnd"))
        assertTrue("a prompt drag must NOT start a reorder, got $calls",
            calls.none { it.startsWith("reorder") })
    }

    @Test
    fun dragOnRulerScrubsWithoutLongPressWindow() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            swipe(
                start = Offset(width * 0.6f, height * rulerY),
                end = Offset(width * 0.35f, height * rulerY),
            )
        }
        composeRule.waitForIdle()
        assertTrue("ruler drag must scrub, got $calls", calls.contains("scrubBegin"))
        assertTrue("ruler drag must end, got $calls", calls.contains("scrubEnd"))
        assertTrue("ruler drag must not reorder, got $calls",
            calls.none { it.startsWith("reorder") })
    }

    // -- long-press reorders --

    @Test
    fun longPressOnVideoClipBeginsReorderNotScrub() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            holdStillThenRelease(Offset(width * 0.567f, height * videoRowY))
        }
        composeRule.waitForIdle()

        assertTrue("a held press on a clip must begin a reorder, got $calls",
            calls.contains("reorderBegin"))
        assertTrue("a held press must not scrub, got $calls",
            calls.none { it == "scrubBegin" })
        assertTrue("the reorder must commit on release, got $calls",
            calls.contains("reorderEnd:true"))
    }

    @Test
    fun longPressThenDragReordersToNewSlot() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            val start = Offset(width * 0.567f, height * videoRowY) // inside c1
            down(start)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            // Substantial move after the hold: the drag portion of reorder.
            moveTo(Offset(width * 0.75f, height * videoRowY))
            up()
        }
        composeRule.waitForIdle()

        assertTrue("reorder must begin after the hold, got $calls",
            calls.contains("reorderBegin"))
        assertTrue("reorder must report slot updates, got $calls",
            calls.contains("reorderTo"))
        assertTrue("reorder must commit on release, got $calls",
            calls.contains("reorderEnd:true"))
        assertTrue("a long-press drag must not scrub, got $calls",
            calls.none { it == "scrubBegin" })
    }

    @Test
    fun longPressOnEmptyAreaDoesNothingDestructive() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            holdStillThenRelease(
                Offset(width * 0.567f, height * rowCenterFraction(TimelineLayout.ROW_TEXT)),
            )
        }
        composeRule.waitForIdle()
        assertTrue("holding empty space must not start any gesture, got $calls",
            calls.none {
                it.startsWith("reorder") || it.startsWith("scrub") ||
                    it.startsWith("trim") || it.startsWith("freeMove") || it.startsWith("zoom")
            })
    }

    // -- pinch zooms --

    @Test
    fun twoFingerPinchZoomsWithoutScrubbing() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            pinch(
                start0 = Offset(width * 0.5f - 50f, height * videoRowY),
                end0 = Offset(width * 0.5f - 150f, height * videoRowY),
                start1 = Offset(width * 0.5f + 50f, height * videoRowY),
                end1 = Offset(width * 0.5f + 150f, height * videoRowY),
            )
        }
        composeRule.waitForIdle()

        assertTrue("a two-finger pinch must begin a zoom, got $calls",
            calls.contains("zoomBegin"))
        assertTrue("pinch motion must scale the zoom, got $calls",
            calls.contains("zoomBy"))
        assertTrue("zoom must end: $calls", calls.contains("zoomEnd"))
        assertTrue("a pinch must not scrub, got $calls",
            calls.none { it == "scrubBegin" })
        assertTrue("a pinch must not reorder, got $calls",
            calls.none { it.startsWith("reorder") })
    }

    @Test
    fun longPressWithSubSlopJitterStillReorders() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            val at = Offset(width * 0.567f, height * videoRowY)
            down(at)
            repeat(6) { index ->
                moveTo(at + Offset(if (index % 2 == 0) 1f else 0f, 0f),
                    delayMillis = viewConfiguration.longPressTimeoutMillis / 5)
            }
            up()
        }
        assertTrue("jitter must not reset the down deadline: $calls", calls.contains("reorderBegin"))
        assertTrue(calls.contains("reorderEnd:true"))
        assertTrue(calls.none { it.startsWith("scrub") })
    }

    @Test
    fun selectedStartHandleDragTrimsAndCommits() {
        assertHandleDrag(0.5f, "Start")
    }

    @Test
    fun selectedEndHandleDragTrimsAndCommits() {
        assertHandleDrag(0.6333f, "End")
    }

    private fun assertHandleDrag(x: Float, edge: String) {
        setContent(selected = true)
        composeRule.onNodeWithTag("timeline").performTouchInput {
            down(Offset(width * x, height * videoRowY))
            moveTo(Offset(width * (x + 0.05f), height * videoRowY))
            up()
        }
        assertTrue("correct handle must begin: $calls", calls.contains("trimBegin:$edge:false"))
        assertTrue(calls.contains("trimBy"))
        assertTrue(calls.contains("trimEnd:true"))
        assertTrue(calls.none { it.startsWith("scrub") || it.startsWith("reorder") })
    }

    @Test
    fun cancelTrimDoesNotCommitAndNextTapWorks() {
        setContent(selected = true)
        composeRule.onNodeWithTag("timeline").performTouchInput {
            down(Offset(width * 0.5f, height * videoRowY))
            moveTo(Offset(width * 0.55f, height * videoRowY))
            cancel()
        }
        assertTrue("cancel must end trim without commit: $calls", calls.contains("trimEnd:false"))
        assertTrue(calls.none { it == "trimEnd:true" })
        composeRule.onNodeWithTag("timeline").performTouchInput {
            click(Offset(width * 0.70f, height * videoRowY))
        }
        assertTrue("parser must accept next gesture: $calls", calls.contains("tap:video:c2:handle=null"))
    }

    @Test
    fun secondFingerCancelsTrimAndHandsOffToZoom() {
        setContent(selected = true)
        composeRule.onNodeWithTag("timeline").performTouchInput {
            down(0, Offset(width * 0.5f, height * videoRowY))
            moveTo(0, Offset(width * 0.52f, height * videoRowY))
            down(1, Offset(width * 0.75f, height * videoRowY))
            moveTo(1, Offset(width * 0.8f, height * videoRowY))
            moveTo(1, Offset(width * 0.85f, height * videoRowY))
            up(1)
            up(0)
        }
        assertTrue(calls.contains("trimEnd:false"))
        assertTrue(calls.none { it == "trimEnd:true" })
        assertTrue(calls.containsAll(listOf("zoomBegin", "zoomBy", "zoomEnd")))
    }

    @Test
    fun cancellationBeforeDecisionDoesNotSelect() {
        setContent()
        composeRule.onNodeWithTag("timeline").performTouchInput {
            down(Offset(width * 0.567f, height * videoRowY))
            cancel()
        }
        assertTrue("cancel must not select or edit: $calls", calls.isEmpty())
    }

    @Test
    fun recompositionDuringTrimPreservesGestureAndCommitsOnce() {
        setContent(selected = true)
        composeRule.onNodeWithTag("timeline").performTouchInput {
            down(Offset(width * 0.5f, height * videoRowY))
            moveTo(Offset(width * 0.55f, height * videoRowY))
        }
        composeRule.runOnIdle { forceRecomposition() }
        composeRule.onNodeWithTag("timeline").performTouchInput { up() }
        assertTrue(calls.count { it == "trimBegin:Start:false" } == 1)
        assertTrue("recomposition must preserve the active pointer stream: $calls",
            calls.count { it == "trimEnd:true" } == 1)
        assertTrue(calls.none { it == "trimEnd:false" })
    }

    @Test
    fun releaseOutsideClipStillEndsTrim() {
        setContent(selected = true)
        composeRule.onNodeWithTag("timeline").performTouchInput {
            down(Offset(width * 0.5f, height * videoRowY))
            moveTo(Offset(width * 0.6f, height + 50f))
            up()
        }
        assertTrue("outside release must end the drag: $calls", calls.contains("trimEnd:true"))
    }

    /** Down, hold past the long-press deadline without moving, then up. */
    private fun TouchInjectionScope.holdStillThenRelease(at: Offset) {
        down(at)
        advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
        up()
    }
}
