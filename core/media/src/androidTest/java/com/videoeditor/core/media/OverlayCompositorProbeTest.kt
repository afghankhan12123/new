package com.videoeditor.core.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled diagnostics for the Media3 1.5.1 multi-input compositor, using
 * solid-color fixtures so every question has a visually unambiguous answer.
 *
 * Three questions are settled here, independently of [CompositionBuilder]:
 *
 *  1. Draw order: does a higher input id draw on top of a lower one, or does
 *     input 0 sit on top? Answered by compositing two full-canvas opaque colors
 *     and reading the surviving color, in both registration orders.
 *  2. Timestamp base: is the `presentationTimeUs` handed to
 *     [VideoCompositorSettings.getOverlaySettings] project time (0..duration) or
 *     an internal offset time? Answered by recording the observed range per
 *     input id across a real export.
 *  3. Placement: where does a scaled, anchored overlay actually land on the
 *     canvas? Answered by scanning the output for the overlay color.
 *
 * Every run writes its decoded frames and a text report into the app's external
 * files directory, which survives instrumentation teardown, so evidence is not
 * lost when the test package is uninstalled.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class OverlayCompositorProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var mediaDir: File
    private lateinit var evidenceDir: File

    @Before
    fun setUp() {
        mediaDir = File(retainedMediaTestDirectory(context), "overlay-test-media").apply { mkdirs() }
        listOf(
            "clip1_720p_6s.mp4", "clip3_1080p_4s.mp4",
            "probe_red_720p_3s.mp4", "probe_blue_720p_3s.mp4", "probe_green_720p_6s.mp4",
        ).forEach { name ->
            val target = File(mediaDir, name)
            if (!target.exists()) {
                context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
            }
        }
        // External files dir is writable without any permission and is not
        // removed with the instrumentation package, so frames survive teardown.
        evidenceDir = File(context.getExternalFilesDir(null), "overlay-evidence").apply { mkdirs() }
        report("evidence directory: ${evidenceDir.absolutePath}")
    }

    // -- diagnostics --

    /**
     * Question 1: draw order. Both inputs cover the full canvas at alpha 1, so
     * exactly one color survives. Run in both registration orders to confirm the
     * rule rather than infer it from a single case.
     */
    @Test
    fun probe_layer_draw_order_with_solid_colors() = runBlocking<Unit> {
        val forward = exportProbes("red-main_blue-overlay", "probe_red_720p_3s.mp4", "probe_blue_720p_3s.mp4")
        val reversed = exportProbes("blue-main_red-overlay", "probe_blue_720p_3s.mp4", "probe_red_720p_3s.mp4")

        // Full-canvas samples: the surviving layer's color.
        val forwardCenter = meanRgb(forward, 0.4f, 0.6f, 0.4f, 0.6f)
        val reversedCenter = meanRgb(reversed, 0.4f, 0.6f, 0.4f, 0.6f)
        val forwardWinner = classify(forwardCenter)
        val reversedWinner = classify(reversedCenter)

        val summary = buildString {
            append("DRAW ORDER PROBE\n")
            append("  sequences[0]=red, sequences[1]=blue -> surviving color = ")
            append("$forwardWinner rgb=$forwardCenter\n")
            append("  sequences[0]=blue, sequences[1]=red -> surviving color = ")
            append("$reversedWinner rgb=$reversedCenter\n")
            val consistent = forwardWinner == "BLUE" && reversedWinner == "RED"
            val inverted = forwardWinner == "RED" && reversedWinner == "BLUE"
            append(
                when {
                    consistent -> "  CONCLUSION: the LAST registered sequence (higher input id) draws ON TOP.\n" +
                        "  Input 0 is the background; inputs 1..n stack above it in ascending order.\n"
                    inverted -> "  CONCLUSION: the FIRST registered sequence (input 0) draws ON TOP.\n" +
                        "  Registration order must be reversed so the background is registered last.\n"
                    else -> "  CONCLUSION: INDETERMINATE — neither color cleanly won; inspect the frames.\n"
                },
            )
        }
        report(summary)
        assertTrue("red/blue probe export produced no decodable frame", forwardWinner != "BLACK")
        assertTrue("blue/red probe export produced no decodable frame", reversedWinner != "BLACK")
    }

    /**
     * Question 2: the timestamp base reaching [VideoCompositorSettings]. Records
     * every `presentationTimeUs` per input id for a padded full-duration overlay,
     * the exact shape [CompositionBuilder] produces for export.
     */
    @Test
    fun probe_presentation_timestamp_base() = runBlocking<Unit> {
        val recorder = TimestampRecorder()
        val mainItem = fullLengthItem("probe_red_720p_3s.mp4", 3_000_000L)
        // Overlay padded to the full project duration, mirroring the export path:
        // the item spans 0..3s and visibility is meant to be gated by alpha.
        val overlayItem = fullLengthItem("probe_blue_720p_3s.mp4", 3_000_000L)
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(mainItem)),
                EditedMediaItemSequence(listOf(overlayItem)),
            ),
        ).setVideoCompositorSettings(recorder).build()

        val output = export(composition, "timestamp-probe")
        assertNotNull("timestamp probe export failed", output)

        val summary = recorder.summarize()
        report("TIMESTAMP BASE PROBE\n$summary")

        // The decisive question for the alpha gate: are these project-time values
        // (0..3_000_000 us) or an internal offset base far above the duration?
        val maxSeen = recorder.maxTimestamp()
        val looksLikeProjectTime = maxSeen in 0..10_000_000L
        report(
            if (looksLikeProjectTime) {
                "  CONCLUSION: presentationTimeUs is PROJECT TIME (max ${maxSeen}us for a 3s clip).\n" +
                    "  A window gate comparing against timelineStart/EndMicros is valid.\n"
            } else {
                "  CONCLUSION: presentationTimeUs is NOT project time (max ${maxSeen}us for a 3s clip).\n" +
                    "  A gate comparing it against timelineStart/EndMicros never matches, so alpha\n" +
                    "  stays 0 and the overlay is invisible. Timestamps must be normalized first.\n"
            },
        )
        assertTrue("timestamp probe recorded no compositor callbacks", recorder.callbackCount() > 0)
        output?.delete()
    }

    /**
     * Question 3: placement. A blue overlay at 30% scale anchored to the right
     * quarter, over a red background. Scans the output for blue to report the
     * actual occupied cells, so the anchor and scale math can be checked against
     * the intended geometry instead of assumed.
     */
    @Test
    fun probe_scaled_overlay_placement() = runBlocking<Unit> {
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>) = Size(1280, 720)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 0) return OverlaySettings.Builder().build()
                return OverlaySettings.Builder()
                    .setAlphaScale(1f)
                    .setScale(0.3f, 0.3f)
                    // Model convention under test: normalized canvas offset of the
                    // layer center, positive Y down, mapped to Media3's anchor.
                    .setBackgroundFrameAnchor(0.5f, 0f)
                    .setOverlayFrameAnchor(0f, 0f)
                    .build()
            }
        }
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(fullLengthItem("probe_red_720p_3s.mp4", 3_000_000L))),
                EditedMediaItemSequence(listOf(fullLengthItem("probe_blue_720p_3s.mp4", 3_000_000L))),
            ),
        ).setVideoCompositorSettings(settings).build()

        val output = export(composition, "placement-probe")
        assertNotNull("placement probe export failed", output)
        val frame = decodeFrame(output!!, 1_500_000)
        saveFrame("placement-probe-frame", frame)

        // 16x9 occupancy scan: which cells contain the overlay color.
        val map = StringBuilder()
        var blueCells = 0
        for (row in 0 until 9) {
            for (col in 0 until 16) {
                val x0 = col / 16f
                val x1 = (col + 1) / 16f
                val y0 = row / 9f
                val y1 = (row + 1) / 9f
                val isBlue = classify(meanRgb(frame, x0, x1, y0, y1)) == "BLUE"
                if (isBlue) blueCells++
                map.append(if (isBlue) '#' else '.')
            }
            map.append('\n')
        }
        val columnsWithBlue = (0 until 16).filter { col ->
            (0 until 9).any { row ->
                classify(meanRgb(frame, col / 16f, (col + 1) / 16f, row / 9f, (row + 1) / 9f)) == "BLUE"
            }
        }
        val rowsWithBlue = (0 until 9).filter { row ->
            (0 until 16).any { col ->
                classify(meanRgb(frame, col / 16f, (col + 1) / 16f, row / 9f, (row + 1) / 9f)) == "BLUE"
            }
        }
        report(
            "PLACEMENT PROBE (scale 0.3, backgroundFrameAnchor x=0.5 y=0)\n" +
                "  occupancy map ('#' = overlay color):\n$map" +
                "  blue cells=$blueCells/144\n" +
                "  columns containing overlay=${columnsWithBlue}\n" +
                "  rows containing overlay=${rowsWithBlue}\n" +
                (if (blueCells == 0) "  CONCLUSION: the overlay is NOT visible anywhere on the canvas.\n"
                else "  CONCLUSION: the overlay occupies the cells marked above; compare with the\n" +
                    "  intended right-quarter placement (columns ~11-14, rows ~3-5).\n"),
        )
        assertTrue("placement probe frame was not decodable", frame.width > 0)
        output.delete()
    }

    /**
     * Disambiguation: the full-canvas draw-order probe cannot tell "input 0 draws
     * on top of input 1" apart from "input 1 is never composited at all", because
     * an opaque full-canvas input 0 hides input 1 either way.
     *
     * Here input 0 is scaled to a small center patch, so the canvas corners are
     * left uncovered. If input 1 (full-canvas blue) is truly composited beneath,
     * the corners show blue. If input 1 is dropped, the corners are black. This
     * separates the two root causes decisively.
     */
    @Test
    fun probe_secondary_input_is_actually_composited() = runBlocking<Unit> {
        // input 0 = red small center patch (scale 0.4, centered), input 1 = blue full canvas.
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>) = Size(1280, 720)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 0) {
                    return OverlaySettings.Builder().setAlphaScale(1f).setScale(0.4f, 0.4f).build()
                }
                return OverlaySettings.Builder().setAlphaScale(1f).build()
            }
        }
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(fullLengthItem("probe_red_720p_3s.mp4", 3_000_000L))),
                EditedMediaItemSequence(listOf(fullLengthItem("probe_blue_720p_3s.mp4", 3_000_000L))),
            ),
        ).setVideoCompositorSettings(settings).build()
        val output = export(composition, "composite-reveal")
        assertNotNull("composite-reveal export failed", output)
        val frame = decodeFrame(output!!, 1_500_000)
        saveFrame("composite-reveal-frame", frame)

        val center = classify(meanRgb(frame, 0.4f, 0.6f, 0.4f, 0.6f))
        val cornerTL = classify(meanRgb(frame, 0.0f, 0.12f, 0.0f, 0.12f))
        val cornerBR = classify(meanRgb(frame, 0.88f, 1.0f, 0.88f, 1.0f))
        val summary = buildString {
            append("SECONDARY-INPUT COMPOSITING PROBE (input0=red@0.4 center, input1=blue full)\n")
            append("  center=${center} topLeft=${cornerTL} bottomRight=${cornerBR}\n")
            append(
                when {
                    cornerTL == "BLUE" && cornerBR == "BLUE" ->
                        "  CONCLUSION: input 1 IS composited beneath input 0. Multi-input works and\n" +
                            "  input 0 draws ON TOP. Fix = register the background LAST (highest input id)\n" +
                            "  and overlays at LOWER input ids.\n"
                    cornerTL == "BLACK" && cornerBR == "BLACK" ->
                        "  CONCLUSION: input 1 is NOT composited at all (corners black). The second\n" +
                            "  sequence never reaches the compositor — a graph/registration bug, not a\n" +
                            "  draw-order issue. Reversing order alone will not fix it.\n"
                    else ->
                        "  CONCLUSION: INDETERMINATE — inspect frame-composite-reveal-frame.png.\n"
                },
            )
        }
        report(summary)
        assertTrue("composite-reveal frame not decodable", frame.width > 0)
        output.delete()
    }

    /**
     * Validates the FIX direction: with the overlay registered as input 0 (on top)
     * and the background as input 1 (below), a small right-quarter overlay must be
     * visible over the full-canvas background. This is the geometry the corrected
     * [CompositionBuilder] ordering must reproduce.
     */
    @Test
    fun probe_corrected_order_shows_overlay_over_background() = runBlocking<Unit> {
        // input 0 = blue overlay (small, right quarter), input 1 = red background (full).
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>) = Size(1280, 720)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 1) return OverlaySettings.Builder().setAlphaScale(1f).build()
                return OverlaySettings.Builder()
                    .setAlphaScale(1f)
                    .setScale(0.3f, 0.3f)
                    .setBackgroundFrameAnchor(0.5f, 0f)
                    .setOverlayFrameAnchor(0f, 0f)
                    .build()
            }
        }
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(fullLengthItem("probe_blue_720p_3s.mp4", 3_000_000L))),
                EditedMediaItemSequence(listOf(fullLengthItem("probe_red_720p_3s.mp4", 3_000_000L))),
            ),
        ).setVideoCompositorSettings(settings).build()
        val output = export(composition, "corrected-order")
        assertNotNull("corrected-order export failed", output)
        val frame = decodeFrame(output!!, 1_500_000)
        saveFrame("corrected-order-frame", frame)

        val map = occupancyMap(frame, "BLUE")
        val blueCols = (0 until 16).filter { col ->
            (0 until 9).any { row -> cellIs(frame, col, row, "BLUE") }
        }
        val blueRows = (0 until 9).filter { row ->
            (0 until 16).any { col -> cellIs(frame, col, row, "BLUE") }
        }
        val center = classify(meanRgb(frame, 0.4f, 0.6f, 0.4f, 0.6f))
        report(
            "CORRECTED-ORDER PROBE (input0=blue overlay right-quarter, input1=red background full)\n" +
                "  occupancy ('#' = blue overlay):\n$map" +
                "  center=${center} (expect RED background)\n" +
                "  overlay columns=$blueCols rows=$blueRows\n" +
                (if (blueCols.isEmpty()) "  CONCLUSION: overlay still not visible with corrected order — deeper bug.\n"
                else "  CONCLUSION: overlay IS visible over the background at columns=$blueCols.\n" +
                    "  Confirms fix direction: overlay at input 0, background at higher input id.\n"),
        )
        assertTrue("corrected-order frame not decodable", frame.width > 0)
        output.delete()
    }

    /**
     * Short-overlay behavior: a 6s green background with a 3s blue overlay that
     * is NOT padded. What does the compositor emit after the overlay input ends
     * at 3s — the background, a frozen last blue frame, black, or a stall — and
     * what is the output duration? This decides whether trailing padding is
     * required, or whether a plain short overlay sequence composites cleanly.
     *
     * Overlay is at input 0 (topmost, per the confirmed draw order), scaled to a
     * center patch so the background stays visible around it throughout.
     */
    @Test
    fun probe_short_overlay_tail_behavior() = runBlocking<Unit> {
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>) = Size(1280, 720)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 1) return OverlaySettings.Builder().setAlphaScale(1f).build()
                // Blue overlay as a center patch; visible whenever it has a frame.
                return OverlaySettings.Builder().setAlphaScale(1f).setScale(0.4f, 0.4f).build()
            }
        }
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(fullLengthItem("probe_blue_720p_3s.mp4", 3_000_000L))),
                EditedMediaItemSequence(listOf(fullLengthItem("probe_green_720p_6s.mp4", 6_000_000L))),
            ),
        ).setVideoCompositorSettings(settings).build()

        val output = export(composition, "short-overlay-tail")
        assertNotNull("short-overlay-tail export failed", output)

        val durationUs = durationOf(output!!)
        val at1s = classify(meanRgb(decodeFrame(output, 1_000_000), 0.4f, 0.6f, 0.4f, 0.6f))
        val at2s = classify(meanRgb(decodeFrame(output, 2_000_000), 0.4f, 0.6f, 0.4f, 0.6f))
        val at4s = classify(meanRgb(decodeFrame(output, 4_000_000), 0.4f, 0.6f, 0.4f, 0.6f))
        val at5s = classify(meanRgb(decodeFrame(output, 5_000_000), 0.4f, 0.6f, 0.4f, 0.6f))
        val corner4s = classify(meanRgb(decodeFrame(output, 4_000_000), 0.0f, 0.12f, 0.0f, 0.12f))
        saveFrame("short-overlay-tail-at4s", decodeFrame(output, 4_000_000))

        report(
            "SHORT-OVERLAY TAIL PROBE (input0=blue 3s center, input1=green 6s full)\n" +
                "  output duration=${durationUs}us (background is 6_000_000us)\n" +
                "  center@1s=$at1s center@2s=$at2s (overlay active: expect BLUE)\n" +
                "  center@4s=$at4s center@5s=$at5s (overlay ended: GREEN=background resumed,\n" +
                "    BLUE=frozen last frame, BLACK=stall/drop)\n" +
                "  corner@4s=$corner4s (background should be GREEN throughout)\n" +
                (
                    when {
                        at4s == "GREEN" && durationUs in 5_500_000..6_500_000 ->
                            "  CONCLUSION: a short overlay input composites cleanly; after it ends the\n" +
                                "  background shows and output duration follows the longest input. Trailing\n" +
                                "  padding is NOT required for export.\n"
                        at4s == "BLUE" ->
                            "  CONCLUSION: the overlay's LAST frame FREEZES after it ends. Trailing padding\n" +
                                "  or an alpha gate is required to hide it outside its window.\n"
                        durationUs < 3_500_000 ->
                            "  CONCLUSION: output duration TRUNCATED to the shortest input ($durationUs us).\n" +
                                "  The composition must span the full project some other way.\n"
                        else ->
                            "  CONCLUSION: UNEXPECTED tail behavior — inspect frame-short-overlay-tail-at4s.png.\n"
                    }
                ),
        )
        assertTrue("short-overlay-tail frame not decodable", durationUs > 0)
        output.delete()
    }

    /**
     * Which sequence drives the output duration? The short-overlay probe above
     * produced a 3s output from a 6s background + 3s overlay, but it cannot tell
     * whether duration follows `sequences[0]`, the shortest input, or the
     * longest. Here the 6s green background is `sequences[0]` and the 3s blue
     * overlay is `sequences[1]`. A 6s result means duration follows the first
     * sequence; a 3s result means it follows the shortest input.
     */
    @Test
    fun probe_output_duration_ownership() = runBlocking<Unit> {
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>) = Size(1280, 720)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 0) return OverlaySettings.Builder().setAlphaScale(1f).build()
                return OverlaySettings.Builder().setAlphaScale(1f).setScale(0.4f, 0.4f).build()
            }
        }
        // sequences[0] = green 6s background (drawn on top), sequences[1] = blue 3s.
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(fullLengthItem("probe_green_720p_6s.mp4", 6_000_000L))),
                EditedMediaItemSequence(listOf(fullLengthItem("probe_blue_720p_3s.mp4", 3_000_000L))),
            ),
        ).setVideoCompositorSettings(settings).build()
        val output = export(composition, "duration-ownership")
        assertNotNull("duration-ownership export failed", output)
        val durationUs = durationOf(output!!)
        report(
            "OUTPUT DURATION OWNERSHIP PROBE (sequences[0]=green 6s, sequences[1]=blue 3s)\n" +
                "  output duration=${durationUs}us\n" +
                (
                    when {
                        durationUs in 5_500_000..6_500_000 ->
                            "  CONCLUSION: duration follows sequences[0] (or the longest input). The first\n" +
                                "  sequence must span the full project for a full-length export.\n"
                        durationUs in 2_500_000..3_500_000 ->
                            "  CONCLUSION: duration follows the SHORTEST input. Every visual sequence must\n" +
                                "  span the full project duration, so overlays need valid full-length padding.\n"
                        else -> "  CONCLUSION: UNEXPECTED duration ${durationUs}us.\n"
                    }
                ),
        )
        output.delete()
    }

    /**
     * Multi-item timestamp base: the export fix pads an overlay sequence with
     * several concatenated video items (leading pad + window + trailing pad). The
     * alpha window gate compares `presentationTimeUs` against the overlay's
     * project-time window, so it must be CUMULATIVE across the sequence's items,
     * not reset per item. This records the timestamps for a two-item overlay
     * sequence: a 1s item then a 3s item (4s total) at input 0.
     *
     * Cumulative project time -> input 0 spans 0..4_000_000us continuously.
     * Per-item reset -> the second item restarts near 0, so the max stays ~3s and
     * the sequence would show a discontinuity.
     */
    @Test
    fun probe_multi_item_timestamp_is_cumulative() = runBlocking<Unit> {
        val recorder = TimestampRecorder()
        val leadingPad = clippedItem("probe_blue_720p_3s.mp4", 0L, 1_000_000L, 1_000_000L)
        val window = clippedItem("probe_blue_720p_3s.mp4", 0L, 3_000_000L, 3_000_000L)
        val background = fullLengthItem("probe_green_720p_6s.mp4", 6_000_000L)
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(leadingPad, window)),
                EditedMediaItemSequence(listOf(background)),
            ),
        ).setVideoCompositorSettings(recorder).build()
        val output = export(composition, "multi-item-timestamp")
        assertNotNull("multi-item-timestamp export failed", output)

        val input0 = recorder.timestampsFor(0)
        val maxT = input0.maxOrNull() ?: -1L
        val monotonic = input0.zipWithNext().all { (a, b) -> b >= a }
        report(
            "MULTI-ITEM TIMESTAMP PROBE (input0 = [1s pad, 3s window], input1 = 6s background)\n" +
                "  input0 callbacks=${input0.size} min=${input0.minOrNull()}us max=${maxT}us\n" +
                "  monotonic non-decreasing=$monotonic\n" +
                "  first=${input0.take(4)} around-1s=${input0.filter { it in 900_000..1_100_000 }} last=${input0.takeLast(4)}\n" +
                (
                    when {
                        maxT in 3_800_000..4_200_000 && monotonic ->
                            "  CONCLUSION: timestamps are CUMULATIVE project time across items (0..~4s).\n" +
                                "  The alpha window gate can compare directly against timelineStart/EndMicros.\n"
                        maxT in 2_800_000..3_200_000 ->
                            "  CONCLUSION: timestamps RESET per item (max ~3s = the second item alone).\n" +
                                "  The gate would need the sequence offset added; padding by concatenation\n" +
                                "  is not directly gate-able.\n"
                        else -> "  CONCLUSION: UNEXPECTED timestamp range (max ${maxT}us).\n"
                    }
                ),
        )
        output?.delete()
    }

    /**
     * Short SECONDARY under a long PRIMARY: input 0 (primary, duration driver) is
     * a 6s green center patch; input 1 is a 3s blue full-canvas secondary. The
     * patch leaves the corners uncovered so the secondary shows through. After
     * the secondary ends at 3s, do the corners freeze blue (harmless — an alpha
     * gate would hide it), go black/drop, or stall the export? This decides
     * whether non-primary overlays need full-duration padding, or only the
     * primary (input 0) does.
     */
    @Test
    fun probe_short_secondary_under_long_primary() = runBlocking<Unit> {
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>) = Size(1280, 720)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                // input 0 = green small center patch (primary), input 1 = blue full.
                if (inputId == 0) return OverlaySettings.Builder().setAlphaScale(1f).setScale(0.4f, 0.4f).build()
                return OverlaySettings.Builder().setAlphaScale(1f).build()
            }
        }
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(fullLengthItem("probe_green_720p_6s.mp4", 6_000_000L))),
                EditedMediaItemSequence(listOf(fullLengthItem("probe_blue_720p_3s.mp4", 3_000_000L))),
            ),
        ).setVideoCompositorSettings(settings).build()
        val output = export(composition, "short-secondary")
        assertNotNull("short-secondary export failed", output)

        val durationUs = durationOf(output!!)
        // Corners reveal the secondary (input 1); center is the green primary patch.
        val corner1s = classify(meanRgb(decodeFrame(output, 1_000_000), 0.0f, 0.12f, 0.0f, 0.12f))
        val corner4s = classify(meanRgb(decodeFrame(output, 4_000_000), 0.0f, 0.12f, 0.0f, 0.12f))
        val corner5s = classify(meanRgb(decodeFrame(output, 5_000_000), 0.0f, 0.12f, 0.0f, 0.12f))
        saveFrame("short-secondary-at5s", decodeFrame(output, 5_000_000))
        report(
            "SHORT-SECONDARY PROBE (input0=green 6s center patch [primary], input1=blue 3s full)\n" +
                "  output duration=${durationUs}us (primary is 6_000_000us)\n" +
                "  corner@1s=$corner1s (secondary active: expect BLUE)\n" +
                "  corner@4s=$corner4s corner@5s=$corner5s (after secondary ends:\n" +
                "    BLUE=froze last frame [harmless under an alpha gate], BLACK=dropped/stall)\n" +
                (
                    when {
                        durationUs in 5_500_000..6_500_000 && corner4s == "BLUE" && corner5s == "BLUE" ->
                            "  CONCLUSION: a short secondary does NOT stall or truncate; output follows the\n" +
                                "  6s primary and the secondary's last frame FREEZES. With the alpha gate zeroing\n" +
                                "  it outside the window, non-primary overlays need NO full-duration padding.\n"
                        durationUs in 5_500_000..6_500_000 && corner4s == "BLACK" ->
                            "  CONCLUSION: a short secondary DROPS after it ends (corners black) but the\n" +
                                "  primary still drives full duration. Alpha gate suffices; no padding needed.\n"
                        durationUs < 3_500_000 ->
                            "  CONCLUSION: export TRUNCATED to the short secondary ($durationUs us) — every\n" +
                                "  visual sequence must span the full project duration.\n"
                        else ->
                            "  CONCLUSION: UNEXPECTED (duration=${durationUs}us corner4s=$corner4s corner5s=$corner5s).\n"
                    }
                ),
        )
        output.delete()
    }

    // -- helpers --

    /** A video item clipping [startUs, endUs) of [asset] with explicit duration. */
    private fun clippedItem(asset: String, startUs: Long, endUs: Long, durationUs: Long): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(fileUri(asset)).setMimeType("video/mp4")
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(startUs).setEndPositionUs(endUs).build(),
                ).build(),
        ).setDurationUs(durationUs).setRemoveAudio(true).build()

    private fun durationOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1000) ?: -1L
        } finally {
            retriever.release()
        }
    }

    /** True if the given 16x9 grid cell is predominantly [target] color. */
    private fun cellIs(frame: Bitmap, col: Int, row: Int, target: String): Boolean =
        classify(meanRgb(frame, col / 16f, (col + 1) / 16f, row / 9f, (row + 1) / 9f)) == target

    /** 16x9 occupancy map marking cells that are predominantly [target] color. */
    private fun occupancyMap(frame: Bitmap, target: String): String = buildString {
        for (row in 0 until 9) {
            for (col in 0 until 16) append(if (cellIs(frame, col, row, target)) '#' else '.')
            append('\n')
        }
    }

    /** Exports red-then-blue and blue-then-red full-canvas probes; returns both frames. */
    private suspend fun exportProbes(tag: String, mainAsset: String, overlayAsset: String): Bitmap {
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(fullLengthItem(mainAsset, 3_000_000L))),
                EditedMediaItemSequence(listOf(fullLengthItem(overlayAsset, 3_000_000L))),
            ),
        ).setVideoCompositorSettings(
            object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: List<Size>) = Size(1280, 720)
                override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long) =
                    OverlaySettings.Builder().setAlphaScale(1f).build()
            },
        ).build()
        val output = export(composition, tag)
        assertNotNull("$tag export failed", output)
        val frame = decodeFrame(output!!, 1_500_000)
        saveFrame("$tag-frame", frame)
        output.delete()
        return frame
    }

    private fun fullLengthItem(asset: String, durationUs: Long): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(fileUri(asset)).setMimeType("video/mp4").build(),
        ).setDurationUs(durationUs).setRemoveAudio(true).build()

    private fun fileUri(name: String) = android.net.Uri.fromFile(File(mediaDir, name))

    private suspend fun export(composition: Composition, tag: String): File? {
        val output = File(retainedMediaTestDirectory(context), "probe-$tag-${System.currentTimeMillis()}.mp4")
        val completed = CompletableDeferred<Boolean>()
        runOnMain {
            Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        completed.complete(true)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        report("export error ($tag): ${e.message}")
                        android.util.Log.e("OverlayProbe", "export error ($tag)", e)
                        completed.complete(false)
                    }
                })
                .build()
                .start(composition, output.absolutePath)
        }
        return if (completed.await()) output.also { report("export ok ($tag): ${it.length()} bytes") } else null
    }

    private fun decodeFrame(file: File, atUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally {
            retriever.release()
        }
    }

    /** Mean R/G/B over a normalized sub-rectangle. */
    private fun meanRgb(src: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): IntArray {
        val w = src.width
        val h = src.height
        val sx = (w * x0).toInt().coerceIn(0, w - 1)
        val sy = (h * y0).toInt().coerceIn(0, h - 1)
        val ex = (w * x1).toInt().coerceIn(sx + 1, w)
        val ey = (h * y1).toInt().coerceIn(sy + 1, h)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in sy until ey step 3) {
            for (x in sx until ex step 3) {
                val p = src.getPixel(x, y)
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0L) return intArrayOf(0, 0, 0)
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun classify(rgb: IntArray): String {
        val (r, g, b) = Triple(rgb[0], rgb[1], rgb[2])
        return when {
            r + g + b < 60 -> "BLACK"
            r > b + 30 && r > g + 30 -> "RED"
            b > r + 30 && b > g + 30 -> "BLUE"
            else -> "MIXED(r=$r,g=$g,b=$b)"
        }
    }

    private fun saveFrame(tag: String, bitmap: Bitmap) {
        val file = File(evidenceDir, "frame-$tag.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        report("saved frame: ${file.absolutePath}")
    }

    private fun report(message: String) {
        android.util.Log.i("OverlayProbe", message)
        runCatching {
            File(evidenceDir, "probe-report.txt").appendText(message + "\n")
        }
        println(message)
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    /**
     * Records the timestamps the compositor asks about, per input id. Kept
     * thread-safe because the compositor calls from its GL/render thread.
     */
    private class TimestampRecorder : VideoCompositorSettings {
        private val perInput = HashMap<Int, MutableList<Long>>()
        private val samples = Collections.synchronizedList(mutableListOf<String>())

        override fun getOutputSize(inputSizes: List<Size>): Size {
            record("getOutputSize inputSizes=$inputSizes")
            return Size(1280, 720)
        }

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            synchronized(perInput) {
                perInput.getOrPut(inputId) { mutableListOf() }.add(presentationTimeUs)
            }
            if (samples.size < 40) {
                record("getOverlaySettings input=$inputId t=${presentationTimeUs}us")
            }
            return OverlaySettings.Builder().setAlphaScale(1f).build()
        }

        private fun record(line: String) {
            if (samples.size < 200) samples.add(line)
            android.util.Log.i("OverlayProbe", line)
        }

        fun callbackCount(): Int = synchronized(perInput) { perInput.values.sumOf { it.size } }

        fun maxTimestamp(): Long = synchronized(perInput) {
            perInput.values.flatten().maxOrNull() ?: Long.MIN_VALUE
        }

        fun timestampsFor(inputId: Int): List<Long> =
            synchronized(perInput) { perInput[inputId]?.toList() ?: emptyList() }

        fun summarize(): String = buildString {
            synchronized(perInput) {
                perInput.toSortedMap().forEach { (inputId, times) ->
                    append("  input $inputId: callbacks=${times.size} ")
                    append("min=${times.min()}us max=${times.max()}us ")
                    append("first=${times.take(3)} last=${times.takeLast(3)}\n")
                }
            }
            append("  first samples:\n")
            samples.take(20).forEach { append("    $it\n") }
        }
    }
}
