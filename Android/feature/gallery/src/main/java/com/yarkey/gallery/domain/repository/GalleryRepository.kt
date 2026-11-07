package com.yarkey.gallery.domain.repository

import com.yarkey.gallery.domain.model.GalleryMedia

interface GalleryRepository {
    suspend fun getMedia(): List<GalleryMedia>
}
