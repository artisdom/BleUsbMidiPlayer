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

    suspend fun listFolderChildren(folderUri: Uri, treeUri: Uri): FolderListing =
        withContext(Dispatchers.IO) {
            val folder = resolveDocument(folderUri)
                ?: throw IllegalArgumentException("Cannot access folder $folderUri")
            val directories = mutableListOf<MidiFolderItem>()
            val files = mutableListOf<MidiFileItem>()
            folder.listFiles().forEach { entry ->
                if (!entry.canRead()) return@forEach
                if (entry.isDirectory) {
                    directories += MidiFolderItem(
                        uri = entry.uri,
                        name = entry.name ?: "Folder",
                    )
                } else if (entry.isMidiFile()) {
                    val title = entry.name?.substringBeforeLast('.') ?: "MIDI"
                    files += MidiFileItem(
                        id = entry.uri.toString(),
                        title = title,
                        source = MidiFileSource.Document(uri = entry.uri, treeUri = treeUri),
                    )
                }
            }
            FolderListing(
                directories = directories.sortedBy { it.name.lowercase() },
                midiFiles = files.sortedBy { it.title.lowercase() },
            )
        }

    suspend fun loadSequence(item: MidiFileItem): MidiSequence? = withContext(Dispatchers.IO) {
        when (val source = item.source) {
            is MidiFileSource.Asset -> context.assets.open(source.assetPath).use(parser::parse)
            is MidiFileSource.Document -> context.contentResolver.openInputStream(source.uri)?.use(parser::parse)
        }
    }

    fun resolveFolderName(uri: Uri): String? {
        return resolveDocument(uri)?.name
    }

    private fun resolveDocument(uri: Uri): DocumentFile? {
        return DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
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
