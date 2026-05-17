package com.anthonyla.paperize.presentation.screens.wallpaper.components
import com.anthonyla.paperize.presentation.theme.AppMaxWidths
import com.anthonyla.paperize.presentation.theme.AppBorderWidths
import com.anthonyla.paperize.core.constants.Constants

import android.app.WallpaperManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.R
import com.anthonyla.paperize.presentation.theme.AppShapes
import com.anthonyla.paperize.presentation.theme.AppSpacing
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import kotlin.math.min

/**
 * Retry helper for reading wallpapers with exponential backoff
 *
 * Android's WallpaperManager writes wallpapers to disk asynchronously after setBitmap() returns.
 * The encoding process can take 1-2 seconds, during which time reading the wallpaper back will
 * fail with ImageDecoder.DecodeException OR return null. This helper retries with increasing
 * delays to allow the system time to complete encoding.
 *
 * IMPORTANT FIX: Now also retries when getDrawable() returns null (which happens when the
 * wallpaper is still being encoded by the system).
 *
 * @param maxAttempts Maximum number of retry attempts (default 6)
 * @param delayMs Initial delay in milliseconds between retries (default 300ms)
 * @param block The operation to retry
 * @return The result of the operation, or null if all attempts fail
 */
private suspend fun <T> retryWallpaperRead(
    maxAttempts: Int = 6,
    delayMs: Long = 300L,
    block: suspend () -> T?
): T? {
    var lastException: Exception? = null

    repeat(maxAttempts) { attempt ->
        try {
            val result = block()
            if (result != null) {
                return result
            }
            // null result - wallpaper not yet available, retry after delay
            lastException = null
            if (attempt < maxAttempts - 1) {
                delay(delayMs * (attempt + 1))
            }
        } catch (e: android.graphics.ImageDecoder.DecodeException) {
            lastException = e
            if (attempt < maxAttempts - 1) {
                delay(delayMs * (attempt + 1))
            }
        } catch (e: Exception) {
            // For non-decode exceptions, retry a couple times then give up
            lastException = e
            if (attempt < 2) {
                delay(delayMs * (attempt + 1))
            } else {
                return null
            }
        }
    }

    // All attempts failed
    return null
}

/**
 * Displays current home and lock screen wallpapers
 *
 * Layout: Home screen on the LEFT, Lock screen on the RIGHT.
 *
 * Automatically updates when wallpapers change using WallpaperManager.OnColorsChangedListener.
 * This allows the preview to stay in sync with system wallpaper changes from any source
 * (this app's wallpaper changer, system settings, other apps, etc.).
 *
 * Uses smooth fade animations and placeholder boxes to prevent layout jumping.
 * Adapts to different screen sizes and orientations following Material 3 responsive design.
 * Respects the app's animate setting for accessibility and user preference.
 */
