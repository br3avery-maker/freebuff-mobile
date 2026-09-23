package com.freebuff.core.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具注册表:OpenAI 兼容序列化与默认工具集完整性。 */
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
}
