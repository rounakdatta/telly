package club.taptappers.telly.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import club.taptappers.telly.MainActivity
import club.taptappers.telly.R
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.data.repository.TaleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TellyForegroundService : Service() {

    companion object {
        private const val TAG = "TellyService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "telly_foreground_channel"

        // Check interval - how often we check if tales need to run
        private const val CHECK_INTERVAL_MS = 10_000L // 10 seconds

        // Minimum execution gap to avoid duplicate runs
        private const val MIN_EXECUTION_GAP_MS = 5_000L // 5 seconds

        fun start(context: Context) {
            Log.d(TAG, "Starting service")
            val intent = Intent(context, TellyForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            Log.d(TAG, "Stopping service")
            val intent = Intent(context, TellyForegroundService::class.java)
            context.stopService(intent)
        }
    }

    @Inject
    lateinit var repository: TaleRepository

    @Inject
    lateinit var taleExecutor: TaleExecutor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    // Track last execution times to prevent duplicate runs
    private val lastExecutionTimes = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        // Acquire wake lock immediately
        acquireWakeLock()

        // Start as foreground immediately with initial notification
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Start the scheduler loop
        startSchedulerLoop()

        // Return STICKY to ensure restart if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopSchedulerLoop()
        serviceScope.cancel()
        releaseWakeLock()
        showToast("Telly service stopped")
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Telly::ServiceWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire() // Indefinite - released when service stops
            }
            Log.d(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Telly Background Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Telly running for scheduled tasks"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(status: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telly")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private val schedulerRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Scheduler tick")
            serviceScope.launch(Dispatchers.IO) {
                try {
                    checkAndExecuteTales()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in scheduler", e)
                }
            }
            // Schedule next check - Handler.postDelayed is more reliable than coroutine delay
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    private fun startSchedulerLoop() {
        Log.d(TAG, "Scheduler loop started")
        showToast("Telly scheduler started")
        // Remove any existing callbacks and start fresh
        handler.removeCallbacks(schedulerRunnable)
        handler.post(schedulerRunnable)
    }

    private fun stopSchedulerLoop() {
        handler.removeCallbacks(schedulerRunnable)
    }

    private suspend fun checkAndExecuteTales() {
        val tales = repository.getAllTales().first()
        val enabledTales = tales.filter { it.isEnabled }

        if (enabledTales.isEmpty()) {
            Log.d(TAG, "No enabled tales, stopping service")
            handler.post {
                updateNotification("No active tales")
                stopSelf()
            }
            return
        }

        val now = System.currentTimeMillis()
        var executedCount = 0
        val statusParts = mutableListOf<String>()

        for (tale in enabledTales) {
            val shouldRun = shouldTaleRun(tale, now)

            if (shouldRun) {
                // Check if we recently executed this tale (prevent duplicates)
                val lastExec = lastExecutionTimes[tale.id] ?: 0L
                if (now - lastExec < MIN_EXECUTION_GAP_MS) {
                    Log.d(TAG, "Skipping ${tale.name} - executed ${now - lastExec}ms ago")
                    continue
                }

                Log.d(TAG, "Executing tale: ${tale.name}")
                lastExecutionTimes[tale.id] = now

                try {
                    val success = taleExecutor.execute(tale.id)
                    if (success) {
                        executedCount++
                        handler.post {
                            showToast("Executed: ${tale.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing ${tale.name}", e)
                }
            }

            // Build status for notification
            val nextRun = calculateNextRunTime(tale, now)
            if (nextRun != null) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextRun))
                statusParts.add("${tale.name}: $timeStr")
            }
        }

        // Update notification with next run times
        val status = if (statusParts.isEmpty()) {
            "${enabledTales.size} tales active"
        } else {
            statusParts.take(2).joinToString(" | ")
        }

        handler.post {
            updateNotification(status)
        }
    }

    private fun shouldTaleRun(tale: Tale, now: Long): Boolean {
        return when (tale.scheduleType) {
            ScheduleType.ONCE -> {
                // Run once if never run before
                tale.lastRunAt == null
            }
            ScheduleType.INTERVAL -> {
                val intervalMs = tale.scheduleValue?.toLongOrNull() ?: return false
                val lastRun = tale.lastRunAt ?: 0L
                val elapsed = now - lastRun

                // Should run if interval has elapsed
                elapsed >= intervalMs
            }
            ScheduleType.DAILY_AT -> {
                val timeParts = tale.scheduleValue?.split(":") ?: return false
                val targetHour = timeParts.getOrNull(0)?.toIntOrNull() ?: return false
                val targetMinute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0

                val calendar = Calendar.getInstance()
                val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
                val currentMinute = calendar.get(Calendar.MINUTE)

                // Check if we're within the target minute
                val isTargetTime = currentHour == targetHour && currentMinute == targetMinute

                if (!isTargetTime) return false

                // Check if already run today
                val lastRun = tale.lastRunAt ?: 0L
                val lastRunCalendar = Calendar.getInstance().apply { timeInMillis = lastRun }
                val today = Calendar.getInstance()

                val alreadyRanToday = lastRunCalendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                        lastRunCalendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

                !alreadyRanToday
            }
        }
    }

    private fun calculateNextRunTime(tale: Tale, now: Long): Long? {
        return when (tale.scheduleType) {
            ScheduleType.ONCE -> {
                if (tale.lastRunAt == null) now else null
            }
            ScheduleType.INTERVAL -> {
                val intervalMs = tale.scheduleValue?.toLongOrNull() ?: return null
                val lastRun = tale.lastRunAt ?: now
                lastRun + intervalMs
            }
            ScheduleType.DAILY_AT -> {
                val timeParts = tale.scheduleValue?.split(":") ?: return null
                val targetHour = timeParts.getOrNull(0)?.toIntOrNull() ?: return null
                val targetMinute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0

                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, targetHour)
                    set(Calendar.MINUTE, targetMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }

                calendar.timeInMillis
            }
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(this@TellyForegroundService, message, Toast.LENGTH_SHORT).show()
        }
    }
}
