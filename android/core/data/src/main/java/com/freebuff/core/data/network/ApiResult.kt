package com.freebuff.core.data.network

import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/* ---------------- 统一错误分类 ---------------- */

/**
 * 网络层错误分类。所有仓库层的失败都归一到这里,UI 只读 [message](已本地化、可直接展示)。
 *
 * 分层约定:
 * - 传输层失败(超时/DNS/连接/TLS)由 [toApiError] 归类
 * - HTTP 非 2xx 由调用方抛 [Http](保留状态码与响应片段)
 * - 响应可读但结构不符预期 → [Parse]
 * - 未配置端点/凭据 → [NotConfigured]
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 连接/读取超时。 */
    class Timeout(cause: Throwable? = null) : ApiError("连接超时,请检查网络或端点可达性", cause)

    /** 无法建立连接(拒绝/不可达)。 */
    class Unreachable(cause: Throwable? = null) : ApiError("无法连接到服务器,请检查端点与网络", cause)

    /** DNS 解析失败。 */
    class Dns(cause: Throwable? = null) : ApiError("无法解析主机,请检查端点地址", cause)

    /** TLS 证书校验失败。 */
    class Tls(cause: Throwable? = null) : ApiError("TLS 证书校验失败,可在自定义模型中开启「跳过 TLS 校验」", cause)

    /** HTTP 非 2xx:保留状态码与响应片段,便于定位鉴权/路径/限流问题。 */
    class Http(val code: Int, val body: String = "") : ApiError(httpErrorMessage(code))

    /** 2xx 但响应结构无法解析。 */
    class Parse(detail: String = "") :
        ApiError(if (detail.isBlank()) "响应格式无法解析" else "响应格式无法解析:$detail")

    /** 必要配置缺失(网关地址 / 更新源 / Git 授权)。 */
    class NotConfigured(what: String) : ApiError(what + "未配置")

    /** 调用方主动取消。 */
    class Cancelled : ApiError("请求已取消")

    class Unknown(cause: Throwable? = null) : ApiError(cause?.message ?: "请求失败", cause)

    /** 兼容旧调用:等价于 [message] 的非空字符串。 */
    val userMessage: String get() = message ?: "请求失败"
}

/** HTTP 状态码 → 可读文案(与产品和原型文案保持一致)。 */
fun httpErrorMessage(code: Int): String = when (code) {
    400 -> "请求无效(400):请检查模型 ID 与请求体"
    401 -> "鉴权失败(401):请检查 API Key"
    403 -> "无权限(403):API Key 无权访问该模型"
    404 -> "端点不存在(404):请确认 /chat/completions 路径"
    408 -> "服务端超时(408):请稍后再试"
    409 -> "请求冲突(409):请稍后再试"
    422 -> "参数不合法(422):请检查模型 ID 与端点"
    429 -> "请求过于频繁(429):请稍后再试"
    in 500..599 -> "服务端错误($code):请稍后再试"
    else -> "请求失败(HTTP $code)"
}

/**
 * 异常 → [ApiError]。
 * @param httpCode 已知的 HTTP 状态码(如响应已读出但被判定为失败)
 */
fun Throwable.toApiError(httpCode: Int? = null): ApiError {
    if (httpCode != null) return ApiError.Http(httpCode)
    return when (this) {
        is ApiError -> this
        is SocketTimeoutException -> ApiError.Timeout(this)
        is UnknownHostException -> ApiError.Dns(this)
        is SSLException -> ApiError.Tls(this)
        is ConnectException -> ApiError.Unreachable(this)
        is NoRouteToHostException -> ApiError.Unreachable(this)
        is CancellationException -> ApiError.Cancelled()
        else -> ApiError.Unknown(this)
    }
}

/* ---------------- 结果包装 ---------------- */

/** 仓库层统一返回:`Ok` 携带数据,`Err` 携带可展示的 [ApiError]。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.data

fun ApiResult<*>.errorOrNull(): ApiError? = (this as? ApiResult.Err)?.error

fun ApiResult<*>.errorMessageOrNull(): String? = errorOrNull()?.userMessage

val ApiResult<*>.isOk: Boolean get() = this is ApiResult.Ok

inline fun <T, R> ApiResult<T>.fold(onOk: (T) -> R, onErr: (ApiError) -> R): R = when (this) {
    is ApiResult.Ok -> onOk(data)
    is ApiResult.Err -> onErr(error)
}

/** 把 [ApiError] 包成失败结果。 */
fun <T> apiErr(error: ApiError): ApiResult<T> = ApiResult.Err(error)

/**
 * 执行一次网络调用并归一化异常。
 * 协程取消([CancellationException])不吞掉,原样抛出以保持结构化并发语义。
 */
suspend inline fun <T> apiCall(block: () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (t: Throwable) {
    if (t is CancellationException) throw t
    ApiResult.Err(t.toApiError())
}

/**
 * 阻塞式 OkHttp 调用的统一入口:切到 IO 线程执行并归一化异常。
 * 主线程直接 `execute()` 会触发 NetworkOnMainThreadException,故仓库层一律走这里。
 */
suspend fun <T> apiCallIo(block: () -> T): ApiResult<T> = try {
    ApiResult.Ok(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() })
} catch (t: Throwable) {
    if (t is CancellationException) throw t
    ApiResult.Err(t.toApiError())
}
