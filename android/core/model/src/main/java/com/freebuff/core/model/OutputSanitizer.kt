package com.freebuff.core.model

/**
 * 模型输出的收尾清洗(纯函数,便于单测)。
 *
 * 为什么要它:自建/中转端点常常把 chat 模板的**特殊控制词**当普通文本漏进正文
 * (实测:一段正常科普回答最后多出一个 `<|eos|>`)。这类词对用户毫无意义,
 * 却是复制正文/落库/回传上下文时的噪声。
 *
 * 只清理「管道包裹」形式(`<|xxx|>`)—— 它们是各家模板的保留词,不会出现在正常
 * 文本或代码里;而 `</s>`、`<s>` 这种可能与正常标记混淆的形式一律不动。
 */
object OutputSanitizer {

    /** chat 模板特殊词:`<|eos|>`、`<|im_end|>`、`<|endoftext|>`、`<|eot_id|>`… */
    private val SPECIAL_TOKEN = Regex("<\\|[A-Za-z0-9_]{1,32}\\|>")

    /** 去掉特殊控制词,并把因此留下的多余空白/空行收干净。 */
    fun clean(text: String): String {
        if (text.isEmpty() || !text.contains("<|")) return text
        var s = SPECIAL_TOKEN.replace(text, "")
        // 行尾被删后留下的空格、以及连续空行,收起一格(不改变正常排版)
        s = s.replace(Regex("[ \\t]+\\n"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return if (s != text) s.trimEnd() else s
    }
}
