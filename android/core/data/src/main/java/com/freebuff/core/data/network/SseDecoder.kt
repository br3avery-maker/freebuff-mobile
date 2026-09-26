package com.freebuff.core.data.network

/**
 * SSE 帧解码器(有状态),把字节流切成「一个完整事件的 data 载荷」。
 *
 * 为什么不能按行直接解析(openai-python 的 SSEDecoder 就是按帧做的):
 * - SSE 规范允许**一个事件跨多行 data**(用换行拼接后才是完整 JSON)。工具参数很长时,
 *   网关(尤其是自建反代/网关套娃)会把一个 JSON 拆到多条 `data:` 行 —— 按行解析会每行都
 *   JSON 失败、整体静默丢失。openai-python 里明确写着「不到空行不产出事件,因为事件可能跨多个 data 行」。
 * - **末尾事件没有空行收尾**时也必须产出(拉取结束时 flush):pi#9047 就是漏了这一步导致
 *   response.completed 这类收尾事件被静默丢掉,表现是「流完了但什么都没收到」。
 * - `:` 开头是注释/心跳(很多网关靠它保活)、`event:` / `id:` / `retry:` 是非数据字段,都要忽略;
 *   `data:` 后**一个**空格按规范去掉(多余空格属于载荷)。
 * - 兼容 NDJSON:部分端点(Ollama 原生、直发 JSON 的网关)不带 `data:` 前缀,整行就是一个 JSON 对象 ——
 *   这种行直接当载荷,不能被忽略。
 *
 * 用法:逐行 [feed],流结束时调用一次 [flush]。
 */
class SseDecoder {

    private val buf = StringBuilder()
    private var hasData = false

    /** 累积缓冲上限(防异常服务端把内存吃光);超限即丢弃并重置。 */
    private val maxBytes = 4 * 1024 * 1024

    /**
     * 喂入一行(**不含换行符**)。
     * @return 本次能确定完整的事件载荷(通常 0 或 1 个;NDJSON 与跨行混流时可能 2 个)
     */
    fun feed(line: String): List<String> {
        val l = line.trimEnd('\r', '\n')
        // 空行 = 事件边界(SSE 规范)
        if (l.isBlank()) return emit()
        // 注释行/心跳
        if (l.startsWith(":")) return emptyList()
        if (l.startsWith("data:")) {
            if (hasData) buf.append('\n')   // 多行 data 用换行拼接
            hasData = true
            buf.append(l.removePrefix("data:").removePrefix(" "))
            if (buf.length > maxBytes) { buf.setLength(0); hasData = false }
            return emptyList()
        }
        if (l.startsWith("event:") || l.startsWith("id:") || l.startsWith("retry:")) return emptyList()
        // NDJSON / 裸 JSON:整行就是一个载荷(先吐出已累积的,再把本行单独产出)
        val out = emit().toMutableList()
        out += l
        return out
    }

    /** 流结束:产出末尾未以空行收尾的事件。 */
    fun flush(): List<String> = emit()

    private fun emit(): List<String> {
        if (!hasData) return emptyList()
        // 只去掉结尾换行拼接带来的空白,payload 自身不做 trim(按规范:data 后仅去掉一个空格)
        val payload = buf.toString().trimEnd('\n', '\r')
        buf.setLength(0)
        hasData = false
        return if (payload.isBlank()) emptyList() else listOf(payload)
    }
}
