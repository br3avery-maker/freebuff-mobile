package com.freebuff.core.data.repository

import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.model.GitState
import com.freebuff.core.model.LATEST_VERSION
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 应用级键值设置(主题/当前模型/解析模式/版本/Git 状态/登录态)。 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dao: FreebuffDao,
) {
    companion object {
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_MODEL_ID = "modelId"
        const val KEY_REPO_PARSE = "repoParse"
        const val KEY_VERSION = "version"
        const val KEY_SIGNED_IN = "signedIn"
        const val KEY_GIT = "git"

        /** Git OAuth access_token(Keystore 加密后的密文)。 */
        const val KEY_GIT_TOKEN = "gitToken"
    }

    /** Git 状态:直接响应 DB 变更。 */
    val git: Flow<GitState> = dao.observeSetting(KEY_GIT)
        .map { it?.value.orEmpty() }
        .map { s ->
            if (s.isBlank()) GitState()
            else try {
                val o = JSONObject(s)
                GitState(o.optBoolean("connected"), o.optString("provider"), o.optString("login"), o.optString("name"))
            } catch (e: Exception) {
                GitState()
            }
        }
        .distinctUntilChanged()

    suspend fun setGit(g: GitState) {
        dao.upsertSetting(
            SettingEntity(
                KEY_GIT,
                JSONObject().apply {
                    put("connected", g.connected)
                    put("provider", g.provider)
                    put("login", g.login)
                    put("name", g.name)
                }.toString(),
            ),
        )
    }

    suspend fun connectGit(provider: String, login: String, name: String) =
        setGit(GitState(true, provider, login, name))

    suspend fun revokeGit() = setGit(GitState())

    /** 保存/清空 Git access_token 密文(加解密由 RealGitAuthRepository 负责)。 */
    suspend fun setGitToken(cipher: String) = setString(KEY_GIT_TOKEN, cipher)

    suspend fun getGitToken(): String = getString(KEY_GIT_TOKEN, "")

    // ---------- 简单键值 ----------
    suspend fun setString(key: String, value: String) = dao.upsertSetting(SettingEntity(key, value))

    fun getStringFlow(key: String, default: String): Flow<String> =
        dao.observeSetting(key).map { it?.value ?: default }
            .distinctUntilChanged()

    suspend fun getString(key: String, default: String): String =
        dao.setting(key)?.value ?: default

    suspend fun setBool(key: String, value: Boolean) = setString(key, value.toString())

    suspend fun getBool(key: String, default: Boolean): Boolean =
        getString(key, default.toString()).toBoolean()

    // ---------- 便捷访问 ----------
    val themeMode = getStringFlow(KEY_THEME_MODE, "dark")
    val modelId = getStringFlow(KEY_MODEL_ID, "deepseek-v4-flash")
    val repoParse = getStringFlow(KEY_REPO_PARSE, "strict")
    val version = getStringFlow(KEY_VERSION, LATEST_VERSION)
    val signedIn = getStringFlow(KEY_SIGNED_IN, "false").map { it.toBoolean() }

    suspend fun setThemeMode(mode: String) = setString(KEY_THEME_MODE, mode)
    suspend fun setModelId(id: String) = setString(KEY_MODEL_ID, id)
    suspend fun setRepoParse(mode: String) = setString(KEY_REPO_PARSE, mode)
    suspend fun setSignedIn(v: Boolean) = setBool(KEY_SIGNED_IN, v)
    suspend fun setVersion(v: String) = setString(KEY_VERSION, v)
    suspend fun applyUpdate() = setString(KEY_VERSION, LATEST_VERSION)
}
