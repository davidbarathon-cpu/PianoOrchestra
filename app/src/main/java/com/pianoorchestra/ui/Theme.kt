package com.pianoorchestra.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
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

object Po {
    val Night = Color(0xFF0E1020)
    val NightHigh = Color(0xFF1B1636)
    val Card = Color(0xFF1C1F3A)
    val CardHigh = Color(0xFF252A4D)
    val Stroke = Color(0xFF2E335C)
    val Gold = Color(0xFFE8B86D)
    val GoldSoft = Color(0xFFF4E2C0)
    val Teal = Color(0xFF5ED3C3)
    val Text = Color(0xFFF2F0EA)
    val Muted = Color(0xFFA6A8C4)
    val Danger = Color(0xFFE57373)
}

private val scheme = darkColorScheme(
    primary = Po.Gold,
    onPrimary = Po.Night,
    secondary = Po.Teal,
    onSecondary = Po.Night,
    background = Po.Night,
    onBackground = Po.Text,
    surface = Po.Card,
    onSurface = Po.Text,
    surfaceVariant = Po.CardHigh,
    onSurfaceVariant = Po.Muted,
    outline = Po.Stroke,
    error = Po.Danger,
)

@Composable
fun PoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Po.Night, Po.NightHigh)))
        ) { content() }
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = Po.Text, modifier = Modifier.size(30.dp))
        }
        Text(title, color = Po.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Une grande carte cliquable, facile à toucher même pendant qu'on joue. */
@Composable
fun BigCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, accent: Color = Po.Gold, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Po.CardHigh, Po.Card)))
            .border(1.dp, Po.Stroke, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.width(4.dp))
        Text(title, color = Po.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Po.Muted, fontSize = 16.sp)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Po.Card)
            .border(1.dp, Po.Stroke, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Po.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = modifier)
}

/** Choix de la langue : français ou russe, toujours visible sur l'accueil. */
@Composable
fun LanguageToggle(current: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Po.Card)
            .border(1.dp, Po.Stroke, RoundedCornerShape(50))
            .padding(4.dp)
    ) {
        for ((code, label) in listOf("fr" to stringResource(R.string.lang_fr), "ru" to stringResource(R.string.lang_ru))) {
            val selected = current == code
            Text(
                label,
                color = if (selected) Po.Night else Po.Text,
                fontSize = 18.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Po.Gold else Color.Transparent)
                    .clickable { if (!selected) onChange(code) }
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            )
        }
    }
}

/** do3 (C4) en français, до (C4) en russe. */
fun noteLabel(note: Int, names: Array<String>, lang: String): String {
    val name = names[note % 12]
    val sci = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")[note % 12] + (note / 12 - 1)
    return if (lang == "fr") "$name${note / 12 - 2} ($sci)" else "$name ($sci)"
}

/** Les écrans de l'application. */
enum class Screen { HOME, KEYBOARD, PLAYALONG, FREEPLAY, SETTINGS }
