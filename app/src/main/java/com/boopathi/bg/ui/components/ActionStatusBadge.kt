package com.boopathi.bg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.boopathi.bg.service.tool.ToolResult
import com.boopathi.bg.service.tool.ToolStatus
import com.boopathi.bg.ui.theme.NeonCyan

@Composable
fun ActionStatusBadge(
    toolResult: ToolResult,
    modifier: Modifier = Modifier
) {
    val statusColor = when (toolResult.status) {
        ToolStatus.SUCCESS -> Color(0xFF00E676)     // Vibrant Neon Green
        ToolStatus.UNAVAILABLE -> Color(0xFFFFAB00) // Amber Warning
        ToolStatus.FAILED -> Color(0xFFFF5252)      // Red Error
    }

    val actionName = toolResult.action ?: toolResult.toolName
    val targetText = toolResult.target?.let { " • $it" } ?: ""

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0C1427))
            .border(1.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "⚡ $actionName$targetText",
                color = NeonCyan,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }

        Text(
            text = "[${toolResult.status}]",
            color = statusColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

