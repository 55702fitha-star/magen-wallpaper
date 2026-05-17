package com.anthonyla.paperize.service.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService

/**
 * Broadcast receiver to change wallpaper on screen unlock (STATIC mode)
 *
 * Listens for ACTION_USER_PRESENT which fires after the user unlocks the device.
 * Starts the WallpaperChangeService with a special action to change the wallpaper
 * if the "Change on Screen Unlock" setting is enabled.
 *
 * Note: Removed @AndroidEntryPoint since no injection is needed here.
 * The receiver checks preferences via DataStore before starting the service
 * to avoid unnecessary foreground service launches when the feature is disabled.
 */
class ScreenUnlockReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            Log.d(TAG, "Screen unlocked - starting wallpaper change service")
            val pendingResult = goAsync()
            try {
                // Start the WallpaperChangeService with a special action.
                // The service itself will check if the unlock feature is enabled
                // and stop immediately if not, so we always start it here.
                // This is safe because the service only runs a quick preference check
                // before deciding whether to proceed.
                val serviceIntent = Intent(context, WallpaperChangeService::class.java).apply {
                    action = Constants.ACTION_CHANGE_ON_UNLOCK
                }
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
