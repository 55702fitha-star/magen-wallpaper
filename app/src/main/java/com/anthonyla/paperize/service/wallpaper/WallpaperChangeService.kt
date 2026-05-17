package com.anthonyla.paperize.service.wallpaper

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.usecase.ChangeWallpaperUseCase
import com.anthonyla.paperize.domain.usecase.ReapplyEffectsUseCase
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.WallpaperChangeLock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground service for changing wallpapers
 *
 * Actions:
 * - CHANGE_WALLPAPER: Change wallpaper immediately
 */
@AndroidEntryPoint
class WallpaperChangeService : Service() {

    @Inject
    lateinit var changeWallpaperUseCase: ChangeWallpaperUseCase

    @Inject
    lateinit var reapplyEffectsUseCase: ReapplyEffectsUseCase

    @Inject
    lateinit var wallpaperRepository: WallpaperRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var wallpaperChangeLock: WallpaperChangeLock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var wallpaperManager: WallpaperManager
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val TAG = "WallpaperChangeService"
        const val ACTION_CHANGE_WALLPAPER = Constants.ACTION_CHANGE_WALLPAPER
        const val ACTION_REAPPLY_EFFECTS = Constants.ACTION_REAPPLY_EFFECTS
        const val ACTION_CHANGE_ON_UNLOCK = Constants.ACTION_CHANGE_ON_UNLOCK
        const val EXTRA_SCREEN_TYPE = Constants.EXTRA_SCREEN_TYPE
        private const val ERROR_NOTIFICATION_ID = Constants.NOTIFICATION_ID + 1
    }

    override fun onCreate() {
        super.onCreate()
        wallpaperManager = WallpaperManager.getInstance(this)
        notificationManager = getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("NotificationManager not available")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground service. Android 14+ requires specifying the service type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constants.NOTIFICATION_ID, createNotification())
        }

        when (intent?.action) {
            ACTION_CHANGE_WALLPAPER -> {
                val screenType = intent.getStringExtra(EXTRA_SCREEN_TYPE)?.let {
                    ScreenType.fromString(it)
                } ?: ScreenType.BOTH
                handleChangeWallpaper(screenType, startId)
            }
            ACTION_REAPPLY_EFFECTS -> {
                val screenType = intent.getStringExtra(EXTRA_SCREEN_TYPE)?.let {
                    ScreenType.fromString(it)
                } ?: ScreenType.BOTH
                handleReapplyEffects(screenType, startId)
            }
            ACTION_CHANGE_ON_UNLOCK -> {
                handleChangeOnUnlock(startId)
            }
            else -> {
                // Unknown or null action — stop immediately to avoid a stuck foreground service
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun handleChangeWallpaper(screenType: ScreenType, startId: Int) {
        serviceScope.launch {
            // Use mutex to prevent concurrent wallpaper changes
            wallpaperChangeLock.mutex.withLock {
                try {
                    // Get schedule settings to determine which albums to use
                    val settings = settingsRepository.getScheduleSettings()

                when (screenType) {
                    ScreenType.LIVE -> {
                        // Live wallpaper changes are handled by the live wallpaper service
                        Log.d(TAG, "Live wallpaper - no action needed in service")
                    }
                    ScreenType.HOME -> {
                        val homeAlbumId = settings.homeAlbumId
                        if (homeAlbumId != null) {
                            changeHomeWallpaper(homeAlbumId)
                        } else {
                            Log.w(TAG, "No home album selected")
                        }
                    }
                    ScreenType.LOCK -> {
                        val lockAlbumId = settings.lockAlbumId
                        if (lockAlbumId != null) {
                            changeLockWallpaper(lockAlbumId)
                        } else {
                            Log.w(TAG, "No lock album selected")
                        }
                    }
                    ScreenType.BOTH -> {
                        val homeAlbumId = settings.homeAlbumId
                        val lockAlbumId = settings.lockAlbumId

                        // If same album and not separately scheduled, use same wallpaper
                        if (homeAlbumId != null && lockAlbumId != null &&
                            homeAlbumId == lockAlbumId && !settings.separateSchedules) {
                            // Get one wallpaper and set for both screens
                            val result = changeWallpaperUseCase(homeAlbumId, ScreenType.HOME)
                            result.onSuccess { bitmap ->
                                try {
                                    // Validate bitmap before setting
                                    if (bitmap.width <= 0 || bitmap.height <= 0) {
                                        throw IllegalStateException("Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}")
                                    }
                                    if (bitmap.isRecycled) {
                                        throw IllegalStateException("Bitmap has been recycled")
                                    }

                                    // Set for home screen (rendered at parallax canvas size)
                                    wallpaperManager.setBitmap(
                                        bitmap,
                                        null,
                                        true,
                                        WallpaperManager.FLAG_SYSTEM
                                    )
                                    Log.d(TAG, "Home wallpaper set in BOTH mode")

                                    // Keep LOCK queue in sync with HOME so that if the user later
                                    // switches to separate schedules, both screens continue from
                                    // the same queue position rather than LOCK restarting at 0.
                                    try {
                                        val homeCurrentId = wallpaperRepository
                                            .getCurrentWallpaper(homeAlbumId, ScreenType.HOME)?.id
                                        if (homeCurrentId != null) {
                                            // Ensure LOCK queue exists (first run in synced mode)
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
                                        Log.w(TAG, "Failed to sync LOCK queue in BOTH mode", e)
                                    }

                                } catch (e: Exception) {
                                    Log.e(TAG, "Error setting wallpaper for both screens", e)
                                } finally {
                                    // Recycle HOME bitmap BEFORE rendering LOCK to avoid
                                    // holding both in memory (~48MB + ~16MB).
                                    bitmap.recycle()
                                }

                                // Render a separate bitmap for the lock screen at physical
                                // screen dimensions. The HOME bitmap was sized for the
                                // launcher's parallax canvas which is typically wider than
                                // the screen; reusing it for LOCK would let Android
                                // center-crop it, defeating FIT/NONE scaling modes.
                                val lockResult = reapplyEffectsUseCase(homeAlbumId, ScreenType.LOCK)
                                lockResult.onSuccess { lockBitmap ->
                                    try {
                                        wallpaperManager.setBitmap(
                                            lockBitmap, null, true, WallpaperManager.FLAG_LOCK
                                        )
                                        Log.d(TAG, "Lock wallpaper set separately in BOTH mode")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error setting lock wallpaper in BOTH mode", e)
                                    } finally {
                                        lockBitmap.recycle()
                                    }
                                }.onError { error ->
                                    Log.w(TAG, "Lock rerender failed in BOTH mode: ${error.message}")
                                }
                            }.onError { error ->
                                if (error is EmptyAlbumException) {
                                    handleEmptyAlbumError(ScreenType.BOTH)
                                } else {
                                    Log.e(TAG, "Error getting wallpaper bitmap", error)
                                }
                            }
                        } else {
                            // Different albums or separately scheduled - change independently
                            if (homeAlbumId != null) {
                                changeHomeWallpaper(homeAlbumId)
                            } else {
                                Log.w(TAG, "No home album selected")
                            }

                            if (lockAlbumId != null) {
                                changeLockWallpaper(lockAlbumId)
                            } else {
                                Log.w(TAG, "No lock album selected")
                            }
                        }
                    }
                }

                    stopSelf(startId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error changing wallpaper", e)
                    stopSelf(startId)
                }
            }
        }
    }

    private suspend fun changeHomeWallpaper(albumId: String) {
        val result = changeWallpaperUseCase(albumId, ScreenType.HOME)
        result.onSuccess { bitmap ->
            try {
                // Validate bitmap before setting
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
                Log.d(TAG, "Home wallpaper changed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting home wallpaper", e)
            } finally {
                // Always recycle bitmap to prevent memory leaks
                bitmap.recycle()
            }
        }.onError { error ->
            if (error is EmptyAlbumException) {
                handleEmptyAlbumError(ScreenType.HOME)
            } else {
                Log.e(TAG, "Error getting home wallpaper bitmap", error)
            }
        }
    }

    private suspend fun changeLockWallpaper(albumId: String) {
        val result = changeWallpaperUseCase(albumId, ScreenType.LOCK)
        result.onSuccess { bitmap ->
            try {
                // Validate bitmap before setting
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
                Log.d(TAG, "Lock wallpaper changed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting lock wallpaper", e)
            } finally {
                // Always recycle bitmap to prevent memory leaks
                bitmap.recycle()
            }
        }.onError { error ->
            if (error is EmptyAlbumException) {
                handleEmptyAlbumError(ScreenType.LOCK)
            } else {
                Log.e(TAG, "Error getting lock wallpaper bitmap", error)
            }
        }
    }

    /**
     * Reapply current effects to the last-applied wallpaper without advancing the queue.
     * Falls back to a normal queue advance if no current wallpaper is recorded.
     */
    private fun handleReapplyEffects(screenType: ScreenType, startId: Int) {
        serviceScope.launch {
            wallpaperChangeLock.mutex.withLock {
                try {
                    val settings = settingsRepository.getScheduleSettings()
                    when (screenType) {
                        ScreenType.HOME -> {
                            val albumId = settings.homeAlbumId ?: run {
                                Log.w(TAG, "No home album selected for reapply"); return@withLock
                            }
                            reapplyHome(albumId)
                        }
                        ScreenType.LOCK -> {
                            val albumId = settings.lockAlbumId ?: run {
                                Log.w(TAG, "No lock album selected for reapply"); return@withLock
                            }
                            reapplyLock(albumId)
                        }
                        ScreenType.BOTH -> {
                            val homeAlbumId = settings.homeAlbumId
                            val lockAlbumId = settings.lockAlbumId
                            if (homeAlbumId != null) reapplyHome(homeAlbumId)
                            if (lockAlbumId != null) reapplyLock(lockAlbumId)
                        }
                        ScreenType.LIVE -> Unit // handled by live wallpaper service
                    }
                    stopSelf(startId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reapplying effects", e)
                    stopSelf(startId)
                }
            }
        }
    }

    private suspend fun reapplyHome(albumId: String) {
        val result = reapplyEffectsUseCase(albumId, ScreenType.HOME)
        result.onSuccess { bitmap ->
            try {
                if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
                    throw IllegalStateException("Invalid bitmap for reapply")
                }
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                Log.d(TAG, "Home effects reapplied successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting home wallpaper during reapply", e)
            } finally {
                bitmap.recycle()
            }
        }.onError { error ->
            // Current wallpaper not recorded yet — fall back to normal queue advance
            Log.w(TAG, "Reapply failed for home, falling back to change: ${error.message}")
            changeHomeWallpaper(albumId)
        }
    }

    private suspend fun reapplyLock(albumId: String) {
        val result = reapplyEffectsUseCase(albumId, ScreenType.LOCK)
        result.onSuccess { bitmap ->
            try {
                if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
                    throw IllegalStateException("Invalid bitmap for reapply")
                }
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                Log.d(TAG, "Lock effects reapplied successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting lock wallpaper during reapply", e)
            } finally {
                bitmap.recycle()
            }
        }.onError { error ->
            // Current wallpaper not recorded yet — fall back to normal queue advance
            Log.w(TAG, "Reapply failed for lock, falling back to change: ${error.message}")
            changeLockWallpaper(albumId)
        }
    }

    /**
     * Handle wallpaper change triggered by screen unlock (STATIC mode)
     * Checks if the homeEffects or lockEffects enableChangeOnScreenUnlock setting is enabled,
     * and if so, changes the wallpaper directly within the already-held mutex lock.
     *
     * IMPORTANT: This method does NOT call handleChangeWallpaper() because that method
     * also acquires wallpaperChangeLock.mutex, which would cause a deadlock since Kotlin's
     * Mutex is not reentrant. Instead, we inline the wallpaper change logic here.
     */
    private fun handleChangeOnUnlock(startId: Int) {
        serviceScope.launch {
            // Use tryLock with timeout instead of blocking withLock.
            // The mutex may be held by a WorkManager job which could take 30-60 seconds.
            // We don't want to block the unlock event for that long.
            // Try to acquire the lock within 5 seconds; if not available, skip this unlock.
            val lockAcquired = withTimeoutOrNull(5000L) {
                wallpaperChangeLock.mutex.lock()
                true
            } ?: false

            if (!lockAcquired) {
                Log.w(TAG, "Screen unlock - could not acquire mutex within 5s, skipping this unlock")
                stopSelf(startId)
                return@launch
            }

            try {
                val settings = settingsRepository.getScheduleSettings()

                // Check if change on screen unlock is enabled for any screen
                val homeUnlockEnabled = settings.homeEffects.enableChangeOnScreenUnlock
                val lockUnlockEnabled = settings.lockEffects.enableChangeOnScreenUnlock

                if (!homeUnlockEnabled && !lockUnlockEnabled) {
                    Log.d(TAG, "Change on screen unlock not enabled, skipping")
                    stopSelf(startId)
                    return@launch
                }

                Log.d(TAG, "Screen unlock - changing wallpaper (static mode) home=$homeUnlockEnabled lock=$lockUnlockEnabled")

                // Determine the screen type based on which unlock effects are enabled
                val screenType = when {
                    homeUnlockEnabled && lockUnlockEnabled -> ScreenType.BOTH
                    homeUnlockEnabled -> ScreenType.HOME
                    lockUnlockEnabled -> ScreenType.LOCK
                    else -> {
                        Log.w(TAG, "No screen enabled for unlock change")
                        stopSelf(startId)
                        return@launch
                    }
                }

                // Inline wallpaper change logic (same as handleChangeWallpaper but without mutex)
                when (screenType) {
                    ScreenType.LIVE -> {
                        Log.d(TAG, "Live wallpaper - no action needed in service")
                    }
                    ScreenType.HOME -> {
                        val homeAlbumId = settings.homeAlbumId
                        if (homeAlbumId != null) {
                            changeHomeWallpaper(homeAlbumId)
                        } else {
                            Log.w(TAG, "No home album selected for unlock change")
                        }
                    }
                    ScreenType.LOCK -> {
                        val lockAlbumId = settings.lockAlbumId
                        if (lockAlbumId != null) {
                            changeLockWallpaper(lockAlbumId)
                        } else {
                            Log.w(TAG, "No lock album selected for unlock change")
                        }
                    }
                    ScreenType.BOTH -> {
                        val homeAlbumId = settings.homeAlbumId
                        val lockAlbumId = settings.lockAlbumId

                        if (homeAlbumId != null && lockAlbumId != null &&
                            homeAlbumId == lockAlbumId && !settings.separateSchedules) {
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
                                    handleEmptyAlbumError(ScreenType.BOTH)
                                } else {
                                    Log.e(TAG, "Error getting wallpaper bitmap (unlock)", error)
                                }
                            }
                        } else {
                            if (homeAlbumId != null) {
                                changeHomeWallpaper(homeAlbumId)
                            } else {
                                Log.w(TAG, "No home album selected for unlock change")
                            }
                            if (lockAlbumId != null) {
                                changeLockWallpaper(lockAlbumId)
                            } else {
                                Log.w(TAG, "No lock album selected for unlock change")
                            }
                        }
                    }
                }

                stopSelf(startId)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling change on unlock", e)
                stopSelf(startId)
            } finally {
                // Always release the mutex lock
                wallpaperChangeLock.mutex.unlock()
            }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.changing_wallpaper))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Show an error notification to the user
     */
    private fun showErrorNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    /**
     * Handle empty album error scoped to the specific screen that failed.
     * Only disables the changer globally if all active screens have empty albums.
     */
    private suspend fun handleEmptyAlbumError(screenType: ScreenType) {
        Log.w(TAG, "Album is empty for $screenType")

        val settings = settingsRepository.getScheduleSettings()

        val updatedSettings = when (screenType) {
            ScreenType.HOME -> {
                // Disable home screen; only disable changer globally if lock is also inactive
                val lockStillActive = settings.lockEnabled && settings.lockAlbumId != null
                settings.copy(
                    homeAlbumId = null,
                    enableChanger = if (lockStillActive) settings.enableChanger else false
                )
            }
            ScreenType.LOCK -> {
                // Disable lock screen; only disable changer globally if home is also inactive
                val homeStillActive = settings.homeEnabled && settings.homeAlbumId != null
                settings.copy(
                    lockAlbumId = null,
                    enableChanger = if (homeStillActive) settings.enableChanger else false
                )
            }
            ScreenType.BOTH, ScreenType.LIVE -> {
                // Both screens or live — disable entirely
                settings.copy(enableChanger = false)
            }
        }

        settingsRepository.updateScheduleSettings(updatedSettings)

        showErrorNotification(
            getString(R.string.no_wallpapers_in_album),
            getString(R.string.wallpaper_changer_disabled_empty_album)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
