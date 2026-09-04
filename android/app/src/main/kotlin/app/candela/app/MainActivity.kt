package app.candela.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat

/**
 * Single activity. Owns exactly three things: the camera permission, the screen
 * wake lock, and the Surface the preview draws into.
 *
 * Everything else is either Compose (the shell) or the ViewModel (the domain
 * adapter). In particular this class makes no decision about whether data may
 * flow — that lives in ReceiveSession/SasGate, where it is unit-testable.
 */
class MainActivity : ComponentActivity() {

    private val vm: ReceiveViewModel by viewModels()
    private var camera: CameraController? = null

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }

    private var hasCamera by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A transfer takes tens of seconds of the user holding still and NOT
        // touching the screen. The default screen timeout would abort it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasCamera) requestCamera.launch(Manifest.permission.CAMERA)

        setContent {
            val ui by vm.ui.collectAsState()
            CandelaReceiveShell(
                ui = ui,
                onStart = {
                    if (hasCamera) vm.startCalibration()
                    else requestCamera.launch(Manifest.permission.CAMERA)
                },
                onConfirmSas = vm::confirmSasMatch,
                onReportMismatch = vm::reportSasMismatch,
                onAbort = vm::abort,
                cameraPreview = { modifier -> CameraSurface(modifier) },
            )
        }
    }

    /**
     * The preview is a raw SurfaceView, not a Compose canvas. Camera frames must
     * never travel through recomposition (audit kill #5); AndroidView here is a
     * one-time embed, and the Surface is written by the camera HAL directly.
     */
    @androidx.compose.runtime.Composable
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
                            ) = Unit

                            override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                        })
                    }
                },
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Release the camera the moment we are not visible. Holding it across a
        // background transition is the classic way to end up unable to reopen it.
        camera?.close()
        camera = null
    }
}

/**
 * Seam between the activity and :optical-camera.
 *
 * Kept as an interface so the shell and ViewModel have no compile-time
 * dependency on Camera2. The concrete binding is wired in Stage 9 alongside
 * on-device bring-up; until then the preview surface simply stays dark, which
 * is honest rather than faked.
 */
interface CameraController {
    fun attachPreview(surface: android.view.Surface)
    fun close()
}
