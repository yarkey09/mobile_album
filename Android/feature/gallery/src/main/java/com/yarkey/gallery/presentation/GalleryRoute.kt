package com.yarkey.gallery.presentation

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.yarkey.gallery.R
import com.yarkey.clip.ChineseClipSearchEngine
import com.yarkey.gallery.data.MediaStoreGalleryRepository
import com.yarkey.gallery.domain.model.GalleryMedia
import com.yarkey.gallery.domain.model.GalleryMediaType
import com.yarkey.gallery.domain.usecase.GetGalleryMediaUseCase
import java.util.Locale

@Composable
fun GalleryRoute(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) {
        GalleryViewModel.Factory(
            GetGalleryMediaUseCase(
                MediaStoreGalleryRepository(context.applicationContext.contentResolver)
            ),
            ChineseClipSearchEngine(context.applicationContext),
        )
    }
    val viewModel: GalleryViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onAccessModeChanged(GalleryPermissions.currentAccessMode(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        viewModel.onAccessModeChanged(GalleryPermissions.currentAccessMode(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAccessModeChanged(GalleryPermissions.currentAccessMode(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GalleryScreen(
        uiState = uiState,
        onRequestPermission = {
            permissionLauncher.launch(GalleryPermissions.permissionsForRequest())
        },
        onRefresh = viewModel::refresh,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSearch = viewModel::search,
        onClearSearch = viewModel::clearSearch,
        onMediaClick = viewModel::selectMedia,
        onDismissPreview = viewModel::clearSelection,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryScreen(
    uiState: GalleryUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onMediaClick: (GalleryMedia) -> Unit,
    onDismissPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageLoader = rememberGalleryImageLoader()

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(text = stringResource(R.string.gallery_title))
                            if (uiState.accessMode == GalleryAccessMode.LIMITED) {
                                Text(
                                    text = stringResource(R.string.gallery_limited_access),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (uiState.accessMode == GalleryAccessMode.FULL ||
                            uiState.accessMode == GalleryAccessMode.LIMITED
                        ) {
                            if (uiState.accessMode == GalleryAccessMode.LIMITED) {
                                IconButton(onClick = onRequestPermission) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoLibrary,
                                        contentDescription = stringResource(
                                            R.string.gallery_manage_access
                                        ),
                                    )
                                }
                            }
                            IconButton(
                                onClick = onRefresh,
                                enabled = !uiState.isLoading,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.gallery_refresh),
                                )
                            }
                        }
                    },
                )
                if (uiState.accessMode == GalleryAccessMode.FULL ||
                    uiState.accessMode == GalleryAccessMode.LIMITED
                ) {
                    ClipSearchPanel(
                        uiState = uiState,
                        onQueryChanged = onSearchQueryChanged,
                        onSearch = onSearch,
                        onClear = onClearSearch,
                    )
                }
            }
        },
    ) { contentPadding ->
        GalleryContent(
            uiState = uiState,
            imageLoader = imageLoader,
            onRequestPermission = onRequestPermission,
            onRefresh = onRefresh,
            onMediaClick = onMediaClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
    }

    uiState.selectedMedia?.let { media ->
        MediaPreview(
            media = media,
            imageLoader = imageLoader,
            onDismiss = onDismissPreview,
        )
    }
}

@Composable
private fun GalleryContent(
    uiState: GalleryUiState,
    imageLoader: ImageLoader,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onMediaClick: (GalleryMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.accessMode == GalleryAccessMode.UNKNOWN -> LoadingState(modifier)
        uiState.accessMode == GalleryAccessMode.NONE -> PermissionState(
            onRequestPermission = onRequestPermission,
            modifier = modifier,
        )

        uiState.isLoading && uiState.media.isEmpty() -> LoadingState(modifier)
        uiState.error != null && uiState.media.isEmpty() -> ErrorState(
            onRetry = onRefresh,
            modifier = modifier,
        )

        uiState.media.isEmpty() -> EmptyState(modifier)
        uiState.isSearchActive && uiState.searchResults.isEmpty() -> SearchEmptyState(modifier)
        else -> {
            val displayedMedia = if (uiState.isSearchActive) {
                uiState.searchResults
            } else {
                uiState.media
            }
            MediaGrid(
                media = displayedMedia,
                scores = uiState.searchScores,
                imageLoader = imageLoader,
                isRefreshing = uiState.isLoading,
                onMediaClick = onMediaClick,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ClipSearchPanel(
    uiState: GalleryUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.clip_search_placeholder)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.clip_clear_search),
                            )
                        }
                    }
                    IconButton(
                        onClick = onSearch,
                        enabled = uiState.searchQuery.isNotBlank() &&
                            uiState.clipModelState == ClipModelState.READY &&
                            !uiState.isSearching,
                    ) {
                        if (uiState.isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.clip_search),
                            )
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )

        Text(
            text = clipStatusText(uiState),
            style = MaterialTheme.typography.labelSmall,
            color = if (uiState.clipModelState == ClipModelState.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )

        if (uiState.clipModelState == ClipModelState.PREPARING) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        } else if (uiState.clipModelState == ClipModelState.INDEXING) {
            LinearProgressIndicator(
                progress = {
                    uiState.indexedImageCount.toFloat() /
                        uiState.indexTargetCount.coerceAtLeast(1)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun clipStatusText(uiState: GalleryUiState): String = when (uiState.clipModelState) {
    ClipModelState.IDLE -> stringResource(R.string.clip_waiting)
    ClipModelState.PREPARING -> stringResource(R.string.clip_preparing)
    ClipModelState.INDEXING -> stringResource(
        R.string.clip_indexing,
        uiState.indexedImageCount,
        uiState.indexTargetCount,
    )
    ClipModelState.READY -> if (uiState.isSearchActive) {
        stringResource(R.string.clip_results, uiState.searchResults.size)
    } else {
        stringResource(R.string.clip_ready, uiState.indexedImageCount)
    }
    ClipModelState.FAILED -> stringResource(R.string.clip_failed)
}

@Composable
private fun MediaGrid(
    media: List<GalleryMedia>,
    scores: Map<Long, Float>,
    imageLoader: ImageLoader,
    isRefreshing: Boolean,
    onMediaClick: (GalleryMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                items = media,
                key = { item -> "${item.type}:${item.id}" },
            ) { item ->
                MediaThumbnail(
                    media = item,
                    score = scores[item.id],
                    imageLoader = imageLoader,
                    onClick = { onMediaClick(item) },
                )
            }
        }

        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .size(28.dp),
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
private fun MediaThumbnail(
    media: GalleryMedia,
    score: Float?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = media.imageRequest(LocalContext.current),
            imageLoader = imageLoader,
            contentDescription = media.displayName.ifBlank {
                stringResource(R.string.gallery_media)
            },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (media.type == GalleryMediaType.VIDEO) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = stringResource(R.string.gallery_video),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(38.dp),
            )
            Text(
                text = media.durationMillis.formatDuration(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.68f),
                        shape = RoundedCornerShape(2.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }

        score?.let {
            Text(
                text = String.format(Locale.ROOT, "%.3f", it),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.68f),
                        shape = RoundedCornerShape(2.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun PermissionState(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredState(
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
            )
        },
        title = stringResource(R.string.gallery_permission_title),
        message = stringResource(R.string.gallery_permission_message),
        action = {
            Button(onClick = onRequestPermission) {
                Text(text = stringResource(R.string.gallery_grant_permission))
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    CenteredState(
        icon = {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
            )
        },
        title = stringResource(R.string.gallery_empty_title),
        message = stringResource(R.string.gallery_empty_message),
        modifier = modifier,
    )
}

@Composable
private fun SearchEmptyState(modifier: Modifier = Modifier) {
    CenteredState(
        icon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
            )
        },
        title = stringResource(R.string.clip_no_results_title),
        message = stringResource(R.string.clip_no_results_message),
        modifier = modifier,
    )
}

@Composable
private fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredState(
        icon = {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
            )
        },
        title = stringResource(R.string.gallery_error_title),
        message = stringResource(R.string.gallery_error_message),
        action = {
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.gallery_retry))
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun CenteredState(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.let {
            Spacer(modifier = Modifier.height(20.dp))
            it()
        }
    }
}

@Composable
private fun MediaPreview(
    media: GalleryMedia,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when (media.type) {
                GalleryMediaType.IMAGE -> AsyncImage(
                    model = media.imageRequest(LocalContext.current),
                    imageLoader = imageLoader,
                    contentDescription = media.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                GalleryMediaType.VIDEO -> VideoPlayer(
                    media = media,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            PreviewHeader(
                title = media.displayName,
                onDismiss = onDismiss,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun VideoPlayer(
    media: GalleryMedia,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(context.applicationContext, media.uri) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(media.uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

@Composable
private fun PreviewHeader(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.gallery_close_preview),
                tint = Color.White,
            )
        }
        Text(
            text = title.ifBlank { stringResource(R.string.gallery_media) },
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun rememberGalleryImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}

private fun GalleryMedia.imageRequest(context: Context): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(uri))
        .crossfade(true)
        .apply {
            if (type == GalleryMediaType.VIDEO) {
                videoFrameMillis(1_000)
            }
        }
        .build()

private fun Long.formatDuration(): String {
    val totalSeconds = (this / 1_000L).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
