package club.taptappers.telly.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "tale_logs",
    foreignKeys = [
        ForeignKey(
            entity = Tale::class,
            parentColumns = ["id"],
            childColumns = ["taleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taleId"])]
)
data class TaleLog(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val taleId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val result: String,
    val success: Boolean = true
)
