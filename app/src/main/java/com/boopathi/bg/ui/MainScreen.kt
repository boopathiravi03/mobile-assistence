package com.boopathi.bg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boopathi.bg.data.model.AssistantState
import com.boopathi.bg.ui.components.AssistantStatusBar
import com.boopathi.bg.ui.components.ConversationArea
import com.boopathi.bg.ui.components.QuickCommandBar
import com.boopathi.bg.ui.components.VoiceOrb
import com.boopathi.bg.ui.theme.BgDarkBackground
import com.boopathi.bg.ui.theme.DangerRed
import com.boopathi.bg.ui.theme.ElectricBlue
import com.boopathi.bg.ui.theme.NeonCyan
import com.boopathi.bg.ui.theme.PulseAmber
import com.boopathi.bg.ui.theme.TextSecondary

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit
) {
    val assistantState by viewModel.assistantState.collectAsState()
    val audioRmsDb by viewModel.audioRmsDb.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val hasMicPermission by viewModel.hasMicPermission.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showProfileDialog by viewModel.showProfileDialog.collectAsState()
    val ownerProfile by viewModel.ownerProfile.collectAsState()
    val memoryCount by viewModel.memoryCount.collectAsState()
    val noteCount by viewModel.noteCount.collectAsState()
    val taskCount by viewModel.taskCount.collectAsState()
    val lastToolResult by viewModel.lastToolResult.collectAsState()

    if (showProfileDialog) {
        com.boopathi.bg.ui.components.OwnerProfileDialog(
            profile = ownerProfile,
            memoryCount = memoryCount,
            noteCount = noteCount,
            taskCount = taskCount,
            onLanguageSelected = { lang -> viewModel.updateLanguage(lang) },
            onDismiss = { viewModel.closeProfile() }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top App Bar & Status Badges
            AssistantStatusBar(
                providerStatus = viewModel.providerStatus,
                hasMicPermission = hasMicPermission,
                errorMessage = errorMessage,
                onProfileClick = { viewModel.openProfile() }
            )

            // Conversation Messages Area
            ConversationArea(
                messages = messages,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Real-time Device Action Status Badge
            lastToolResult?.let { toolRes ->
                com.boopathi.bg.ui.components.ActionStatusBadge(
                    toolResult = toolRes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Quick Testing Commands Bar
            QuickCommandBar(
                onCommandSelected = { command ->
                    viewModel.executeCommand(command)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Voice Interaction Control Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large Interactive Voice Orb
                VoiceOrb(
                    state = assistantState,
                    audioRmsDb = audioRmsDb,
                    onClick = {
                        viewModel.onOrbClicked(requestPermission = onRequestPermission)
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Assistant State Text Label
                val stateTextColor = when (assistantState) {
                    AssistantState.IDLE -> TextSecondary
                    AssistantState.LISTENING -> NeonCyan
                    AssistantState.PROCESSING -> PulseAmber
                    AssistantState.SPEAKING -> ElectricBlue
                    AssistantState.ERROR -> DangerRed
                }

                Text(
                    text = assistantState.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = stateTextColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
            }
        }
    }
}

