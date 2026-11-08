package com.yarkey.gallery.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yarkey.clip.ClipImage
import com.yarkey.clip.ClipIndexResult
import com.yarkey.clip.ClipSearchEngine
import com.yarkey.clip.ClipSearchResult
import com.yarkey.gallery.domain.model.GalleryMedia
import com.yarkey.gallery.domain.model.GalleryMediaType
import com.yarkey.gallery.domain.usecase.GetGalleryMediaUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 统一管理相册媒体加载、CLIP 图片索引、文本搜索和媒体预览状态。
 *
 * Gallery 领域对象只保留在本模块中，传给 CLIP 模块的边界数据仅包含基于 URI 的透明 ID。
 */
class GalleryViewModel(
    private val getGalleryMedia: GetGalleryMediaUseCase,
    private val clipSearchEngine: ClipSearchEngine = EmptyClipSearchEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        GalleryUiState(
            availableModels = clipSearchEngine.availableModels,
            selectedModelId = clipSearchEngine.selectedModelId,
        )
    )
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var indexJob: Job? = null
    private var searchJob: Job? = null

    /** 权限被撤销时立即清空受保护媒体；访问模式发生变化后重新加载相册。 */
    fun onAccessModeChanged(accessMode: GalleryAccessMode) {
        val previousMode = _uiState.value.accessMode
        if (accessMode == GalleryAccessMode.NONE) {
            loadJob?.cancel()
            indexJob?.cancel()
            searchJob?.cancel()
            _uiState.value = GalleryUiState(
                accessMode = GalleryAccessMode.NONE,
                availableModels = clipSearchEngine.availableModels,
                selectedModelId = clipSearchEngine.selectedModelId,
            )
            return
        }

        _uiState.update { it.copy(accessMode = accessMode) }
        if (accessMode != GalleryAccessMode.UNKNOWN && accessMode != previousMode) {
            refresh()
        }
    }

    /** 重新读取 MediaStore，并根据最新媒体快照重建 CLIP 索引。 */
    fun refresh() {
        if (_uiState.value.accessMode == GalleryAccessMode.UNKNOWN ||
            _uiState.value.accessMode == GalleryAccessMode.NONE
        ) {
            return
        }

        loadJob?.cancel()
        indexJob?.cancel()
        searchJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    isSearchActive = false,
                    searchResults = emptyList(),
                    searchScores = emptyMap(),
                    clipModelState = ClipModelState.IDLE,
                    indexedImageCount = 0,
                    indexTargetCount = 0,
                    cacheHitCount = 0,
                )
            }
            runCatching { getGalleryMedia() }
                .onSuccess { media ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            media = media,
                            selectedMedia = it.selectedMedia?.let { selected ->
                                media.firstOrNull { item -> item.id == selected.id }
                            },
                        )
                    }
                    startImageIndex(media)
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = GalleryError.LOAD_FAILED,
                        )
                    }
                }
        }
    }

    fun selectMedia(media: GalleryMedia) {
        _uiState.update { it.copy(selectedMedia = media) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedMedia = null) }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                isSearchActive = false,
                searchResults = emptyList(),
                searchScores = emptyMap(),
            )
        }
    }

    /** 执行文本检索，并将 CLIP 返回的透明 ID 映射回当前相册对象。 */
    fun search() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            _uiState.update {
                it.copy(
                    isSearchActive = false,
                    isSearching = false,
                    searchResults = emptyList(),
                    searchScores = emptyMap(),
                )
            }
            return
        }
        if (_uiState.value.clipModelState != ClipModelState.READY) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            try {
                val results = clipSearchEngine.search(query)
                val mediaByClipId = _uiState.value.media.associateBy { it.uri }
                val matchedResults = results.mapNotNull { result ->
                    mediaByClipId[result.imageId]?.let { media -> media to result.score }
                }
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        isSearchActive = true,
                        searchResults = matchedResults.map { result -> result.first },
                        searchScores = matchedResults.associate { result ->
                            result.first.id to result.second
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        clipModelState = ClipModelState.FAILED,
                    )
                }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = "",
                isSearchActive = false,
                isSearching = false,
                searchResults = emptyList(),
                searchScores = emptyMap(),
            )
        }
    }

    fun selectModel(modelId: String) {
        val state = _uiState.value
        if (modelId == state.selectedModelId ||
            state.availableModels.none { it.id == modelId }
        ) {
            return
        }

        indexJob?.cancel()
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                selectedModelId = modelId,
                clipModelState = ClipModelState.PREPARING,
                indexedImageCount = 0,
                indexTargetCount = 0,
                cacheHitCount = 0,
                isSearchActive = false,
                isSearching = false,
                searchResults = emptyList(),
                searchScores = emptyMap(),
            )
        }
        indexJob = viewModelScope.launch {
            try {
                clipSearchEngine.selectModel(modelId)
                buildImageIndex(_uiState.value.media)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        selectedModelId = clipSearchEngine.selectedModelId,
                        clipModelState = ClipModelState.FAILED,
                    )
                }
            }
        }
    }

    /**
     * 仅索引最近的图片。开始新的加载任务前会取消旧索引任务，防止过期 MediaStore 快照覆盖当前 UI 状态。
     */
    private fun startImageIndex(media: List<GalleryMedia>) {
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            try {
                buildImageIndex(media)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _uiState.update { it.copy(clipModelState = ClipModelState.FAILED) }
            }
        }
    }

    private suspend fun buildImageIndex(media: List<GalleryMedia>) {
        val clipImages = media
            .asSequence()
            .filter { it.type == GalleryMediaType.IMAGE }
            .take(MAX_INDEXED_IMAGES)
            .map { image ->
                ClipImage(
                    id = image.uri,
                    uri = image.uri,
                    fingerprint = listOf(
                        image.dateModifiedMillis,
                        image.sizeBytes,
                        image.width,
                        image.height,
                    ).joinToString(":"),
                )
            }
            .toList()
        if (clipImages.isEmpty()) {
            _uiState.update {
                it.copy(
                    clipModelState = ClipModelState.READY,
                    indexTargetCount = 0,
                    indexedImageCount = 0,
                    cacheHitCount = 0,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                clipModelState = ClipModelState.PREPARING,
                indexTargetCount = clipImages.size,
                indexedImageCount = 0,
                cacheHitCount = 0,
            )
        }
        val result: ClipIndexResult = clipSearchEngine.index(clipImages) { completed, total ->
            _uiState.update {
                it.copy(
                    clipModelState = ClipModelState.INDEXING,
                    indexedImageCount = completed,
                    indexTargetCount = total,
                )
            }
        }
        _uiState.update {
            it.copy(
                clipModelState = ClipModelState.READY,
                indexedImageCount = result.indexedCount,
                cacheHitCount = result.cacheHitCount,
            )
        }
    }

    override fun onCleared() {
        clipSearchEngine.close()
        super.onCleared()
    }

    class Factory(
        private val getGalleryMedia: GetGalleryMediaUseCase,
        private val clipSearchEngine: ClipSearchEngine,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GalleryViewModel::class.java))
            return GalleryViewModel(getGalleryMedia, clipSearchEngine) as T
        }
    }

    companion object {
        const val MAX_INDEXED_IMAGES = 100
    }

    private object EmptyClipSearchEngine : ClipSearchEngine {
        override val availableModels = emptyList<com.yarkey.clip.ClipModel>()
        override val selectedModelId = ""

        override suspend fun selectModel(modelId: String) = Unit

        override suspend fun index(
            images: List<ClipImage>,
            onProgress: (completed: Int, total: Int) -> Unit,
        ): ClipIndexResult {
            onProgress(images.size, images.size)
            return ClipIndexResult(
                indexedCount = images.size,
                cacheHitCount = 0,
                generatedCount = images.size,
            )
        }

        override suspend fun search(
            query: String,
            limit: Int,
        ): List<ClipSearchResult> = emptyList()
    }
}
