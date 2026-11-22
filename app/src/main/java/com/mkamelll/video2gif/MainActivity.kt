package com.mkamelll.video2gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.mkamelll.video2gif.ui.theme.Video2gifTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds


class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Video2gifTheme {
                val context = LocalContext.current
                var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
                var isExporting by remember { mutableStateOf(false) }
                val pickVideoLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        selectedVideoUri = uri
                    }
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                pickVideoLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (selectedVideoUri != null) "Change Video" else "Select Video")
                        }

                        selectedVideoUri?.let { uri ->
                            VideoRangeSelector(
                                modifier = Modifier.weight(1f),
                                videoUri = uri,
                                isExporting = isExporting,
                                onExtractSelectedRange = { startMs, endMs ->
                                    isExporting = true
                                    val fileName = "trimmed_${System.currentTimeMillis()}.mp4"
                                    val tempFile = File(context.cacheDir, fileName)
                                    trimVideoSegment(
                                        context = context,
                                        sourceUri = uri,
                                        outputPath = tempFile.absolutePath,
                                        startMs = startMs,
                                        endMs = endMs,
                                        onSuccess = {
                                            val savedUri =
                                                saveVideoToGallery(context, tempFile, fileName)
                                            isExporting = false
                                            val msg = if (savedUri != null) {
                                                "Saved to Movies/Video2Gif/${fileName}"
                                            } else {
                                                "Failed to save to gallery"
                                            }
                                            Toast.makeText(
                                                context,
                                                msg,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        onError = { e ->
                                            isExporting = false
                                            tempFile.delete()
                                            Toast.makeText(
                                                context,
                                                "Error: ${e.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            )
                        } ?: Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Pick a video to begin")
                        }

                    }
                }
            }
        }
    }
}


@Composable
fun VideoRangeSelector(
    modifier: Modifier = Modifier,
    videoUri: Uri,
    isExporting: Boolean,
    onExtractSelectedRange: (startMs: Long, endMs: Long) -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }
    var videoDurationMs by remember(videoUri) { mutableStateOf(0L) }
    var selectedRange by remember(videoUri) { mutableStateOf(0f..1f) }
    var filmStripFrames by remember(videoUri) { mutableStateOf<List<Bitmap>>(emptyList()) }

    DisposableEffect(exoPlayer) {
        val listener = object : Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && videoDurationMs == 0L) {
                    val duration = exoPlayer.duration.coerceAtLeast(1L)
                    videoDurationMs = duration
                    selectedRange = 0f..duration.toFloat()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, selectedRange, videoDurationMs) {
        if (videoDurationMs <= 0L) return@LaunchedEffect
        while (isActive) {
            val startMs = selectedRange.start.toLong()
            val endMs = selectedRange.endInclusive.toLong()
            if (exoPlayer.isPlaying && (exoPlayer.currentPosition !in startMs..<endMs)) {
                exoPlayer.seekTo(startMs)
            }
            delay(50.milliseconds)
        }
    }

    LaunchedEffect(videoUri, videoDurationMs) {
        if (videoDurationMs > 0L) {
            filmStripFrames = extractFilmstripThumbnails(
                context = context,
                videoUri = videoUri,
                videoDurationMs = videoDurationMs,
                frameCount = 8
            )
        }
    }
    val startMs = selectedRange.start.toLong()
    val endMs = selectedRange.endInclusive.toLong()

    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            update = { view ->
                view.player = exoPlayer
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Start: ${"%.1f".format(startMs / 1000f)}s")
            Text("End: ${"%.1f".format(endMs / 1000f)}s")
        }

        if (videoDurationMs > 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray)
                ) {
                    filmStripFrames.forEach { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
        RangeSlider(
            value = selectedRange,
            onValueChange = { newRange ->
                exoPlayer.pause()
                val seekTarget = if (newRange.start != selectedRange.start) {
                    newRange.start.toLong()
                } else {
                    newRange.endInclusive.toLong()
                }
                selectedRange = newRange
                exoPlayer.seekTo(seekTarget)
            },
            onValueChangeFinished = {
                exoPlayer.play()
            },
            valueRange = 0f..videoDurationMs.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onExtractSelectedRange(startMs, endMs) },
            enabled = videoDurationMs > 0L && !isExporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("Extract")
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun extractFilmstripThumbnails(
    context: Context,
    videoUri: Uri,
    videoDurationMs: Long,
    frameCount: Int
): List<Bitmap> = withContext(Dispatchers.IO) {
    val bitmaps = mutableListOf<Bitmap>()
    val mediaItem = MediaItem.fromUri(videoUri)
    val effects = listOf(Presentation.createForHeight(160))

    try {
        FrameExtractor.Builder(context, mediaItem)
            .setEffects(effects)
            .build()
            .use { extractor ->
                val step = videoDurationMs / frameCount
                for (i in 0 until frameCount) {
                    val frame = extractor.getFrame(i * step).await()
                    bitmaps.add(frame.bitmap)
                }
            }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    bitmaps
}

@androidx.annotation.OptIn(UnstableApi::class)
fun trimVideoSegment(
    context: Context,
    sourceUri: Uri,
    outputPath: String,
    startMs: Long,
    endMs: Long,
    onSuccess: () -> Unit,
    onError: (Exception) -> Unit
) {
    val clippedMediaItem = MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
        ).build()

    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                onSuccess()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                onError(exportException)
            }
        }).build()

    transformer.start(clippedMediaItem, outputPath)
}

fun saveVideoToGallery(
    context: Context,
    tempFile: File,
    displayName: String
): Uri? {
    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Video2Gif")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val videoUri =
        resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

    try {
        resolver.openOutputStream(videoUri)?.use { outputStream ->
            tempFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(videoUri, contentValues, null, null)
        }
        tempFile.delete()
        return videoUri
    } catch (e: Exception) {
        resolver.delete(videoUri, null, null)
        e.printStackTrace()
        return null
    }
}