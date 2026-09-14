package com.freebuff.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/* ---------------- 设计变量(与原型一致) ---------------- */
val Lime = Color(0xFFA8E641)
val LimeInk = Color(0xFF10140A)
val GlowLime = Color(0xFF7CFF3F)
val OkGreen = Color(0xFF22C55E)
val WarnAmber = Color(0xFFFBBF24)
val DangerRed = Color(0xFFEF4444)
val PurpleTier = Color(0xFFB39DDB)

// 深色(默认)
val DarkBg = Color(0xFF0C0C0F)
val DarkElev = Color(0xFF101014)
val DarkSurface = Color(0xFF15151A)
val DarkSurface2 = Color(0xFF1C1C22)
val DarkSurface3 = Color(0xFF26262E)
val DarkChip = Color(0xFF202027)
val DarkBorder = Color(0x12FFFFFF)
val DarkBorderStrong = Color(0x24FFFFFF)
val DarkText = Color(0xFFF2F2F4)
val DarkText2 = Color(0xFFA2A2AB)
val DarkText3 = Color(0xFF6F6F79)
val DarkBackdrop = Color(0x9E040406)
val DarkCodeBg = Color(0xFF0A0A0D)
val DarkCodeHeader = Color(0x08000000)
val DarkCodeText = Color(0xFFDFE3E8)
val DarkUserBubble = Color(0xFF23232B)
val DarkTabbar = Color(0xDB0F0F13)

// 浅色
val LightBg = Color(0xFFF6F6F4)
val LightElev = Color(0xFFFBFBF9)
val LightSurface = Color(0xFFFFFFFF)
val LightSurface2 = Color(0xFFF0F0ED)
val LightSurface3 = Color(0xFFE7E7E2)
val LightChip = Color(0xFFEBEBE6)
val LightBorder = Color(0x170E0C10)
val LightBorderStrong = Color(0x2E0E0C10)
val LightText = Color(0xFF191A16)
val LightText2 = Color(0xFF5C5D55)
val LightText3 = Color(0xFF8A8B81)
val LightBackdrop = Color(0x59202210)
val LightCodeBg = Color(0xFFF2F2EE)
val LightCodeText = Color(0xFF2C2E28)
val LightUserBubble = Color(0xFFE3E6DA)
val LightTabbar = Color(0xE6FBFBF9)
val LightLime = Color(0xFF79A80F)

private val DarkScheme = darkColorScheme(
    primary = Lime,
    onPrimary = LimeInk,
    secondary = Lime,
    onSecondary = LimeInk,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkText2,
    outline = DarkBorder,
    outlineVariant = DarkBorderStrong,
    error = DangerRed,
)

private val LightScheme = lightColorScheme(
    primary = LightLime,
    onPrimary = Color.White,
    secondary = LightLime,
    onSecondary = Color.White,
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurface2,
    onSurfaceVariant = LightText2,
    outline = LightBorder,
    outlineVariant = LightBorderStrong,
    error = DangerRed,
)

@Composable
fun FreebuffTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}

/* 按主题取语义色 */
data class ThemeTokens(
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val bg: Color,
    val elev: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val chip: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val backdrop: Color,
    val codeBg: Color,
    val codeHeader: Color,
    val codeText: Color,
    val userBubble: Color,
    val tabbar: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val tier: Color,
)

val DarkTokens = ThemeTokens(
    Lime, LimeInk, Color(0x1FA8E641), DarkBg, DarkElev, DarkSurface, DarkSurface2, DarkSurface3,
    DarkChip, DarkBorder, DarkBorderStrong, DarkText, DarkText2, DarkText3, DarkBackdrop,
    DarkCodeBg, DarkCodeHeader, DarkCodeText, DarkUserBubble, DarkTabbar, OkGreen, WarnAmber, DangerRed, PurpleTier,
)

val LightTokens = ThemeTokens(
    LightLime, Color.White, Color(0x2179A80F), LightBg, LightElev, LightSurface, LightSurface2, LightSurface3,
    LightChip, LightBorder, LightBorderStrong, LightText, LightText2, LightText3, LightBackdrop,
    LightCodeBg, Color(0x0A0E0C10), LightCodeText, LightUserBubble, LightTabbar, OkGreen, WarnAmber, DangerRed, Color(0xFF7C5CB8),
)

/* 全局 token 提供者 */
val LocalTokens = staticCompositionLocalOf { DarkTokens }
