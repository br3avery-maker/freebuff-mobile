package com.freebuff.core.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** GitHub 设备流与只读查询:请求结构、响应解析、错误分类。 */
class GithubApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: GithubApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        api = GithubApi(OkHttpClient(), webBase = base, apiBase = base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `申请设备码提交 client_id 与 scope 并解析响应`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"dev-1","user_code":"AB12-CD34","verification_uri":"https://github.com/login/device","interval":5,"expires_in":900}""",
            ),
        )
        val r = api.startDeviceFlow("cid-123")
        val dc = (r as ApiResult.Ok).data
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertEquals("/login/device/code", req.path)
        assertTrue(body.contains("client_id=cid-123"))
        assertTrue(body.contains("scope=repo"))
        assertEquals("dev-1", dc.deviceCode)
        assertEquals("AB12-CD34", dc.userCode)
        assertEquals(5, dc.intervalSec)
        assertEquals(900, dc.expiresInSec)
    }

    @Test
    fun `申请设备码缺失字段归类 Parse`() = runTest {
        server.enqueue(MockResponse().setBody("""{"user_code":"AB12"}"""))
        val r = api.startDeviceFlow("cid")
        assertTrue((r as ApiResult.Err).error is ApiError.Parse)
    }

    @Test
    fun `轮询未授权返回 Pending`() = runTest {
        server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
        assertEquals(DevicePoll.Pending, (api.pollToken("cid", "dev") as ApiResult.Ok).data)
    }

    @Test
    fun `轮询被限速返回 SlowDown 与新间隔`() = runTest {
        server.enqueue(MockResponse().setBody("""{"error":"slow_down","interval":12}"""))
        val p = (api.pollToken("cid", "dev") as ApiResult.Ok).data
        assertEquals(DevicePoll.SlowDown(12), p)
    }

    @Test
    fun `轮询取到 token 返回 Granted`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"tok-1","token_type":"bearer"}"""))
        val p = (api.pollToken("cid", "dev") as ApiResult.Ok).data
        assertEquals(DevicePoll.Granted("tok-1"), p)
        assertTrue(server.takeRequest().body.readUtf8().contains("device_code=dev"))
    }

    @Test
    fun `轮询拒绝返回 Denied`() = runTest {
        server.enqueue(MockResponse().setBody("""{"error":"access_denied"}"""))
        assertEquals(DevicePoll.Denied("授权被拒绝"), (api.pollToken("cid", "dev") as ApiResult.Ok).data)
    }

    @Test
    fun `读取当前账号携带 Bearer 并解析登录名`() = runTest {
        server.enqueue(MockResponse().setBody("""{"login":"octocat","name":"The Octocat"}"""))
        val u = (api.currentUser("tok") as ApiResult.Ok).data
        val req = server.takeRequest()
        assertEquals("/user", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("octocat", u.login)
        assertEquals("The Octocat", u.name)
    }

    @Test
    fun `账号缺少名称时回退登录名`() = runTest {
        server.enqueue(MockResponse().setBody("""{"login":"octocat"}"""))
        assertEquals("octocat", (api.currentUser("tok") as ApiResult.Ok).data.name)
    }

    @Test
    fun `仓库列表解析并回退描述与分支`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[
                    {"full_name":"octocat/freebuff","default_branch":"main","description":"官方仓库"},
                    {"full_name":"octocat/secret","default_branch":"","description":"","private":true}
                ]""",
            ),
        )
        val repos = (api.repos("tok") as ApiResult.Ok).data
        assertEquals(2, repos.size)
        assertEquals("octocat/freebuff", repos[0].name)
        assertEquals("main", repos[0].branch)
        assertEquals("官方仓库", repos[0].desc)
        assertEquals("main", repos[1].branch)
        assertEquals("私有仓库", repos[1].desc)
    }

    @Test
    fun `过期 token 归类 Http 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))
        val r = api.repos("stale")
        val e = (r as ApiResult.Err).error
        assertTrue(e is ApiError.Http)
        assertEquals(401, (e as ApiError.Http).code)
    }
}
