package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具参数宽容解析:空串、栅栏、尾逗号、单引号、夹带文字、数组、不可修复。 */
class JsonArgsTest {

    private fun ok(raw: String?): org.json.JSONObject =
        (JsonArgs.parse(raw) as JsonArgs.Result.Ok).obj

    @Test
    fun `空参数归一为空对象`() {
        assertEquals("{}", ok("").toString())
        assertEquals("{}", ok("   ").toString())
        assertEquals("{}", ok(null).toString())
    }

    @Test
    fun `正常 JSON 原样解析不标记修复`() {
        val r = JsonArgs.parse("""{"query":"安卓"}""") as JsonArgs.Result.Ok
        assertEquals("安卓", r.obj.optString("query"))
        assertFalse(r.repaired)
    }

    @Test
    fun `markdown 栅栏被剥掉`() {
        val r = JsonArgs.parse("```json\n{\"query\": \"x\"}\n```") as JsonArgs.Result.Ok
        assertEquals("x", r.obj.optString("query"))
        assertTrue(r.repaired)
    }

    @Test
    fun `前后夹带说明文字时截取最外层对象`() {
        val obj = ok("好的,参数是:{\"expression\":\"1+1\"} 以上")
        assertEquals("1+1", obj.optString("expression"))
    }

    @Test
    fun `尾逗号与智能引号被修掉`() {
        assertEquals("1", ok("{\"expression\":\"1\",}").optString("expression"))
        assertEquals("1", ok("{\u201cexpression\u201d:\u201c1\u201d}").optString("expression"))
    }

    @Test
    fun `单引号 JSON 被转成双引号`() {
        assertEquals("2+2", ok("{'expression': '2+2'}").optString("expression"))
    }

    @Test
    fun `顶层数组包装成 items`() {
        val obj = ok("[{\"q\":1}]")
        assertEquals(1, obj.getJSONArray("items").length())
    }

    @Test
    fun `完全不是 JSON 时给出可回传的诊断`() {
        val r = JsonArgs.parse("表达式是 1+1")
        assertTrue(r is JsonArgs.Result.Invalid)
        assertTrue((r as JsonArgs.Result.Invalid).raw.contains("1+1"))
        assertTrue(r.reason.isNotBlank())
    }
}
