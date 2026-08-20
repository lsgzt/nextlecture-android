package com.gndec.timetable.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LectureEntity::class,
        TimetableMetaEntity::class,
        AiCacheEntity::class,
        ScheduledAlarmEntity::class,
        SyllabusChatSessionEntity::class,
        SyllabusChatMessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lectureDao(): LectureDao
    abstract fun metaDao(): MetaDao
    abstract fun aiCacheDao(): AiCacheDao
    abstract fun alarmDao(): AlarmDao
    abstract fun syllabusChatDao(): SyllabusChatDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gndec_timetable.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
