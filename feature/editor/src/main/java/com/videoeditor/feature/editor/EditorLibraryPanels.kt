package com.videoeditor.feature.editor

import android.graphics.Bitmap
import com.videoeditor.editing.timeline.videoClipId
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** Existing import/extract/record entry points, presented inside the editor workspace. */
@Composable
internal fun EditorAudioPanel(state: EditorUiState, onClose: () -> Unit,
    onImport: () -> Unit, onExtract: () -> Unit, onVoiceover: () -> Unit, onReuse: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    EditorToolPanel("Add Audio", onClose, null, onClose, cancelLabel = "Close", applyLabel = "Done") {
        EditorSearchField(query, { query = it }, "Search project music")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EditorChoice("Music", true, onImport, Modifier.weight(1f), activeFill = true)
            EditorChoice("Extract", false, onExtract, Modifier.weight(1f), state.project?.timeline?.videoClips?.any { it.id == state.selection.videoClipId && !it.isPhoto } == true)
            EditorChoice("Voiceover", false, onVoiceover, Modifier.weight(1f))
        }
        Text("PROJECT LIBRARY", color = EditorColor.DarkMutedText, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp))
        val audio = state.project?.mediaRefs.orEmpty().filter { it.type == com.videoeditor.core.model.MediaType.Audio }
        audio.filter { it.displayName.contains(query, ignoreCase = true) }.forEach { media ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(EditorColor.DarkSecondarySurface, RoundedCornerShape(8.dp))
                .border(1.dp, EditorColor.DarkBorder, RoundedCornerShape(8.dp)).heightIn(min = 56.dp)
                .clickable { onReuse(media.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(EditorColor.PrimaryBlue.copy(alpha = .18f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    EditorIcon(Icons.Outlined.MusicNote, null, tint = EditorColor.AccentCyan, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(media.displayName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                    Text(formatEditorTimecode(media.durationMicros ?: 0), color = EditorColor.DarkSecondaryText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
                }
                EditorIcon(Icons.Outlined.Add, "Add ${media.displayName}", tint = EditorColor.AccentCyan)
            }
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(Modifier.testTag("audio-import")), shape = RoundedCornerShape(8.dp)) {
            EditorIcon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Import music or sound")
        }
        if (audio.isEmpty()) Text("Choose an audio file from your device to add it to the timeline.",
            color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The functional sticker library. Every entry comes from the bundled
 * [StickerCatalog], so the thumbnail the user taps is the exact artwork the
 * preview renderer and exporter decode — one durable offline source.
 */
@Composable
internal fun EditorStickerPanel(onSelect: (String) -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("editor_asset_recents", android.content.Context.MODE_PRIVATE) }
    val catalog = com.videoeditor.core.media.stickers.StickerCatalog
    val recent = remember { preferences.getString("stickers", "").orEmpty().split('|').filter { catalog.asset(it) != null } }
    var category by remember { mutableStateOf(catalog.categories.first()) }
    var query by remember { mutableStateOf("") }
    EditorToolPanel("Stickers", onClose, null, onClose, applyLabel = "Done", cancelLabel = "Close", applyEnabled = true) {
        EditorSearchField(query, { query = it }, "Search stickers")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf("Recent") + catalog.categories).forEach { label ->
                EditorChoice(label, category == label, { category = label }, Modifier.widthIn(min = 64.dp))
            }
        }
        val assets = (if (query.isNotBlank()) catalog.categories.flatMap { catalog.assetsIn(it) }.distinctBy { it.key }
            else if (category == "Recent") recent.mapNotNull(catalog::asset) else catalog.assetsIn(category))
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        if (assets.isEmpty()) {
            Text("No stickers match your search.", color = EditorColor.DarkSecondaryText,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        assets.chunked(4).forEach { row ->
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { asset ->
                    Column(Modifier.weight(1f)
                        .heightIn(min = 48.dp)
                        .background(EditorColor.DarkSecondarySurface, RoundedCornerShape(8.dp))
                        .border(1.dp, EditorColor.DarkBorder.copy(alpha = .65f), RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val keys = (listOf(asset.key) + recent).distinct().take(16)
                            preferences.edit().putString("stickers", keys.joinToString("|")).apply()
                            onSelect(asset.key)
                        }
                        .testTag("sticker-${asset.key}"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(68.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            coil.compose.AsyncImage(model = asset.drawableRes,
                                contentDescription = "${asset.label} sticker design",
                                modifier = Modifier.size(48.dp), contentScale = ContentScale.Fit)
                        }
                        Text(asset.label, color = EditorColor.DarkSecondaryText, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("Tap a sticker to place it at the playhead.", color = EditorColor.DarkSecondaryText,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
    }
}
