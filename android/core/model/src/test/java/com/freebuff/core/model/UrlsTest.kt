package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对齐 custom-model-spec.md §4 的 URL 规范化规则。 */
class UrlsTest {

    // ---------- normEndpoint ----------
    @Test
    fun `normEndpoint 补全 https`() {
        assertEquals("https://api.example.com", normEndpoint("api.example.com"))
        assertEquals("https://api.example.com/v1", normEndpoint("api.example.com/v1"))
    }

    @Test
    fun `normEndpoint 已有协议原样返回`() {
        assertEquals("http://api.example.com/v1", normEndpoint("http://api.example.com/v1"))
        assertEquals("https://api.example.com/v1", normEndpoint("https://api.example.com/v1"))
    }

    @Test
    fun `normEndpoint 空串与空白返回空`() {
        assertEquals("", normEndpoint(""))
        assertEquals("", normEndpoint("   "))
    }

    // ---------- endpointUrl ----------
    @Test
    fun `endpointUrl 非 chat 路径自动拼接`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            endpointUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun `endpointUrl 去除尾部斜杠再拼接`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            endpointUrl("https://api.example.com/v1/"),
        )
    }

    @Test
    fun `endpointUrl 已含 chat completions 原样返回`() {
        val base = "https://api.example.com/chat/completions"
        assertEquals(base, endpointUrl(base))
    }

    @Test
    fun `endpointUrl 检测已含时大小写不敏感且不改写输入`() {
        // 检测时忽略大小写(不重复拼接),返回时保留用户输入原样
        val upper = "https://api.example.com/CHAT/COMPLETIONS"
        assertEquals(upper, endpointUrl(upper))
    }

    @Test
    fun `endpointUrl 空串返回空`() {
        assertEquals("", endpointUrl(""))
    }

    // ---------- normRepoUrl ----------
    @Test
    fun `normRepoUrl 无协议补 https`() {
        assertEquals("https://github.com/foo/bar", normRepoUrl("github.com/foo/bar"))
    }

    @Test
    fun `normRepoUrl http 或 SSH 原样`() {
        assertEquals("https://github.com/foo/bar", normRepoUrl("https://github.com/foo/bar"))
        assertEquals("git@github.com:foo/bar.git", normRepoUrl("git@github.com:foo/bar.git"))
    }

    @Test
    fun `normRepoUrl 空返回空`() {
        assertEquals("", normRepoUrl(""))
    }

    // ---------- parseClone ----------
    @Test
    fun `parseClone 解析 git clone 命令`() {
        val c = parseClone("git clone https://github.com/foo/bar.git")
        assertNotNull(c)
        assertEquals("github.com", c!!.host)
        assertEquals("foo/bar", c.path)
        assertEquals("bar", c.repo)
        assertEquals("", c.branch)
        assertEquals("https", c.scheme)
    }

    @Test
    fun `parseClone 解析短横线分支参数`() {
        val c = parseClone("git clone -b dev https://github.com/foo/bar.git")
        assertEquals("dev", c!!.branch)
    }

    @Test
    fun `parseClone 解析等号分支参数`() {
        val c = parseClone("git clone --branch=dev https://github.com/foo/bar.git")
        assertEquals("dev", c!!.branch)
    }

    @Test
    fun `parseClone 分支参数可在 URL 之后`() {
        val c = parseClone("git clone https://github.com/a/b.git -b main")
        assertEquals("main", c!!.branch)
    }

    @Test
    fun `parseClone 解析 tree 深链分支`() {
        val c = parseClone("https://github.com/foo/bar/tree/main")
        assertNotNull(c)
        assertEquals("main", c!!.branch)
        assertEquals("bar", c.repo)
    }

    @Test
    fun `parseClone 解析带 fragment 的分支`() {
        val c = parseClone("https://github.com/foo/bar#main")
        assertEquals("main", c!!.branch)
    }

    @Test
    fun `parseClone 解析 SSH 地址`() {
        val c = parseClone("git@github.com:foo/bar.git")
        assertNotNull(c)
        assertEquals("github.com", c!!.host)
        assertEquals("foo/bar", c.path)
        assertEquals("", c.scheme)
        assertEquals("bar", c.repo)
    }

    @Test
    fun `parseClone 去除 git 后缀`() {
        val c = parseClone("https://github.com/foo/bar.git")
        assertEquals("bar", c!!.repo)
    }

    @Test
    fun `parseClone 忽略查询参数`() {
        val c = parseClone("https://github.com/foo/bar.git?ref=main")
        assertNotNull(c)
        assertEquals("bar", c!!.repo)
    }

    @Test
    fun `parseClone 非法输入返回 null`() {
        assertNull(parseClone(""))
        assertNull(parseClone("https://github.com/foo"))
        assertNull(parseClone("not a url"))
    }

    // ---------- maskKey ----------
    @Test
    fun `maskKey 脱敏保留前后缀`() {
        assertEquals("sk…5678", maskKey("sk-abcdefgh12345678"))
    }

    @Test
    fun `maskKey 过短一律遮罩`() {
        assertEquals("••••", maskKey("short"))
        assertEquals("••••", maskKey("12345678"))
    }

    // ---------- modelsUrl ----------
    @Test
    fun `modelsUrl base 含 v1 时直接补 models`() {
        assertEquals("https://api.example.com/v1/models", modelsUrl("https://api.example.com/v1"))
        assertEquals("https://api.example.com/v1/models", modelsUrl("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/v1/models", modelsUrl("api.example.com/v1"))
    }

    @Test
    fun `modelsUrl 其余情况取 origin 补 v1 models`() {
        assertEquals("https://api.example.com/v1/models", modelsUrl("https://api.example.com"))
        assertEquals("https://api.example.com/v1/models", modelsUrl("https://api.example.com/relay"))
        assertEquals("http://127.0.0.1:8080/v1/models", modelsUrl("http://127.0.0.1:8080/v1"))
    }

    @Test
    fun `modelsUrl 剥离完整 chat 路径`() {
        assertEquals(
            "https://api.example.com/v1/models",
            modelsUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `modelsUrl 空串返回空`() {
        assertEquals("", modelsUrl(""))
    }

    // ---------- 组合示例 ----------
    @Test
    fun `规范化的完整链路`() {
        // 中转站完整路径原样直达,不重复拼接
        val full = "https://a.com/v1/chat/completions"
        assertEquals(full, endpointUrl(normEndpoint(full)))
        assertTrue(endpointUrl(full).endsWith("/chat/completions"))
    }
}
