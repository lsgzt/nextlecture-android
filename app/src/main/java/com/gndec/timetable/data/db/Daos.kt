package com.gndec.timetable.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LectureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LectureEntity>)

    @Query("DELETE FROM lectures")
    suspend fun deleteAll()

    @Query("SELECT * FROM lectures WHERE groupName = :g ORDER BY dayOfWeek, startMinutes")
    fun observeForGroup(g: String): Flow<List<LectureEntity>>

    @Query("SELECT * FROM lectures WHERE groupName = :g ORDER BY dayOfWeek, startMinutes")
    suspend fun getForGroup(g: String): List<LectureEntity>

    @Query("SELECT * FROM lectures WHERE groupName = :g AND dayOfWeek = :d AND startMinutes = :s LIMIT 1")
    suspend fun findOne(g: String, d: Int, s: Int): LectureEntity?

    @Query("SELECT COUNT(*) FROM lectures WHERE groupName = :g")
    suspend fun countForGroup(g: String): Int

    @Query("SELECT COUNT(*) FROM lectures")
    suspend fun countAll(): Int

    @Query("SELECT DISTINCT groupName FROM lectures ORDER BY groupName")
    suspend fun distinctGroups(): List<String>
}

@Dao
interface MetaDao {
    @Query("SELECT * FROM timetable_meta WHERE id = 1")
    fun observe(): Flow<TimetableMetaEntity?>

    @Query("SELECT * FROM timetable_meta WHERE id = 1")
    suspend fun get(): TimetableMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: TimetableMetaEntity)
}

@Dao
interface AiCacheDao {
    @Query("SELECT * FROM ai_cache WHERE rawHash = :h")
    suspend fun get(h: String): AiCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: AiCacheEntity)
}

@Dao
interface AlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(items: List<ScheduledAlarmEntity>)

    @Query("SELECT * FROM scheduled_alarms")
    suspend fun getAll(): List<ScheduledAlarmEntity>

    @Query("SELECT COUNT(*) FROM scheduled_alarms WHERE epochMillis > :now")
    suspend fun countFuture(now: Long): Int

    @Query("DELETE FROM scheduled_alarms WHERE requestCode = :code")
    suspend fun delete(code: Int)

    @Query("DELETE FROM scheduled_alarms")
    suspend fun clear()
}


@Dao
interface SyllabusChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SyllabusChatSessionEntity)

    @Query("SELECT * FROM syllabus_chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<SyllabusChatSessionEntity>>

    @Query("SELECT * FROM syllabus_chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): SyllabusChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: SyllabusChatMessageEntity)

    @Query("SELECT * FROM syllabus_chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun observeMessages(sessionId: String): Flow<List<SyllabusChatMessageEntity>>

    @Query("SELECT * FROM syllabus_chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessages(sessionId: String): List<SyllabusChatMessageEntity>

    @Query("UPDATE syllabus_chat_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, title: String, updatedAt: Long)

    @Query("DELETE FROM syllabus_chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM syllabus_chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
