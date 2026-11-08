package com.freebuff.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.ui.theme.LocalTokens

@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(90.dp))
        Box(
            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(t.surface)
                .clickable { viewModel.cycleTheme() },
            contentAlignment = Alignment.Center,
        ) {
            Text(">_", color = t.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text("Bring your ideas to", color = t.text, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Freebuff", color = t.accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("An Android coding assistant in your pocket: plan, work with code, and explain each step.",
            color = t.text2, fontSize = 13.5.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        FeatureRow("Use built-in models with a configured gateway, or add your own API endpoint")
        FeatureRow("The agent plans, runs tools, and explains each step")
        FeatureRow("Chats saved locally · Read them offline anytime")
        Spacer(Modifier.weight(1f))
        PrimaryBtn("Sign in to Freebuff") { viewModel.enter(signedIn = true) }
        Spacer(Modifier.height(10.dp))
        Text("Explore first (guest mode)", color = t.text3, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { viewModel.enter(signedIn = false) }.padding(12.dp))
        Spacer(Modifier.height(8.dp))
        Text("Guest mode · Data stays on this device", color = t.text3, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun FeatureRow(text: String) {
    val t = LocalTokens.current
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✦", color = t.accent, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = t.text2, fontSize = 13.sp)
    }
}

@Composable
private fun PrimaryBtn(text: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Text(text, color = t.accentInk, fontSize = 15.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.accent)
            .clickable { onClick() }.padding(vertical = 15.dp),
        textAlign = TextAlign.Center)
}
