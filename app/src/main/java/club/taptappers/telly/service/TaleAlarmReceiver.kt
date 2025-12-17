package club.taptappers.telly.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TaleAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaleAlarmReceiver"
    }

    @Inject
    lateinit var taleExecutor: TaleExecutor

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Alarm received: ${intent.action}")

        when (intent.action) {
            TellyForegroundService.ACTION_EXECUTE_TALE -> {
                val taleId = intent.getStringExtra(TellyForegroundService.EXTRA_TALE_ID)
                if (taleId != null) {
                    executeTaleWithWakeLock(context, taleId)
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // Restart service after device reboot
                Log.d(TAG, "Device rebooted, starting service")
                TellyForegroundService.start(context)
            }
        }
    }

    private fun executeTaleWithWakeLock(context: Context, taleId: String) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Telly::TaleExecution"
        )

        // Acquire wake lock to ensure execution completes
        wakeLock.acquire(5 * 60 * 1000L) // 5 minutes max

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Executing tale: $taleId")
                taleExecutor.execute(taleId)

                // Reschedule via service
                TellyForegroundService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing tale $taleId", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}
