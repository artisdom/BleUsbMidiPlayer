package com.example.bleusbmidiplayer.midi

import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class TrackReference(
    val id: String,
    val title: String,
    val source: TrackReferenceSource,
)

@Serializable
sealed interface TrackReferenceSource {
    @Serializable
    @SerialName("asset")
    data class Asset(val assetPath: String) : TrackReferenceSource

    @Serializable
    @SerialName("document")
    data class Document(val uri: String) : TrackReferenceSource
}

@Serializable
data class MidiPlaylist(
    val id: String,
    val name: String,
    val tracks: List<TrackReference> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

fun MidiFileItem.toTrackReference(): TrackReference {
    val sourceRef = when (val src = source) {
        is MidiFileSource.Asset -> TrackReferenceSource.Asset(assetPath = src.assetPath)
        is MidiFileSource.Document -> TrackReferenceSource.Document(uri = src.uri.toString())
    }
    return TrackReference(
        id = id,
        title = title,
        source = sourceRef,
    )
}

fun TrackReference.toMidiFileItem(): MidiFileItem {
    val sourceItem = when (val src = source) {
        is TrackReferenceSource.Asset -> MidiFileSource.Asset(src.assetPath)
        is TrackReferenceSource.Document -> MidiFileSource.Document(uri = Uri.parse(src.uri))
    }
    return MidiFileItem(
        id = id,
        title = title,
        source = sourceItem,
    )
}
