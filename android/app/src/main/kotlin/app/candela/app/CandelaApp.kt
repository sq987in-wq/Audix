package app.candela.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.candela.protocol.SessionState

/**
 * The Compose shell.
 *
 * Compose owns navigation, the coach HUD and the SAS screen — and NOTHING on a
 * per-frame path. The QR transmit plane is a SurfaceView (:optical-render) and
 * the camera preview is a SurfaceView too. Audit kill #5 is per-frame
 * recomposition; the shell is structured so there is nothing here for a frame to
 * invalidate.
 *
 * Screen routing is driven entirely by [SessionState], the domain state machine.
 * There is no separate UI-navigation state that could disagree with it — in
 * particular there is no route that reaches RECEIVING without passing through
 * PAIRING.
 */
@Composable
fun CandelaReceiveShell(
    ui: ReceiveViewModel.UiState,
    hasCameraPermission: Boolean,
    onStart: () -> Unit,
    onGrantPermission: () -> Unit,
    onConfirmSas: () -> Unit,
    onReportMismatch: () -> Unit,
    onAbort: () -> Unit,
    cameraPreview: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0F1115))) {
        // Permission is checked before anything else: without it Camera2 throws
        // deep in the stack and the user sees only a black rectangle.
        if (!hasCameraPermission) {
            CameraPermissionScreen(onGrantPermission)
            return@Box
        }
        when (ui.sessionState) {
            SessionState.IDLE -> IdleScreen(onStart)

            SessionState.CALIBRATING -> CalibratingScreen(ui, cameraPreview)

            // The hard stop. Nothing behind this renders until both humans confirm.
            SessionState.PAIRING -> SasPairingScreen(
                sas = ui.sas.orEmpty(),
                gateState = ui.sasState,
                prompt = ui.sasPrompt,
                onConfirmMatch = onConfirmSas,
                onReportMismatch = onReportMismatch,
                onAbort = onAbort,
            )

            SessionState.RECEIVING, SessionState.SENDING ->
                ReceivingScreen(ui, cameraPreview, onAbort)

            SessionState.VERIFYING -> CenteredMessage(
                "Verifying integrity",
                "Checking SHA-256 against the signed header. Nothing is written until it matches.",
            )

            SessionState.COMPLETE -> CompleteScreen(ui, onStart)

            SessionState.ABORTED -> AbortedScreen(ui, onStart)

            // The governor's own wording, not a generic string: it names the
            // cause and promises resume, because an unexplained stall makes the
            // user move the phone and lose the calibration pose.
            SessionState.PAUSED -> CenteredMessage(
                "Paused to cool down",
                ui.thermalMessage
                    ?: "The device is warm, so the session paused. Progress is kept " +
                    "and the transfer resumes automatically. Hold position.",
            )
        }
    }
}

@Composable
private fun IdleScreen(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Candela", color = Color(0xFFE8EAF0), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Air-gapped transfer over light. No radio, no network, no server.",
            color = Color(0xFF9AA3B2),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Text(
            "Place both phones on a desk, 15–40 cm apart, facing each other. " +
                "Avoid direct sunlight and remove privacy screen protectors.",
            color = Color(0xFF6F7787),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6DF6)),
        ) {
            Text("Start receiving", fontSize = 16.sp)
        }
    }
}

@Composable
private fun CalibratingScreen(
    ui: ReceiveViewModel.UiState,
    cameraPreview: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        cameraPreview(Modifier.fillMaxSize())
        // A black preview is ambiguous on its own, so say which of the three
        // possible causes is actually happening.
        if (!ui.cameraStreaming || ui.cameraError != null) {
            CameraStatusOverlay(ui.cameraError)
        }
        CornerGuideOverlay(
            ui.roiLeft, ui.roiTop, ui.roiWidth, ui.roiHeight,
            locked = ui.coach.gatePass,
        )
        Column(
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) {
            ui.calibrationMessage?.let {
                Text(
                    it,
                    color = Color(0xFFFFB300),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            CoachHud(
                blur = ui.coach.blur,
                blurMin = ui.thresholds.blurMin,
                contrast = ui.coach.contrast,
                contrastMin = ui.thresholds.contrastMin,
                motionStable = ui.motionStable,
                gatePass = ui.coach.gatePass,
                hint = "Calibrating — hold steady on the sender's code",
                fps = ui.coach.fps,
                recovered = 0,
                total = 0,
                decodeMs = ui.coach.decodeMs,
            )
        }
    }
}

@Composable
private fun ReceivingScreen(
    ui: ReceiveViewModel.UiState,
    cameraPreview: @Composable (Modifier) -> Unit,
    onAbort: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        cameraPreview(Modifier.fillMaxSize())
        // A black preview is ambiguous on its own, so say which of the three
        // possible causes is actually happening.
        if (!ui.cameraStreaming || ui.cameraError != null) {
            CameraStatusOverlay(ui.cameraError)
        }
        CornerGuideOverlay(
            ui.roiLeft, ui.roiTop, ui.roiWidth, ui.roiHeight,
            locked = ui.coach.gatePass,
        )
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            CoachHud(
                blur = ui.coach.blur,
                blurMin = ui.thresholds.blurMin,
                contrast = ui.coach.contrast,
                contrastMin = ui.thresholds.contrastMin,
                motionStable = ui.motionStable,
                gatePass = ui.coach.gatePass,
                hint = ui.coach.reason,
                fps = ui.coach.fps,
                recovered = ui.coach.recovered,
                total = ui.coach.k,
                decodeMs = ui.coach.decodeMs,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = Color(0xFF9AA3B2))
            }
        }
    }
}

@Composable
private fun CompleteScreen(ui: ReceiveViewModel.UiState, onRestart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("\u2713", color = Color(0xFF3DDC84), fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Transfer complete", color = Color(0xFFE8EAF0), fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        ui.exportedName?.let {
            Text(it, color = Color(0xFFE8EAF0), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }
        Text(
            ui.exportMessage ?: "",
            color = if (ui.exportSucceeded) Color(0xFF9AA3B2) else Color(0xFFFF8A80),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Receive another")
        }
    }
}

@Composable
private fun AbortedScreen(ui: ReceiveViewModel.UiState, onRestart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Transfer aborted", color = Color(0xFFFF5252), fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            ui.sasPrompt.ifBlank { ui.exportMessage ?: "The session ended without writing anything." },
            color = Color(0xFF9AA3B2),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "No partial file was saved.",
            color = Color(0xFF6F7787),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Start over")
        }
    }
}

@Composable
private fun CenteredMessage(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Color(0xFFE8EAF0), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(body, color = Color(0xFF9AA3B2), fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/**
 * Shown until the sensor delivers its first frame, or when Camera2 reports an
 * error. Without this the user cannot tell "starting up" from "camera failed"
 * from "the scene is genuinely dark" — all three look identical.
 */
@Composable
private fun CameraStatusOverlay(error: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (error != null) "Camera problem" else "Starting camera…",
                color = if (error != null) Color(0xFFFF6B6B) else Color.White,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                error ?: "Waiting for the first frame from the sensor.",
                color = Color(0xFF9AA3B2),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

/**
 * The receiver is useless without CAMERA, so ask explicitly and explain why.
 * Silently showing a dead viewfinder — the previous behaviour — is the worst
 * option: the user grants the permission in Settings and nothing changes.
 */
@Composable
private fun CameraPermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera access needed", color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Receiving works by reading QR codes off the other device's screen, " +
                "so the camera is the only way in. Nothing is uploaded — the app " +
                "has no network permission at all.",
            color = Color(0xFF9AA3B2),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Allow camera") }
    }
}
