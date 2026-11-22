package com.mkamelll.video2gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.ui.PlayerView
import com.mkamelll.video2gif.ui.theme.Video2gifTheme
import com.waynejo.androidndkgif.GifEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                val coroutineScope = rememberCoroutineScope()
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
                    ExportingDialog(isExporting)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        selectedVideoUri?.let { uri ->
                            VideoRangeSelector(
                                modifier = Modifier.weight(1f),
                                videoUri = uri,
                                isExporting = isExporting,
                                onExtractSelectedRange = { startMs, endMs ->
                                    coroutineScope.launch {
                                        isExporting = true
                                        val fileName = "gif_${System.currentTimeMillis()}.gif"
                                        val tempFile = File(context.cacheDir, fileName)
                                        val success = exportVideoRangeToGif(
                                            context = context,
                                            sourceUri = uri,
                                            outputFile = tempFile,
                                            startMs = startMs,
                                            endMs = endMs,
                                            fps = 15,
                                            maxDimension = 480
                                        )
                                        val savedUri = if (success) {
                                            withContext(Dispatchers.IO) {
                                                saveGifToGallery(context, tempFile, fileName)
                                            }
                                        } else {
                                            tempFile.delete()
                                            null
                                        }
                                        isExporting = false
                                        val msg = if (savedUri != null) {
                                            "Saved to Pictures/Video2Gif/$fileName"
                                        } else {
                                            "Failed to export Gif"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        } ?: Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Pick a video to begin")
                        }
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
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Extract")
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

fun saveGifToGallery(
    context: Context,
    tempFile: File,
    displayName: String
): Uri? {
    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Video2Gif"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val gifUri =
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

    try {
        resolver.openOutputStream(gifUri)?.use { outputStream ->
            tempFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(gifUri, contentValues, null, null)
        }
        tempFile.delete()
        return gifUri
    } catch (e: Exception) {
        resolver.delete(gifUri, null, null)
        e.printStackTrace()
        return null
    }
}

@Composable
fun ExportingDialog(isExporting: Boolean, message: String = "Exporting..") {
    if (!isExporting) return

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(text = message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

suspend fun exportVideoRangeToGif(
    context: Context,
    sourceUri: Uri,
    outputFile: File,
    startMs: Long,
    endMs: Long,
    fps: Int = 10,
    maxDimension: Int = 480
): Boolean = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    val encoder = GifEncoder()
    var encodedStarted = false

    try {
        retriever.setDataSource(context, sourceUri)
        val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: maxDimension
        val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: maxDimension
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0

        val (videoWidth, videoHeight) = if (rotation == 90 || rotation == 270) {
            rawHeight to rawWidth
        } else {
            rawWidth to rawHeight
        }

        val scale = maxDimension.toFloat() / maxOf(videoWidth, videoHeight).coerceAtLeast(1)
        val targetWidth = ((videoWidth * scale).toInt() / 2) * 2
        val targetHeight = ((videoHeight * scale).toInt() / 2) * 2
        val frameDelayMs = 1000 / fps
        val stepUs = frameDelayMs * 1000L // Us is microsecond
        val startUs = startMs * 1000L
        val endUs = endMs * 1000L

        encoder.init(
            targetWidth,
            targetHeight,
            outputFile.absolutePath,
            GifEncoder.EncodingType.ENCODING_TYPE_NORMAL_LOW_MEMORY
        )
        encodedStarted = true

        var currentUs = startUs
        while (currentUs <= endUs) {
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    currentUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight
                )
            } else {
                retriever.getFrameAtTime(currentUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?.let { raw ->
                        val scaled = Bitmap.createScaledBitmap(raw, targetWidth, targetHeight, true)
                        if (scaled != raw) raw.recycle()
                        scaled
                    }
            }
            if (frame != null) {
                val exactFrame = if (frame.width != targetWidth || frame.height != targetHeight) {
                    Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true).also {
                        frame.recycle()
                    }
                } else {
                    frame
                }
                encoder.encodeFrame(exactFrame, frameDelayMs)
                exactFrame.recycle()
            }
            currentUs += stepUs
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    } finally {
        if (encodedStarted) {
            encoder.close()
        }
        retriever.release()
    }
}