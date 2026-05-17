package com.anthonyla.paperize.service.worker

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.usecase.ChangeWallpaperUseCase
import com.anthonyla.paperize.domain.usecase.ReapplyEffectsUseCase
import com.anthonyla.paperize.service.WallpaperChangeLock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WorkManager worker for changing wallpapers on screen unlock
 *
 * This worker is triggered by ScreenUnlockReceiver when the user unlocks the device.
 * It replaces the foreground service approach which fails on Android 12+ when the
 * app is not in the foreground (ForegroundServiceStartNotAllowedException).
 *
 * WorkManager expedited work requests CAN run from the background, making this
 * the correct approach for unlock-triggered wallpaper changes.
 */
@HiltWorker
class UnlockWallpaperWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val changeWallpaperUseCase: ChangeWallpaperUseCase,
    private val reapplyEffectsUseCase: ReapplyEffectsUseCase,
    private val settingsRepository: SettingsRepository,
    private val wallpaperRepository: WallpaperRepository,
    private val wallpaperChangeLock: WallpaperChangeLock
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UnlockWallpaperWorker"
        private const val WORK_NAME = "unlock_wallpaper_change"
        private const val MUTEX_TIMEOUT_MS = 10_000L
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Screen unlock - checking if wallpaper change is needed")

        return try {
            val settings = settingsRepository.getScheduleSettings()

            // Check if change on screen unlock is enabled for any screen
            val homeUnlockEnabled = settings.homeEffects.enableChangeOnScreenUnlock
            val lockUnlockEnabled = settings.lockEffects.enableChangeOnScreenUnlock

            if (!homeUnlockEnabled && !lockUnlockEnabled) {
                Log.d(TAG, "Change on screen unlock not enabled, skipping")
                return Result.success()
            }

            // Determine the screen type based on which unlock effects are enabled
            val screenType = when {
                homeUnlockEnabled && lockUnlockEnabled -> ScreenType.BOTH
                homeUnlockEnabled -> ScreenType.HOME
                lockUnlockEnabled -> ScreenType.LOCK
                else -> {
                    Log.w(TAG, "No screen enabled for unlock change")
                    return Result.success()
                }
            }

            Log.d(TAG, "Screen unlock - changing wallpaper (work manager) home=$homeUnlockEnabled lock=$lockUnlockEnabled")

            // Use shared mutex to prevent concurrent wallpaper changes across service and worker
            // Use tryLock with timeout instead of blocking indefinitely
            val lockAcquired = withTimeoutOrNull(MUTEX_TIMEOUT_MS) {
                wallpaperChangeLock.mutex.lock()
                true
            } ?: false

            if (!lockAcquired) {
                Log.w(TAG, "Screen unlock - could not acquire mutex within ${MUTEX_TIMEOUT_MS}ms, skipping this unlock")
                return Result.retry()
            }

            try {
                changeWallpaper(screenType, settings)
                Log.d(TAG, "Wallpaper change on unlock completed successfully")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Error changing wallpaper on unlock", e)
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            } finally {
                wallpaperChangeLock.mutex.unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in unlock wallpaper worker", e)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private suspend fun changeWallpaper(screenType: ScreenType, settings: com.anthonyla.paperize.domain.model.ScheduleSettings) {
        val wallpaperManager = WallpaperManager.getInstance(context)

        when (screenType) {
            ScreenType.LIVE -> {
                // Send broadcast to trigger live wallpaper reload
                val intent = android.content.Intent("com.magen.wallpaper.ACTION_RELOAD_WALLPAPER")
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
                Log.d(TAG, "Sent reload broadcast to live wallpaper service")
            }
            ScreenType.HOME -> {
                val homeAlbumId = settings.homeAlbumId
                if (homeAlbumId != null) {
                    changeHomeWallpaper(homeAlbumId, settings, wallpaperManager)
                } else {
                    Log.w(TAG, "No home album selected for unlock change")
                }
            }
            ScreenType.LOCK -> {
                val lockAlbumId = settings.lockAlbumId
                if (lockAlbumId != null) {
                    changeLockWallpaper(lockAlbumId, settings, wallpaperManager)
                } else {
                    Log.w(TAG, "No lock album selected for unlock change")
                }
            }
            ScreenType.BOTH -> {
                val homeAlbumId = settings.homeAlbumId
                val lockAlbumId = settings.lockAlbumId

                if (homeAlbumId != null && lockAlbumId != null && homeAlbumId == lockAlbumId) {
                    // Same album for both screens
                    val result = changeWallpaperUseCase(homeAlbumId, ScreenType.HOME)
                    result.onSuccess { bitmap ->
                        try {
                            if (bitmap.width <= 0 || bitmap.height <= 0) {
                                throw IllegalStateException("Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}")
                            }
                            if (bitmap.isRecycled) {
                                throw IllegalStateException("Bitmap has been recycled")
                            }

                            wallpaperManager.setBitmap(
                                bitmap,
                                null,
                                true,
                                WallpaperManager.FLAG_SYSTEM
                            )
                            Log.d(TAG, "Home wallpaper set in BOTH mode (unlock)")

                            // Keep LOCK queue in sync with HOME
                            try {
                                val homeCurrentId = wallpaperRepository
                                    .getCurrentWallpaper(homeAlbumId, ScreenType.HOME)?.id
                                if (homeCurrentId != null) {
                                    if (wallpaperRepository.getNextWallpaperInQueue(
                                            homeAlbumId, ScreenType.LOCK) == null) {
                                        wallpaperRepository.buildWallpaperQueue(
                                            homeAlbumId, ScreenType.LOCK, settings.shuffleEnabled)
                                    }
                                    wallpaperRepository.getAndDequeueWallpaper(
                                        homeAlbumId, ScreenType.LOCK)
                                    wallpaperRepository.setCurrentWallpaper(
                                        homeAlbumId, ScreenType.LOCK, homeCurrentId)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to sync LOCK queue in BOTH mode (unlock)", e)
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting wallpaper for both screens (unlock)", e)
                        } finally {
                            bitmap.recycle()
                        }

                        // Render a separate bitmap for the lock screen
                        val lockResult = reapplyEffectsUseCase(homeAlbumId, ScreenType.LOCK)
                        lockResult.onSuccess { lockBitmap ->
                            try {
                                wallpaperManager.setBitmap(
                                    lockBitmap, null, true, WallpaperManager.FLAG_LOCK
                                )
                                Log.d(TAG, "Lock wallpaper set separately in BOTH mode (unlock)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error setting lock wallpaper in BOTH mode (unlock)", e)
                            } finally {
                                lockBitmap.recycle()
                            }
                        }.onError { error ->
                            Log.w(TAG, "Lock rerender failed in BOTH mode (unlock): ${error.message}")
                        }
                    }.onError { error ->
                        if (error is EmptyAlbumException) {
                            Log.w(TAG, "Album is empty for BOTH screens (unlock)")
                            val updatedSettings = settings.copy(
                                homeAlbumId = null,
                                lockAlbumId = null,
                                enableChanger = false
                            )
                            settingsRepository.updateScheduleSettings(updatedSettings)
                        } else {
                            Log.e(TAG, "Error getting wallpaper bitmap (unlock)", error)
                            throw error
                        }
                    }
                } else {
                    // Different albums or separately scheduled - change independently
                    if (homeAlbumId != null) {
                        changeHomeWallpaper(homeAlbumId, settings, wallpaperManager)
                    } else {
                        Log.w(TAG, "No home album selected for unlock change")
                    }
                    if (lockAlbumId != null) {
                        changeLockWallpaper(lockAlbumId, settings, wallpaperManager)
                    } else {
                        Log.w(TAG, "No lock album selected for unlock change")
                    }
                }
            }
        }
    }

    private suspend fun changeHomeWallpaper(
        albumId: String,
        settings: com.anthonyla.paperize.domain.model.ScheduleSettings,
        wallpaperManager: WallpaperManager
    ) {
        val result = changeWallpaperUseCase(albumId, ScreenType.HOME)
        result.onSuccess { bitmap ->
            try {
                if (bitmap.width <= 0 || bitmap.height <= 0) {
                    throw IllegalStateException("Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}")
                }
                if (bitmap.isRecycled) {
                    throw IllegalStateException("Bitmap has been recycled")
                }

                wallpaperManager.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM
                )
                Log.d(TAG, "Home wallpaper changed successfully (unlock)")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting home wallpaper (unlock)", e)
                throw e
            } finally {
                bitmap.recycle()
            }
        }.onError { error ->
            if (error is EmptyAlbumException) {
                Log.w(TAG, "Home album is empty (unlock)")
                val lockStillActive = settings.lockEnabled && settings.lockAlbumId != null
                settingsRepository.updateScheduleSettings(settings.copy(
                    homeAlbumId = null,
                    enableChanger = if (lockStillActive) settings.enableChanger else false
                ))
            } else {
                Log.e(TAG, "Error getting home wallpaper bitmap (unlock)", error)
                throw error
            }
        }
    }

    private suspend fun changeLockWallpaper(
        albumId: String,
        settings: com.anthonyla.paperize.domain.model.ScheduleSettings,
        wallpaperManager: WallpaperManager
    ) {
        val result = changeWallpaperUseCase(albumId, ScreenType.LOCK)
        result.onSuccess { bitmap ->
            try {
                if (bitmap.width <= 0 || bitmap.height <= 0) {
                    throw IllegalStateException("Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}")
                }
                if (bitmap.isRecycled) {
                    throw IllegalStateException("Bitmap has been recycled")
                }

                wallpaperManager.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_LOCK
                )
                Log.d(TAG, "Lock wallpaper changed successfully (unlock)")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting lock wallpaper (unlock)", e)
                throw e
            } finally {
                bitmap.recycle()
            }
        }.onError { error ->
            if (error is EmptyAlbumException) {
                Log.w(TAG, "Lock album is empty (unlock)")
                val homeStillActive = settings.homeEnabled && settings.homeAlbumId != null
                settingsRepository.updateScheduleSettings(settings.copy(
                    lockAlbumId = null,
                    enableChanger = if (homeStillActive) settings.enableChanger else false
                ))
            } else {
                Log.e(TAG, "Error getting lock wallpaper bitmap (unlock)", error)
                throw error
            }
        }
    }
}
