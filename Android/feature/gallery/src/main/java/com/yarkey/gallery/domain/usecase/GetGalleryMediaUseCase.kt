package com.yarkey.gallery.domain.usecase

import com.yarkey.gallery.domain.model.GalleryMedia
import com.yarkey.gallery.domain.repository.GalleryRepository

class GetGalleryMediaUseCase(
    private val repository: GalleryRepository,
) {
    suspend operator fun invoke(): List<GalleryMedia> = repository.getMedia()
}
