package club.taptappers.telly.service

import android.content.Context
import android.util.Log
import club.taptappers.telly.data.repository.TaleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TaleRepository
) {
    companion object {
        private const val TAG = "ServiceManager"
    }

    fun startServiceIfNeeded() {
        Log.d(TAG, "Starting foreground service")
        TellyForegroundService.start(context)
    }

    fun stopService() {
        Log.d(TAG, "Stopping foreground service")
        TellyForegroundService.stop(context)
    }

    suspend fun checkAndManageService() {
        val tales = repository.getAllTales().first()
        val hasEnabledTales = tales.any { it.isEnabled }

        if (hasEnabledTales) {
            Log.d(TAG, "Has enabled tales, ensuring service is running")
            startServiceIfNeeded()
        } else {
            Log.d(TAG, "No enabled tales, stopping service")
            stopService()
        }
    }
}
