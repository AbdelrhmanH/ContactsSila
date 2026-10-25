package app.sila.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sila.core.model.ArhamStatus
import app.sila.core.model.Category

/**
 * نظام ألوان صِلَة المعتمد (DESIGN_SYSTEM.md §1).
 */
object SilaColors {
    // Primary — Emerald
    val PrimaryLight = Color(0xFF0F9D73)
    val PrimaryDark = Color(0xFF3CCB9A)

    // Secondary — Sunset Orange (أرحام)
    val SecondaryLight = Color(0xFFFF6A3D)
    val SecondaryDark = Color(0xFFFF8A63)

    // Tertiary — Deep Navy (الشعار والهوية)
    val TertiaryLight = Color(0xFF1E3A5F)
    val TertiaryDark = Color(0xFF9DB8DB)

    // Surfaces (Light)
    val BackgroundLight = Color(0xFFF7F5F2)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceVariantLight = Color(0xFFF2F0ED)
    val ArhamListContainerLight = Color(0xFFFFFBF3)
    val OnSurfaceLight = Color(0xFF1B1B1F)
    val OnSurfaceVariantLight = Color(0xFF77757A)
    val OutlineLight = Color(0xFFECEAE7)
    val ErrorLight = Color(0xFFD93F3F)

    // Surfaces (Dark)
    val BackgroundDark = Color(0xFF121212)
    val SurfaceDark = Color(0xFF1E1E1E)
    val SurfaceVariantDark = Color(0xFF2A2A2A)
    val ArhamListContainerDark = Color(0xFF221F1A)
    val OnSurfaceDark = Color(0xFFE6E1E5)
    val OnSurfaceVariantDark = Color(0xFFA9A6AD)
    val OutlineDark = Color(0xFF3A3A3A)
    val ErrorDark = Color(0xFFFF7A7A)

    // Category Colors
    val CategoryFamily = Color(0xFFE5484D)
    val CategoryFriends = Color(0xFFF5A524)
    val CategoryWork = Color(0xFF3E7BFA)
    val CategoryServices = Color(0xFF0F9D73)
    val CategoryEmergency = Color(0xFF8E4EC6)
    val CategoryUncategorized = Color(0xFF9A9A9A)

    // Arham Status Colors
    val StatusOverdue = Color(0xFFE5484D)
    val StatusSoon = Color(0xFFF5A524)
    val StatusOk = Color(0xFF30A46C)

    // Directory Badges
    val BadgeVerifiedFg = Color(0xFF0F9D73)
    val BadgeVerifiedBg = Color(0xFFE6F6F0)
    val BadgeCommunityFg = Color(0xFFE07B00)
    val BadgeCommunityBg = Color(0xFFFFF2E0)

    fun forCategory(category: Category): Color = when (category) {
        Category.FAMILY -> CategoryFamily
        Category.FRIENDS -> CategoryFriends
        Category.WORK -> CategoryWork
        Category.SERVICES -> CategoryServices
        Category.EMERGENCY -> CategoryEmergency
        Category.UNCATEGORIZED -> CategoryUncategorized
    }

    fun forArhamStatus(status: ArhamStatus): Color = when (status) {
        ArhamStatus.OVERDUE -> StatusOverdue
        ArhamStatus.SOON -> StatusSoon
        ArhamStatus.OK -> StatusOk
    }
}

/**
 * شبكة المسافات والزوايا (DESIGN_SYSTEM.md §3 — 4dp Grid).
 */
@Immutable
object SilaDimens {
    val Space4: Dp = 4.dp
    val Space8: Dp = 8.dp
    val Space12: Dp = 12.dp
    val Space16: Dp = 16.dp
    val Space20: Dp = 20.dp
    val Space24: Dp = 24.dp
    val Space32: Dp = 32.dp

    val ScreenHorizontalPadding: Dp = 16.dp
    val CardCornerRadius: Dp = 20.dp
    val SettingsGroupCornerRadius: Dp = 16.dp
    val PodiumCornerRadius: Dp = 24.dp
    val SheetCornerRadius: Dp = 28.dp
    val MinTouchTarget: Dp = 48.dp
}

/**
 * السلم الطباعي (DESIGN_SYSTEM.md §2).
 */
val SilaTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Light,
        lineHeight = 34.sp
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp
    )
)

private val LightColorScheme = lightColorScheme(
    primary = SilaColors.PrimaryLight,
    onPrimary = SilaColors.SurfaceLight,
    secondary = SilaColors.SecondaryLight,
    onSecondary = SilaColors.SurfaceLight,
    tertiary = SilaColors.TertiaryLight,
    background = SilaColors.BackgroundLight,
    surface = SilaColors.SurfaceLight,
    surfaceVariant = SilaColors.SurfaceVariantLight,
    onSurface = SilaColors.OnSurfaceLight,
    onSurfaceVariant = SilaColors.OnSurfaceVariantLight,
    outline = SilaColors.OutlineLight,
    error = SilaColors.ErrorLight
)

private val DarkColorScheme = darkColorScheme(
    primary = SilaColors.PrimaryDark,
    onPrimary = SilaColors.BackgroundDark,
    secondary = SilaColors.SecondaryDark,
    onSecondary = SilaColors.BackgroundDark,
    tertiary = SilaColors.TertiaryDark,
    background = SilaColors.BackgroundDark,
    surface = SilaColors.SurfaceDark,
    surfaceVariant = SilaColors.SurfaceVariantDark,
    onSurface = SilaColors.OnSurfaceDark,
    onSurfaceVariant = SilaColors.OnSurfaceVariantDark,
    outline = SilaColors.OutlineDark,
    error = SilaColors.ErrorDark
)

@Composable
fun SilaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isRtl: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SilaTypography,
            content = content
        )
    }
}
