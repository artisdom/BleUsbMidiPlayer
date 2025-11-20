package com.example.bleusbmidiplayer.midi

import android.net.Uri

data class FolderNodeState(
    val uri: Uri,
    val name: String,
)

data class FolderTreeChildren(
    val directories: List<FolderNodeState> = emptyList(),
    val files: List<MidiFileItem> = emptyList(),
)

data class FolderTreeUiState(
    val selectedFolder: Uri? = null,
    val root: FolderNodeState? = null,
    val childMap: Map<Uri, FolderTreeChildren> = emptyMap(),
    val expanded: Set<Uri> = emptySet(),
    val loading: Set<Uri> = emptySet(),
    val errors: Map<Uri, String> = emptyMap(),
    val globalError: String? = null,
) {
    fun flatten(): List<FolderTreeRow> {
        val rootNode = root ?: return emptyList()
        val rows = mutableListOf<FolderTreeRow>()
        fun append(node: FolderNodeState, depth: Int) {
            val uri = node.uri
            val isExpanded = expanded.contains(uri)
            rows += FolderTreeRow.DirectoryRow(
                directory = node,
                depth = depth,
                isExpanded = isExpanded,
                isLoading = loading.contains(uri),
                error = errors[uri],
                isEmpty = childMap[uri]?.let { it.directories.isEmpty() && it.files.isEmpty() } == true
            )
            if (isExpanded) {
                val children = childMap[uri]
                if (children != null) {
                    children.directories.forEach { append(it, depth + 1) }
                    children.files.forEach { file ->
                        rows += FolderTreeRow.FileRow(
                            file = file,
                            depth = depth + 1,
                        )
                    }
                    if (children.directories.isEmpty() && children.files.isEmpty()) {
                        rows += FolderTreeRow.PlaceholderRow(
                            depth = depth + 1,
                            message = "Empty folder"
                        )
                    }
                }
            }
        }
        append(rootNode, 0)
        return rows
    }
}

sealed interface FolderTreeRow {
    val depth: Int

    data class DirectoryRow(
        val directory: FolderNodeState,
        override val depth: Int,
        val isExpanded: Boolean,
        val isLoading: Boolean,
        val error: String?,
        val isEmpty: Boolean,
    ) : FolderTreeRow

    data class FileRow(
        val file: MidiFileItem,
        override val depth: Int,
    ) : FolderTreeRow

    data class PlaceholderRow(
        override val depth: Int,
        val message: String,
    ) : FolderTreeRow
}
