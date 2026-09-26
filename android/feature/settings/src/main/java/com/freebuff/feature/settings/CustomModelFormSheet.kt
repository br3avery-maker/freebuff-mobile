package com.freebuff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.ProbeResult
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.Probe
import com.freebuff.core.model.endpointUrl
import com.freebuff.core.model.normEndpoint
import com.freebuff.core.model.uid
import com.freebuff.core.ui.R14
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

/** 自定义模型编辑/新增:真实测试连接 + /v1/models 拉取,保存时快照真实结果。 */
@Composable
fun CustomModelFormSheet(editId: String? = null, viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val customModels by viewModel.customModels.collectAsState()
    val editing = customModels.firstOrNull { it.id == editId }
    val f = remember(editId, editing?.id) { FState(editing) }
    Column(Modifier.fillMaxSize()) {
        Text(if (editing != null) "编辑自定义模型" else "添加自定义模型",
            color = t.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp))
        Text("填写端点后可用「测试连接」拉取可用模型", color = t.text3, fontSize = 12.sp,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 14.dp)) {
            FormBasic(f)
            FormAbility(f)
            FormEndpoint(viewModel, f)
            FormTest(viewModel, f)
            if (editing != null && editing.models.isNotEmpty()) FormSnapshot(f, editing)
            FormAdvanced(f)
        }
        FormActions(viewModel, navigator, f, editing)
    }
}

private class FState(edit: CustomModel?) {
    var name by mutableStateOf(edit?.name ?: "")
    var apiId by mutableStateOf(edit?.apiId ?: "")
    var base by mutableStateOf(edit?.base ?: "")
    var key by mutableStateOf(edit?.key ?: "")
    var ctx by mutableStateOf(edit?.ctx ?: "8k")
    var timeout by mutableStateOf(edit?.timeout ?: "60s")
    var headers by mutableStateOf(edit?.headers ?: "")
    var skipTLS by mutableStateOf(edit?.skipTLS ?: false)
    var keyVisible by mutableStateOf(false)
    var advOpen by mutableStateOf(false)
    var testPhase by mutableStateOf("idle")
    var tested by mutableStateOf(false)
    var err by mutableStateOf<String?>(null)
    var ms by mutableStateOf<Int?>(null)
    var resultList by mutableStateOf<List<String>?>(null)
    var probe by mutableStateOf<Map<String, Probe>>(emptyMap())
    var fetchPhase by mutableStateOf("idle")
    var pickList by mutableStateOf<List<String>?>(null)

    /** 「拉取列表」的专属错误:就地显示在按钮下方,不与「测试连接」的 err 混用。 */
    var fetchErr by mutableStateOf<String?>(null)

    /** 修改会影响请求的关键字段后,测试结果不再可信:失效成功标记与快照网格。 */
    fun invalidateTest() {
        if (testPhase == "ok") testPhase = "idle"
        tested = false
        resultList = null
        ms = null
        pickList = null
    }
}

@Composable
private fun FormBasic(f: FState) {
    SectionLabel2("基本信息")
    Field("显示名称", f.name) { f.name = it }
}

@Composable
private fun FormAbility(f: FState) {
    SectionLabel2("能力")
    val t = LocalTokens.current
    Text("上下文长度", color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("4k", "8k", "16k", "32k", "128k", "524k").forEach { o ->
            Chip(o, f.ctx == o) { f.ctx = o }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("请求超时", color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("10s", "30s", "60s", "120s", "300s").forEach { o ->
            Chip(o, f.timeout == o) { f.timeout = o }
        }
    }
}

@Composable
private fun Chip(o: String, on: Boolean, onSel: () -> Unit) {
    val t = LocalTokens.current
    Text(o, color = if (on) t.accentInk else t.text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RFull).background(if (on) t.accent else t.surface2).clickable { onSel() }
            .padding(horizontal = 13.dp, vertical = 6.dp))
    Spacer(Modifier.width(7.dp))
}

@Composable
private fun SectionLabel2(txt: String) {
    val t = LocalTokens.current
    Text(txt, color = t.text3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 7.dp))
}

