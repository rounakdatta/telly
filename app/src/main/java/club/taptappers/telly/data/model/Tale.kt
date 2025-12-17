package club.taptappers.telly.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class ActionType {
    TIME
}

enum class ScheduleType {
    ONCE,
    INTERVAL,
    DAILY_AT
}

@Entity(tableName = "tales")
data class Tale(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val actionType: ActionType = ActionType.TIME,
    val scheduleType: ScheduleType,
    val scheduleValue: String? = null, // interval in ms, or HH:mm for daily
    val webhookUrl: String? = null, // HTTP endpoint to POST results to
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null
)
