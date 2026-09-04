package app.candela.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.candela.protocol.SendSession
import app.candela.protocol.SessionState
import app.candela.render.QrSurfaceView
import app.candela.render.SymbolBitmapCache
import app.candela.render.SymbolScheduler

/**
 * The sender flow, routed entirely off [SessionState] — the same discipline as
 * the receiver shell, so no UI-only navigation state can display payload before
 * PAIRING has been passed.
 */
@Composable
fun SendFlow(
    ui: SendViewModel.UiState,
    vm: SendViewModel,
    onPickFile: () -> Unit,
    onConfirmSas: () -> Unit,
    onReportMismatch: () -> Unit,
    onAbort: () -> Unit,
    onDone: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0F1115))) {
        when {
            ui.error != null -> ErrorPane(ui.error, onPickFile, onAbort)

            ui.fileName == null -> PickPane(ui.loading, onPickFile)

            // The blocking compare. Nothing is transmitted behind this.
            ui.sessionState == SessionState.PAIRING -> SasPairingScreen(
                sas = ui.sas.orEmpty(),
                gateState = ui.sasState,
                prompt = ui.sasPrompt,
                onConfirmMatch = onConfirmSas,
                onReportMismatch = onReportMismatch,
                onAbort = onAbort,
            )

            ui.sessionState == SessionState.SENDING -> TransmitPane(ui, vm, onAbort)

            ui.sessionState == SessionState.PAUSED -> CenteredNotice(
                "Paused to cool down",
                ui.thermalMessage ?: "The device is warm. Transmission resumes shortly.",
                onAbort,
            )

            ui.sessionState == SessionState.ABORTED -> CenteredNotice(
                "Transfer stopped",
                "The session ended before it finished. Nothing partial was sent.",
                onDone,
            )

            ui.sessionState == SessionState.COMPLETE -> CenteredNotice(
                "Finished displaying",
                "All ${ui.symbolCount} symbols were shown. Check the receiving " +
                    "device — it verifies the file's SHA-256 before saving.",
                onDone,
            )

            else -> PickPane(ui.loading, onPickFile)
        }
    }
}

@Composable
private fun PickPane(loading: Boolean, onPickFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Send a file", color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (loading) "Reading and encoding…"
            else "Choose a file up to 1 MB. It is split into fountain-coded " +
                "symbols, signed, and displayed as a stream of QR codes.",
            color = Color(0xFF9AA3B2),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPickFile,
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C8DFF)),
        ) {
            Text(if (loading) "Working…" else "Choose file", fontSize = 17.sp)
        }
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't send this file", color = Color(0xFFFF6B6B), fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color(0xFF9AA3B2), fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Choose another file") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

/**
 * The transmit plane.
 *
 * The QR itself is a [QrSurfaceView] driven by Choreographer — never a Compose
 * canvas. Compose draws the status strip once per symbol at most; the symbol
 * blit never travels through recomposition (audit kill #5).
 */
@Composable
private fun TransmitPane(
    ui: SendViewModel.UiState,
    vm: SendViewModel,
    onAbort: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    QrSurfaceView(ctx).also { view ->
                        val session = vm.sendSession
                        val cache = SymbolBitmapCache()
                        val data = session.allDataFrames()
                        val header = session.headerFrameBytes()
                        val cal = session.calFrameBytes()
                        if (header != null && cal != null) {
                            cache.prerender(data, header, cal)
                        }
                        val scheduler = SymbolScheduler(vm.plan, data.size)
                        view.configure(
                            scheduler,
                            object : QrSurfaceView.Source {
                                override fun bitmapFor(
                                    content: SymbolScheduler.Content,
                                ) = cache.bitmapFor(content)

                                override fun onSlotAdvanced(
                                    content: SymbolScheduler.Content,
                                    slot: Int,
                                ) {
                                    vm.onSlotAdvanced()
                                }
                            },
                        )
                        view.start()
                    }
                },
            )
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                ui.fileName.orEmpty(),
                color = Color.White,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${ui.sizeBytes / 1024} KB · k=${ui.k} · ${ui.symbolCount} symbols · " +
                    "%.1f/s".format(ui.effectiveFps),
                color = Color(0xFF9AA3B2),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (ui.thermalMessage != null) {
                Spacer(Modifier.height(6.dp))
                Text(ui.thermalMessage, color = Color(0xFFFFB020), fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Keep both phones still, 15–40 cm apart, screen facing the " +
                    "other camera. Symbols repeat, so nothing is lost if a few are missed.",
                color = Color(0xFF6B7385),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onAbort) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun CenteredNotice(title: String, body: String, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Color(0xFF9AA3B2), fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss) { Text("Done") }
    }
}
