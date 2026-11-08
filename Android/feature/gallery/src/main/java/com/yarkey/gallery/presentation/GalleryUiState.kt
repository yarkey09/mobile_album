package com.yarkey.gallery.presentation

import com.yarkey.clip.ClipModel
import com.yarkey.gallery.domain.model.GalleryMedia

enum class GalleryAccessMode {
    UNKNOWN,
    NONE,
    LIMITED,
    FULL,
}

data class GalleryUiState(
    val accessMode: GalleryAccessMode = GalleryAccessMode.UNKNOWN,
    val isLoading: Boolean = false,
    val media: List<GalleryMedia> = emptyList(),
    val selectedMedia: GalleryMedia? = null,
    val error: GalleryError? = null,
    val searchQuery: String = "",
    val searchResults: List<GalleryMedia> = emptyList(),
    val searchScores: Map<Long, Float> = emptyMap(),
    val isSearchActive: Boolean = false,
    val isSearching: Boolean = false,
    val availableModels: List<ClipModel> = emptyList(),
    val selectedModelId: String = "",
    val clipModelState: ClipModelState = ClipModelState.IDLE,
    val indexedImageCount: Int = 0,
    val indexTargetCount: Int = 0,
    val cacheHitCount: Int = 0,
)

enum class GalleryError {
    LOAD_FAILED,
}

enum class ClipModelState {
    IDLE,
    PREPARING,
    INDEXING,
    READY,
    FAILED,
}
