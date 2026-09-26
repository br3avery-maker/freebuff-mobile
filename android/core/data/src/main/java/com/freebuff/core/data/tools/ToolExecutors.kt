package com.freebuff.core.data.tools

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.FreebuffApi
import com.freebuff.core.model.AgentLoop
import com.freebuff.core.model.JsonArgs
import com.freebuff.core.model.ToolCallReq
import com.freebuff.core.model.ToolOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端侧工具执行器:把模型的 function calling 分派到真实实现。
 *
 * 执行环境隔离:HTTP 用共享 OkHttp,全部通过 [withContext] 移出主线程;
 * 任何异常归一化为 [ToolOutcome] 错误文案(模型收到的是可读错误,可自行决定重试或换路)。
 */
@Singleton
class ToolExecutors @Inject constructor(
    private val client: OkHttpClient,
) {
    private val io: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * 单次执行入口。未知工具 → 错误结果;执行异常 → 错误结果(绝不抛向调用方)。
     *
     * 参数先过一层宽容解析([JsonArgs],整个链路只做这一次):
     * - 空参数(Anthropic 经兼容层对无参工具发 `""`)归一为 `{}`,否则 `JSONObject("")` 抛异常、
     *   无参工具永远执行不了(vercel/ai#6687);
     * - 栅栏/尾逗号/单引号等手写瑕疵自动修复;
     * - 实在修不好:回一条「参数不是合法 JSON」给模型(可自愈重试),而不是报个看不懂的异常。
     */
    suspend fun execute(call: ToolCallReq): ToolOutcome = withContext(io) {
        val args = when (val r = JsonArgs.parse(call.argsJson)) {
            is JsonArgs.Result.Ok -> r.obj.toString()
            is JsonArgs.Result.Invalid ->
                return@withContext ToolOutcome(
                    "参数不是合法 JSON(工具 " + call.name + "):" + r.reason +
                        "。收到的是:" + r.raw.take(200) + "。请修正后重试。",
                    isError = true,
                )
        }
        try {
            when (call.name) {
                "web_search" -> webSearch(args)
                "web_fetch" -> webFetch(args)
                "github_search_repositories" -> ghSearchRepos(args)
                "github_get_file" -> ghGetFile(args)
                "github_get_readme" -> ghGetReadme(args)
                "calculator" -> calculator(args)
                "current_time" -> ToolOutcome(currentTime())
                AgentLoop.COMPLETION_TOOL -> completion(args)
                else -> ToolOutcome("未知工具:" + call.name + "。可用工具见系统提示。", isError = true)
            }
        } catch (e: ApiError) {
            ToolOutcome("工具执行失败:" + e.userMessage, isError = true)
        } catch (e: Exception) {
            ToolOutcome("工具执行失败:" + (e.message ?: e::class.java.simpleName), isError = true)
        }
    }

    /**
     * 任务完成声明:模型用它在目标达成后结束本轮循环(Cline 的 attempt_completion 同构)。
     * 这里不调外部能力,只把 summary 作为执行结果回填,让卡片与协议消息都有据可查。
     */
    private fun completion(argsJson: String): ToolOutcome {
        val summary = try {
            JSONObject(argsJson.ifBlank { "{}" }).optString("summary")
        } catch (e: Exception) {
            ""
        }
        return ToolOutcome(if (summary.isBlank()) "任务已标记完成。" else "任务已标记完成:" + summary.take(500))
    }

    /* ---------------- 联网搜索(免钥:DuckDuckGo Instant Answer API) ---------------- */

    private fun webSearch(argsJson: String): ToolOutcome {
        val q = JSONObject(argsJson).optString("query").trim()
        if (q.isEmpty()) return ToolOutcome("缺少参数 query", isError = true)
        val req = Request.Builder()
            .url("https://api.duckduckgo.com/?q=" + urlEncode(q) + "&format=json&no_html=1&skip_disambig=1")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(200))
            val o = JSONObject(text)
            val sb = StringBuilder()
            o.optString("AbstractText").takeIf { it.isNotBlank() }?.let { sb.append(it) }
            val related = o.optJSONArray("RelatedTopics") ?: JSONArray()
            var n = 0
            for (i in 0 until related.length()) {
                if (n >= 5) break
                val t = related.optJSONObject(i) ?: continue
                val txt = t.optString("Text")
                val url = t.optString("FirstURL")
                if (txt.isBlank() || url.isBlank()) continue
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(n + 1).append(". ").append(txt.take(160)).append("  ").append(url)
                n++
            }
            if (sb.isEmpty()) sb.append("未找到「").append(q).append("」的结果,可换关键词或用 github/web_fetch 工具补充。")
            return ToolOutcome(sb.toString())
        }
    }

    /* ---------------- 网页抓取 ---------------- */

    private fun webFetch(argsJson: String): ToolOutcome {
        val url = JSONObject(argsJson).optString("url").trim()
        if (url.isEmpty()) return ToolOutcome("缺少参数 url", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolOutcome("url 需以 http(s):// 开头", isError = true)
        }
        val req = Request.Builder().url(url)
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .get().build()
        val body = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiError.Http(r.code, r.body?.string()?.take(200).orEmpty())
            r.body?.string().orEmpty()
        }
        return ToolOutcome(htmlToText(body).take(4000))
    }

    /* ---------------- GitHub(匿名 REST,公开数据) ---------------- */

    private fun ghSearchRepos(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val q = a.optString("query").trim()
        if (q.isEmpty()) return ToolOutcome("缺少参数 query", isError = true)
        val limit = a.optInt("limit", 5).coerceIn(1, 10)
        val req = Request.Builder()
            .url("https://api.github.com/search/repositories?q=" + urlEncode(q) + "&per_page=" + limit)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(200))
            val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) return ToolOutcome("GitHub 未搜到「" + q + "」相关仓库。")
            val sb = StringBuilder("GitHub 仓库搜索「").append(q).append("」:\n")
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                sb.append(i + 1).append(". ").append(o.optString("full_name"))
                    .append(" ⭐").append(o.optInt("stargazers_count"))
                    .append(" · ").append(o.optString("language").ifBlank { "未知语言" })
                    .append(" · ").append(o.optString("description").take(100))
                    .append("\n")
            }
            return ToolOutcome(sb.toString().trim())
        }
    }

    private fun ghGetFile(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        val path = a.optString("path").trim()
        if (owner.isBlank() || repo.isBlank() || path.isBlank()) {
            return ToolOutcome("缺少参数 owner/repo/path", isError = true)
        }
        val ref = a.optString("ref").trim()
        val url = buildString {
            append("https://raw.githubusercontent.com/")
            append(owner).append('/').append(repo).append('/')
            if (ref.isNotBlank()) append(urlEncode(ref)) else append("HEAD")
            append('/').append(path.split("/").joinToString("/") { urlEncode(it) })
        }
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                return ToolOutcome("读取文件失败(HTTP " + r.code + "):" + owner + "/" + repo + "/" + path, isError = true)
            }
            return ToolOutcome(text.take(4000))
        }
    }

    private fun ghGetReadme(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        if (owner.isBlank() || repo.isBlank()) return ToolOutcome("缺少参数 owner/repo", isError = true)
        return ghGetFile(
            JSONObject().put("owner", owner).put("repo", repo).put("path", "README.md").toString(),
        )
    }

    /* ---------------- 本地工具 ---------------- */

    /** 四则运算:递归下降解析,避免 eval 类安全/兼容问题。 */
    private fun calculator(argsJson: String): ToolOutcome {
        val expr = JSONObject(argsJson).optString("expression").trim()
        if (expr.isEmpty()) return ToolOutcome("缺少参数 expression", isError = true)
        return try {
            val v = CalcParser(expr).parse()
            val out = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
                v.toLong().toString()
            } else {
                v.toString()
            }
            ToolOutcome(expr + " = " + out)
        } catch (e: Exception) {
            ToolOutcome("计算失败:表达式「" + expr + "」无法解析(支持 + - * / 与括号)", isError = true)
        }
    }

    private fun currentTime(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", java.util.Locale.CHINA)
            .format(java.util.Date())

    /* ---------------- 杂项 ---------------- */

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** HTML → 纯文本:去 script/style 与标签,解实体,压缩空白。 */
    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n")
        return s.trim()
    }
}

