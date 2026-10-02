package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.HttpTarget
import com.freebuff.core.data.network.apiCallIo
import com.freebuff.core.data.network.silentRetry
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用内更新:把发布页的 APK 下到本地并**校验指纹**。
 *
 * 为什么不让用户自己点「前往下载」了事:那是把「下没下全、下的是不是官方包」的判断题
 * 丢给用户。这里下完对 sha256,不符就丢弃重来,只把校验过的包交出去。
 *
 * 几点约定:
 * - 先写 `xxx.apk.part`,校验通过才改名成 `.apk` —— 中途失败不会留下一个看似完整的半截包
 * - 瞬时失败(超时/连接/5xx)由 [silentRetry] 静默重试,每次重试都从头下(不做断点续传:
 *   续传要处理服务端 Range 语义,收益远不及实现风险)
 * - 指纹不符**不重试**:那更可能是清单或产物出了问题,继续重下只会让用户白等
 *
 * @param client 复用应用共享的 OkHttp(代理/超时设置一致)
 */
@Singleton
class UpdateDownloader @Inject constructor(
    private val client: OkHttpClient,
) {

    /**
     * 下载 [url] 到 [dest] 并校验 sha256。
     *
     * @param sha256 期望的小写十六进制指纹;为空表示不校验(清单缺 apk 块时的兜底路径)
     * @param onProgress (已下载字节, 总字节);总字节为 0 表示服务端没给 Content-Length
     * @return 成功时是落盘的 [dest]
     */
    suspend fun download(
        url: String,
        sha256: String = "",
        dest: File,
        attempts: Int = 3,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): ApiResult<File> = silentRetry(attempts) { attempt(url, sha256, dest, onProgress) }

    private suspend fun attempt(
        url: String,
        sha256: String,
        dest: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): ApiResult<File> = apiCallIo {
        dest.parentFile?.mkdirs()
        // 上一轮失败留下的半成品必须清掉:它既不是有效包,也会让进度从旧字节续着显示
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "freebuff-mobile")
            .get()
            .build()
        val actual = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiError.Http(
                    response.code,
                    response.body?.string().orEmpty().take(300),
                    HttpTarget.UpdateSource,
                )
            }
            val body = response.body ?: throw ApiError.Parse("Download response has no content")
            val total = body.contentLength().coerceAtLeast(0L)
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
            }
            digest.digest().toHex()
        }
        if (sha256.isNotBlank() && !actual.equals(sha256, ignoreCase = true)) {
            part.delete()
            throw ApiError.ChecksumFailed(sha256, actual)
        }
        if (dest.exists() && !dest.delete()) {
            throw ApiError.Unknown(IllegalStateException("Could not replace the existing APK: " + dest.name))
        }
        // 改名是原子的:要么是校验过的完整包,要么什么都没有
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        dest
    }
}

/** 字节 → 小写十六进制。不用 HexFormat:它是 Java 17 的,Android 上要 API 34+。 */
private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
