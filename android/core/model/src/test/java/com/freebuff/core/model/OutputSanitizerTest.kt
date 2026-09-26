package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 正文收尾清洗:chat 模板控制词要去掉,正常文本/代码/标记不能误伤。 */
class OutputSanitizerTest {

    @Test
    fun `去掉管道形式的控制词`() {
        // 实测遇到的那条:一段正常回答末尾漏出 <|eos|>
        assertEquals("黑洞是已知最著名的天体。", OutputSanitizer.clean("黑洞是已知最著名的天体。<|eos|>"))
        assertEquals("你好", OutputSanitizer.clean("<|im_start|>你好<|im_end|>"))
        assertEquals("最后一句", OutputSanitizer.clean("最后一句<|eot_id|>"))
        assertEquals("", OutputSanitizer.clean("<|eos|>"))
    }

    @Test
    fun `清掉控制词后不留多余空行`() {
        assertEquals("第一段\n\n第二段", OutputSanitizer.clean("第一段  \n<|eos|>\n\n\n\n第二段<|end|>"))
    }

    @Test
    fun `正常文本与标记不被误伤`() {
        val samples = listOf(
            "普通回答,没有任何特殊词。",
            "代码:<div class=\"a\">x</div>",
            "HTML 结束标记 </s> 保留(可能是示例代码)",
            "数学:1 < 2 && 3 > 2",
            "标记 <|not-a-token 少了右管道",
        )
        samples.forEach { assertEquals(it, OutputSanitizer.clean(it)) }
    }

    @Test
    fun `只有确实含控制词时才改动字符串`() {
        val same = "内容很长" + "x".repeat(2000)
        assertTrue(OutputSanitizer.clean(same) === same) // 未命中时原样返回(避免无谓的复制)
    }
}