@Composable
fun CurrentWallpaperPreview(
    animate: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    var homeWallpaper by remember { mutableStateOf<Drawable?>(null) }
    var lockWallpaper by remember { mutableStateOf<Drawable?>(null) }
    var hasPermission by remember { mutableStateOf(true) }

    // Use rememberSaveable to prevent reloading on navigation
    var refreshTrigger by rememberSaveable { mutableIntStateOf(0) }
    var pendingRefresh by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    // Calculate appropriate aspect ratio based on device screen
    val screenAspectRatio = remember(configuration) {
        val screenWidth = configuration.screenWidthDp.toFloat()
        val screenHeight = configuration.screenHeightDp.toFloat()
        val aspectRatio = min(screenWidth, screenHeight) / kotlin.math.max(screenWidth, screenHeight)
        aspectRatio
    }

    // Listen for wallpaper changes with debouncing
    DisposableEffect(Unit) {
        val wallpaperManager = WallpaperManager.getInstance(context)
        val handler = Handler(Looper.getMainLooper())
        val listener = WallpaperManager.OnColorsChangedListener { _, _ ->
            pendingRefresh = true
        }

        wallpaperManager.addOnColorsChangedListener(listener, handler)

        onDispose {
            wallpaperManager.removeOnColorsChangedListener(listener)
        }
    }

    // Reduced debounce from 2000ms to 500ms for faster preview updates
    LaunchedEffect(pendingRefresh) {
        if (pendingRefresh) {
            delay(500L)
            pendingRefresh = false
            refreshTrigger++
        }
    }

    // Fetch wallpapers on initial load and when refreshTrigger changes
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger == 0) {
            isLoading = true
        }

        val (home, lock, hasAccess) = withContext(Dispatchers.IO) {
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // API 34+: Use getDrawable(WallpaperManager.FLAG_SYSTEM/FLAG_LOCK)
                    val homeDrawable = retryWallpaperRead {
                        wallpaperManager.getDrawable(WallpaperManager.FLAG_SYSTEM)
                    }

                    val lockDrawable = retryWallpaperRead {
                        wallpaperManager.getDrawable(WallpaperManager.FLAG_LOCK)
                    } ?: homeDrawable

                    Triple(homeDrawable, lockDrawable, true)
                } else {
                    // For API 31-33, use getDrawable() which returns the current wallpaper
                    val drawable = retryWallpaperRead {
                        wallpaperManager.drawable
                    }
                    Triple(drawable, drawable, true)
                }
            } catch (e: SecurityException) {
                Triple(null, null, false)
            } catch (e: Exception) {
                Triple(null, null, true)
            }
        }
        homeWallpaper = home
        lockWallpaper = lock
        hasPermission = hasAccess
        isLoading = false
    }

    // Don't show if no permission
    if (!hasPermission) {
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = AppMaxWidths.contentMaxWidth)
            .padding(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall),
        shape = AppShapes.cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.large)
        ) {
            Text(
                text = stringResource(R.string.current_wallpapers),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = AppSpacing.medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                // Home wallpaper preview (on the LEFT)
                WallpaperPreviewBox(
                    wallpaper = homeWallpaper,
                    isLoading = isLoading,
                    aspectRatio = screenAspectRatio,
                    label = stringResource(R.string.home),
                    contentDescription = stringResource(R.string.content_desc_current_home_wallpaper),
                    animate = animate,
                    modifier = Modifier.weight(1f)
                )

                // Lock wallpaper preview (on the RIGHT)
                WallpaperPreviewBox(
                    wallpaper = lockWallpaper,
                    isLoading = isLoading,
                    aspectRatio = screenAspectRatio,
                    label = stringResource(R.string.lock),
                    contentDescription = stringResource(R.string.content_desc_current_lock_wallpaper),
                    animate = animate,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Displays a single wallpaper preview with label, loading indicator, and smooth fade animation
 */
@Composable
private fun WallpaperPreviewBox(
    wallpaper: Drawable?,
    isLoading: Boolean,
    aspectRatio: Float,
    label: String,
    contentDescription: String,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(aspectRatio)
                .border(
                    width = AppBorderWidths.thick,
                    color = Color.Black,
                    shape = AppShapes.imageShape
                )
                .clip(AppShapes.imageShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            // Show loading indicator while loading
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.medium)
                )
            }

            // Use general AnimatedVisibility (not ColumnScope version)
            if (!isLoading && wallpaper != null) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = if (animate) {
                        fadeIn(animationSpec = tween(300))
                    } else {
                        fadeIn(animationSpec = tween(0))
                    },
                    exit = if (animate) {
                        fadeOut(animationSpec = tween(300))
                    } else {
                        fadeOut(animationSpec = tween(0))
                    },
                    modifier = Modifier.matchParentSize()
                ) {
                    wallpaper?.let { drawable ->
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(drawable)
                                .size(Size(Constants.PREVIEW_THUMBNAIL_WIDTH, Constants.PREVIEW_THUMBNAIL_HEIGHT))
                                .crossfade(true)
                                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                                .diskCachePolicy(coil3.request.CachePolicy.DISABLED)
                                .build(),
                            contentDescription = contentDescription,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .zoomable(rememberZoomState())
                        )
                    }
                }
            }

            // Show "no wallpaper" text when not loading and wallpaper is null
            if (!isLoading && wallpaper == null) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Label below the preview
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppSpacing.extraSmall),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
