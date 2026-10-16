package com.boopathi.bg.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.boopathi.bg.ui.theme.BGTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initVoiceManager(this)

        val hasInitialMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasInitialMicPermission) viewModel.onMicPermissionResult(true)

        setContent {
            BGTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted ->
                        viewModel.onMicPermissionResult(isGranted)
                    }
                )

                MainScreen(
                    viewModel = viewModel,
                    onRequestPermission = {
                        val prefs = getSharedPreferences("voice_permission", MODE_PRIVATE)
                        val requestedBefore = prefs.getBoolean("requested", false)
                        if (requestedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        } else {
                            prefs.edit().putBoolean("requested", true).apply()
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }
        }
    }
}