@Composable
private fun Field(label: String, value: String, onFocusedEdit: (() -> Unit)? = null, onValue: (String) -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(label, color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        BasicTextField(value = value, onValueChange = onValue,
            textStyle = TextStyle(color = t.text, fontSize = 13.5.sp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    Spacer(Modifier.height(9.dp))
}

@Composable
private fun FormEndpoint(viewModel: SettingsViewModel, f: FState) {
    val t = LocalTokens.current
    SectionLabel2("端点")
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("Base URL", color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        BasicTextField(value = f.base, onValueChange = {
            f.base = it
            f.invalidateTest()
        },
            textStyle = TextStyle(color = t.text, fontSize = 13.sp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    val norm = normEndpoint(f.base)
    if (f.base.isNotBlank()) {
        val full = endpointUrl(norm)
        val isDirect = norm.lowercase().endsWith("/chat/completions")
        Text((if (isDirect) "● 完整路径 · 直接使用  " else "● 自动拼接  ") + full,
            color = if (isDirect) t.accent else t.text3, fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, bottom = 8.dp))
    } else {
        Text("输入后实时显示请求地址 · 支持直接粘贴中转站完整路径", color = t.text3, fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, bottom = 8.dp))
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("API Key", color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            BasicTextField(value = f.key, onValueChange = {
                f.key = it
                f.invalidateTest()
            },
                textStyle = TextStyle(color = t.text, fontSize = 13.sp),
                singleLine = true,
                visualTransformation = if (f.keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        Text(if (f.keyVisible) "隐藏" else "显示", color = t.text2, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RFull).clickable { f.keyVisible = !f.keyVisible }.padding(horizontal = 10.dp, vertical = 4.dp))
    }
    Spacer(Modifier.height(9.dp))
    // 模型 ID:手填 + 「拉取列表」下拉选择(需先填 Base URL;拉取只 GET /v1/models,不做连接测试)
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("模型 ID", color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            BasicTextField(value = f.apiId, onValueChange = {
                f.apiId = it
                f.invalidateTest()
            },
                textStyle = TextStyle(color = t.text, fontSize = 13.sp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        Text(if (f.fetchPhase == "loading") "◐ 拉取中" else "拉取列表",
            color = if (f.fetchPhase == "loading") t.text3 else t.accentInk, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RFull)
                .background(if (f.fetchPhase == "loading") t.surface3 else t.accent)
                .clickable(enabled = f.fetchPhase != "loading") {
                    if (f.base.isBlank()) {
                        f.fetchErr = "请先填写 Base URL,再拉取模型列表"
                    } else {
                        f.fetchErr = null
                        f.fetchPhase = "loading"
                        viewModel.fetchModelList(
                            CustomModel(
                                name = f.name, apiId = f.apiId,
                                base = normEndpoint(f.base),
                                key = f.key, ctx = f.ctx, timeout = f.timeout,
                                headers = f.headers, skipTLS = f.skipTLS,
                            ),
                        ) { r ->
                            f.fetchPhase = "idle"
                            when (r) {
                                is ApiResult.Ok -> {
                                    f.pickList = r.data
                                    if (r.data.isEmpty()) f.fetchErr = "端点返回空模型列表,可直接手动填写"
                                }
                                is ApiResult.Err -> f.fetchErr = r.error.userMessage
                            }
                        }
                    }
                }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
    f.pickList?.let { list -> ModelPickList(f, list) }
    f.fetchErr?.let {
        Text(it, color = t.danger, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth().clip(R14).background(t.surface2).clickable { f.skipTLS = !f.skipTLS }
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("跳过 TLS 证书校验", color = t.text, fontSize = 13.sp)
            Text("仅用于本地自签名端点,请谨慎开启", color = t.text3, fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.size(width = 40.dp, height = 23.dp).clip(RFull)
            .background(if (f.skipTLS) t.accent else t.surface3))
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun FormAdvanced(f: FState) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().clickable { f.advOpen = !f.advOpen }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("高级选项", color = t.text2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Text(if (f.advOpen) "▾" else "▸", color = t.text3, fontSize = 13.sp)
    }
    if (f.advOpen) {
        Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("自定义请求头(JSON,每行一个,可选)", color = t.text3, fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp))
            BasicTextField(value = f.headers, onValueChange = { f.headers = it },
                textStyle = TextStyle(color = t.text, fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                minLines = 2)
        }
    }
}

/** 「拉取列表」的下拉选择面板:点选回填模型 ID,可收起;选择后失效旧测试结果。 */
@Composable
private fun ModelPickList(f: FState, list: List<String>) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { f.pickList = null }.padding(vertical = 6.dp)) {
            Text("端点可用模型 " + list.size + " 个 · 点选填入", color = t.text3, fontSize = 10.5.sp,
                modifier = Modifier.weight(1f).padding(start = 2.dp))
            Text("收起 ▴", color = t.text3, fontSize = 10.5.sp, modifier = Modifier.padding(start = 8.dp))
        }
        // 有界高度 + LazyColumn:大列表(数百个模型)不会撑爆弹窗,内部滚动浏览
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(R14)
            .background(t.surface).border(1.dp, t.border, R14).padding(horizontal = 8.dp, vertical = 6.dp)) {
            items(list.size) { i ->
                val mid = list[i]
                val cur = mid == f.apiId
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                        .background(if (cur) t.accentSoft else Color.Transparent)
                        .clickable {
                            f.apiId = mid
                            f.invalidateTest()
                            f.pickList = null
                        }.padding(horizontal = 9.dp, vertical = 8.dp)) {
                    // 选中行底色是 accentSoft(浅色洗底),文字必须用 accent;
                    // accentInk 是压 accent 实底用的,放在浅底上两种主题都看不见
                    Text(mid, color = if (cur) t.accent else t.text, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (cur) Text("已选", color = t.accent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                }
                if (i < list.lastIndex) Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** 真实测试连接:POST 最小 body 校验连通/鉴权 + GET /v1/models 拉取快照。 */
@Composable
private fun FormTest(viewModel: SettingsViewModel, f: FState) {
    val t = LocalTokens.current
    SectionLabel2("测试连接")
    f.err?.let {
        Text(it, color = t.danger, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
    // 修改关键字段后旧结果失效:提示重测,避免拿旧快照保存
    if (f.testPhase != "loading" && !f.tested && f.resultList == null && f.err == null &&
        f.name.isNotBlank() && f.apiId.isNotBlank() && f.base.isNotBlank()
    ) {
        Text("提示:修改端点或密钥后请重新测试连接", color = t.text3, fontSize = 10.5.sp,
            modifier = Modifier.padding(bottom = 6.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (f.testPhase == "loading") "◐ 正在请求 " + endpointUrl(normEndpoint(f.base)) + " …"
            else "测试连接",
            color = if (f.testPhase == "loading") t.accent else t.accentInk, fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).background(if (f.testPhase == "loading") t.surface2 else t.accent)
                .clickable(enabled = f.testPhase != "loading") {
                    if (f.name.isBlank() || f.apiId.isBlank() || f.base.isBlank()) {
                        f.err = "请先填写 显示名称 / 模型 ID / Base URL 三项必填内容"
                    } else {
                        f.err = null
                        f.testPhase = "loading"
                        viewModel.probeModel(
                            CustomModel(
                                name = f.name, apiId = f.apiId,
                                base = normEndpoint(f.base),
                                key = f.key, ctx = f.ctx, timeout = f.timeout,
                                headers = f.headers, skipTLS = f.skipTLS,
                            ),
                        ) { (updated, result) ->
                            when (result) {
                                is ProbeResult.Success -> {
                                    f.resultList = updated.models
                                    f.probe = updated.probe
                                    f.ms = result.ms
                                    f.testPhase = "ok"
                                    f.tested = true
                                }
                                is ProbeResult.Fail -> {
                                    f.err = result.reason
                                    f.testPhase = "idle"
                                }
                            }
                        }
                    }
                }.padding(horizontal = 15.dp, vertical = 8.dp))
    }
    if (f.testPhase == "ok") {
        Text("连接成功 · 返回 200 · " + (f.ms ?: 0) + "ms",
            color = t.ok, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp))
    }
    f.resultList?.let { TestResultGrid(f, it) }
}

@Composable
private fun TestResultGrid(f: FState, list: List<String>) {
    val t = LocalTokens.current
    Spacer(Modifier.height(10.dp))
    Text("可用模型 /v1/models · 点选填入模型 ID", color = t.text3, fontSize = 10.5.sp,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
    if (list.isEmpty()) {
        Text("端点返回空模型列表,可直接手动填写模型 ID", color = t.text3, fontSize = 10.5.sp,
            modifier = Modifier.padding(start = 2.dp))
        return
    }
    Column {
        list.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                row.forEach { mid ->
                    val cur = mid == f.apiId
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (cur) t.accentSoft else t.surface2)
                        .border(1.dp, if (cur) t.accent else Color.Transparent,
                            RoundedCornerShape(10.dp))
                        .clickable { f.apiId = mid }.padding(9.dp)) {
                        Text(mid, color = t.text, fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (cur) Text("已填", color = t.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FormSnapshot(f: FState, editing: CustomModel) {
    val t = LocalTokens.current
    val models = editing.models
    SectionLabel2("已保存快照")
    Text("上次可用 " + models.size + " 个模型 · 已随记录保存", color = t.text3, fontSize = 11.sp,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
    Column {
        models.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                row.forEach { mid ->
                    val cur = mid == f.apiId
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (cur) t.accentSoft else t.surface2)
                        .clickable { f.apiId = mid }.padding(9.dp)) {
                        Text(mid, color = t.text, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (cur) Text("当前", color = t.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (models.isNotEmpty()) {
        Text("从快照重建", color = t.accentInk, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).background(t.accent).clickable {
                f.apiId = models.first()
                f.err = null
            }.padding(horizontal = 13.dp, vertical = 6.dp).padding(top = 8.dp))
    }
}

@Composable
private fun FormActions(
    viewModel: SettingsViewModel,
    navigator: com.freebuff.core.ui.navigation.AppNavigator,
    f: FState,
    editing: CustomModel?,
) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().background(t.elev).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (editing != null) {
            Text("删除", color = t.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).clickable {
                    viewModel.deleteCustomModel(editing.id)
                    navigator.closeSheet()
                    navigator.showSnack("已删除 " + editing.name)
                }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("取消", color = t.text2, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).clickable { navigator.closeSheet() }.padding(horizontal = 18.dp, vertical = 10.dp))
        Spacer(Modifier.width(6.dp))
        Text("保存", color = t.accentInk, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).background(t.accent).clickable {
                if (f.name.isBlank() || f.apiId.isBlank() || f.base.isBlank()) {
                    f.err = "请先填写 显示名称 / 模型 ID / Base URL 三项必填内容"
                } else {
                    val snap = if (f.tested && f.resultList != null) f.resultList!!
                    else (editing?.models ?: emptyList())
                    val rec = CustomModel(
                        id = editing?.id ?: uid(),
                        name = f.name.trim(),
                        apiId = f.apiId.trim(),
                        base = normEndpoint(f.base.trim()),
                        key = f.key,
                        ctx = f.ctx, timeout = f.timeout, headers = f.headers, skipTLS = f.skipTLS,
                        models = snap,
                        probe = if (f.tested) f.probe else (editing?.probe ?: emptyMap()),
                    )
                    if (editing == null) viewModel.addCustomModel(rec) else viewModel.updateCustomModel(rec)
                    navigator.closeSheet()
                    navigator.showSnack(if (editing == null) "已添加 " + rec.name else "已保存 " + rec.name)
                }
            }.padding(horizontal = 24.dp, vertical = 10.dp))
    }
}
