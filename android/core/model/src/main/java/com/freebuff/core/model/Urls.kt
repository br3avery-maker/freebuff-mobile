package com.freebuff.core.model

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------------- 官方地址(设置 → 关于) ---------------- */

/** 官方网站,由「关于 → 官方网站」在浏览器打开。 */
const val OFFICIAL_SITE = "https://freebuff.com"

/** 反馈入口,由「关于 → 反馈与建议」在浏览器打开(官方开源仓库的 issue 页)。 */
const val FEEDBACK_URL = "https://github.com/CodebuffAI/freebuff/issues"

/* ---------------- URL 工具:协议自动补全 + 完整请求路径 ---------------- */

/** 协议自动补全:无 scheme 时补 https://。空串原样返回。 */
fun normEndpoint(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return ""
    if (!s.contains("://")) s = "https://$s"
    return s
}

/** 判断自定义模型端点是否为可发起 HTTP(S) 请求的地址。 */
fun isValidHttpEndpoint(raw: String): Boolean {
    val s = normEndpoint(raw)
    if (s.isBlank() || s.any { it.isWhitespace() }) return false
    return runCatching {
        val uri = URI(s)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

/** 完整请求路径:以 /chat/completions 结尾的 base 原样,否则追加。 */
fun endpointUrl(base: String): String {
    var b = normEndpoint(base)
    while (b.endsWith("/")) b = b.dropLast(1)
    if (b.isEmpty()) return ""
    return if (b.lowercase(Locale.ROOT).endsWith("/chat/completions")) b else "$b/chat/completions"
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
    if (b.lowercase(Locale.ROOT).endsWith("/v1")) return "$b/models"
    val idx = b.indexOf("://")
    if (idx == -1) return "$b/v1/models"
    val after = b.substring(idx + 3)
    val slash = after.indexOf('/')
    val origin = if (slash == -1) b else b.substring(0, idx + 3 + slash)
    return "$origin/v1/models"
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
    return "https://$s"
}

data class CloneParsed(
    val host: String,
    val path: String,
    val repo: String,
    val branch: String,
    val scheme: String,
)

/**
 * 解析 git clone 命令 / HTTP(S) 深链 / SSH URI / scp 风格地址。
 * host 会保留端口(如 host:2222),path 为 owner/repo,branch 为可识别的分支。
 */
fun parseClone(raw: String): CloneParsed? {
    var s = raw.trim()
    var branch = ""
    if (s.isEmpty()) return null

    if (s.lowercase(Locale.ROOT).startsWith("git clone")) {
        val tokens = s.substring("git clone".length).trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.trim('"', '\'') }
        val urlTokens = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token == "-b" || token == "--branch" -> {
                    if (i + 1 < tokens.size) branch = tokens[++i]
                }
                token.startsWith("--branch=") -> branch = token.substringAfter('=')
                token == "-o" || token == "-j" || token == "--depth" ||
                    token == "--jobs" || token == "--origin" -> i++
                token.startsWith("-") -> Unit
                else -> urlTokens += token
            }
            i++
        }
        s = urlTokens.firstOrNull().orEmpty()
    }
    if (s.isEmpty()) return null

    val hash = s.indexOf('#')
    if (hash != -1) {
        if (branch.isEmpty()) branch = s.substring(hash + 1)
        s = s.substring(0, hash)
    }
    val queryStart = s.indexOf('?')
    if (queryStart != -1) {
        val query = s.substring(queryStart + 1)
        if (branch.isEmpty()) {
            Regex("(?:^|&)(?:ref|branch)=([^&]+)", RegexOption.IGNORE_CASE)
                .find(query)?.groupValues?.getOrNull(1)?.let { branch = it }
        }
        s = s.substring(0, queryStart)
    }

    // GitHub/GitLab/Gitee 以及私有 Git 服务的 tree 深链。
    val treeIdx = s.indexOf("/tree/")
    if (treeIdx != -1) {
        val treeBranch = s.substring(treeIdx + "/tree/".length).substringBefore('/')
        if (branch.isEmpty() && treeBranch.isNotBlank()) branch = treeBranch
        s = s.substring(0, treeIdx)
    }

    var host: String
    var path: String
    var scheme = ""
    val schemeIdx = s.indexOf("://")
    if (schemeIdx != -1) {
        scheme = s.substring(0, schemeIdx)
        val parsed = runCatching { URI(s) }.getOrNull()
        val uriHost = parsed?.host
        if (!uriHost.isNullOrBlank()) {
            host = uriHost + if (parsed.port != -1) ":${parsed.port}" else ""
            path = parsed.rawPath.orEmpty().trimStart('/')
        } else {
            val authorityAndPath = s.substring(schemeIdx + 3)
            val slash = authorityAndPath.indexOf('/')
            if (slash == -1) return null
            val authority = authorityAndPath.substring(0, slash)
            host = authority.substringAfterLast('@')
            path = authorityAndPath.substring(slash + 1)
        }
    } else {
        val at = s.indexOf('@')
        val afterAt = if (at > 0) s.substring(at + 1) else ""
        val colon = afterAt.indexOf(':')
        if (at > 0 && colon != -1) {
            val hostPart = afterAt.substring(0, colon)
            val tail = afterAt.substring(colon + 1)
            // 非标准但常见的 git@host:2222/owner/repo 写法。
            val portForm = Regex("^(\\d+)/(.*)$").matchEntire(tail)
            if (portForm != null) {
                host = "$hostPart:${portForm.groupValues[1]}"
                path = portForm.groupValues[2]
            } else {
                host = hostPart
                path = tail
            }
        } else {
            val slash = s.indexOf('/')
            if (slash == -1) return null
            host = s.substring(0, slash)
            path = s.substring(slash + 1)
        }
    }

    val parts = path.split('/').filter { it.isNotBlank() }.toMutableList()
    if (host.isBlank() || parts.size < 2) return null
    val last = parts.lastIndex
    if (parts[last].lowercase(Locale.ROOT).endsWith(".git")) parts[last] = parts[last].dropLast(4)
    if (parts[last].isBlank()) return null
    return CloneParsed(host, parts.joinToString("/"), parts[last], branch, scheme)
}

/** 把已解析的仓库输入收敛为可保存/提交的完整 clone 地址。 */
fun canonicalRepoUrl(raw: String): String {
    val parsed = parseClone(raw) ?: return normRepoUrl(raw)
    val suffix = if (parsed.path.lowercase(Locale.ROOT).endsWith(".git")) parsed.path else "${parsed.path}.git"
    val trimmed = raw.trim()
    return when {
        parsed.scheme.equals("ssh", ignoreCase = true) -> "ssh://git@${parsed.host}/$suffix"
        trimmed.contains("git@") && parsed.host.contains(':') -> "ssh://git@${parsed.host}/$suffix"
        trimmed.contains("git@") -> "git@${parsed.host}:$suffix"
        parsed.scheme.isNotBlank() -> "${parsed.scheme}://${parsed.host}/$suffix"
        else -> "https://${parsed.host}/$suffix"
    }
}

/** 简易 id 生成。 */
fun uid(): String = "id" + Math.random().toString().substring(2, 9)
