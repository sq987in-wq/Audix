package com.candela.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.TextureView
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.core.content.ContextCompat
import com.candela.camera.Camera2Session
import com.candela.camera.GateResult
import com.candela.camera.ZxingRoiDecoder
import com.candela.platform.SessionWakeLock
import com.candela.platform.ThermalGovernor
import com.candela.platform.ThermalLevel
import com.candela.platform.VerifiedFileWriter
import com.candela.protocol.Bytes
import com.candela.protocol.Crypto
import com.candela.protocol.DecodedFrame
import com.candela.protocol.FountainDecoder
import com.candela.protocol.Frames
import com.candela.protocol.SessionState

@Composable
fun ReceiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) ask.launch(Manifest.permission.CAMERA) }

    var storageOk by remember { mutableStateOf(true) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { storageOk = it }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            storageOk = granted
            if (!granted) askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    var state by remember { mutableStateOf(SessionState.IDLE) }
    var sas by remember { mutableStateOf("") }
    var sasConfirmed by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf("Point camera at sender QR") }
    var hint by remember { mutableStateOf("Gate closed") }
    var notice by remember { mutableStateOf<String?>(null) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var blur by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var recovered by remember { mutableStateOf("0 / 0") }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var session by remember { mutableStateOf<Camera2Session?>(null) }
    val wake = remember { SessionWakeLock(context) }
    val thermal = remember { ThermalGovernor(context) }
    var thermalNote by remember { mutableStateOf("") }

    var header by remember { mutableStateOf<DecodedFrame.Header?>(null) }
    var decoder by remember { mutableStateOf<FountainDecoder?>(null) }
    var publicKey by remember { mutableStateOf<ByteArray?>(null) }

    fun finish(d: FountainDecoder, h: DecodedFrame.Header) {
        activity.runOnUiThread { state = SessionState.VERIFYING }
        try {
            val data = d.assemble() ?: throw IllegalStateException("reassembly failed")
            val path = VerifiedFileWriter.save(context, h.fileName, h.mime, data, h.fileHash)
            activity.runOnUiThread {
                state = SessionState.COMPLETE
                savedPath = path
                notice = "Verified SHA-256 ${Bytes.toHex(h.fileHash).take(16)}… saved"
            }
            session?.stop()
            wake.release()
        } catch (e: Exception) {
            activity.runOnUiThread {
                state = SessionState.ABORTED
                notice = e.message
            }
        }
    }

    fun ingest(bytes: ByteArray) {
        val frame = Frames.decode(bytes, publicKey) ?: return
        when (frame) {
            is DecodedFrame.Cal -> {
                if (state == SessionState.CALIBRATING || state == SessionState.IDLE) {
                    activity.runOnUiThread { state = SessionState.PAIRING }
                }
            }
            is DecodedFrame.Header -> {
                if (header != null && !Bytes.eq(header!!.sessionId, frame.sessionId)) return
                if (header == null) {
                    header = frame
                    publicKey = frame.publicKey
                    decoder = FountainDecoder(frame.k, frame.blockSize, frame.fileSize)
                    val s = Crypto.sasFromPublicKey(frame.publicKey)
                    activity.runOnUiThread {
                        sas = s
                        state = SessionState.PAIRING
                        meta = "${frame.fileName} · ${Bytes.formatBytes(frame.fileSize)} · k=${frame.k}"
                    }
                }
            }
            is DecodedFrame.Data -> {
                if (!sasConfirmed) return
                val h = header ?: return
                val d = decoder ?: return
                if (!Bytes.eq(frame.sessionId, h.sessionId)) return
                if (state == SessionState.PAIRING) activity.runOnUiThread { state = SessionState.RECEIVING }
                val neu = d.ingest(frame.symbolId, frame.payload)
                if (neu) {
                    activity.runOnUiThread {
                        recovered = "${d.doneCount()} / ${d.k}"
                        meta = "symbols ${d.uniqueCount()} · recovered ${d.doneCount()}/${d.k}"
                    }
                }
                if (d.isComplete()) finish(d, h)
            }
        }
    }

    fun startCamera(tv: TextureView) {
        session?.stop()
        header = null
        decoder = null
        publicKey = null
        sasConfirmed = false
        sas = ""
        savedPath = null
        notice = null
        state = SessionState.CALIBRATING
        wake.acquire()
        val cam = Camera2Session(context, tv)
        cam.listener = object : Camera2Session.Listener {
            override fun onFrame(luma: ByteArray, width: Int, height: Int, gate: GateResult) {
                activity.runOnUiThread {
                    blur = gate.blur
                    contrast = gate.contrast
                    hint = if (gate.pass) "Gate open · ${gate.reason}" else "Gate closed — ${gate.reason}"
                    if (gate.refuse && state == SessionState.CALIBRATING) {
                        notice = "Contrast below floor. Move out of sun, remove privacy film, go head-on."
                    }
                }
                if (!gate.pass) return
                val hit = ZxingRoiDecoder.decode(luma, width, height, null) ?: return
                cam.updateRoi(hit.rect)
                ingest(hit.bytes)
            }
            override fun onError(message: String) { notice = message }
            override fun onLocked() { hint = "AE/AF locked" }
        }
        session = cam
        cam.start()
        thermal.onLevel = { lvl ->
            thermalNote = when (lvl) {
                ThermalLevel.LIGHT -> "thermal light — downsample"
                ThermalLevel.MODERATE -> "thermal moderate — duty cycle"
                ThermalLevel.SEVERE -> "thermal severe — paused"
                ThermalLevel.CRITICAL -> "thermal critical — abort"
                else -> ""
            }
            if (lvl == ThermalLevel.CRITICAL) {
                cam.stop()
                state = SessionState.ABORTED
            }
            if (lvl == ThermalLevel.SEVERE) state = SessionState.PAUSED
        }
        thermal.start()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    session?.stop()
                    wake.release()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    session?.stop()
                    wake.release()
                    thermal.stop()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            session?.stop()
            wake.release()
            thermal.stop()
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0A0908)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("RECEIVER", color = Color(0xFFC49A4A), fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
            Text(state.name, color = Color(0xFFC49A4A), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!permission) ask.launch(Manifest.permission.CAMERA)
                    else texture?.let { startCamera(it) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4EFE6), contentColor = Color(0xFF0A0908)),
            ) { Text("Open camera") }
            OutlinedButton(onClick = {
                session?.stop()
                wake.release()
                onBack()
            }) { Text("Back") }
        }
        if (sas.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    .border(1.dp, Color(0xFFC49A4A), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text("COMPARE WITH SENDER — ZRTP, no server", color = Color(0xFF7D7468), fontSize = 10.sp, letterSpacing = 1.sp)
                Text(Crypto.sasPretty(sas), color = Color(0xFFC49A4A), fontSize = 32.sp, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
                Button(
                    onClick = { sasConfirmed = true; if (state == SessionState.PAIRING) state = SessionState.RECEIVING },
                    enabled = !sasConfirmed,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC49A4A), contentColor = Color(0xFF0A0908)),
                ) { Text(if (sasConfirmed) "SAS confirmed" else "SAS matches — receive data") }
            }
        }
        Text(meta, color = Color(0xFFB7AEA0), fontSize = 13.sp)
        Text(hint, color = Color(0xFF7D7468), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (thermalNote.isNotEmpty()) Text(thermalNote, color = Color(0xFFC45C3E), fontSize = 12.sp)
        notice?.let { Text(it, color = Color(0xFF7A9E6A), fontSize = 13.sp) }
        savedPath?.let { Text("Saved: $it", color = Color(0xFF7A9E6A), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("blur ${"%.1f".format(blur)}", color = Color(0xFFF4EFE6), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("CR ${(contrast * 100).toInt()}%", color = Color(0xFFF4EFE6), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(recovered, color = Color(0xFFF4EFE6), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    tv.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    texture = tv
                    if (permission) startCamera(tv)
                }
            },
        )
    }
}
