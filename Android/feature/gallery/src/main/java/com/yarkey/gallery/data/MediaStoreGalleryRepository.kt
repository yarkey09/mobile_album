package com.yarkey.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.yarkey.gallery.domain.model.GalleryMedia
import com.yarkey.gallery.domain.model.GalleryMediaType
import com.yarkey.gallery.domain.repository.GalleryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreGalleryRepository(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GalleryRepository {

    override suspend fun getMedia(): List<GalleryMedia> = withContext(ioDispatcher) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        buildList {
            contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val durationColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Video.VideoColumns.DURATION
                )
                val mediaTypeColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Files.FileColumns.MEDIA_TYPE
                )

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val type = when (cursor.getInt(mediaTypeColumn)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> GalleryMediaType.VIDEO
                        else -> GalleryMediaType.IMAGE
                    }
                    val contentUri = when (type) {
                        GalleryMediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        GalleryMediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                    add(
                        GalleryMedia(
                            id = id,
                            uri = ContentUris.withAppendedId(contentUri, id).toString(),
                            displayName = cursor.getString(nameColumn).orEmpty(),
                            dateAddedMillis = cursor.getLong(dateColumn) * 1_000L,
                            width = cursor.getInt(widthColumn),
                            height = cursor.getInt(heightColumn),
                            durationMillis = cursor.getLong(durationColumn),
                            mimeType = cursor.getString(mimeTypeColumn).orEmpty(),
                            type = type,
                        )
                    )
                }
            }
        }
    }
}
