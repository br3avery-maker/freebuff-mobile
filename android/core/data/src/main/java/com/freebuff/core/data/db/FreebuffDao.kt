package com.freebuff.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FreebuffDao {

    // ---------- sessions ----------
    @Transaction
    @Query("SELECT * FROM sessions ORDER BY sort DESC")
    fun observeSessionsWithMessages(): Flow<List<SessionWithMessages>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun sessionWithMessages(id: String): SessionWithMessages?

    @Query("SELECT * FROM sessions")
    suspend fun allSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun session(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY sort ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    // ---------- custom models ----------
    @Query("SELECT * FROM custom_models ORDER BY sort ASC")
    fun observeCustomModels(): Flow<List<CustomModelEntity>>

    @Query("SELECT * FROM custom_models WHERE id = :id LIMIT 1")
    suspend fun customModel(id: String): CustomModelEntity?

    @Query("SELECT * FROM custom_models")
    suspend fun allCustomModels(): List<CustomModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomModel(model: CustomModelEntity)

    @Query("DELETE FROM custom_models WHERE id = :id")
    suspend fun deleteCustomModel(id: String)

    @Query("DELETE FROM custom_models")
    suspend fun clearCustomModels()

    // ---------- settings ----------
    @Query("SELECT * FROM settings")
    suspend fun allSettings(): List<SettingEntity>

    @Query("SELECT * FROM settings WHERE key = :key LIMIT 1")
    suspend fun setting(key: String): SettingEntity?

    @Query("SELECT * FROM settings WHERE key = :key LIMIT 1")
    fun observeSetting(key: String): Flow<SettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun deleteSetting(key: String)

    @Query("DELETE FROM settings")
    suspend fun clearSettings()

    // ---------- memories(Letta 式核心记忆块) ----------
    @Query("SELECT * FROM memories ORDER BY sort ASC")
    suspend fun allMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY sort ASC")
    fun observeMemories(): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE block = :block")
    suspend fun deleteMemory(block: String)

    @Query("DELETE FROM memories")
    suspend fun clearMemories()
}
