package com.boopathi.bg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boopathi.bg.ui.theme.ElectricBlue
import com.boopathi.bg.ui.theme.NeonCyan
import com.boopathi.bg.ui.theme.TextPrimary

@Composable
fun QuickCommandBar(
    onCommandSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val quickCommands = listOf(
        "Open Settings",
        "Inspect this screen",
        "Screen-la enna irukku sollu",
        "Scroll down",
        "Keela scroll pannu",
        "Scroll up",
        "Mela scroll pannu",
        "Go back",
        "Back po",
        "Open WiFi settings",
        "Turn on flashlight",
        "Turn off flashlight",
        "Increase volume",
        "Decrease volume",
        "Go home",
        "Open YouTube",
        "Open Instagram",
        "Open WhatsApp",
        "Instagram open pannu",
        "Flashlight on pannu",
        "Volume increase pannu",
        "WhatsApp open pannu",
        "Remember that my favorite programming language is Kotlin.",
        "What is my favorite programming language?",
        "What do you remember about me?",
        "What time is it?"
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(quickCommands) { command ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(ElectricBlue.copy(alpha = 0.15f))
                    .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .clickable { onCommandSelected(command) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    fontSize = 12.sp
                )
            }
        }
    }
}
