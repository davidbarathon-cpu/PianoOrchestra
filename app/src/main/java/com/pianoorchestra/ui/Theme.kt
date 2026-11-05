package com.pianoorchestra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoorchestra.R

/** Les trois ambiances : Bermet choisit la sienne dans les réglages. */
enum class Ambiance(
    val key: String,
    val bg: List<Color>,
    val glow1: Color, val glow2: Color, val glow3: Color,
    val accent: Color, val accentLight: Color, val accentDeep: Color,
) {
    NUIT("nuit", listOf(Color(0xFF0B0D1E), Color(0xFF141632), Color(0xFF1E1630)),
        Color(0xFF6E54FF), Color(0xFFE8B86D), Color(0xFF5ED3C3),
        Color(0xFFE8B86D), Color(0xFFF3CC86), Color(0xFFD59A45)),
    AURORE("aurore", listOf(Color(0xFF150B22), Color(0xFF22113A), Color(0xFF2A1026)),
        Color(0xFFFF6FA5), Color(0xFF8A6BFF), Color(0xFFFFBE78),
        Color(0xFFF6A9C6), Color(0xFFFFC6DB), Color(0xFFE2789F)),
    OCEAN("ocean", listOf(Color(0xFF04121A), Color(0xFF0A2230), Color(0xFF0B1A2C)),
        Color(0xFF2EC4B6), Color(0xFF3A7BFF), Color(0xFFB4F0FF),
        Color(0xFF7FE3D6), Color(0xFFB2F2E9), Color(0xFF3DB8A8));

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NUIT
    }
}

object Po {
    /** L'ambiance en cours ; la changer redessine toute l'application. */
    var ambiance by mutableStateOf(Ambiance.NUIT)

    val Night = Color(0xFF0B0D1E)
    val NightHigh = Color(0xFF141632)
    /** Le verre : blanc très transparent posé sur le fond coloré. */
    val Card = Color.White.copy(alpha = 0.07f)
    val CardHigh = Color.White.copy(alpha = 0.13f)
    val Stroke = Color.White.copy(alpha = 0.16f)
    /** Surface opaque (fenêtres, menus) pour rester lisible par-dessus tout. */
    val Solid = Color(0xFF1A1A36)
    /** L'accent de l'ambiance (or, rose ou turquoise). */
    val Gold: Color get() = ambiance.accent
    val GoldSoft: Color get() = ambiance.accentLight
    val Teal = Color(0xFF5ED3C3)
    val Text = Color(0xFFF5F2EA)
    val Muted = Color(0xFFB4B3CC)
    val Danger = Color(0xFFEF7A85)

    /** Dégradé brillant des boutons principaux. */
    val accentBrush: Brush get() = Brush.linearGradient(listOf(ambiance.accentLight, ambiance.accentDeep))
    val tealBrush: Brush = Brush.linearGradient(listOf(Color(0xFF8FF0E3), Color(0xFF3DB8A8)))
}

/** Police Jost : géométrique et élégante, en français comme en russe. */
val Jost = FontFamily(
    Font(R.font.jost_light, FontWeight.Light),
    Font(R.font.jost_regular, FontWeight.Normal),
    Font(R.font.jost_medium, FontWeight.Medium),
    Font(R.font.jost_semibold, FontWeight.SemiBold),
    Font(R.font.jost_bold, FontWeight.Bold),
)

private fun poTypography(): Typography {
    val t = Typography()
    fun TextStyle.j() = copy(fontFamily = Jost)
    return Typography(
        displayLarge = t.displayLarge.j(), displayMedium = t.displayMedium.j(), displaySmall = t.displaySmall.j(),
        headlineLarge = t.headlineLarge.j(), headlineMedium = t.headlineMedium.j(), headlineSmall = t.headlineSmall.j(),
        titleLarge = t.titleLarge.j(), titleMedium = t.titleMedium.j(), titleSmall = t.titleSmall.j(),
        bodyLarge = t.bodyLarge.j(), bodyMedium = t.bodyMedium.j(), bodySmall = t.bodySmall.j(),
        labelLarge = t.labelLarge.j(), labelMedium = t.labelMedium.j(), labelSmall = t.labelSmall.j(),
    )
}

