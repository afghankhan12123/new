package com.videoeditor.feature.editor

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.videoeditor.core.designsystem.component.GradientButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.videoeditor.feature.editor.EditorColor as VEColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/** Recording is foreground-only. A stopped take can be reviewed before importing it. */
@Composable
internal fun VoiceoverDialog(onSave: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val file = remember { File(File(context.filesDir, "voiceovers").apply { mkdirs() }, "voiceover-${System.currentTimeMillis()}.m4a") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var recording by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf(false) }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(recording) {
        if (recording) {
            val started = android.os.SystemClock.elapsedRealtime()
            while (recording) {
                elapsedMillis = android.os.SystemClock.elapsedRealtime() - started
                level = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)
                kotlinx.coroutines.delay(100)
            }
        }
    }
    var message by remember { mutableStateOf("Record your voiceover at the playhead.") }
    fun stop() {
        val active = recorder ?: return
        try { active.stop(); ready = file.length() > 0L; message = "Take ready. Play it back or record again." }
        catch (_: RuntimeException) { ready = false; file.delete(); message = "Recording was too short. Try again." }
        finally { active.release(); recorder = null; recording = false }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { stop(); runCatching { player?.pause() } } }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            recorder?.let { active -> runCatching { active.stop() }; active.release() }
            player?.release()
            if (!kept) file.delete()
        }
    }
    Dialog(onDismissRequest = { stop(); onDismiss() }) {
        Surface(color = VEColor.DarkPrimarySurface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .8f).dp)
            .verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EditorIcon(Icons.Default.Mic, null, tint = VEColor.PrimaryBlue, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("Record Voiceover", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(if (recording) "Recording… ${elapsedMillis / 1000}s" else message, color = Color.White)
            if (recording) androidx.compose.material3.LinearProgressIndicator(progress = { level.coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth(), color = VEColor.PrimaryBlue)
            TextButton(onClick = {
                if (recording) stop() else {
                    player?.release(); player = null
                    ready = false
                    val next = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                    try {
                        next.setAudioSource(MediaRecorder.AudioSource.MIC)
                        next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        next.setAudioSamplingRate(48000)
                        next.setAudioEncodingBitRate(192000)
                        next.setOutputFile(file.absolutePath)
                        next.prepare(); next.start(); recorder = next; recording = true
                    } catch (error: Exception) { next.release(); message = error.message ?: "Microphone unavailable" }
                }
            }) { Text(if (recording) "Stop" else if (ready) "Record again" else "Record") }
            TextButton(enabled = ready && !recording, onClick = {
                player?.release(); player = null
                val next = MediaPlayer()
                try { next.setDataSource(file.absolutePath); next.prepare(); next.start(); player = next }
                catch (error: Exception) { next.release(); message = error.message ?: "Playback failed" }
            }) { Text("Play take") }
            GradientButton("Save Voiceover", enabled = ready && !recording, onClick = { kept = true; onSave(Uri.fromFile(file)); onDismiss() })
            TextButton(onClick = { stop(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
        }
    }
}
