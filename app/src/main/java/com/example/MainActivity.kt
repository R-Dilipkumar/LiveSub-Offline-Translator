package com.example

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ui.AudioTranslatorViewModel
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: AudioTranslatorViewModel by viewModels()

    // Activity result launcher for MediaProjection permission dialog
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Window first: if the capture fails right away, the service can close it again
            openOverlayIfEnabled()
            viewModel.startInternalCapture(result.resultCode, result.data!!)
        } else {
            viewModel.showError("Audio capture permission was denied or cancelled.")
        }
    }

    private var pendingMicCapture = false
    // Capture must wait until the user has granted the overlay permission.
    // Nullable distinguishes no pending request from a pending microphone=false request.
    private var pendingCaptureAfterOverlay: Boolean? = null

    // Permission launcher for RECORD_AUDIO and POST_NOTIFICATIONS
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            if (pendingMicCapture) {
                openOverlayIfEnabled()
                viewModel.startMicCapture()
            } else {
                launchScreenCaptureIntent()
            }
        } else {
            viewModel.showError("Audio recording permission is required to capture sound.")
            Toast.makeText(this, "Permission denied: Audio Recording", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher for SYSTEM_ALERT_WINDOW (Floating Window overlay)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission()) {
            // Clear any stale permission warning as soon as Android confirms the grant.
            viewModel.dismissError()
            val pendingCapture = pendingCaptureAfterOverlay
            if (pendingCapture != null) {
                pendingCaptureAfterOverlay = null
                // Resume the Start subtitles flow only after overlay access is granted.
                checkAndRequestPermissions(pendingCapture)
            } else {
                // The user opened the permission screen from the floating-overlay toggle.
                viewModel.startFloatingOverlay()
                Toast.makeText(this, "Floating subtitles on", Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingCaptureAfterOverlay = null
            viewModel.showError("Floating window overlay permission was not granted.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestCapturePermission = { isMic -> checkAndRequestPermissions(isMic) },
                    onRequestOverlayPermission = { requestFloatingOverlay() }
                )
            }
        }
    }

    /**
     * Android can briefly report a stale overlay AppOp immediately after a permission
     * change or when returning from Settings. Check the public API first, then the
     * authoritative AppOps mode on Android 10+ before treating the permission as absent.
     */
    private fun hasOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(this)) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val appOps = getSystemService(AppOpsManager::class.java) ?: return false
            return appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                Process.myUid(),
                packageName
            ) == AppOpsManager.MODE_ALLOWED
        }
        return false
    }

    /** Opens the floating subtitles together with capture, unless turned off in Settings. */
    private fun openOverlayIfEnabled() {
        if (viewModel.appSettings.value.isAutoOverlayEnabled) {
            requestFloatingOverlay(quiet = true)
        }
    }

    fun requestFloatingOverlay(quiet: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasOverlayPermission()) {
                // Give Android/OEM permission state a moment to settle before opening
                // Settings. This avoids sending users to Settings for a stale false result.
                lifecycleScope.launch {
                    repeat(4) {
                        delay(250)
                        if (hasOverlayPermission()) {
                            viewModel.startFloatingOverlay()
                            if (!quiet) Toast.makeText(
                                this@MainActivity,
                                "Floating subtitles on",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                    }

                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlayPermissionLauncher.launch(intent)
                    } catch (e: Exception) {
                        val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        overlayPermissionLauncher.launch(fallbackIntent)
                    }
                }
            } else {
                viewModel.dismissError()
                viewModel.startFloatingOverlay()
                if (!quiet) Toast.makeText(this, "Floating subtitles on", Toast.LENGTH_SHORT).show()
            }
        } else {
            viewModel.startFloatingOverlay()
        }
    }

    private fun checkAndRequestPermissions(isMic: Boolean = false) {
        // Overlay permission is part of the Start subtitles flow. Request it first so
        // the capture flow cannot begin while the required floating-window access is missing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlayPermission()) {
            pendingCaptureAfterOverlay = isMic
            requestFloatingOverlay()
            return
        }

        pendingCaptureAfterOverlay = null
        viewModel.dismissError()
        pendingMicCapture = isMic
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            if (isMic) {
                openOverlayIfEnabled()
                viewModel.startMicCapture()
            } else {
                launchScreenCaptureIntent()
            }
        }
    }

    private fun launchScreenCaptureIntent() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager != null) {
            try {
                val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                mediaProjectionLauncher.launch(captureIntent)
            } catch (e: Exception) {
                viewModel.showError("Failed to initiate screen/audio capture: ${e.localizedMessage}")
            }
        } else {
            viewModel.showError("MediaProjection service unavailable on this device.")
        }
    }
}
