package club.taptappers.telly.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import club.taptappers.telly.data.model.ActionType
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.data.model.TaleLog

class Converters {
    @TypeConverter
    fun fromActionType(value: ActionType): String = value.name

    @TypeConverter
    fun toActionType(value: String): ActionType = ActionType.valueOf(value)

    @TypeConverter
    fun fromScheduleType(value: ScheduleType): String = value.name

    @TypeConverter
    fun toScheduleType(value: String): ScheduleType = ScheduleType.valueOf(value)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tales ADD COLUMN webhookUrl TEXT")
    }
}

@Database(
    entities = [Tale::class, TaleLog::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class TellyDatabase : RoomDatabase() {
    abstract fun taleDao(): TaleDao
    abstract fun taleLogDao(): TaleLogDao
}
