package com.telly.di

import android.content.Context
import androidx.room.Room
import com.telly.data.db.TaleDao
import com.telly.data.db.TaleLogDao
import com.telly.data.db.TellyDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TellyDatabase {
        return Room.databaseBuilder(
            context,
            TellyDatabase::class.java,
            "telly_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideTaleDao(database: TellyDatabase): TaleDao {
        return database.taleDao()
    }

    @Provides
    @Singleton
    fun provideTaleLogDao(database: TellyDatabase): TaleLogDao {
        return database.taleLogDao()
    }
}
