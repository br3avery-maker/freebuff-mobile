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
    fun sessionWithMessages(id: String): SessionWithMessages?

    @Query("SELECT * FROM sessions")
    fun allSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    fun session(id: String): SessionEntity?

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
    fun customModel(id: String): CustomModelEntity?

    @Query("SELECT * FROM custom_models")
    fun allCustomModels(): List<CustomModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomModel(model: CustomModelEntity)

    @Query("DELETE FROM custom_models WHERE id = :id")
    suspend fun deleteCustomModel(id: String)

    @Query("DELETE FROM custom_models")
    suspend fun clearCustomModels()

    // ---------- settings ----------
    @Query("SELECT * FROM settings")
    fun allSettings(): List<SettingEntity>

    @Query("SELECT * FROM settings WHERE key = :key LIMIT 1")
    fun setting(key: String): SettingEntity?

    @Query("SELECT * FROM settings WHERE key = :key LIMIT 1")
    fun observeSetting(key: String): Flow<SettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun deleteSetting(key: String)

    @Query("DELETE FROM settings")
    suspend fun clearSettings()
}
