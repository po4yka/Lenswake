package dev.po4yka.lenswake

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.net.toUri
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.ui.LenswakeApp
import dev.po4yka.lenswake.ui.AndroidUiStringProvider
import dev.po4yka.lenswake.ui.LenswakeViewModel
import dev.po4yka.lenswake.ui.theme.LenswakeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LenswakeViewModel by viewModels {
        LenswakeViewModel.Factory(
            graph = (application as LenswakeApplication).graph,
            strings = AndroidUiStringProvider(applicationContext),
        )
    }
    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshPreflight()
    }
    private val mediaVideoPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPreflight()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LenswakeTheme {
                LenswakeApp(
                    viewModel = viewModel,
                    onRemediate = ::remediate,
                    onOpenPixelCamera = ::openPixelCamera,
                    onExportDiagnostics = ::exportDiagnostics,
                )
            }
        }
    }

    private fun remediate(action: SetupRemediationAction) {
        when (action) {
            SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION ->
                notificationPermissionRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS)

            SetupRemediationAction.REQUEST_MEDIA_VIDEO_PERMISSION ->
                mediaVideoPermissionRequest.launch(
                    arrayOf(
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    ),
                )

            else -> startRemediationActivity(action)
        }
    }

    private fun openPixelCamera() {
        val intent = packageManager.getLaunchIntentForPackage(PIXEL_CAMERA_PACKAGE) ?: return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Opening Camera is an optional manual aid; it must never resolve the incident.
        }
    }

    private fun exportDiagnostics() {
        val report = viewModel.diagnosticsExport() ?: return
        try {
            startActivity(
                diagnosticsShareIntent(
                    report = report,
                    subject = getString(R.string.diagnostics_export_title),
                    chooserTitle = getString(R.string.diagnostics_share_title),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                R.string.diagnostics_share_unavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun startRemediationActivity(action: SetupRemediationAction) {
        val packageUri = "package:$packageName".toUri()
        val intent = when (action) {
            SetupRemediationAction.OPEN_NOTIFICATION_SETTINGS -> Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            ).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

            SetupRemediationAction.OPEN_EXACT_ALARM_SETTINGS -> Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                packageUri,
            )

            SetupRemediationAction.OPEN_ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            SetupRemediationAction.OPEN_FULL_SCREEN_INTENT_SETTINGS -> Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                packageUri,
            )

            SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION,
            SetupRemediationAction.REQUEST_MEDIA_VIDEO_PERMISSION,
            -> return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.reportSetupRemediationUnavailable(action)
        }
    }

    private companion object {
        const val PIXEL_CAMERA_PACKAGE = "com.google.android.GoogleCamera"
    }
}

internal fun diagnosticsShareIntent(
    report: String,
    subject: String,
    chooserTitle: String,
): Intent {
    val shareIntent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, report)
    return Intent.createChooser(shareIntent, chooserTitle)
}
