package com.boopathi.bg.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.boopathi.bg.data.model.AssistantState
import com.boopathi.bg.ui.theme.CyberPurple
import com.boopathi.bg.ui.theme.DangerRed
import com.boopathi.bg.ui.theme.ElectricBlue
import com.boopathi.bg.ui.theme.NeonCyan
import com.boopathi.bg.ui.theme.PulseAmber

@Composable
fun VoiceOrb(
    state: AssistantState,
    audioRmsDb: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbTransition")

    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idlePulse"
    )

    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    // Dynamic scale based on state and microphone RMS volume
    val effectiveScale = when (state) {
        AssistantState.IDLE, AssistantState.CANCELLED, AssistantState.PAUSED -> idlePulse
        AssistantState.LISTENING -> {
            val normalizedRms = (audioRmsDb.coerceIn(0f, 12f) / 12f)
            1.0f + (normalizedRms * 0.22f)
        }
        AssistantState.PROCESSING, AssistantState.RECOGNIZING, AssistantState.EXECUTING,
        AssistantState.WAITING_FOR_CONFIRMATION, AssistantState.REQUESTING_PERMISSION -> idlePulse
        AssistantState.SPEAKING -> idlePulse * 1.08f
        AssistantState.ERROR -> 1.0f
    }

    val (glowColors, coreColors, icon) = when (state) {
        AssistantState.IDLE, AssistantState.CANCELLED, AssistantState.PAUSED,
        AssistantState.REQUESTING_PERMISSION -> Triple(
            listOf(NeonCyan.copy(alpha = 0.15f), Color.Transparent),
            listOf(ElectricBlue, NeonCyan),
            Icons.Default.Mic
        )
        AssistantState.LISTENING -> Triple(
            listOf(NeonCyan.copy(alpha = 0.45f), ElectricBlue.copy(alpha = 0.1f)),
            listOf(NeonCyan, ElectricBlue),
            Icons.Default.Mic
        )
        AssistantState.PROCESSING, AssistantState.RECOGNIZING, AssistantState.EXECUTING,
        AssistantState.WAITING_FOR_CONFIRMATION -> Triple(
            listOf(PulseAmber.copy(alpha = 0.35f), CyberPurple.copy(alpha = 0.15f)),
            listOf(PulseAmber, CyberPurple),
            Icons.Default.Psychology
        )
        AssistantState.SPEAKING -> Triple(
            listOf(NeonCyan.copy(alpha = 0.4f), CyberPurple.copy(alpha = 0.2f)),
            listOf(CyberPurple, NeonCyan),
            Icons.Default.GraphicEq
        )
        AssistantState.ERROR -> Triple(
            listOf(DangerRed.copy(alpha = 0.4f), Color.Transparent),
            listOf(DangerRed, Color(0xFFB71C1C)),
            Icons.Default.Warning
        )
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(190.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow ripple
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(effectiveScale)
                .clip(CircleShape)
                .background(Brush.radialGradient(glowColors))
        )

        // Middle ring
        Box(
            modifier = Modifier
                .size(130.dp)
                .scale(effectiveScale)
                .clip(CircleShape)
                .border(2.dp, Brush.sweepGradient(coreColors), CircleShape)
                .background(Color(0xFF0F172A).copy(alpha = 0.7f))
        )

        // Inner glowing core button
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(coreColors)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (state == AssistantState.SPEAKING) Icons.Default.Stop else icon,
                contentDescription = state.name,
                tint = Color.White,
                modifier = Modifier.size(42.dp)
            )
        }
    }
}

