package com.telly.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.telly.data.model.ActionType
import com.telly.data.model.ScheduleType
import com.telly.data.model.Tale
import com.telly.data.model.TaleLog

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

@Database(
    entities = [Tale::class, TaleLog::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class TellyDatabase : RoomDatabase() {
    abstract fun taleDao(): TaleDao
    abstract fun taleLogDao(): TaleLogDao
}
