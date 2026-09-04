package com.candela.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.candela.platform.SessionWakeLock
import com.candela.platform.ThermalGovernor
import com.candela.protocol.BlockSource
import com.candela.protocol.Bytes
import com.candela.protocol.Crypto
import com.candela.protocol.Density
import com.candela.protocol.Protocol
import com.candela.protocol.SessionState
import com.candela.render.QrSurfaceView
import com.candela.render.SenderDisplayController
import java.security.SecureRandom

@Composable
fun SendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileSize by remember { mutableStateOf(0L) }
    var mime by remember { mutableStateOf("application/octet-stream") }
    var state by remember { mutableStateOf(SessionState.IDLE) }
    var sas by remember { mutableStateOf("") }
    var sasConfirmed by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var density by remember { mutableStateOf(Density.STANDARD) }
    var surfaceView by remember { mutableStateOf<QrSurfaceView?>(null) }
    var controller by remember { mutableStateOf<SenderDisplayController?>(null) }
    val wake = remember { SessionWakeLock(context) }
    val thermal = remember { ThermalGovernor(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}
        fileUri = uri
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) fileName = c.getString(nameIdx)
                if (sizeIdx >= 0) fileSize = c.getLong(sizeIdx)
            }
        }
        mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    DisposableEffect(Unit) {
        thermal.start()
        onDispose {
            controller?.stop()
            wake.release()
            thermal.stop()
        }
    }

    fun startTransmit() {
        val uri = fileUri ?: return
        val name = fileName ?: "file.bin"
        if (fileSize <= 0L || fileSize > Protocol.MAX_FILE_BYTES) {
            error = "File must be 1 B … 1 MB"
            return
        }
        val view = surfaceView ?: return
        error = null
        sasConfirmed = false
        val keys = Crypto.generateKeyPair()
        val sessionId = ByteArray(8).also { SecureRandom().nextBytes(it) }
        sas = Crypto.sasFromPublicKey(keys.publicKey)
        val source = BlockSource(
            open = { context.contentResolver.openInputStream(uri) ?: error("open failed") },
            fileSize = fileSize,
            blockSize = density.payloadBytes,
        )
        val hash = source.sha256()
        val ctrl = SenderDisplayController(activity, view, density)
        ctrl.onState = { s -> activity.runOnUiThread { state = s } }
        ctrl.onProgress = { i, t -> activity.runOnUiThread { progress = "symbol $i / $t" } }
        controller = ctrl
        wake.acquire()
        ctrl.start(
            source = source,
            sessionId = sessionId,
            fileName = name,
            mime = mime,
            fileHash = hash,
            publicKey = keys.publicKey,
            secretKey = keys.secretKey,
            waitForSas = { sasConfirmed },
        )
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0A0908)).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("SENDER", color = Color(0xFFC49A4A), fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
            Text(state.name, color = Color(0xFFC49A4A), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(fileName?.let { "$it · ${Bytes.formatBytes(fileSize)}" } ?: "No file selected", color = Color(0xFFF4EFE6), fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose file") }
            Button(
                onClick = { startTransmit() },
                enabled = fileUri != null && state != SessionState.SENDING && state != SessionState.CALIBRATING,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4EFE6), contentColor = Color(0xFF0A0908)),
            ) { Text("Prepare") }
            OutlinedButton(onClick = {
                controller?.stop()
                wake.release()
                onBack()
            }) { Text("Back") }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Density.entries.forEach { d ->
                val selected = density == d
                OutlinedButton(onClick = { density = d }) {
                    Text(d.name.lowercase(), color = if (selected) Color(0xFFC49A4A) else Color(0xFFB7AEA0))
                }
            }
        }
        if (sas.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    .border(1.dp, Color(0xFFC49A4A), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text("SHORT AUTHENTICATION STRING — read aloud", color = Color(0xFF7D7468), fontSize = 10.sp, letterSpacing = 1.sp)
                Text(Crypto.sasPretty(sas), color = Color(0xFFC49A4A), fontSize = 32.sp, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
                Text("Compare with the receiver. Only confirm if the digits match.", color = Color(0xFFB7AEA0), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { sasConfirmed = true },
                    enabled = !sasConfirmed && state == SessionState.PAIRING,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC49A4A), contentColor = Color(0xFF0A0908)),
                ) { Text(if (sasConfirmed) "SAS confirmed — transmitting" else "SAS matches — start data") }
            }
        }
        if (progress.isNotEmpty()) Text(progress, color = Color(0xFFB7AEA0), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        error?.let { Text(it, color = Color(0xFFC45C3E), fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                QrSurfaceView(ctx).also { v ->
                    v.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    surfaceView = v
                }
            },
        )
    }
}
