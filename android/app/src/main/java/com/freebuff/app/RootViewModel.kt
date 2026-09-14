package com.freebuff.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.repository.DataMigrator
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.model.RepairReport
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 根级 ViewModel:启动时执行 v1 SharedPreferences → Room 一次性迁移。
 * 迁移产生的清洗修复报告交给 SettingsViewModel 展示「恢复原始记录」。
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val migrator: DataMigrator,
    private val settings: SettingsRepository,
    private val navigator: AppNavigator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _repairReport = MutableStateFlow<RepairReport?>(null)
    val repairReport: StateFlow<RepairReport?> = _repairReport.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("freebuff_proto_v1", Context.MODE_PRIVATE)
            _repairReport.value = migrator.migrate(prefs)
            // 原型行为:已登录/已进入过的启动直达会话列表,跳过欢迎页
            if (settings.getBool(SettingsRepository.KEY_SIGNED_IN, false)) {
                navigator.navigate(AppNavState.ROUTE_HOME)
            }
        }
    }

    /** 报告已交给设置页后消费,避免重复注入。 */
    fun consumeRepairReport() {
        _repairReport.value = null
    }
}
