package com.example.bleusbmidiplayer.midi

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MidiRepository(
    private val context: Context,
    private val parser: MidiFileParser = MidiFileParser(),
) {
    fun listBundledMidi(): List<MidiFileItem> {
        val assetRoot = BUNDLED_MIDI_FOLDER
        val assetManager = context.assets
        val entries = assetManager.list(assetRoot).orEmpty()
        return entries
            .filter { it.endsWith(".mid", true) || it.endsWith(".midi", true) }
            .sorted()
            .map { name ->
                val path = "$assetRoot/$name"
                MidiFileItem(
                    id = "asset-$path",
                    title = name.substringBeforeLast('.'),
                    source = MidiFileSource.Asset(assetPath = path),
                )
            }
    }

    suspend fun listExternalMidi(treeUri: Uri?): List<MidiFileItem> = withContext(Dispatchers.IO) {
        if (treeUri == null) return@withContext emptyList()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val items = mutableListOf<MidiFileItem>()
        fun visit(node: DocumentFile) {
            if (!node.canRead()) return
            if (node.isDirectory) {
                node.listFiles().forEach { visit(it) }
                return
            }
            if (node.isMidiFile()) {
                val title = node.name?.substringBeforeLast('.') ?: "MIDI"
                items += MidiFileItem(
                    id = node.uri.toString(),
                    title = title,
                    source = MidiFileSource.Document(uri = node.uri, treeUri = treeUri),
                )
            }
        }
        visit(root)
        return@withContext items.sortedBy { it.title }
    }

    suspend fun loadSequence(item: MidiFileItem): MidiSequence? = withContext(Dispatchers.IO) {
        when (val source = item.source) {
            is MidiFileSource.Asset -> context.assets.open(source.assetPath).use(parser::parse)
            is MidiFileSource.Document -> context.contentResolver.openInputStream(source.uri)?.use(parser::parse)
        }
    }

    private fun DocumentFile.isMidiFile(): Boolean {
        val fileName = name.orEmpty()
        val lower = fileName.lowercase()
        if (lower.endsWith(".mid") || lower.endsWith(".midi") || lower.endsWith(".kar")) {
            return true
        }
        val mime = type.orEmpty()
        return mime.contains("midi", ignoreCase = true)
    }

    companion object {
        private const val BUNDLED_MIDI_FOLDER = "midi"
    }
}
