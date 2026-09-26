package com.freebuff.core.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具注册表:OpenAI 兼容序列化、说明书质量与执行前参数体检。 */
class ToolsTest {

    @Test
    fun `AgentTool 序列化为 OpenAI function 定义`() {
        val json = AgentTool(
            name = "calculator",
            description = "计算",
            params = listOf(
                AgentTool.Param("expression", "string", "算式"),
                AgentTool.Param("precision", "integer", "小数位", required = false),
            ),
        ).toJsonObject()

        assertEquals("function", json.getString("type"))
        val fn = json.getJSONObject("function")
        assertEquals("calculator", fn.getString("name"))
        assertEquals("计算", fn.getString("description"))
        val params = fn.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertTrue(params.getJSONObject("properties").has("expression"))
        // 必填参数只含 expression
        val required = params.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("expression", required.getString(0))
    }

    @Test
    fun `默认工具集包含 GitHub 搜索与基础工具`() {
        val names = DefaultTools.ALL.map { it.name }
        assertTrue(names.containsAll(
            listOf("web_search", "web_fetch", "github_search_repositories", "github_get_file", "github_get_readme", "calculator", "current_time"),
        ))
    }

    @Test
    fun `toJsonArrayString 产出可解析的 tools 数组`() {
        val arr = JSONArray(DefaultTools.toJsonArrayString())
        assertEquals(DefaultTools.ALL.size, arr.length())
        assertEquals("function", arr.getJSONObject(0).getString("type"))
        assertEquals("web_search", arr.getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun `记忆工具包含保存与检索 关闭记忆后整体移除`() {
        val names = DefaultTools.ALL.map { it.name }
        assertTrue(names.containsAll(listOf("save_memory", "memory_recall")))

        val recall = DefaultTools.ALL.first { it.name == "memory_recall" }
        assertEquals(listOf("user_id", "query", "top_k", "memory_type"), recall.params.map { it.name })
        val params = recall.toJsonObject().getJSONObject("function").getJSONObject("parameters")
        val required = params.getJSONArray("required")
        assertEquals("仅 user_id 与 query 必填", 2, required.length())
        assertEquals("user_id", required.getString(0))
        assertEquals("query", required.getString(1))

        val off = DefaultTools.forCapabilities(memory = false).map { it.name }
        assertTrue("关闭记忆后不应暴露记忆工具", off.none { it == "save_memory" || it == "memory_recall" })
        assertTrue("关闭记忆后其他工具保留", off.contains("web_search"))
    }

    @Test
    fun `每个注册工具都有中文展示名`() {
        // 设置页工具权限列表、对话工具卡都用 toolDisplayName;缺标签会退化成英文原名(两个同名的行)
        val missing = DefaultTools.ALL.map { it.name }.filter { toolDisplayName(it) == it }
        assertTrue("以下工具缺少 TOOL_LABELS 展示名: $missing", missing.isEmpty())
    }

    @Test
    fun `编排类工具有专用字形`() {
        assertEquals("✷", toolGlyph(Subagent.TOOL_NAME))
        assertEquals("✷", toolGlyph("spawn_agents"))
    }

    /* ---------------- 说明书质量(弱模型尤其吃这一套) ---------------- */

    @Test
    fun `每份说明书都有示例且示例用的是真名字`() {
        DefaultTools.ALL.forEach { t ->
            assertTrue("${t.name} 缺调用示例", t.example.startsWith(t.name + "({"))
            assertTrue("${t.name} 的说明书写得太短,没讲清做什么/何时用", t.description.length >= 20)
            assertTrue("${t.name} 的参数描述不能为空", t.params.all { it.desc.isNotBlank() })
            assertTrue(
                "${t.name} 有非法参数类型",
                t.params.all { it.type in setOf("string", "integer", "boolean") },
            )
        }
    }

    @Test
    fun `闭集与上下界写进 schema`() {
        val save = DefaultTools.find("save_memory")!!
        assertEquals(
            "记忆块名是可枚举的闭集",
            listOf("persona", "user", "project"),
            save.params.first { it.name == "block" }.enum,
        )
        val blockSchema = save.toJsonObject().getJSONObject("function")
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("block")
        assertEquals(3, blockSchema.getJSONArray("enum").length())

        val limit = DefaultTools.find("github_search_repositories")!!.params.first { it.name == "limit" }
        assertEquals(1, limit.min)
        assertEquals(10, limit.max)
        assertEquals("5", limit.default)
    }

    @Test
    fun `说明书体积有预算 —— 每轮都会随请求注入`() {
        val json = DefaultTools.toJsonArrayString()
        assertTrue("说明书里要带示例行", json.contains("例: "))
        assertTrue("工具说明书过大(${json.length} 字符),8k 上下文下会挤掉对话", json.length <= 12000)
    }

    @Test
    fun `参数体检拦得住弱模型的常见错法`() {
        val getFile = DefaultTools.find("github_get_file")!!

        // 1) 别名归一:弱模型常把 path 写成 file
        val (aliased, p1) = DefaultTools.prepare(
            getFile,
            JSONObject("{\"owner\":\"CodebuffAI\",\"repo\":\"freebuff\",\"file\":\"README.md\"}"),
        )
        assertTrue("别名应该被接受:$p1", p1.isEmpty())
        assertEquals("README.md", aliased.getString("path"))

        // 2) 缺必填
        val (_, p2) = DefaultTools.prepare(getFile, JSONObject("{\"owner\":\"a\",\"repo\":\"b\"}"))
        assertTrue("缺 path 要说出来", p2.any { it is ToolErrors.ArgProblem.Missing && it.name == "path" })

        // 3) 自造参数名(填了 path 也多加一个 schema 里没有的)
        val (_, p3) = DefaultTools.prepare(
            getFile,
            JSONObject("{\"owner\":\"a\",\"repo\":\"b\",\"path\":\"README.md\",\"mode\":\"fast\"}"),
        )
        assertTrue("自造参数要报出来", p3.any { it is ToolErrors.ArgProblem.Unknown && it.name == "mode" })

        // 4) 类型与上下界:字符串数字要能收,越界/非数字要报
        val search = DefaultTools.find("github_search_repositories")!!
        val (coerced, p4) = DefaultTools.prepare(search, JSONObject("{\"q\":\"compose\",\"limit\":\"7\"}"))
        assertTrue("数字字符串应被接受:$p4", p4.isEmpty())
        assertEquals(7, coerced.getInt("limit"))
        assertTrue(
            "越界要报",
            DefaultTools.validate(search, JSONObject("{\"query\":\"x\",\"limit\":99}"))
                .any { it is ToolErrors.ArgProblem.Range },
        )
        assertTrue(
            "非数字要报类型错",
            DefaultTools.validate(search, JSONObject("{\"query\":\"x\",\"limit\":\"很多\"}"))
                .any { it is ToolErrors.ArgProblem.Type },
        )

        // 5) 枚举大小写对齐 + 非法取值
        val save = DefaultTools.find("save_memory")!!
        val (enumNorm, _) = DefaultTools.prepare(save, JSONObject("{\"block\":\"User\",\"content\":\"偏好短\"}"))
        assertEquals("user", enumNorm.getString("block"))
        assertTrue(
            "闭集外的取值要报",
            DefaultTools.validate(save, JSONObject("{\"block\":\"memory\",\"content\":\"x\"}"))
                .any { it is ToolErrors.ArgProblem.Enum },
        )
    }

    @Test
    fun `关掉记忆能力后记忆工具不再算可用`() {
        val on = DefaultTools.forCapabilities(memory = true)
        assertTrue(DefaultTools.isKnown("save_memory", on))

        val off = DefaultTools.forCapabilities(memory = false)
        assertTrue("关掉记忆后 save_memory 不该被认作可用", !DefaultTools.isKnown("save_memory", off))
        assertTrue(DefaultTools.isKnown("web_search", off))
    }
}
