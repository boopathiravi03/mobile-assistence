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

import com.boopathi.bg.service.agent.AgentTask
import com.boopathi.bg.service.agent.AgentTaskState
import com.boopathi.bg.service.agent.StepStatus

@Composable
fun AppAgentStatusBar(
    isAccessibilityEnabled: Boolean,
    isAccessibilityConnected: Boolean,
    currentPackage: String,
    agentState: AppAgentState,
    lastResult: AppActionResult?,
    currentTask: AgentTask? = null,
    taskState: AgentTaskState = AgentTaskState.IDLE,
    onOpenSettingsClick: () -> Unit,
    onCancelTaskClick: () -> Unit = {},
    onConfirmClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectionColor = when {
        isAccessibilityConnected -> Color(0xFF00E676) // Neon Green Connected
        isAccessibilityEnabled -> PulseAmber           // Enabled but disconnected
        else -> Color(0xFFFF5252)                      // Disabled
    }

    val connectionLabel = when {
        isAccessibilityConnected -> "CONNECTED"
        isAccessibilityEnabled -> "STANDBY"
        else -> "DISABLED"
    }

    val effectiveStateName = if (currentTask != null && !currentTask.isComplete) {
        taskState.name
    } else {
        agentState.name
    }

    val stateColor = when {
        taskState == AgentTaskState.EXECUTING || agentState == AppAgentState.EXECUTING -> NeonCyan
        taskState == AgentTaskState.VERIFYING || agentState == AppAgentState.VERIFYING -> PulseAmber
        taskState == AgentTaskState.PLANNING || agentState == AppAgentState.PLANNING -> ElectricBlue
        taskState == AgentTaskState.SUCCESS || agentState == AppAgentState.SUCCESS -> Color(0xFF00E676)
        taskState == AgentTaskState.FAILED || agentState == AppAgentState.FAILED -> Color(0xFFFF5252)
        taskState == AgentTaskState.BLOCKED || agentState == AppAgentState.BLOCKED -> Color(0xFFFF1744)
        taskState == AgentTaskState.CANCELLED -> Color(0xFFFF9100)
        taskState == AgentTaskState.WAITING_FOR_CONFIRMATION -> PulseAmber
        else -> TextSecondary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A0F1D))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Top status row
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
                    text = effectiveStateName,
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

        // Active Multi-Step Task Progress Section
        if (currentTask != null && (!currentTask.isComplete || taskState == AgentTaskState.WAITING_FOR_CONFIRMATION)) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF070B14))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Task: ${currentTask.normalizedGoal}",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Step ${currentTask.currentStepIndex + 1}/${currentTask.totalSteps}",
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Steps list view
                currentTask.steps.forEachIndexed { idx, step ->
                    val stepIcon = when (step.status) {
                        StepStatus.SUCCESS -> "✓"
                        StepStatus.RUNNING, StepStatus.VERIFYING -> "●"
                        StepStatus.RETRYING -> "↻"
                        StepStatus.FAILED -> "✗"
                        StepStatus.BLOCKED -> "⛔"
                        StepStatus.WAITING_FOR_CONFIRMATION -> "⚠️"
                        else -> "○"
                    }
                    val stepColor = when (step.status) {
                        StepStatus.SUCCESS -> Color(0xFF00E676)
                        StepStatus.RUNNING -> NeonCyan
                        StepStatus.FAILED, StepStatus.BLOCKED -> Color(0xFFFF5252)
                        StepStatus.WAITING_FOR_CONFIRMATION -> PulseAmber
                        else -> TextSecondary
                    }

                    Text(
                        text = "$stepIcon ${step.description}",
                        color = stepColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (taskState == AgentTaskState.WAITING_FOR_CONFIRMATION) {
                        Text(
                            text = "✓ CONFIRM",
                            color = Color(0xFF00E676),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF00E676).copy(alpha = 0.2f))
                                .border(1.dp, Color(0xFF00E676), RoundedCornerShape(4.dp))
                                .clickable { onConfirmClick() }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }

                    Text(
                        text = "⏹ STOP",
                        color = Color(0xFFFF5252),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFF5252).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(4.dp))
                            .clickable { onCancelTaskClick() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        } else if (currentPackage.isNotEmpty() || lastResult != null) {
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

