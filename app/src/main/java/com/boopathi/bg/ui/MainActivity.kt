package com.boopathi.bg.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.boopathi.bg.ui.theme.BGTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val credentialConfirmationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onDeviceCredentialAuthResult(result.resultCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.attachDeviceCredentialAuthLauncher { intent ->
            credentialConfirmationLauncher.launch(intent)
        }

        viewModel.initVoiceManager(this)
        viewModel.onNotificationPermissionResult()

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
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { viewModel.onNotificationPermissionResult() }
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
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            val permission = Manifest.permission.POST_NOTIFICATIONS
                            val prefs = getSharedPreferences("reminder_permission", MODE_PRIVATE)
                            val requestedBefore = prefs.getBoolean("requested", false)
                            if (requestedBefore && !shouldShowRequestPermissionRationale(permission)) {
                                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                })
                            } else {
                                prefs.edit().putBoolean("requested", true).apply()
                                notificationPermissionLauncher.launch(permission)
                            }
                        } else {
                            viewModel.onNotificationPermissionResult()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onNotificationPermissionResult()
    }
}