/** 四则运算递归下降解析器:expr → term → factor → number/(expr) 一遍扫描。 */
private class CalcParser(private val src: String) {
    private var i = 0

    fun parse(): Double {
        val v = expr()
        skipWs()
        if (i < src.length) throw IllegalArgumentException("多余字符:" + src.substring(i))
        return v
    }

    private fun expr(): Double {
        var v = term()
        while (true) {
            skipWs()
            when (src.getOrNull(i)) {
                '+' -> { i++; v += term() }
                '-' -> { i++; v -= term() }
                else -> return v
            }
        }
    }

    /** 乘除:除零得 Infinity/NaN,由调用方格式化输出(不抛异常,行为可预期)。 */
    private fun term(): Double {
        var v = factor()
        while (true) {
            skipWs()
            when (src.getOrNull(i)) {
                '*' -> { i++; v *= factor() }
                '/' -> { i++; v /= factor() }
                else -> return v
            }
        }
    }

    private fun factor(): Double {
        skipWs()
        when (src.getOrNull(i)) {
            '(' -> {
                i++
                val v = expr()
                skipWs()
                if (src.getOrNull(i) != ')') throw IllegalArgumentException("括号不匹配")
                i++
                return v
            }
            '+' -> { i++; return factor() }
            '-' -> { i++; return -factor() }
            else -> {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                if (start == i) throw IllegalArgumentException("缺少数字")
                return src.substring(start, i).toDouble()
            }
        }
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }
}
