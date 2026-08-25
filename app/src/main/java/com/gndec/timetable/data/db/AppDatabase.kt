package com.gndec.timetable.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LectureEntity::class,
        TimetableMetaEntity::class,
        AiCacheEntity::class,
        ScheduledAlarmEntity::class,
        SyllabusChatSessionEntity::class,
        SyllabusChatMessageEntity::class,
        TimetableSnapshotEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lectureDao(): LectureDao
    abstract fun metaDao(): MetaDao
    abstract fun aiCacheDao(): AiCacheDao
    abstract fun alarmDao(): AlarmDao
    abstract fun syllabusChatDao(): SyllabusChatDao
    abstract fun timetableSnapshotDao(): TimetableSnapshotDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS timetable_snapshots (
                        id TEXT NOT NULL PRIMARY KEY,
                        groupName TEXT NOT NULL,
                        attendanceDate TEXT NOT NULL,
                        dayOfWeek INTEGER NOT NULL,
                        startMinutes INTEGER NOT NULL,
                        endMinutes INTEGER NOT NULL,
                        subject TEXT,
                        teacher TEXT,
                        venue TEXT,
                        lectureType TEXT,
                        rawText TEXT NOT NULL,
                        fetchId INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timetable_snapshots_groupName_attendanceDate ON timetable_snapshots(groupName, attendanceDate)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timetable_snapshots_attendanceDate ON timetable_snapshots(attendanceDate)")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gndec_timetable.db"
                ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
