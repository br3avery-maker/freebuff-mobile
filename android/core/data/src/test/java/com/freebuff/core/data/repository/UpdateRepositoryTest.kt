package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 远程版本检查:解析版本 JSON、未配置与失败分类。 */
class UpdateRepositoryTest {
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

    private fun repo(url: String) = UpdateRepository(url, OkHttpClient())


    @Test
    fun `解析远端版本与更新说明`() = runTest {
        server.enqueue(MockResponse().setBody("""{"version":"0.3.0","notes":["修复 A","新增 B"]}"""))
        val v = (repo(server.url("/update.json").toString()).check() as ApiResult.Ok).data
        assertEquals("0.3.0", v.version)
        assertEquals(listOf("修复 A", "新增 B"), v.notes)
        assertTrue(v.isNewerThan("0.2.0"))
    }

    @Test
    fun `notes 缺失时为空列表`() = runTest {
        server.enqueue(MockResponse().setBody("""{"version":"0.3.0"}"""))
        val v = (repo(server.url("/u").toString()).check() as ApiResult.Ok).data
        assertEquals("0.3.0", v.version)
        assertEquals(emptyList<String>(), v.notes)
    }

    @Test
    fun `未配置更新地址返回 NotConfigured`() = runTest {
        val r = repo("").check()
        assertTrue(r is ApiResult.Err)
        assertTrue((r as ApiResult.Err).error is ApiError.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `非成功响应归类 HTTP 错误并保留状态码`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val e = (repo(server.url("/u").toString()).check() as ApiResult.Err).error
        assertTrue(e is ApiError.Http)
        assertEquals(500, (e as ApiError.Http).code)
    }

    @Test
    fun `响应不是 JSON 时归类 Parse`() = runTest {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        assertTrue((repo(server.url("/u").toString()).check() as ApiResult.Err).error is ApiError.Parse)
    }

    @Test
    fun `缺少 version 字段时归类 Parse`() = runTest {
        server.enqueue(MockResponse().setBody("""{"notes":["a"]}"""))
        assertTrue((repo(server.url("/u").toString()).check() as ApiResult.Err).error is ApiError.Parse)
    }

    @Test
    fun `解析下载地址 url 字段`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"version":"0.3.0","url":"https://example.com/releases/latest"}"""),
        )
        val v = (repo(server.url("/u").toString()).check() as ApiResult.Ok).data
        assertEquals("https://example.com/releases/latest", v.url)
    }

    @Test
    fun `sourceLabel 取主机名,未配置为空,无法解析时回退原文`() {
        assertEquals(
            "raw.example.com",
            UpdateRepository("https://raw.example.com/main/dist/update.json", OkHttpClient()).sourceLabel,
        )
        assertEquals("", UpdateRepository("", OkHttpClient()).sourceLabel)
        assertEquals("not a url", UpdateRepository("not a url", OkHttpClient()).sourceLabel)
    }

    @Test
    fun `远端版本不低于当前时无更新`() = runTest {
        server.enqueue(MockResponse().setBody("""{"version":"0.1.0"}"""))
        val v = (repo(server.url("/u").toString()).check() as ApiResult.Ok).data
        assertEquals("0.1.0", v.version)
        assertTrue(!v.isNewerThan("0.1.0"))
    }
}
