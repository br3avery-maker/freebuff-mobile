package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.FreebuffApi
import com.freebuff.core.model.OfficialModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 官方模型目录:只来自官方网关 `GET /v1/models`。
 *
 * 行为约定:
 * - 未配置网关时不发请求、不报错,[official] 保持为空(UI 按「未配置」提示)
 * - 拉取成功替换目录并置 [loaded];失败保留现有目录并记录 [lastError]
 */
@Singleton
class ModelCatalogRepository @Inject constructor(
    private val api: FreebuffApi,
) {
    private val _official = MutableStateFlow<List<OfficialModel>>(emptyList())
    val official: StateFlow<List<OfficialModel>> = _official.asStateFlow()

    /** 是否至少成功拉到过一次网关目录(用于区分「未配置」「拉取失败」「已就绪」)。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _lastError = MutableStateFlow<ApiError?>(null)
    val lastError: StateFlow<ApiError?> = _lastError.asStateFlow()

    /** 网关是否已配置。 */
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
                _official.value = result.data
                _loaded.value = true
                _lastError.value = null
            }
            is ApiResult.Err -> _lastError.value = result.error
        }
        return result
    }
}
