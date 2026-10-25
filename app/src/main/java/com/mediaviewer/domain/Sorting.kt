package com.mediaviewer.domain

import com.mediaviewer.domain.model.FileEntry

enum class SortBy { NAME, DATE, SIZE, TYPE }

data class SortSpec(
    val by: SortBy = SortBy.DATE,
    val descending: Boolean = true,
    val foldersFirst: Boolean = true,
)

/** A folder's own order, picked in its top bar (README §2, "Folder view"); `foldersFirst` stays global. */
data class FolderSort(val by: SortBy, val descending: Boolean)

object Sorting {
    /**
     * The order a folder is shown in: its own choice if it has one; a folder without images (audio,
     * documents, lectures, subfolders) by name, A–Z; otherwise the sort from settings.
     */
    fun effective(global: SortSpec, own: FolderSort?, hasImages: Boolean): SortSpec = when {
        own != null -> global.copy(by = own.by, descending = own.descending)
        !hasImages -> global.copy(by = SortBy.NAME, descending = false)
        else -> global
    }

    /**
     * Folder view order (README §4): folders, then media, then other files, each group sorted by [spec].
     * With `foldersFirst = false`, folders are mixed with the media group.
     */
    fun comparator(spec: SortSpec): Comparator<FileEntry> {
        val byName = Comparator<FileEntry> { a, b -> NaturalOrder.compare(a.name, b.name) }
        val key: Comparator<FileEntry> = when (spec.by) {
            SortBy.NAME -> byName
            SortBy.DATE -> compareBy<FileEntry> { it.mtime }.then(byName)
            SortBy.SIZE -> compareBy<FileEntry> { it.size }.then(byName)
            SortBy.TYPE -> compareBy<FileEntry> { extension(it.name) }.then(byName)
        }
        val directed = if (spec.descending) key.reversed() else key
        val group = compareBy<FileEntry> { if (spec.foldersFirst) it.group else minOf(it.group, 1) }
        return group.then(directed)
    }

    private fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot < 0) "" else name.substring(dot + 1).lowercase()
    }
}
