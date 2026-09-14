package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.FreebuffApi
import com.freebuff.core.model.OFFICIAL_MODELS
import com.freebuff.core.model.OfficialModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 官方目录来源。 */
enum class CatalogSource { BUILTIN, GATEWAY }

/**
 * 官方模型目录:优先从官方网关实时拉取(`GET /v1/models`),失败时使用内置目录。
 *
 * 行为约定:
 * - 首屏立即有内置目录可用,不阻塞 UI
 * - 网关未配置时不视为错误(不产生 [lastError],也不发请求)
 * - 拉取失败时保留当前目录并记录 [lastError],由设置/模型面板按需展示
 */
@Singleton
class ModelCatalogRepository @Inject constructor(
    private val api: FreebuffApi,
) {
    private val _official = MutableStateFlow(OFFICIAL_MODELS)
    val official: StateFlow<List<OfficialModel>> = _official.asStateFlow()

    private val _source = MutableStateFlow(CatalogSource.BUILTIN)
    val source: StateFlow<CatalogSource> = _source.asStateFlow()

    private val _lastError = MutableStateFlow<ApiError?>(null)
    val lastError: StateFlow<ApiError?> = _lastError.asStateFlow()

    /** 网关是否已配置(未配置时目录固定为内置)。 */
    val isGatewayConfigured: Boolean get() = api.isConfigured

    private var autoTried = false

    /** 启动时自动尝试一次;网关未配置或已尝试过则直接跳过。 */
    suspend fun refreshIfNeeded() {
        if (autoTried) return
        autoTried = true
        if (!api.isConfigured) return
        refresh()
    }

    /** 手动刷新:成功替换为网关目录,失败保留现有目录并记录错误。 */
    suspend fun refresh(): ApiResult<List<OfficialModel>> {
        val result = api.fetchOfficialModels()
        when (result) {
            is ApiResult.Ok -> {
                if (result.data.isNotEmpty()) _official.value = result.data
                _source.value = if (result.data.isNotEmpty()) CatalogSource.GATEWAY else CatalogSource.BUILTIN
                _lastError.value = null
            }
            is ApiResult.Err -> _lastError.value = result.error
        }
        return result
    }
}
