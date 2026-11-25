// ARCH-705 visual system: Apple's current iOS clarity and grouped-surface rhythm, adapted to an Android radio console.
package uk.hamdigital.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colors. Existing names are kept so every feature screen inherits the redesign at once. */
object Pal {
    val Bg = Color(0xFF05070B)
    val Canvas = Color(0xFF000000)
    val Surface1 = Color(0xFF111318)
    val Surface2 = Color(0xFF1C1F25)
    val Surface3 = Color(0xFF2C3038)
    val Glass = Color(0xCC1A1D23)
    val GlassStrong = Color(0xF21A1D23)
    val Tert = Surface1
    val Elevated = Surface2
    val Separator = Color(0x33FFFFFF)
    val Border = Color(0x2EFFFFFF)
    val Accent = Color(0xFF0A84FF)
    val AccentPressed = Color(0xFF0066CC)
    val Cyan = Color(0xFF64D2FF)
    val IcomBlue = Color(0xFF2478A8)
    val IcomBlueBright = Color(0xFF35A8E0)
    val IcomBlueDeep = Color(0xFF0B3550)
    val Sky = Color(0xFF5AC8FA)
    val Indigo = Color(0xFF5E5CE6)
    val Purple = Color(0xFFBF5AF2)
    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0xFFD1D1D6)
    val Muted = Color(0xFF8E8E93)
    val Dim = Color(0xFF636366)
    val Amber = Color(0xFFFF9F0A)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Heat = listOf(Color(0xFF3A1114), Color(0xFFA82924), Color(0xFFFF6B35), Color(0xFFFFD60A), Color(0xFF9BE564), Color(0xFF30D158))
    fun heat(pct: Int): Color = Heat[when { pct >= 80 -> 5; pct >= 60 -> 4; pct >= 40 -> 3; pct >= 20 -> 2; pct >= 10 -> 1; else -> 0 }]
}

val ArchCardShape = RoundedCornerShape(24.dp)
val ArchPanelShape = RoundedCornerShape(20.dp)
val ArchControlShape = RoundedCornerShape(14.dp)
val ArchPillShape = CircleShape

/** Orbitron Bold is reserved for the ARCH wordmark and large technical numerals. */
val OrbitronFamily = FontFamily(
    androidx.compose.ui.text.font.Font(
        uk.hamdigital.R.font.orbitron,
        FontWeight.Bold,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
            androidx.compose.ui.text.font.FontVariation.weight(700),
        ),
    ),
)

@Composable
fun ArchPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.CenterStart,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "arch-press-scale",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.48f }
            .then(
                if (onLongClick != null) Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ) else Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
            ),
        contentAlignment = contentAlignment,
    ) { content() }
}

@Composable
fun ArchCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    color: Color = Pal.Surface1,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = ArchCardShape,
        color = color,
        border = BorderStroke(1.dp, Pal.Separator),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
fun ArchSectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 16.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        uk.hamdigital.ui.Text(title, color = Pal.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
        Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
fun ArchNavAction(text: String, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        ArchPressable(onClick, Modifier.heightIn(min = 34.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .clip(ArchPillShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { uk.hamdigital.ui.Text(text, color = Pal.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun SmallChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        ArchPressable(onClick = onClick, modifier = modifier.heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .clip(ArchPillShape)
                    .background(if (selected) Pal.Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.055f))
                    .then(if (selected) Modifier.background(Brush.horizontalGradient(listOf(Pal.Accent.copy(alpha = 0.14f), Color.Transparent))) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                uk.hamdigital.ui.Text(text, color = if (selected) Pal.Text else Pal.Text2, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    password: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = when {
        isError -> Pal.Red
        focused -> Pal.Accent
        else -> Pal.Border
    }
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(ArchControlShape)
            .background(if (enabled) Pal.Surface2 else Pal.Surface1)
            .then(if (focused) Modifier.background(Pal.Accent.copy(alpha = 0.05f)) else Modifier)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.035f), Color.Transparent)))
            .border(1.dp, border.copy(alpha = if (focused || isError) 0.9f else 0.35f), ArchControlShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            uk.hamdigital.ui.Text(label, color = if (isError) Pal.Red else Pal.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                readOnly = readOnly,
                singleLine = true,
                textStyle = TextStyle(color = if (enabled) Pal.Text else Pal.Dim, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Pal.Accent),
                keyboardOptions = keyboardOptions,
                interactionSource = interaction,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun HamDigitalTheme(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.3f)),
        LocalMinimumInteractiveComponentSize provides 0.dp,
    ) { ThemeInner(content) }
}

@Composable
private fun ThemeInner(content: @Composable () -> Unit) {
    val typography = Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.7).sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.45).sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.25).sp),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = (-0.15).sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Accent,
            onPrimary = Color.White,
            primaryContainer = Pal.Accent.copy(alpha = 0.24f),
            onPrimaryContainer = Pal.Text,
            secondary = Pal.Cyan,
            onSecondary = Pal.Bg,
            background = Pal.Bg,
            onBackground = Pal.Text,
            surface = Pal.Surface1,
            onSurface = Pal.Text,
            surfaceVariant = Pal.Surface2,
            onSurfaceVariant = Pal.Text2,
            surfaceContainer = Pal.Surface1,
            surfaceContainerHigh = Pal.Surface2,
            surfaceContainerHighest = Pal.Surface3,
            outline = PaletteOutline,
            error = Pal.Red,
            onError = Color.White,
        ),
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(30.dp),
        ),
        content = content,
    )
}

private val PaletteOutline = Color(0x46FFFFFF)
