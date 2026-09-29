package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 应用内下载:进度回调、sha256 校验、瞬时失败静默重试、半成品清理。
 *
 * 这里断言的是「交给安装器之前」的每道门:校验不过的包绝不能留在盘上。
 */
class UpdateDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun payload(): String = "apk-binary-payload-".repeat(4096)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun downloader() = UpdateDownloader(OkHttpClient())

    @Test
    fun `下载成功时校验指纹并原子落盘`() = runTest {
        val body = payload()
        server.enqueue(MockResponse().setBody(body))
        val dest = File(tmp.root, "FreebuffMobile-9.9.9-release.apk")
        val seen = mutableListOf<Long>()

        val r = downloader().download(
            server.url("/a.apk").toString(),
            sha256(body),
            dest,
            attempts = 1,
        ) { received, _ -> seen += received }

        assertTrue(r is ApiResult.Ok)
        assertEquals(dest, (r as ApiResult.Ok).data)
        assertEquals(body, dest.readText())
        assertEquals(body.toByteArray().size.toLong(), seen.last())
        assertFalse(File(tmp.root, dest.name + ".part").exists())
    }

    @Test
    fun `进度回调报出服务端给的总量`() = runTest {
        val body = payload()
        server.enqueue(MockResponse().setBody(body))
        val totals = mutableListOf<Long>()

        downloader().download(
            server.url("/a.apk").toString(),
            sha256(body),
            File(tmp.root, "app.apk"),
            attempts = 1,
        ) { _, total -> totals += total }

        assertEquals(body.toByteArray().size.toLong(), totals.last())
    }

    @Test
    fun `指纹不符时丢弃文件且不重试`() = runTest {
        server.enqueue(MockResponse().setBody(payload()))
        val dest = File(tmp.root, "app.apk")

        val r = downloader().download(
            server.url("/a.apk").toString(),
            "0".repeat(64),
            dest,
            attempts = 3,
        )

        assertTrue((r as ApiResult.Err).error is ApiError.ChecksumFailed)
        assertFalse(dest.exists())
        assertFalse(File(tmp.root, "app.apk.part").exists())
        // 指纹不符属「配置/产物问题」,重下只会让用户白等
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `非 2xx 归类为更新源 HTTP 错误且不重试`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))

        val r = downloader().download(
            server.url("/a.apk").toString(),
            "a".repeat(64),
            File(tmp.root, "app.apk"),
            attempts = 3,
        )

        val e = (r as ApiResult.Err).error
        assertTrue(e is ApiError.Http)
        assertEquals(404, (e as ApiError.Http).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `瞬时 5xx 静默重试后成功`() = runTest {
        val body = payload()
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        server.enqueue(MockResponse().setBody(body))
        val dest = File(tmp.root, "app.apk")

        val r = downloader().download(
            server.url("/a.apk").toString(),
            sha256(body),
            dest,
            attempts = 3,
        )

        assertTrue(r is ApiResult.Ok)
        assertEquals(2, server.requestCount)
        assertEquals(body, dest.readText())
        assertFalse(File(tmp.root, "app.apk.part").exists())
    }

    @Test
    fun `上次失败留下的半成品会被清掉`() = runTest {
        val body = payload()
        val dest = File(tmp.root, "app.apk")
        File(tmp.root, "app.apk.part").writeText("half-written-garbage")
        server.enqueue(MockResponse().setBody(body))

        val r = downloader().download(server.url("/a.apk").toString(), sha256(body), dest, attempts = 1)

        assertTrue(r is ApiResult.Ok)
        assertEquals(body, dest.readText())
        assertFalse(File(tmp.root, "app.apk.part").exists())
    }
}
