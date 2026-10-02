package com.freebuff.core.data.repository

import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.toDomain
import com.freebuff.core.data.db.toEntity
import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.ProbeResult
import com.freebuff.core.data.network.apiCallIo
import com.freebuff.core.data.network.buildChatRequest
import com.freebuff.core.data.network.clientFor
import com.freebuff.core.data.network.toApiError
import com.freebuff.core.data.security.CryptoManager
import com.freebuff.core.model.ChatTarget
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.Probe
import com.freebuff.core.model.modelsUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义模型仓库:Room 持久化(Key 经 Keystore 加密)、真实测试连接、/v1/models 拉取。
 * 所有阻塞式 HTTP 调用统一经 [apiCallIo] 切到 IO 线程并归一化错误。
 */
@Singleton
class CustomModelRepository internal constructor(
    private val dao: FreebuffDao,
    private val crypto: CryptoManager,
    /** 阻塞式请求的执行线程。测试传入可观测 dispatcher,断言「确实切出了调用线程」。 */
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(dao: FreebuffDao, crypto: CryptoManager) : this(dao, crypto, Dispatchers.IO)
    /** 加密存储、解密展示;进入内存后为明文。 */
    private fun decryptEntity(e: com.freebuff.core.data.db.CustomModelEntity): CustomModel =
        e.toDomain().copy(key = crypto.decrypt(e.keyCipher))

    val models: Flow<List<CustomModel>> = dao.observeCustomModels()
        .map { list -> list.map { decryptEntity(it) } }
        .distinctUntilChanged()

    suspend fun all(): List<CustomModel> = dao.allCustomModels().map { decryptEntity(it) }

    suspend fun get(id: String): CustomModel? = dao.customModel(id)?.let { decryptEntity(it) }

    suspend fun add(m: CustomModel) {
        val maxSort = dao.allCustomModels().maxOfOrNull { it.sort } ?: 0L
        dao.upsertCustomModel(m.copy(key = crypto.encrypt(m.key)).toEntity(maxSort + 1))
    }

    suspend fun update(m: CustomModel) {
        val cur = dao.customModel(m.id)
        dao.upsertCustomModel(
            m.copy(key = crypto.encrypt(m.key)).toEntity(cur?.sort ?: System.currentTimeMillis()),
        )
    }

    suspend fun delete(id: String) = dao.deleteCustomModel(id)

    /** 真实测试连接:最小 body POST 端点,校验连通与鉴权,返回耗时/失败分类文案。 */
    suspend fun testConnection(m: CustomModel): ProbeResult {
        val start = System.currentTimeMillis()
        val failed = runCatchingProbe {
            val (request, client) = buildChatRequest(
                endpoint = m.base,
                model = m.apiId.ifBlank { m.name },
                apiKey = m.key,
                headers = parseHeaders(m.headers),
                messages = listOf(com.freebuff.core.data.network.ChatMessage.text("user", "ping")),
                stream = false,
                skipTLS = m.skipTLS,
            )
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) {
                    val snippet = r.body?.string()?.take(300).orEmpty()
                    throw ApiError.Http(r.code, snippet)
                }
            }
        }
        return failed ?: ProbeResult.Success((System.currentTimeMillis() - start).toInt())
    }

    /** 拉取 {origin}/v1/models 的模型 id 列表。 */
    suspend fun fetchModels(m: CustomModel): ApiResult<List<String>> {
        val url = modelsUrl(m.base)
        if (url.isEmpty()) return ApiResult.Err(ApiError.NotConfigured("Endpoint URL"))
        return apiCallIo {
            val client = clientFor(m.skipTLS)
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "freebuff-mobile")
                .get()
            if (m.key.isNotBlank()) builder.addHeader("Authorization", "Bearer ${m.key}")
            parseHeaders(m.headers).forEach { (k, v) -> if (k.isNotBlank()) builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError.Http(r.code, body.take(300))
                val arr = try {
                    JSONObject(body).optJSONArray("data")
                } catch (t: Throwable) {
                    throw ApiError.Parse("Model list is not valid JSON")
                } ?: throw ApiError.Parse("Model list is missing the data field")
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotEmpty() } }
            }
        }
    }

    /**
     * 测试连接并拉取模型快照,更新 probe 与 models 字段。
     * 连接成功但快照刷新失败时保留旧快照,并在 [ProbeResult.Success.note] 中说明。
     */
    suspend fun probe(m: CustomModel): Pair<CustomModel, ProbeResult> {
        val result = testConnection(m)
        if (result !is ProbeResult.Success) return m to result
        val fetched = fetchModels(m)
        return when (fetched) {
            is ApiResult.Ok -> {
                val ids = fetched.data
                val at = System.currentTimeMillis()
                val updated = m.copy(
                    models = if (ids.isEmpty()) m.models else ids,
                    probe = ids.associateWith { Probe(at, result.ms) },
                )
                updated to result
            }
            is ApiResult.Err -> {
                // 连接本身可用,快照未刷新:保留旧数据,仅提示原因
                m to ProbeResult.Success(result.ms, "Connected, but the model snapshot was not updated: " + fetched.error.userMessage)
            }
        }
    }

    private fun parseHeaders(headers: String): Map<String, String> {
        if (headers.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(headers)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 从端点提取 origin(scheme://host[:port])。 */
    fun originOf(endpoint: String): String {
        val e = com.freebuff.core.model.endpointUrl(endpoint)
        if (e.isEmpty()) return ""
        return try {
            val u = java.net.URI(e)
            val host = u.host ?: return ""
            val port = if (u.port != -1) ":${u.port}" else ""
            "${u.scheme}://$host$port"
        } catch (ex: Exception) {
            ""
        }
    }

    /** 解析自定义模型为完整对话目标(端点/模型 id/key/头/跳过 TLS)。 */
    fun chatTarget(m: CustomModel): ChatTarget = ChatTarget(
        endpoint = m.base,
        model = m.apiId.ifBlank { m.name },
        apiKey = m.key,
        headers = parseHeaders(m.headers),
        skipTLS = m.skipTLS,
        name = m.name,
        ctxWindow = m.ctx,
    )

    /** 解析自定义请求头 JSON → 键值。 */
    fun resolveHeaders(headers: String): Map<String, String> = parseHeaders(headers)

    /**
     * 探测包装:在 [io] 上执行阻塞请求(在 Main 上会直接抛无 message 的
     * NetworkOnMainThreadException,界面只能看到兜底的「请求失败」),
     * 失败时返回 [ProbeResult.Fail](含分类文案与状态码),成功返回 null。
     */
    private suspend inline fun runCatchingProbe(crossinline block: () -> Unit): ProbeResult.Fail? = try {
        withContext(io) { block() }
        null
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        val err = t.toApiError()
        ProbeResult.Fail(err.userMessage, (err as? ApiError.Http)?.code)
    }
}
