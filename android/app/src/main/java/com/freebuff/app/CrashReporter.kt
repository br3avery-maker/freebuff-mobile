package com.freebuff.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃黑匣子:未捕获异常写文件(环形保留最近 [MAX_FILES] 份),
 * 下次启动在欢迎页顶部显示「上次异常退出」横幅,点击复制完整堆栈。
 *
 * 为什么不弹「崩溃了」对话框:启动即崩的场景对话框本身也会随之消失,
 * 落盘 + 下次启动回看是唯一能可靠把堆栈交到用户手里的方式。
 */
object CrashReporter {

    private const val DIR = "crash"
    private const val CURRENT = "last-crash.txt"
    private const val MAX_FILES = 3

    /** App 启动时调用:接管未捕获异常。 */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, throwable) }
            Timber.e(throwable, "Uncaught exception")
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 是否存在上次崩溃记录。 */
    fun hasCrash(context: Context): Boolean = crashFile(context).exists()

    /** 读取上次崩溃全文(无记录返回 null)。 */
    fun readLast(context: Context): String? {
        val f = crashFile(context)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    /** 把崩溃文本复制到剪贴板(用户点「复制堆栈」)。 */
    fun copyToClipboard(context: Context, text: String): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("freebuff-crash", text))
        return true
    }

    /** 用户已确认(复制/忽略)后清除记录。 */
    fun clear(context: Context) {
        runCatching {
            crashFile(context).delete()
            // 环形清理:超量的历史崩溃文件一并删除
            crashDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    private fun write(context: Context, throwable: Throwable) {
        val dir = crashDir(context)
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            appendLine("time: $ts")
            appendLine("version: ${runCatching { versionName() }.getOrDefault("?")}")
            appendLine("thread: ${threadName()}")
            appendLine()
            appendLine(stackOf(throwable))
            appendLine()
            appendLine("---- cause chain ----")
            var c = throwable.cause
            var depth = 0
            while (c != null && depth < 5) {
                appendLine("caused by: ${stackOf(c)}")
                c = c.cause
                depth++
            }
        }
        // 原子写:先写临时文件再改名,避免崩溃中途被杀留下半截文件
        val tmp = File(dir, "$CURRENT.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(File(dir, CURRENT))) {
            File(dir, CURRENT).writeText(body)
            tmp.delete()
        }
        trimHistory(dir)
    }

    private fun trimHistory(dir: File) {
        val files = dir.listFiles()?.filter { it.name != CURRENT }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    private fun crashDir(context: Context) = File(context.filesDir, DIR)
    private fun crashFile(context: Context) = File(crashDir(context), CURRENT)
    private fun threadName() = Thread.currentThread().name
    private fun versionName(): String =
        Class.forName("com.freebuff.mobile.BuildConfig").getField("VERSION_NAME").get(null) as String

    private fun stackOf(t: Throwable): String = t.stackTraceToString()
        .lineSequence()
        .take(25)
        .joinToString("\n")
        .let { "${t.javaClass.name}: ${t.message ?: "(no message)"}\n$it" }
}
