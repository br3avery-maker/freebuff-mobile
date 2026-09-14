package com.freebuff.core.data.repository

import android.content.SharedPreferences
import com.freebuff.core.data.db.CustomModelEntity
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.data.db.toEntity
import com.freebuff.core.data.security.CryptoManager
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.RepairReport
import com.freebuff.core.model.SEED_SESSIONS
import com.freebuff.core.model.Probe
import com.freebuff.core.model.sanitizeCustomModels
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次性迁移器:把 v1 概念版的 SharedPreferences("freebuff_proto_v1") 迁移到 Room。
 * - customModels: 解析旧 JSON → sanitize 清洗 → Keystore 加密 key → 落库
 * - 设置键(themeMode/modelId/repoParse/version/signedIn/git) 平移
 * - 会话表为空时灌入 SEED_SESSIONS
 */
@Singleton
class DataMigrator @Inject constructor(
    private val dao: FreebuffDao,
    private val crypto: CryptoManager,
) {
    companion object {
        /** 一次性迁移完成标志。写入后不再重放迁移。 */
        const val KEY_MIGRATED = "migrated_v2"
    }

    /**
     * 一次性迁移,返回修复报告(仅当清洗过程修复过脏数据)。
     *
     * 已迁移过([KEY_MIGRATED] 为 true)时直接返回 null 且不触碰任何数据。
     * 这个守卫是必需的:迁移里含「会话表为空则灌入演示会话」与「用旧偏好覆写设置键」,
     * 若每次冷启动都重放,会导致清空会话后重启时演示会话复活,以及主题/模型/自定义模型
     * 被旧偏好回滚。
     */
    suspend fun migrate(legacy: SharedPreferences): RepairReport? {
        if (legacy.getBoolean(KEY_MIGRATED, false)) return null

        var report: RepairReport? = null

        // 1. 自定义模型:解析 → 清洗 → 加密 → 落库
        val legacyModels = parseLegacyCustomModels(legacy.getString("customModels", "").orEmpty())
        if (legacyModels.isNotEmpty()) {
            val (clean, rep) = sanitizeCustomModels(legacyModels)
            report = rep.takeIf { it.fixed > 0 }
            dao.clearCustomModels()
            var sort = 0L
            clean.forEach { m ->
                dao.upsertCustomModel(m.copy(key = crypto.encrypt(m.key)).toEntity(++sort))
            }
        }

        // 2. 设置键平移
        val settingKeys = listOf(
            SettingsRepository.KEY_THEME_MODE,
            SettingsRepository.KEY_MODEL_ID,
            SettingsRepository.KEY_REPO_PARSE,
            SettingsRepository.KEY_VERSION,
            SettingsRepository.KEY_SIGNED_IN,
            SettingsRepository.KEY_GIT,
        )
        settingKeys.forEach { key ->
            legacy.getString(key, null)?.let { v ->
                dao.upsertSetting(SettingEntity(key, v))
            }
        }

        // 3. 首启灌入演示会话
        if (dao.allSessions().isEmpty()) {
            seedSessions()
        }

        legacy.edit().putBoolean(KEY_MIGRATED, true).apply()
        return report
    }

    private suspend fun seedSessions() {
        var sort = 0L
        SEED_SESSIONS.forEach { session ->
            dao.upsertSession(SessionEntity(session.id, session.title, session.time, session.preview, ++sort))
            val msgs = session.messages.mapIndexed { i, m -> m.toEntity(session.id, i.toLong()) }
            dao.upsertMessages(msgs)
        }
    }

    /**
     * 解析旧版自定义模型 JSON。**保留位置**:非对象条目(字符串/数字/JSON null)映射为 null,
     * 交给 [sanitizeCustomModels] 报告为 drop——否则解析阶段提前丢掉会导致报告看不到「丢弃非对象记录」,
     * 且 rawN 统计的不是真实原始条数。
     */
    private fun parseLegacyCustomModels(s: String): List<CustomModel?> {
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: return@map null
                val ms = o.optJSONArray("models")
                val models = if (ms == null) emptyList()
                else (0 until ms.length()).mapNotNull { j -> ms.optString(j).takeIf { it.isNotEmpty() } }
                val pr = o.optJSONObject("probe")
                val probe = if (pr == null) emptyMap()
                else pr.keys().asSequence().mapNotNull { k ->
                    val p = pr.optJSONObject(k) ?: return@mapNotNull null
                    k to Probe(p.optLong("at"), p.optInt("ms"))
                }.toMap()
                CustomModel(
                    o.optString("id"), o.optString("name"), o.optString("apiId"), o.optString("base"),
                    o.optString("key"), o.optString("ctx"), o.optString("timeout"), o.optString("headers"),
                    o.optBoolean("skipTLS"), models, probe,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
