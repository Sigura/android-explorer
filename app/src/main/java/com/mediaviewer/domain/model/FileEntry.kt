package com.mediaviewer.domain.model

import androidx.compose.runtime.Immutable

enum class MediaType { FOLDER, IMAGE, ANIMATED, VIDEO, PARTIAL, OTHER }

/** Sub-kind for [MediaType.OTHER], picks the tile icon (README §3). */
enum class OtherKind {
    TEXT,
    /** Source code: monospace text preview (README §3.10). */
    CODE,
    /** Word processor documents (doc, docx, odt, rtf). */
    DOCUMENT,
    PDF,
    AUDIO,
    ARCHIVE,
    APK,
    UNKNOWN,
    ;

    /** Tiles show the first lines of the file (README §3.10). */
    val hasTextPreview: Boolean get() = this == TEXT || this == CODE
}

@Immutable
data class FileEntry(
    val path: String,
    val name: String,
    val type: MediaType,
    val size: Long,
    val mtime: Long,
    val other: OtherKind = OtherKind.UNKNOWN,
) {
    val isMedia: Boolean get() = type == MediaType.IMAGE || type == MediaType.ANIMATED || type == MediaType.VIDEO
    val isFolder: Boolean get() = type == MediaType.FOLDER

    /** Sort group from README §4 "Item order": folders, media, other files. */
    val group: Int
        get() = when {
            isFolder -> 0
            isMedia -> 1
            else -> 2
        }
}
