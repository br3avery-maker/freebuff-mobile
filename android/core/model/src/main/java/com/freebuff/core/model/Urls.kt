package com.freebuff.core.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------------- URL 工具:协议自动补全 + 完整请求路径 ---------------- */

/** 协议自动补全:无 scheme 时补 https://。空串原样返回。 */
fun normEndpoint(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return ""
    if (!s.contains("://")) s = "https://" + s
    return s
}

/** 完整请求路径:以 /chat/completions 结尾的 base 原样,否则追加。 */
fun endpointUrl(base: String): String {
    var b = normEndpoint(base)
    while (b.endsWith("/")) b = b.dropLast(1)
    if (b.isEmpty()) return ""
    return if (b.lowercase(Locale.ROOT).endsWith("/chat/completions")) b else b + "/chat/completions"
}

/**
 * 模型目录请求路径(/v1/models):
 * - base 已以 /v1 结尾 → {base}/models
 * - 其余取 origin(scheme://host[:port])→ {origin}/v1/models
 * - 已带 /chat/completions 的完整端点先剥掉该后缀
 */
fun modelsUrl(base: String): String {
    var b = normEndpoint(base)
    while (b.endsWith("/")) b = b.dropLast(1)
    if (b.isEmpty()) return ""
    if (b.lowercase(Locale.ROOT).endsWith("/chat/completions")) b = b.dropLast("/chat/completions".length)
    while (b.endsWith("/")) b = b.dropLast(1)
    if (b.lowercase(Locale.ROOT).endsWith("/v1")) return b + "/models"
    val idx = b.indexOf("://")
    if (idx == -1) return b + "/v1/models"
    val after = b.substring(idx + 3)
    val slash = after.indexOf('/')
    val origin = if (slash == -1) b else b.substring(0, idx + 3 + slash)
    return origin + "/v1/models"
}

/** API Key 脱敏:前 2 + … + 后 4;过短一律 ••••。 */
fun maskKey(k: String): String {
    val st = k
    return if (st.length <= 8) "••••" else st.take(2) + "…" + st.takeLast(4)
}

/** 时间格式化 HH:MM:SS(失败回退占位)。 */
fun fmtT(t: Long): String =
    try {
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(t))
    } catch (e: Exception) {
        "--:--:--"
    }

/** 仓库地址规范化:http(s) 原样 / SSH(git@host:path)原样 / 其余补 https:// */
fun normRepoUrl(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    if (s.contains("://")) return s
    val at = s.indexOf("@")
    if (at > 0 && s.indexOf(":", at) > at) return s
    return "https://" + s
}

data class CloneParsed(
    val host: String,
    val path: String,
    val repo: String,
    val branch: String,
    val scheme: String,
)

/** 解析 git clone 命令 / 带分支深链,返回 host/path/repo/branch/scheme。 */
fun parseClone(raw: String): CloneParsed? {
    var s = raw.trim()
    var branch = ""
    val low = s.lowercase(Locale.ROOT)
    if (low.startsWith("git clone")) {
        var rest = s.substring("git clone".length).trim()
        val toks = rest.split(" ").filter { it.isNotEmpty() }
        val urlTok = mutableListOf<String>()
        var k = 0
        while (k < toks.size) {
            val tk = toks[k]
            when {
                tk == "-b" || tk == "--branch" -> {
                    if (k + 1 < toks.size) {
                        branch = toks[k + 1]
                        k++
                    }
                }
                tk.startsWith("--branch=") -> branch = tk.substring("--branch=".length)
                tk == "-o" || tk == "-j" || tk == "--depth" || tk == "--jobs" || tk == "--origin" -> k++
                tk.startsWith("-") -> {}
                else -> urlTok.add(tk)
            }
            k++
        }
        s = urlTok.firstOrNull() ?: ""
    }
    if (s.isEmpty()) return null
    var frag = ""
    val hash = s.indexOf("#")
    if (hash != -1) {
        frag = s.substring(hash + 1)
        s = s.substring(0, hash)
    }
    val q = s.indexOf("?")
    if (q != -1) s = s.substring(0, q)
    val treeIdx = s.lastIndexOf("/tree/")
    if (treeIdx != -1) {
        val rest = s.substring(treeIdx + 6)
        if (rest.isNotEmpty() && !rest.contains("/")) {
            if (branch.isEmpty()) branch = rest
            s = s.substring(0, treeIdx)
        }
    } else if (frag.isNotEmpty() && branch.isEmpty()) {
        branch = frag
    }
    var host = ""
    var path = ""
    var scheme = ""
    val sc = s.indexOf("://")
    if (sc != -1) {
        scheme = s.substring(0, sc)
        val after = s.substring(sc + 3)
        val slash = after.indexOf("/")
        if (slash == -1) return null
        host = after.substring(0, slash)
        path = after.substring(slash + 1)
    } else {
        val at = s.indexOf("@")
        if (at > 0) {
            val colon = s.indexOf(":", at)
            if (colon == -1) return null
            host = s.substring(at + 1, colon)
            path = s.substring(colon + 1)
        } else {
            val slash = s.indexOf("/")
            if (slash == -1) return null
            host = s.substring(0, slash)
            path = s.substring(slash + 1)
        }
    }
    if (host.isEmpty() || path.isEmpty()) return null
    val parts = path.split("/").filter { it.isNotEmpty() }.toMutableList()
    if (parts.size < 2) return null
    val last = parts.size - 1
    if (parts[last].lowercase(Locale.ROOT).endsWith(".git")) parts[last] = parts[last].dropLast(4)
    return CloneParsed(host, parts.joinToString("/"), parts[last], branch, scheme)
}

/** 简易 id 生成。 */
fun uid(): String = "id" + Math.random().toString().substring(2, 9)
