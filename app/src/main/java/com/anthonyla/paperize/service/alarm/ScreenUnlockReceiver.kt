package com.anthonyla.paperize.service.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService
import dagger.hilt.android.AndroidEntryPoint

/**
 * Broadcast receiver to change wallpaper on screen unlock (STATIC mode)
 *
 * Listens for ACTION_USER_PRESENT which fires after the user unlocks the device.
 * Starts the WallpaperChangeService with a special action to change the wallpaper
 * if the "Change on Screen Unlock" setting is enabled.
 */
@AndroidEntryPoint
class ScreenUnlockReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            Log.d(TAG, "Screen unlocked - checking wallpaper change")
            // Use goAsync to allow coroutine work
            val pendingResult = goAsync()
            try {
                // Start the WallpaperChangeService with a special action
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
