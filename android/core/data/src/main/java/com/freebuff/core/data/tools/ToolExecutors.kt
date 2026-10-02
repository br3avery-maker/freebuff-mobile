package com.freebuff.core.data.tools

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.FreebuffApi
import com.freebuff.core.model.AgentLoop
import com.freebuff.core.model.AgentTool
import com.freebuff.core.model.DefaultTools
import com.freebuff.core.model.JsonArgs
import com.freebuff.core.model.ToolCallReq
import com.freebuff.core.model.ToolErrors
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
     * 单次执行入口。
     *
     * 给模型的每一次回应都要带「下一步怎么改」,顺序如下:
     * 1. **工具名**必须在当前可用清单里(未注册的名字给近邻建议,见 [ToolErrors.unknownTool]);
     * 2. **宽容解析参数**([JsonArgs],整个链路只做这一次):空参数(Anthropic 经兼容层对无参工具发 `""`)
     *    归一为 `{}`(否则 `JSONObject("")` 抛异常、无参工具永远执行不了 — vercel/ai#6687);
     *    栅栏/尾逗号/单引号等手写瑕疵自动修复;修不好则回「不是合法 JSON」+ 正确调用示例;
     * 3. **参数体检 + 归一**([DefaultTools.prepare]):未知参数名 / 缺必填 / 越界 / 取值不在闭集 →
     *    有处方的信封;别名与类型顺手归一到规范形;
     * 4. 真正执行;任何异常都变成有处方的错误信封 —— 绝不抛给调用方,也绝不把堆栈丢给模型。
     *
     * @param tools 当前会话可用工具集(记忆关闭时不含记忆工具),未知名字的提示据此生成
     * @param attempt 同一工具在本轮会话里连续第几次失败(≥2 时信封劝「换路」而不是重试)
     */
    suspend fun execute(
        call: ToolCallReq,
        tools: List<AgentTool> = DefaultTools.ALL,
        attempt: Int = 1,
    ): ToolOutcome = withContext(io) {
        val tool = DefaultTools.find(call.name, tools)
            ?: return@withContext ToolOutcome(
                ToolErrors.unknownTool(call.name, DefaultTools.names(tools)),
                isError = true,
            )
        val raw = when (val r = JsonArgs.parse(call.argsJson)) {
            is JsonArgs.Result.Ok -> r.obj
            is JsonArgs.Result.Invalid -> return@withContext ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.INVALID_ARGS, tool.name,
                    "Arguments are not valid JSON: " + r.reason + "; received: " + r.raw.take(120),
                    "Retry with the documented arguments as a JSON object",
                    attempt, tool.example,
                ),
                isError = true,
            )
        }
        val (argsObj, problems) = DefaultTools.prepare(tool, raw)
        if (problems.isNotEmpty()) {
            return@withContext ToolOutcome(ToolErrors.forProblems(tool, problems, attempt), isError = true)
        }
        val args = argsObj.toString()
        val outcome = try {
            when (call.name) {
                "web_search" -> webSearch(args)
                "web_fetch" -> webFetch(args)
                "github_search_repositories" -> ghSearchRepos(args)
                "github_get_file" -> ghGetFile(args)
                "github_get_readme" -> ghGetReadme(args)
                "calculator" -> calculator(args)
                "current_time" -> ToolOutcome(currentTime())
                AgentLoop.COMPLETION_TOOL -> completion(args)
                else -> ToolOutcome(ToolErrors.unknownTool(call.name, DefaultTools.names(tools)), isError = true)
            }
        } catch (e: Exception) {
            errorFor(tool.name, e, attempt)
        }
        // 统一兜底:任何错误分支在「同一工具第 2 次失败」时都要劝换路(各分支不一定拿得到计数)
        if (outcome.isError) {
            outcome.copy(content = ToolErrors.escalateIfNeeded(outcome.content, attempt))
        } else {
            outcome
        }
    }

    /**
     * 工具失败 → 有处方的错误信封:
     * HTTP 状态码分开给下一步(401/403 别重试、404 先核对名字、429 等一会儿、5xx 可重试一次),
     * 超时与其它异常也有各自的处方;堆栈只进日志、不进模型上下文。
     */
    private fun errorFor(tool: String, e: Throwable, attempt: Int): ToolOutcome {
        // 工具特有的下一步建议:通用处方之外再给一条「怎么找对目标」
        val hint = when (tool) {
            "github_get_file", "github_get_readme" ->
                "Check owner/repo/path first. Use github_search_repositories to find the correct repository name."
            "web_fetch" -> "The link may have expired or the site may block fetching. Use another source or find a new link with web_search."
            "github_search_repositories" -> "If repository search is unavailable, use web_search to find the project's website."
            else -> ""
        }
        val envelope = when (e) {
            is ApiError.Http -> ToolErrors.http(tool, e.code, e.body.take(160), attempt, hint)
            is ApiError.Timeout -> ToolErrors.report(
                ToolErrors.Kind.NETWORK, tool, "Request timeout",
                "Retry once after a delay. If it still times out, use another source or tell the user no result was obtained.", attempt,
            )
            is ApiError.NotConfigured -> ToolErrors.report(
                ToolErrors.Kind.INTERNAL, tool, e.userMessage,
                "This feature is unavailable (missing configuration). Use another tool.", attempt,
            )
            else -> ToolErrors.internalError(
                tool,
                ((e as? ApiError)?.userMessage ?: e.message ?: e::class.java.simpleName).take(160),
                attempt,
            )
        }
        return ToolOutcome(envelope, isError = true)
    }

    /** 兜底:必填参数缺失(正常路径由 [DefaultTools.validate] 拦住,这里防执行器被单独调用)。 */
    private fun missing(tool: String, param: String): ToolOutcome = ToolOutcome(
        ToolErrors.report(
            ToolErrors.Kind.MISSING_PARAM, tool, "Required argument " + param + " is missing",
            "Add " + param + " and retry once", 1, DefaultTools.find(tool)?.example.orEmpty(),
        ),
        isError = true,
    )

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
        return ToolOutcome(if (summary.isBlank()) "Task marked complete." else "Task marked complete: " + summary.take(500))
    }

    /* ---------------- 联网搜索(免钥:DuckDuckGo Instant Answer API) ---------------- */

    private fun webSearch(argsJson: String): ToolOutcome {
        val q = JSONObject(argsJson).optString("query").trim()
        if (q.isEmpty()) return missing("web_search", "query")
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
            if (sb.isEmpty()) sb.append("No results for “").append(q).append("”. Try other keywords or github/web_fetch tools.")
            return ToolOutcome(sb.toString())
        }
    }

    /* ---------------- 网页抓取 ---------------- */

    private fun webFetch(argsJson: String): ToolOutcome {
        val url = JSONObject(argsJson).optString("url").trim()
        if (url.isEmpty()) return missing("web_fetch", "url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.BAD_VALUE, "web_fetch",
                    "url「" + url.take(80) + "” is missing a URL scheme",
                    "url must start with http:// or https://", 1,
                    "web_fetch({\"url\": \"https://example.com/post\"})",
                ),
                isError = true,
            )
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
        if (q.isEmpty()) return missing("github_search_repositories", "query")
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
            if (items.length() == 0) return ToolOutcome("No GitHub repositories found for “" + q + "”.")
            val sb = StringBuilder("GitHub repository search: “").append(q).append("」:\n")
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                sb.append(i + 1).append(". ").append(o.optString("full_name"))
                    .append(" ⭐").append(o.optInt("stargazers_count"))
                    .append(" · ").append(o.optString("language").ifBlank { "Unknown language" })
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
            val absent = listOf("owner" to owner, "repo" to repo, "path" to path)
                .filter { it.second.isBlank() }.joinToString(", ") { it.first }
            return ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.MISSING_PARAM, "github_get_file", "Required argument " + absent + " is missing",
                    "Supply owner, repo, and path separately", 1,
                    DefaultTools.find("github_get_file")?.example.orEmpty(),
                ),
                isError = true,
            )
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
                return ToolOutcome(
                    ToolErrors.http(
                        "github_get_file", r.code, "", 1,
                        hint = "Check whether " + owner + "/" + repo + "/" + path +
                            " is misspelled. Use github_search_repositories to confirm the repository name.",
                    ),
                    isError = true,
                )
            }
            return ToolOutcome(text.take(4000))
        }
    }

    private fun ghGetReadme(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        if (owner.isBlank() || repo.isBlank()) return missing("github_get_readme", "owner/repo")
        return ghGetFile(
            JSONObject().put("owner", owner).put("repo", repo).put("path", "README.md").toString(),
        )
    }

    /* ---------------- 本地工具 ---------------- */

    /** 四则运算:递归下降解析,避免 eval 类安全/兼容问题。 */
    private fun calculator(argsJson: String): ToolOutcome {
        val expr = JSONObject(argsJson).optString("expression").trim()
        if (expr.isEmpty()) return missing("calculator", "expression")
        return try {
            val v = CalcParser(expr).parse()
            val out = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
                v.toLong().toString()
            } else {
                v.toString()
            }
            ToolOutcome(expr + " = " + out)
        } catch (e: Exception) {
            ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.BAD_VALUE, "calculator",
                    "Expression “" + expr.take(60) + "” could not be parsed",
                    "Only numbers, + - * /, and parentheses are supported. Retry with a valid expression.", 1,
                    "calculator({\"expression\": \"(12+8)*3.5\"})",
                ),
                isError = true,
            )
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
        if (i < src.length) throw IllegalArgumentException("Unexpected characters: " + src.substring(i))
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
                if (src.getOrNull(i) != ')') throw IllegalArgumentException("Unmatched parentheses")
                i++
                return v
            }
            '+' -> { i++; return factor() }
            '-' -> { i++; return -factor() }
            else -> {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                if (start == i) throw IllegalArgumentException("Missing number")
                return src.substring(start, i).toDouble()
            }
        }
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }
}
