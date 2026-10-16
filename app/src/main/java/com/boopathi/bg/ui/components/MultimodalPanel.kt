package com.boopathi.bg.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boopathi.bg.service.multimodal.MultimodalAnalysisResult
import com.boopathi.bg.service.multimodal.MultimodalImage
import com.boopathi.bg.ui.theme.BgCardDark
import com.boopathi.bg.ui.theme.DangerRed
import com.boopathi.bg.ui.theme.ElectricBlue
import com.boopathi.bg.ui.theme.NeonCyan
import com.boopathi.bg.ui.theme.PulseAmber
import com.boopathi.bg.ui.theme.TextPrimary
import com.boopathi.bg.ui.theme.TextSecondary

/**
 * Phase 7 Multimodal interaction panel.
 *
 * Provides:
 * - User-authorized photo picker integration.
 * - Image thumbnail preview & metadata display.
 * - Question input with quick preset prompts (English & Tamil/Tanglish).
 * - Action-separated advisory perception output.
 */
@Composable
fun MultimodalPanel(
    selectedImage: MultimodalImage?,
    isAnalyzing: Boolean,
    lastAnalysisResult: MultimodalAnalysisResult?,
    onImageSelected: (android.net.Uri) -> Unit,
    onClearImage: () -> Unit,
    onAnalyzeImage: (String) -> Unit,
    onCancelAnalysis: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    var questionText by remember { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onImageSelected(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCardDark.copy(alpha = 0.85f))
            .border(1.dp, ElectricBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        if (selectedImage == null) {
            // Header button to pick an image or screenshot
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AddPhotoAlternate,
                    contentDescription = "Attach image or screenshot",
                    tint = NeonCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Attach Image / Screenshot for Visual Analysis",
                    color = NeonCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // Image preview header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bitmap thumbnail preview
                val bitmap = remember(selectedImage.id) {
                    try {
                        BitmapFactory.decodeByteArray(selectedImage.bytes, 0, selectedImage.bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Selected Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Active Image Session",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${selectedImage.width}x${selectedImage.height} • ${selectedImage.sizeBytes / 1024} KB",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Source: ${selectedImage.source.name} • Ephemeral Memory",
                        color = ElectricBlue,
                        fontSize = 10.sp
                    )
                }

                IconButton(
                    onClick = {
                        onClearImage()
                        questionText = ""
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove image",
                        tint = DangerRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick preset analysis prompts
            val presetPrompts = listOf(
                "Summarize this image",
                "Indha screenshot-la enna irukku?",
                "Explain this error message",
                "Indha screen-la Settings enga irukku?",
                "Indha error-a simple Tamil-la explain pannu"
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(presetPrompts) { preset ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElectricBlue.copy(alpha = 0.15f))
                            .border(1.dp, ElectricBlue.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .clickable(enabled = !isAnalyzing) {
                                questionText = preset
                                onAnalyzeImage(preset)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = preset,
                            color = NeonCyan,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Question Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = { questionText = it },
                    placeholder = {
                        Text(
                            text = "Ask about this image...",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    },
                    enabled = !isAnalyzing,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = ElectricBlue.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (isAnalyzing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cancel",
                            color = DangerRed,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { onCancelAnalysis() }
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            val promptToSend = questionText.ifBlank { "Summarize this image" }
                            onAnalyzeImage(promptToSend)
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NeonCyan.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Analyze image",
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Analysis Result View
        if (lastAnalysisResult != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgCardDark)
                    .border(1.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Visual Perception (${lastAnalysisResult.providerName})",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "✕",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onDismissResult() }
                            .padding(4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = lastAnalysisResult.summary,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                if (lastAnalysisResult.visualElements.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Detected Elements: ${lastAnalysisResult.visualElements.joinToString(", ")}",
                        color = ElectricBlue,
                        fontSize = 11.sp
                    )
                }

                if (lastAnalysisResult.suggestedActions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Suggested Next Steps (Advisory Only):",
                        color = PulseAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    for (action in lastAnalysisResult.suggestedActions) {
                        Text(
                            text = "• $action",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                if (lastAnalysisResult.isPromptInjectionDetected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠️ Embedded prompt override instructions in image were neutralized.",
                        color = DangerRed,
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ℹ Advisory Only — No Android device action was executed.",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

