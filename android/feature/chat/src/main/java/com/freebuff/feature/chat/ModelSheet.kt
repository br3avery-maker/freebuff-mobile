package com.freebuff.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.OfficialModel
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

/** 选择模型:官方 + 自定义分组。 */
@Composable
fun ModelSheet(viewModel: ChatViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val all by viewModel.modelList.collectAsState()
    val modelId by viewModel.modelId.collectAsState()
    val catalogLoaded by viewModel.catalogLoaded.collectAsState()
    val catalogError by viewModel.catalogError.collectAsState()
    val gatewayConfigured = viewModel.gatewayConfigured
    val official = all.filter { it.tier != "custom" }
    val customs = all.filter { it.tier == "custom" }
    SheetScaffold("Choose a model", "Built-in and custom models") {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(
                        when {
                            !gatewayConfigured -> "Built-in models · Gateway not configured"
                            catalogLoaded -> "Built-in models · Live gateway catalog"
                            else -> "Built-in models · Not loaded yet"
                        },
                        color = if (catalogError != null || !gatewayConfigured) t.warn else t.text3,
                        fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (gatewayConfigured) {
                        Text("Refresh", color = t.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RFull).clickable { viewModel.refreshCatalog() }
                                .padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                if (!gatewayConfigured) {
                    Text("This build has no built-in gateway configured. Add a custom model to get started.",
                        color = t.text3, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                } else if (catalogError != null) {
                    Text("Could not load catalog: " + catalogError, color = t.warn, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
            items(official, key = { it.id }) { m ->
                ModelRow(
                    m = m,
                    on = m.id == modelId,
                    onClick = {
                        viewModel.setModel(m.id)
                        navigator.closeSheet()
                    },
                )
            }
            item { Text("My models", color = t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 6.dp)) }
            if (customs.isEmpty()) {
                item { Text("No custom models yet. Add one in Settings → Custom models.", color = t.text3, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
            } else {
                items(customs, key = { it.id }) { m ->
                    ModelRow(
                        m = m,
                        on = m.id == modelId,
                        onClick = {
                            viewModel.setModel(m.id)
                            navigator.closeSheet()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(m: OfficialModel, on: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val badgeColor = if (m.tier == "custom") t.warn else t.accent
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.surface2).clickable { onClick() }
        .padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.badge, color = t.accentInk, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(badgeColor).padding(horizontal = 7.dp, vertical = 3.dp))
            Spacer(Modifier.width(8.dp))
            Text(m.name, color = t.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (on) Text("Current", color = t.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
        Text(m.tierText + " · " + m.desc, color = t.text3, fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}
