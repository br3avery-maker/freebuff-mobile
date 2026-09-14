package com.freebuff.core.data.network

import com.freebuff.core.model.RepoItem
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 设备流挑战:用户在浏览器输入 [userCode] 完成授权。 */
data class DeviceChallenge(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSec: Int,
    val expiresInSec: Int,
)

/** 设备流单次轮询结果。 */
sealed interface DevicePoll {
    data class Granted(val accessToken: String) : DevicePoll
    data object Pending : DevicePoll
    data class SlowDown(val intervalSec: Int) : DevicePoll
    data class Denied(val reason: String) : DevicePoll
}

/** GitHub 账号。 */
data class GitAccount(val login: String, val name: String)

/**
 * GitHub REST 接入:OAuth 设备流 + 账号/仓库只读查询。
 *
 * 选设备流而非回调授权的原因:无需自定义 scheme 回调与后端中转小服务,
 * 只需一枚 OAuth App 的 client_id,适合纯客户端原型/早期版本。
 *
 * 端点可在构造时覆盖(单测指向 MockWebServer)。
 *
 * 通过 [com.freebuff.core.data.di.DataModule] 提供单例;测试可直接构造并指向 MockWebServer。
 */
class GithubApi(
    private val client: OkHttpClient,
    private val webBase: String = GITHUB_WEB,
    private val apiBase: String = GITHUB_API,
) {
    /** 1) 申请设备码与用户码。 */
    suspend fun startDeviceFlow(
        clientId: String,
        scope: String = "repo read:user",
    ): ApiResult<DeviceChallenge> = apiCallIo {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        val req = Request.Builder()
            .url("$webBase/login/device/code")
            .header("Accept", "application/json")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .post(form)
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val o = JSONObject(text)
            val deviceCode = o.optString("device_code")
            val userCode = o.optString("user_code")
            if (deviceCode.isBlank() || userCode.isBlank()) throw ApiError.Parse("设备码响应缺少字段")
            DeviceChallenge(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = o.optString("verification_uri").ifBlank { "$webBase/login/device" },
                intervalSec = o.optInt("interval", 5).coerceAtLeast(1),
                expiresInSec = o.optInt("expires_in", 900).coerceAtLeast(30),
            )
        }
    }

    /** 2) 轮询换取 access_token。 */
    suspend fun pollToken(clientId: String, deviceCode: String): ApiResult<DevicePoll> = apiCallIo {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder()
            .url("$webBase/login/oauth/access_token")
            .header("Accept", "application/json")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .post(form)
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val o = JSONObject(text)
            val token = o.optString("access_token")
            if (token.isNotBlank()) {
                DevicePoll.Granted(token)
            } else {
                when (val err = o.optString("error")) {
                    "authorization_pending" -> DevicePoll.Pending
                    "slow_down" -> DevicePoll.SlowDown(o.optInt("interval", 10).coerceAtLeast(1))
                    "expired_token" -> DevicePoll.Denied("设备码已过期")
                    "access_denied" -> DevicePoll.Denied("授权被拒绝")
                    "" -> throw ApiError.Parse("授权响应缺少 access_token")
                    else -> DevicePoll.Denied(err)
                }
            }
        }
    }

    /** 3) 查询当前账号。 */
    suspend fun currentUser(token: String): ApiResult<GitAccount> = apiCallIo {
        val req = apiRequest("$apiBase/user", token).get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val o = JSONObject(text)
            val login = o.optString("login")
            if (login.isBlank()) throw ApiError.Parse("用户信息缺少 login")
            GitAccount(login = login, name = o.optString("name").ifBlank { login })
        }
    }

    /** 4) 读取账号仓库(按最近更新排序,单页 100)。 */
    suspend fun repos(token: String, perPage: Int = 100): ApiResult<List<RepoItem>> = apiCallIo {
        val url = "$apiBase/user/repos?per_page=$perPage&sort=updated&affiliation=owner,collaborator,organization_member"
        val req = apiRequest(url, token).get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val full = o.optString("full_name")
                if (full.isBlank()) return@mapNotNull null
                RepoItem(
                    name = full,
                    branch = o.optString("default_branch").ifBlank { "main" },
                    desc = o.optString("description").ifBlank {
                        if (o.optBoolean("private")) "私有仓库" else "公开仓库"
                    },
                )
            }
        }
    }

    private fun apiRequest(url: String, token: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", FreebuffApi.USER_AGENT)
        .header("Authorization", "Bearer $token")

    companion object {
        const val GITHUB_WEB = "https://github.com"
        const val GITHUB_API = "https://api.github.com"
    }
}
