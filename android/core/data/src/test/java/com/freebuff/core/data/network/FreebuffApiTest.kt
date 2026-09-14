package com.freebuff.core.data.network

import com.freebuff.core.model.OFFICIAL_MODELS
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 官方网关目录拉取:请求路径、解析、错误分类。 */
class FreebuffApiTest {
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

    private fun api(base: String) = FreebuffApi(base, OkHttpClient())

    @Test
    fun `未配置网关时返回 NotConfigured 且不发请求`() = runTest {
        val r = api("").fetchOfficialModels()
        assertTrue(r is ApiResult.Err)
        assertTrue((r as ApiResult.Err).error is ApiError.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `base 含 v1 时请求 v1 models 并解析 data 数组`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"id":"deepseek-v4-flash"},{"id":"glm-5.3-flash"},{"id":"brand-new-model"}]}""",
            ),
        )
        val r = api(server.url("/v1").toString()).fetchOfficialModels()
        val models = (r as ApiResult.Ok).data
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals(3, models.size)
        // 已知 id 复用内置元数据,未知 id 自动生成
        assertEquals(OFFICIAL_MODELS.first { it.id == "deepseek-v4-flash" }.name, models[0].name)
        // 未知 id 自动美化:连字符转空格、单词首字母大写
        assertEquals("Brand New Model", models[2].name)
        assertEquals("BRA", models[2].badge)
        assertEquals("brand-new-model", models[2].id)
    }

    @Test
    fun `base 不含 v1 时补全 v1 models`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"}]}"""))
        api(server.url("/gateway").toString()).fetchOfficialModels()
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun `HTTP 错误归类为 Http 并保留状态码`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val r = api(server.url("/v1").toString()).fetchOfficialModels()
        val e = (r as ApiResult.Err).error
        assertTrue(e is ApiError.Http)
        assertEquals(500, (e as ApiError.Http).code)
    }

    @Test
    fun `空模型列表归类为 Parse`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val r = api(server.url("/v1").toString()).fetchOfficialModels()
        assertTrue((r as ApiResult.Err).error is ApiError.Parse)
    }

    @Test
    fun `解析兼容 models 数组与纯数组`() {
        assertEquals(listOf("a", "b"), parseModelIds("""{"models":[{"id":"a"},{"id":"b"}]}"""))
        assertEquals(listOf("a", "b"), parseModelIds("""["a","b"]"""))
        assertEquals(listOf("a"), parseModelIds("""{"model_ids":["a"]}"""))
        assertEquals(listOf("a"), parseModelIds("""{"data":["a"]}"""))
        assertEquals(emptyList<String>(), parseModelIds("not json"))
        assertEquals(emptyList<String>(), parseModelIds(""))
    }
}
