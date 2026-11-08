package com.yarkey.gallery.presentation

import com.yarkey.clip.ClipImage
import com.yarkey.clip.ClipIndexResult
import com.yarkey.clip.ClipModel
import com.yarkey.clip.ClipSearchEngine
import com.yarkey.clip.ClipSearchResult
import com.yarkey.gallery.domain.model.GalleryMedia
import com.yarkey.gallery.domain.model.GalleryMediaType
import com.yarkey.gallery.domain.repository.GalleryRepository
import com.yarkey.gallery.domain.usecase.GetGalleryMediaUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun grantingAccessLoadsImagesAndVideos() = runTest {
        val media = listOf(
            testMedia(1, GalleryMediaType.IMAGE),
            testMedia(2, GalleryMediaType.VIDEO),
        )
        val viewModel = viewModel(repository = FakeGalleryRepository(media))

        viewModel.onAccessModeChanged(GalleryAccessMode.FULL)
        advanceUntilIdle()

        assertEquals(media, viewModel.uiState.value.media)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun denyingAccessClearsPreviouslyLoadedMedia() = runTest {
        val viewModel = viewModel(
            repository = FakeGalleryRepository(listOf(testMedia(1, GalleryMediaType.IMAGE)))
        )
        viewModel.onAccessModeChanged(GalleryAccessMode.FULL)
        advanceUntilIdle()

        viewModel.onAccessModeChanged(GalleryAccessMode.NONE)

        assertEquals(GalleryAccessMode.NONE, viewModel.uiState.value.accessMode)
        assertTrue(viewModel.uiState.value.media.isEmpty())
    }

    @Test
    fun loadFailureExposesRetryableError() = runTest {
        val viewModel = viewModel(repository = FakeGalleryRepository(error = IllegalStateException()))

        viewModel.onAccessModeChanged(GalleryAccessMode.FULL)
        advanceUntilIdle()

        assertEquals(GalleryError.LOAD_FAILED, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun successfulIndexEnablesTextSearch() = runTest {
        val first = testMedia(1, GalleryMediaType.IMAGE)
        val second = testMedia(2, GalleryMediaType.IMAGE)
        val engine = FakeClipSearchEngine(
            results = listOf(
                ClipSearchResult(second.uri, 0.8f),
                ClipSearchResult(first.uri, 0.6f),
            )
        )
        val viewModel = viewModel(
            repository = FakeGalleryRepository(listOf(first, second)),
            clipSearchEngine = engine,
        )

        viewModel.onAccessModeChanged(GalleryAccessMode.FULL)
        advanceUntilIdle()
        viewModel.onSearchQueryChanged("红色的猫")
        viewModel.search()
        advanceUntilIdle()

        assertEquals(ClipModelState.READY, viewModel.uiState.value.clipModelState)
        assertEquals(2, viewModel.uiState.value.indexedImageCount)
        assertEquals(listOf(second, first), viewModel.uiState.value.searchResults)
        assertEquals(0.8f, viewModel.uiState.value.searchScores[second.id])
        assertTrue(viewModel.uiState.value.isSearchActive)
        assertEquals("红色的猫", engine.lastQuery)
    }

    @Test
    fun selectingModelRebuildsIndexForCurrentImages() = runTest {
        val image = testMedia(1, GalleryMediaType.IMAGE)
        val engine = FakeClipSearchEngine()
        val viewModel = viewModel(
            repository = FakeGalleryRepository(listOf(image)),
            clipSearchEngine = engine,
        )
        viewModel.onAccessModeChanged(GalleryAccessMode.FULL)
        advanceUntilIdle()

        viewModel.selectModel("model-b")
        advanceUntilIdle()

        assertEquals("model-b", viewModel.uiState.value.selectedModelId)
        assertEquals(ClipModelState.READY, viewModel.uiState.value.clipModelState)
        assertEquals(listOf("model-a", "model-b"), engine.indexedModelIds)
    }

    private fun viewModel(
        repository: GalleryRepository,
        clipSearchEngine: ClipSearchEngine = FakeClipSearchEngine(),
    ) = GalleryViewModel(GetGalleryMediaUseCase(repository), clipSearchEngine)

    private fun testMedia(id: Long, type: GalleryMediaType) = GalleryMedia(
        id = id,
        uri = "content://media/$id",
        displayName = "media-$id",
        dateAddedMillis = id,
        dateModifiedMillis = id,
        sizeBytes = id * 100,
        width = 100,
        height = 100,
        durationMillis = if (type == GalleryMediaType.VIDEO) 5_000 else 0,
        mimeType = if (type == GalleryMediaType.VIDEO) "video/mp4" else "image/jpeg",
        type = type,
    )

    private class FakeGalleryRepository(
        private val media: List<GalleryMedia> = emptyList(),
        private val error: Throwable? = null,
    ) : GalleryRepository {
        override suspend fun getMedia(): List<GalleryMedia> {
            error?.let { throw it }
            return media
        }
    }

    private class FakeClipSearchEngine(
        private val results: List<ClipSearchResult> = emptyList(),
    ) : ClipSearchEngine {
        override val availableModels = listOf(
            ClipModel("model-a", "Model A", "Test"),
            ClipModel("model-b", "Model B", "Test"),
        )
        override var selectedModelId = "model-a"
        var lastQuery: String? = null
        val indexedModelIds = mutableListOf<String>()

        override suspend fun selectModel(modelId: String) {
            selectedModelId = modelId
        }

        override suspend fun index(
            images: List<ClipImage>,
            onProgress: (completed: Int, total: Int) -> Unit,
        ): ClipIndexResult {
            indexedModelIds += selectedModelId
            onProgress(images.size, images.size)
            return ClipIndexResult(images.size, 0, images.size)
        }

        override suspend fun search(query: String, limit: Int): List<ClipSearchResult> {
            lastQuery = query
            return results.take(limit)
        }
    }
}
