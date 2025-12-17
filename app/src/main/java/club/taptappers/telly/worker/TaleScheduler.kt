package club.taptappers.telly.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaleScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    fun scheduleTale(tale: Tale) {
        if (!tale.isEnabled) {
            cancelTale(tale.id)
            return
        }

        when (tale.scheduleType) {
            ScheduleType.ONCE -> scheduleOnce(tale)
            ScheduleType.INTERVAL -> scheduleInterval(tale)
            ScheduleType.DAILY_AT -> scheduleDaily(tale)
        }
    }

    private fun scheduleOnce(tale: Tale) {
        val request = OneTimeWorkRequestBuilder<TaleWorker>()
            .setInputData(workDataOf(TaleWorker.KEY_TALE_ID to tale.id))
            .addTag(getTag(tale.id))
            .build()

        workManager.enqueueUniqueWork(
            getWorkName(tale.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun scheduleInterval(tale: Tale) {
        val intervalMs = tale.scheduleValue?.toLongOrNull() ?: return

        // WorkManager minimum interval is 15 minutes
        // For shorter intervals, we use a chained approach
        if (intervalMs < 15 * 60 * 1000) {
            scheduleShortInterval(tale, intervalMs)
            return
        }

        val request = PeriodicWorkRequestBuilder<TaleWorker>(
            intervalMs, TimeUnit.MILLISECONDS
        )
            .setInputData(workDataOf(TaleWorker.KEY_TALE_ID to tale.id))
            .addTag(getTag(tale.id))
            .build()

        workManager.enqueueUniquePeriodicWork(
            getWorkName(tale.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun scheduleShortInterval(tale: Tale, intervalMs: Long) {
        // For intervals less than 15 min, run immediately then reschedule
        val request = OneTimeWorkRequestBuilder<TaleWorker>()
            .setInputData(workDataOf(
                TaleWorker.KEY_TALE_ID to tale.id,
                KEY_RESCHEDULE_INTERVAL to intervalMs
            ))
            .addTag(getTag(tale.id))
            .build()

        workManager.enqueueUniqueWork(
            getWorkName(tale.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun scheduleNextShortInterval(taleId: String, intervalMs: Long) {
        val request = OneTimeWorkRequestBuilder<TaleWorker>()
            .setInputData(workDataOf(
                TaleWorker.KEY_TALE_ID to taleId,
                KEY_RESCHEDULE_INTERVAL to intervalMs
            ))
            .setInitialDelay(intervalMs, TimeUnit.MILLISECONDS)
            .addTag(getTag(taleId))
            .build()

        workManager.enqueueUniqueWork(
            getWorkName(taleId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun scheduleDaily(tale: Tale) {
        val timeStr = tale.scheduleValue ?: return
        val parts = timeStr.split(":")
        if (parts.size != 2) return

        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (before(now)) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val delayMs = target.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<TaleWorker>(
            24, TimeUnit.HOURS
        )
            .setInputData(workDataOf(TaleWorker.KEY_TALE_ID to tale.id))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(getTag(tale.id))
            .build()

        workManager.enqueueUniquePeriodicWork(
            getWorkName(tale.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelTale(taleId: String) {
        workManager.cancelUniqueWork(getWorkName(taleId))
    }

    fun runTaleNow(tale: Tale) {
        val request = OneTimeWorkRequestBuilder<TaleWorker>()
            .setInputData(workDataOf(TaleWorker.KEY_TALE_ID to tale.id))
            .addTag(getTag(tale.id))
            .build()

        workManager.enqueue(request)
    }

    private fun getWorkName(taleId: String) = "tale_$taleId"
    private fun getTag(taleId: String) = "tale_tag_$taleId"

    companion object {
        const val KEY_RESCHEDULE_INTERVAL = "reschedule_interval"
    }
}
