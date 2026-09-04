package app.candela.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.candela.platform.SessionWakeLock
import app.candela.platform.ThermalMonitor
import app.candela.protocol.SessionState

/**
 * Single activity hosting both roles.
 *
 * Owns exactly four things: role navigation, the camera permission, the screen
 * wake lock, and the Surfaces. Every transfer rule lives in SendSession /
 * ReceiveSession, which are pure and unit-tested.
 */
class MainActivity : ComponentActivity() {

    private enum class Route { HOME, SEND, RECEIVE }

    private val receiveVm: ReceiveViewModel by viewModels()
    private val sendVm: SendViewModel by viewModels()

    private var camera: CameraBinding? = null
    private lateinit var thermal: ThermalMonitor
    private lateinit var wakeLock: SessionWakeLock

    private var route by mutableStateOf(Route.HOME)
    private var hasCamera by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCamera = granted
        // Only start the camera once the user has actually granted it. Opening
        // on a denied permission throws SecurityException deep in Camera2 and
        // surfaces as an unexplained black preview.
        if (granted && route == Route.RECEIVE) startReceiving()
    }

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let { sendVm.loadFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A transfer is tens of seconds of holding still without touching the
        // screen; the default timeout would abort it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        thermal = ThermalMonitor(this)
        wakeLock = SessionWakeLock(this)

        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

        if (thermal.isSupported) {
            thermal.start(ContextCompat.getMainExecutor(this)) { budget ->
                receiveVm.onThermalBudget(budget)
                sendVm.onThermalBudget(budget)
            }
        }

        setContent {
            val rxUi by receiveVm.ui.collectAsState()
            val txUi by sendVm.ui.collectAsState()

            when (route) {
                Route.HOME -> HomeScreen(
                    onSend = {
                        route = Route.SEND
                        wakeLock.acquire()
                        pickFile.launch(arrayOf("*/*"))
                    },
                    onReceive = {
                        route = Route.RECEIVE
                        wakeLock.acquire()
                        if (hasCamera) startReceiving()
                        else requestCamera.launch(Manifest.permission.CAMERA)
                    },
                )

                Route.SEND -> SendFlow(
                    ui = txUi,
                    vm = sendVm,
                    onPickFile = { pickFile.launch(arrayOf("*/*")) },
                    onConfirmSas = sendVm::confirmSasMatch,
                    onReportMismatch = sendVm::reportSasMismatch,
                    onAbort = {
                        sendVm.abort()
                        goHome()
                    },
                    onDone = {
                        sendVm.reset()
                        goHome()
                    },
                )

                Route.RECEIVE -> CandelaReceiveShell(
                    ui = rxUi,
                    hasCameraPermission = hasCamera,
                    onStart = {
                        if (hasCamera) startReceiving()
                        else requestCamera.launch(Manifest.permission.CAMERA)
                    },
                    onGrantPermission = {
                        requestCamera.launch(Manifest.permission.CAMERA)
                    },
                    onConfirmSas = receiveVm::confirmSasMatch,
                    onReportMismatch = receiveVm::reportSasMismatch,
                    onAbort = {
                        receiveVm.abort()
                        goHome()
                    },
                    cameraPreview = { modifier -> CameraSurface(modifier) },
                )
            }
        }
    }

    private fun goHome() {
        stopReceiving()
        wakeLock.release()
        route = Route.HOME
    }

    /**
     * Create the binding and open the device.
     *
     * Order does not matter here: Camera2Session defers capture-session
     * configuration until both the CameraDevice and the preview Surface exist.
     */
    private fun startReceiving() {
        if (!hasCamera) return
        if (camera == null) {
            camera = CameraBinding(this, receiveVm) { block ->
                runOnUiThread(block)
            }
        }
        if (receiveVm.ui.value.sessionState == SessionState.IDLE) {
            receiveVm.startCalibration()
        }
        camera?.open()
    }

    private fun stopReceiving() {
        camera?.close()
        camera = null
    }

    /**
     * The preview is a raw SurfaceView, not a Compose canvas: camera frames must
     * never travel through recomposition (audit kill #5). AndroidView is a
     * one-time embed and the HAL writes the Surface directly.
     */
    @Composable
    private fun CameraSurface(modifier: Modifier) {
        Box(modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                camera?.attachPreview(h.surface)
                            }

                            override fun surfaceChanged(
                                h: SurfaceHolder,
                                f: Int,
                                w: Int,
                                ht: Int,
                            ) {
                                // Re-attach: a size change recreates the buffers,
                                // and the session must target the current surface.
                                camera?.attachPreview(h.surface)
                            }

                            override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                        })
                    }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Reopen when returning to the receiver: onStop released the device.
        if (route == Route.RECEIVE && hasCamera && camera == null) startReceiving()
    }

    override fun onStop() {
        super.onStop()
        // Release everything the moment we are not visible. Holding the camera
        // across a background transition is the classic way to be unable to
        // reopen it, and "no background scanning, ever" is an audit rule.
        wakeLock.release()
        if (thermal.isSupported) thermal.stop()
        stopReceiving()
    }
}

/** Seam so the shell and ViewModel keep no compile-time dependency on Camera2. */
interface CameraController {
    fun attachPreview(surface: android.view.Surface)
    fun close()
}
