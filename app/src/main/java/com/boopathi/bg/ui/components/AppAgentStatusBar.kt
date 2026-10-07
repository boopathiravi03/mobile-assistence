package com.boopathi.bg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boopathi.bg.service.agent.AppActionResult
import com.boopathi.bg.service.agent.AppActionResultStatus
import com.boopathi.bg.service.agent.AppAgentState
import com.boopathi.bg.ui.theme.ElectricBlue
import com.boopathi.bg.ui.theme.NeonCyan
import com.boopathi.bg.ui.theme.PulseAmber
import com.boopathi.bg.ui.theme.TextPrimary
import com.boopathi.bg.ui.theme.TextSecondary

@Composable
fun AppAgentStatusBar(
    isAccessibilityEnabled: Boolean,
    isAccessibilityConnected: Boolean,
    currentPackage: String,
    agentState: AppAgentState,
    lastResult: AppActionResult?,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionColor = when {
        isAccessibilityConnected -> Color(0xFF00E676) // Neon Green Connected
        isAccessibilityEnabled -> PulseAmber           // Enabled but disconnected
        else -> Color(0xFFFF5252)                      // Disabled
    }

    val connectionLabel = when {
        isAccessibilityConnected -> "CONNECTED"
        isAccessibilityEnabled -> "ENABLED (STANDBY)"
        else -> "DISABLED"
    }

    val stateColor = when (agentState) {
        AppAgentState.IDLE -> TextSecondary
        AppAgentState.PLANNING -> ElectricBlue
        AppAgentState.EXECUTING -> NeonCyan
        AppAgentState.VERIFYING -> PulseAmber
        AppAgentState.SUCCESS -> Color(0xFF00E676)
        AppAgentState.FAILED -> Color(0xFFFF5252)
        AppAgentState.BLOCKED -> Color(0xFFFF1744)
        AppAgentState.REQUIRES_CONFIRMATION -> PulseAmber
        AppAgentState.WAITING_FOR_ACCESSIBILITY -> PulseAmber
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A0F1D))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "🤖 APP AGENT:",
                    color = NeonCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = agentState.name,
                    color = stateColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "A11Y: $connectionLabel",
                    color = connectionColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                if (!isAccessibilityConnected) {
                    Text(
                        text = "⚙️ ENABLE",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ElectricBlue.copy(alpha = 0.25f))
                            .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .clickable { onOpenSettingsClick() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (currentPackage.isNotEmpty() || lastResult != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPackage.isNotEmpty()) {
                    Text(
                        text = "PKG: ${currentPackage.takeLast(28)}",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }

                lastResult?.let { res ->
                    val resColor = if (res.isSuccess) Color(0xFF00E676) else Color(0xFFFF5252)
                    Text(
                        text = "${res.actionType}: ${res.status}",
                        color = resColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

