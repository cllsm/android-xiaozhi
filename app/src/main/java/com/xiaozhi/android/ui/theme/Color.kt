package com.xiaozhi.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 扩展语义色板:Material3 colorScheme 之外的项目级语义色。
 * 星星、成功/警示状态、品牌渐变等统一在此定义,
 * 界面不再散落硬编码色值(深浅两套,随主题切换)。
 */

/** 扩展语义色集合 */
data class ExtendedColors(
    /** 星星金:奖励/结算/成长场景的主强调色 */
    val starGold: Color,
    val onStarGold: Color,
    val starGoldContainer: Color,
    /** 成功状态(已通过/已订正/连接正常) */
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** 警示状态(低电量/断连重试/配置缺失) */
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** 品牌渐变端点:大卡片/总结页背景的纵向渐变 */
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
    /** 芯片次级底色:快捷指令在浅色页面的柔和底 */
    val chipNeutralContainer: Color,
    val onChipNeutralContainer: Color
)

/** 浅色扩展色:暖金 + 草木绿成功 + 琥珀警示,与晨雾绿主色同族 */
val LightExtendedColors = ExtendedColors(
    starGold = Color(0xFFE8930C),
    onStarGold = Color(0xFFFFFFFF),
    starGoldContainer = Color(0xFFFFE9C2),
    success = Color(0xFF2E7D46),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD3F3DC),
    onSuccessContainer = Color(0xFF0B3D1D),
    warning = Color(0xFF9A6200),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFE6C4),
    onWarningContainer = Color(0xFF3A2500),
    heroGradientStart = Color(0xFF006B5F),
    heroGradientEnd = Color(0xFF2E9E8A),
    chipNeutralContainer = Color(0xFFEFF4F1),
    onChipNeutralContainer = Color(0xFF3A4A44)
)

/** 深色扩展色:降低饱和度的金/绿/琥珀,保证暗底可读 */
val DarkExtendedColors = ExtendedColors(
    starGold = Color(0xFFFFC94D),
    onStarGold = Color(0xFF3F2A00),
    starGoldContainer = Color(0xFF5C4200),
    success = Color(0xFF86C998),
    onSuccess = Color(0xFF0B3D1D),
    successContainer = Color(0xFF1D4A2B),
    onSuccessContainer = Color(0xFFC8EFD2),
    warning = Color(0xFFE5B25D),
    onWarning = Color(0xFF3A2500),
    warningContainer = Color(0xFF56400F),
    onWarningContainer = Color(0xFFFFE6C4),
    heroGradientStart = Color(0xFF74DCC9),
    heroGradientEnd = Color(0xFF2E8C7C),
    chipNeutralContainer = Color(0xFF232E2B),
    onChipNeutralContainer = Color(0xFFB7C6C0)
)
