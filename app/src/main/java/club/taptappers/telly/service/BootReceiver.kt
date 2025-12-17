package club.taptappers.telly.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import club.taptappers.telly.data.repository.TaleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    @Inject
    lateinit var repository: TaleRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "Device booted, checking for enabled tales")

            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val tales = repository.getAllTales().first()
                    val hasEnabledTales = tales.any { it.isEnabled }

                    if (hasEnabledTales) {
                        Log.d(TAG, "Found enabled tales, starting foreground service")
                        TellyForegroundService.start(context)
                    } else {
                        Log.d(TAG, "No enabled tales, not starting service")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking tales on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
