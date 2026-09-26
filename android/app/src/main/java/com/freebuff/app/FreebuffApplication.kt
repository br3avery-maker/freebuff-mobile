package com.freebuff.app

import android.app.Application
import android.util.Log
import com.freebuff.core.data.repository.SessionRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 应用入口:Hilt 装配根 + 崩溃黑匣子 + 冷启动修复。 */
@HiltAndroidApp
class FreebuffApplication : Application() {

    /** 冷启动修复要写库,用独立 IO 作用域:不占主线程,生命周期跟随进程。 */
    @Inject lateinit var sessionRepo: SessionRepository

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // 上一次进程被杀时,那条写到一半的消息会留在库里(空正文 + 「连接模型并开始生成…」步骤,
        // 看起来像永远在生成);进程刚启动时库里不可能有真在写入的消息,此刻修最安全。
        startupScope.launch {
            runCatching { sessionRepo.repairAbandonedMessages() }
                .onSuccess { n -> if (n > 0) Log.i(TAG, "repaired $n interrupted message(s)") }
                .onFailure { Log.w(TAG, "startup repair failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "FreebuffStartup"
    }
}