/** Verre dépoli : voile blanc, liseré lumineux en haut, plus discret en bas. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(28.dp), strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(
        if (strong) listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.09f))
        else listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.045f))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.07f))), shape)

/** Le fond : dégradé de l'ambiance et grandes lumières douces. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val a = Po.ambiance
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(a.bg, start = Offset(0f, 0f), end = Offset(size.width * 0.6f, size.height)))
        fun glow(c: Color, cx: Float, cy: Float, r: Float, alpha: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = 0f)), center = Offset(cx, cy), radius = r),
                radius = r, center = Offset(cx, cy))
        val m = maxOf(size.width, size.height)
        glow(a.glow1, size.width * 0.12f, size.height * 0.05f, m * 0.42f, 0.42f)
        glow(a.glow2, size.width * 0.92f, size.height * 1.0f, m * 0.40f, 0.34f)
        glow(a.glow3, size.width * 0.55f, size.height * 0.62f, m * 0.22f, 0.16f)
    }
}

@Composable
fun PoTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Po.Gold,
        onPrimary = Po.Night,
        secondary = Po.Teal,
        onSecondary = Po.Night,
        background = Po.Night,
        onBackground = Po.Text,
        surface = Po.Solid,
        onSurface = Po.Text,
        surfaceVariant = Po.CardHigh,
        onSurfaceVariant = Po.Muted,
        surfaceContainer = Po.Solid,
        surfaceContainerHigh = Po.Solid,
        outline = Po.Stroke,
        error = Po.Danger,
    )
    MaterialTheme(colorScheme = scheme, typography = remember { poTypography() }) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
            Box(Modifier.fillMaxSize()) {
                AmbientBackground()
                content()
            }
        }
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).glass(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = Po.Text, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(title, color = Po.Text, fontSize = 27.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** Une grande carte en verre, facile à toucher même pendant qu'on joue. */
@Composable
fun BigCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, accent: Color = Po.Gold, onClick: () -> Unit) {
    Column(
        modifier
            .glass(RoundedCornerShape(30.dp))
            .clickable(onClick = onClick)
            .padding(26.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier
                .size(66.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.08f))))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Po.Text, fontSize = 25.sp, fontWeight = FontWeight.Medium, lineHeight = 29.sp)
            Text(subtitle, color = Po.Muted, fontSize = 16.sp, lineHeight = 21.sp)
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .glass(RoundedCornerShape(26.dp))
            .padding(22.dp)
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Po.Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp, modifier = modifier)
}

/** Choix de la langue : français ou russe, toujours visible sur l'accueil. */
@Composable
fun LanguageToggle(current: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .glass(RoundedCornerShape(50))
            .padding(5.dp)
    ) {
        for ((code, label) in listOf("fr" to stringResource(R.string.lang_fr), "ru" to stringResource(R.string.lang_ru))) {
            val selected = current == code
            Text(
                label,
                color = if (selected) Po.Night else Po.Text,
                fontSize = 18.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Po.accentBrush else SolidColor(Color.Transparent))
                    .clickable { if (!selected) onChange(code) }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }
}

/** Petite pastille de verre cliquable (bas de l'accueil, raccourcis). */
@Composable
fun GlassPill(text: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .glass(RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = Po.Text, modifier = Modifier.size(20.dp))
        Text(text, color = Po.Text, fontSize = 16.sp)
    }
}

/** Couleurs des « pochettes » des morceaux. */
val Covers = listOf(
    Color(0xFFF3CC86) to Color(0xFFC98B36),
    Color(0xFFB9A6FF) to Color(0xFF6E54FF),
    Color(0xFF8FF0E3) to Color(0xFF2E9C8F),
    Color(0xFFFFB3CF) to Color(0xFFD9578A),
    Color(0xFF9CC3FF) to Color(0xFF3A6BD9),
    Color(0xFFFFC59A) to Color(0xFFE0703A),
)

/** do3 (C4) en français, до (C4) en russe. */
fun noteLabel(note: Int, names: Array<String>, lang: String): String {
    val name = names[note % 12]
    val sci = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")[note % 12] + (note / 12 - 1)
    return if (lang == "fr") "$name${note / 12 - 2} ($sci)" else "$name ($sci)"
}

/** Les écrans de l'application. */
enum class Screen { HOME, KEYBOARD, PLAYALONG, FREEPLAY, SETTINGS, CATALOG }
