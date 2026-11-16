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
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lectureDao(): LectureDao
    abstract fun metaDao(): MetaDao
    abstract fun aiCacheDao(): AiCacheDao
    abstract fun alarmDao(): AlarmDao
    abstract fun syllabusChatDao(): SyllabusChatDao
    abstract fun timetableSnapshotDao(): TimetableSnapshotDao

    companion object {
        /**
         * Release 2.0 added the two syllabus-chat tables but shipped no migration, so the
         * destructive fallback silently rebuilt the DB for anyone still on v1. That
         * fallback is gone now, which would turn those same users into a hard
         * "migration from 1 to 4 was required but not found" crash on launch.
         *
         * The v1 -> v2 delta is purely additive (verified against b72706b): no existing
         * table was altered, so a v1 user's cached timetable, meta, AI cache and
         * scheduled alarms all carry through untouched. Columns below mirror the v2
         * entity definitions exactly; `AUTOINCREMENT` on the message id is what Room
         * emits for `@PrimaryKey(autoGenerate = true) val id: Long`.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS syllabus_chat_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        branch TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS syllabus_chat_messages (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        sessionId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_syllabus_chat_messages_sessionId_timestamp ON syllabus_chat_messages(sessionId, timestamp)")
            }
        }

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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Additive only: indexes are built from existing rows, so no data is
                // touched and a re-run is a no-op. The name must match Room's generated
                // convention (index_<table>_<column>) or schema validation fails.
                database.execSQL("CREATE INDEX IF NOT EXISTS index_lectures_groupName ON lectures(groupName)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Additive only: a nullable column with no default, so existing rows read
                // back as NULL and no saved chat is rewritten. Messages written before this
                // version simply have no reasoning, which the UI already handles.
                database.execSQL("ALTER TABLE syllabus_chat_messages ADD COLUMN reasoning TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Additive only: FET document generation timestamp from the official footer.
                // Existing rows read back as NULL until the next successful refresh parses it.
                database.execSQL("ALTER TABLE timetable_meta ADD COLUMN generatedAtMillis INTEGER")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gndec_timetable.db"
                )
                    // No destructive fallback: a missing migration must fail loudly at
                    // startup rather than silently wiping a student's cached timetable,
                    // 366 days of attendance snapshots and their saved reminder set.
                    // Every version bump now has to ship an explicit Migration below.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
    }
}
