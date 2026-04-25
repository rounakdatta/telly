package club.taptappers.telly.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class ActionType {
    TIME,
    EMAIL_JUGGLE,
    STRAVA_LAST_HEVY,
    HEVY_LAST_WORKOUT
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
    val webhookUrl: String? = null, // legacy: kept for back-compat; new tales encode webhooks inside reactionsJson
    val searchQuery: String? = null, // Gmail search query for EMAIL_JUGGLE
    val reactionsJson: String? = null, // ordered chain of Reactions (see ReactionCodec). null = legacy fallback to webhookUrl.
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null
)
