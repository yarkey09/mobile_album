package com.yarkey.gallery.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yarkey.clip.ClipEmbeddingRecord
import com.yarkey.gallery.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmbeddingDatabaseScreen(
    uiState: GalleryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.embedding_database_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.embedding_database_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !uiState.isEmbeddingDatabaseLoading,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(
                                R.string.embedding_database_refresh
                            ),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            uiState.isEmbeddingDatabaseLoading && uiState.embeddingRecords.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.embeddingDatabaseLoadFailed && uiState.embeddingRecords.isEmpty() -> {
                DatabaseMessage(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.DataObject,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                        )
                    },
                    title = stringResource(R.string.embedding_database_error_title),
                    message = stringResource(R.string.embedding_database_error_message),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                )
            }

            uiState.embeddingRecords.isEmpty() -> {
                DatabaseMessage(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.DataObject,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                        )
                    },
                    title = stringResource(R.string.embedding_database_empty_title),
                    message = stringResource(R.string.embedding_database_empty_message),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                )
            }

            else -> EmbeddingRecordList(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }
}

@Composable
private fun EmbeddingRecordList(
    uiState: GalleryUiState,
    modifier: Modifier = Modifier,
) {
    val modelNames = uiState.availableModels.associate { it.id to it.displayName }
    val totalVectorBytes = uiState.embeddingRecords.sumOf {
        it.dimension.toLong() * Float.SIZE_BYTES
    }

    LazyColumn(modifier = modifier) {
        item(key = "summary") {
            DatabaseSummary(
                recordCount = uiState.embeddingRecords.size,
                modelCount = uiState.embeddingRecords.mapTo(HashSet()) { it.modelId }.size,
                vectorBytes = totalVectorBytes,
                isRefreshing = uiState.isEmbeddingDatabaseLoading,
            )
        }
        items(
            items = uiState.embeddingRecords,
            key = { "${it.modelId}:${it.imageId}" },
        ) { record ->
            EmbeddingRecordRow(
                record = record,
                modelName = modelNames[record.modelId] ?: record.modelId,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun DatabaseSummary(
    recordCount: Int,
    modelCount: Int,
    vectorBytes: Long,
    isRefreshing: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DatabaseMetric(
                value = recordCount.toString(),
                label = stringResource(R.string.embedding_database_records),
            )
            DatabaseMetric(
                value = modelCount.toString(),
                label = stringResource(R.string.embedding_database_models),
            )
            DatabaseMetric(
                value = vectorBytes.formatBytes(),
                label = stringResource(R.string.embedding_database_vector_size),
            )
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun DatabaseMetric(
    value: String,
    label: String,
) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmbeddingRecordRow(
    record: ClipEmbeddingRecord,
    modelName: String,
) {
    var expanded by remember(record.modelId, record.imageId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = record.imageId,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(
                        R.string.embedding_database_record_meta,
                        record.dimension,
                        record.updatedAtMillis.formatDateTime(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = record.imageId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = record.feature.formatPreview(if (expanded) EXPANDED_VALUES else COLLAPSED_VALUES),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.embedding_database_fingerprint,
                    record.fingerprint,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DatabaseMessage(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun FloatArray.formatPreview(limit: Int): String =
    take(limit)
        .joinToString(
            prefix = "[",
            postfix = if (size > limit) ", …]" else "]",
            separator = ", ",
        ) { value -> String.format(Locale.ROOT, "%.5f", value) }

private fun Long.formatDateTime(): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(this))

private fun Long.formatBytes(): String = when {
    this >= 1024L * 1024L -> String.format(
        Locale.ROOT,
        "%.1f MB",
        this / (1024f * 1024f),
    )
    this >= 1024L -> String.format(Locale.ROOT, "%.1f KB", this / 1024f)
    else -> "$this B"
}

private const val COLLAPSED_VALUES = 8
private const val EXPANDED_VALUES = 48
