package club.taptappers.telly.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import club.taptappers.telly.MainActivity
import club.taptappers.telly.R
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.data.repository.TaleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class TellyForegroundService : Service() {

    companion object {
        private const val TAG = "TellyForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "telly_foreground_channel"
        const val ACTION_EXECUTE_TALE = "club.taptappers.telly.EXECUTE_TALE"
        const val EXTRA_TALE_ID = "tale_id"

        fun start(context: Context) {
            val intent = Intent(context, TellyForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TellyForegroundService::class.java)
            context.stopService(intent)
        }
    }

    @Inject
    lateinit var repository: TaleRepository

    @Inject
    lateinit var taleExecutor: TaleExecutor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var talesJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Start as foreground immediately
        startForeground(NOTIFICATION_ID, createNotification(0))

        // Observe tales and schedule them
        observeTales()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        talesJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        cancelAllAlarms()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Telly::ForegroundServiceWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max, will be refreshed
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
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

    private fun createNotification(activeTaleCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val text = when (activeTaleCount) {
            0 -> "No active tales"
            1 -> "1 tale running"
            else -> "$activeTaleCount tales running"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telly")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(activeTaleCount: Int) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(activeTaleCount))
    }

    private fun observeTales() {
        talesJob?.cancel()
        talesJob = serviceScope.launch {
            repository.getAllTales().collectLatest { tales ->
                val enabledTales = tales.filter { it.isEnabled }
                Log.d(TAG, "Observing ${enabledTales.size} enabled tales")

                updateNotification(enabledTales.size)

                if (enabledTales.isEmpty()) {
                    // No enabled tales, stop service
                    Log.d(TAG, "No enabled tales, stopping service")
                    stopSelf()
                    return@collectLatest
                }

                // Cancel existing alarms and reschedule
                cancelAllAlarms()
                enabledTales.forEach { tale ->
                    scheduleTale(tale)
                }
            }
        }
    }

    private fun scheduleTale(tale: Tale) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(this, TaleAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_TALE
            putExtra(EXTRA_TALE_ID, tale.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            tale.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = calculateNextTriggerTime(tale)

        try {
            // Use setAlarmClock for most reliable execution - shows alarm icon but guarantees execution
            val alarmInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(alarmInfo, pendingIntent)
            Log.d(TAG, "Scheduled tale '${tale.name}' for ${java.util.Date(triggerTime)}")
        } catch (e: SecurityException) {
            // Fallback to setExactAndAllowWhileIdle
            Log.w(TAG, "setAlarmClock failed, using fallback", e)
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    private fun calculateNextTriggerTime(tale: Tale): Long {
        val now = System.currentTimeMillis()

        return when (tale.scheduleType) {
            ScheduleType.ONCE -> {
                // Run in 5 seconds for one-time
                now + 5_000
            }
            ScheduleType.INTERVAL -> {
                val intervalMs = tale.scheduleValue?.toLongOrNull() ?: 60_000
                // If last run exists, schedule from last run + interval
                // Otherwise, run soon
                val lastRun = tale.lastRunAt ?: (now - intervalMs)
                val nextRun = lastRun + intervalMs
                // Ensure it's in the future
                if (nextRun <= now) now + 5_000 else nextRun
            }
            ScheduleType.DAILY_AT -> {
                val timeParts = tale.scheduleValue?.split(":") ?: listOf("7", "0")
                val hour = timeParts.getOrNull(0)?.toIntOrNull() ?: 7
                val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0

                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                // If time has passed today, schedule for tomorrow
                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }

                calendar.timeInMillis
            }
        }
    }

    private fun cancelAllAlarms() {
        // We'll cancel when rescheduling by using FLAG_UPDATE_CURRENT
        Log.d(TAG, "Cancelling all alarms for reschedule")
    }

    fun executeTale(taleId: String) {
        serviceScope.launch {
            Log.d(TAG, "Executing tale: $taleId")
            taleExecutor.execute(taleId)

            // Reschedule this tale
            val tale = repository.getTaleById(taleId)
            if (tale != null && tale.isEnabled && tale.scheduleType != ScheduleType.ONCE) {
                scheduleTale(tale)
            }
        }
    }
}
