package club.taptappers.telly.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint

/**
 * Receiver for handling boot completed events.
 * The main scheduling is now handled by the foreground service's internal timer.
 */
@AndroidEntryPoint
class TaleAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaleAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Broadcast received: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // Restart service after device reboot
                Log.d(TAG, "Device rebooted, starting service")
                TellyForegroundService.start(context)
            }
        }
    }
}
