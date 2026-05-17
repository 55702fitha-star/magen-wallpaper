package com.anthonyla.paperize.service.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.anthonyla.paperize.service.worker.UnlockWallpaperWorker

/**
 * Broadcast receiver to change wallpaper on screen unlock
 *
 * Listens for ACTION_USER_PRESENT which fires after the user unlocks the device.
 * Enqueues an expedited WorkManager job to change the wallpaper.
 *
 * IMPORTANT: We use WorkManager instead of a foreground service because:
 * - On Android 12+ (API 31+), starting a foreground service from the background
 *   throws ForegroundServiceStartNotAllowedException
 * - WorkManager expedited work requests CAN run from the background
 * - This ensures wallpaper changes work even when the app is not in the foreground
 *
 * The worker itself checks preferences to determine if the unlock feature is enabled
 * and stops immediately if not, avoiding unnecessary work.
 */
class ScreenUnlockReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
        private const val UNLOCK_WORK_NAME = "unlock_wallpaper_change_immediate"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            Log.d(TAG, "Screen unlocked - enqueuing wallpaper change work")

            try {
                // Use an expedited OneTimeWorkRequest which can run even when
                // the app is in the background (unlike foreground services on Android 12+)
                val unlockWork = OneTimeWorkRequestBuilder<UnlockWallpaperWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        UNLOCK_WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        unlockWork
                    )

                Log.d(TAG, "Unlock wallpaper change work enqueued successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error enqueuing unlock wallpaper work", e)
            }
        }
    }
}
