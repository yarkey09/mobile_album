package com.yarkey.gallery.domain.model

data class GalleryMedia(
    val id: Long,
    val uri: String,
    val displayName: String,
    val dateAddedMillis: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val mimeType: String,
    val type: GalleryMediaType,
)

enum class GalleryMediaType {
    IMAGE,
    VIDEO,
}
