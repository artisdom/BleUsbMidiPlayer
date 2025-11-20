package com.example.bleusbmidiplayer.midi

import android.net.Uri

/**
 * Represents a playable MIDI file, either bundled inside assets or provided by the user via SAF.
 */
data class MidiFileItem(
    val id: String,
    val title: String,
    val source: MidiFileSource,
    val durationMs: Long? = null,
) {
    val subtitle: String
        get() = when (source) {
            is MidiFileSource.Asset -> "Bundled • ${source.assetPath.substringAfterLast('/')}"
            is MidiFileSource.Document -> source.uri.lastPathSegment ?: "User file"
        }
}

sealed interface MidiFileSource {
    data class Asset(val assetPath: String) : MidiFileSource
    data class Document(
        val uri: Uri,
        val treeUri: Uri? = null,
    ) : MidiFileSource
}

data class MidiEvent(
    val timestampMs: Long,
    val data: ByteArray,
)

data class MidiSequence(
    val events: List<MidiEvent>,
    val durationMs: Long,
)

data class MidiFolderItem(
    val uri: Uri,
    val name: String,
)

data class FolderListing(
    val directories: List<MidiFolderItem>,
    val midiFiles: List<MidiFileItem>,
)
