package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.FreebuffApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 官方目录:只来自官方网关,未配置/失败时保持为空(不回退内置)。 */
class ModelCatalogRepositoryTest {
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

    private fun repo(base: String) = ModelCatalogRepository(FreebuffApi(base, OkHttpClient()))

    @Test
    fun `初始目录为空且未加载`() {
        val r = repo("")
        assertTrue(r.official.value.isEmpty())
        assertFalse(r.loaded.value)
        assertNull(r.lastError.value)
    }

    @Test
    fun `网关未配置时刷新不发请求且不报错`() = runTest {
        val r = repo("")
        r.refreshIfNeeded()
        assertTrue(!r.isGatewayConfigured)
        assertEquals(0, server.requestCount)
        assertNull(r.lastError.value)
        assertTrue(r.official.value.isEmpty())
        assertFalse(r.loaded.value)
    }

    @Test
    fun `拉取成功后替换为网关目录并标记已加载`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-5.6-luna"},{"id":"new-hotness"}]}"""))
        val r = repo(server.url("/v1").toString())
        val res = r.refresh()
        assertTrue(res is ApiResult.Ok)
        assertEquals(2, r.official.value.size)
        assertEquals("gpt-5.6-luna", r.official.value[0].id)
        assertTrue(r.loaded.value)
        assertNull(r.lastError.value)
    }

    @Test
    fun `拉取失败保持空目录并记录错误`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("maintenance"))
        val r = repo(server.url("/v1").toString())
        val res = r.refresh()
        assertTrue(res is ApiResult.Err)
        assertTrue(r.official.value.isEmpty())
        assertFalse(r.loaded.value)
        assertNotNull(r.lastError.value)
        assertTrue(!r.lastError.value!!.userMessage.isBlank())
    }

    @Test
    fun `拉取失败不清空上一次成功的目录`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-5.6-luna"}]}"""))
        val r = repo(server.url("/v1").toString())
        r.refresh()
        server.enqueue(MockResponse().setResponseCode(503).setBody("maintenance"))
        r.refresh()

        assertEquals(listOf("gpt-5.6-luna"), r.official.value.map { it.id })
        assertTrue(r.loaded.value)
        assertNotNull(r.lastError.value)
    }

    @Test
    fun `自动刷新只尝试一次`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"}]}"""))
        val r = repo(server.url("/v1").toString())
        r.refreshIfNeeded()
        r.refreshIfNeeded()
        assertEquals(1, server.requestCount)
    }
}
