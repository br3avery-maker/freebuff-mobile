package com.freebuff.feature.settings

import java.io.File

/**
 * 应用内更新的下载状态。
 *
 * 单独建模而不是几个布尔量:界面要区分「在下载(带进度)」「下好了等安装」「失败了」,
 * 而失败文案来自数据层,不该由界面再拼一次。
 */
sealed interface UpdateDownload {
    /** 还没开始下载(或已重置)。 */
    data object Idle : UpdateDownload

    /**
     * 下载中。
     * @param total 服务端给的总字节数;为 0 表示没给 Content-Length,界面只显示已下载量
     */
    data class Running(val received: Long, val total: Long) : UpdateDownload

    /** 已下载并通过 sha256 校验,可以交给系统安装器。 */
    data class Ready(val file: File) : UpdateDownload

    /** 失败原因(已经是可直接展示的文案)。 */
    data class Failed(val message: String) : UpdateDownload
}
