package app.candela.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.candela.platform.MediaStoreExporter
import app.candela.protocol.CoachMetrics
import app.candela.protocol.DecodeResult
import app.candela.protocol.ExportGate
import app.candela.protocol.ReceiveSession
import app.candela.protocol.SasGate
import app.candela.protocol.SessionState
import app.candela.render.ThermalGovernor
import app.candela.render.ThermalLevel
import app.candela.vision.GateThresholds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin adapter between [ReceiveSession] (pure domain, fully unit-tested) and
 * Compose. It deliberately contains NO decisions of its own: every rule about
 * when data may flow lives in the domain, so a UI change cannot weaken it.
 *
 * Threading: frames arrive on the camera HandlerThread and call [onFrameDecoded]
 * directly. State is published through a StateFlow that Compose collects on the
 * main thread. The heavy work (fountain ingest, SHA-256) already happened by the
 * time the UI sees anything.
 */
class ReceiveViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val sessionState: SessionState = SessionState.IDLE,
        val coach: CoachMetrics = CoachMetrics(),
        val sas: String? = null,
        val sasState: SasGate.State = SasGate.State.IDLE,
        val sasPrompt: String = "",
        val thresholds: GateThresholds = GateThresholds.BOOTSTRAP,
        val roiLeft: Float = 0f,
        val roiTop: Float = 0f,
        val roiWidth: Float = 0f,
        val roiHeight: Float = 0f,
        val motionStable: Boolean = true,
        val exportMessage: String? = null,
        val exportedName: String? = null,
        val exportSucceeded: Boolean = false,
        val framesBlockedBySas: Int = 0,
        val calibrationMessage: String? = null,
        val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        val thermalMessage: String? = null,
        val thermallyPaused: Boolean = false,
    )

    private val session = ReceiveSession()

    /**
     * Current thermal budget. Set by [onThermalBudget] from the platform
     * listener; read by the camera pipeline for ROI downsample and gate
     * thresholds. Never consulted per-frame from Compose.
     */
    @Volatile
    var thermalBudget: ThermalGovernor.Budget =
        ThermalGovernor.budgetFor(ThermalLevel.NONE)
        private set
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun startCalibration() {
        session.startCalibration()
        publish()
    }

    fun onCalibrationResult(accepted: Boolean, message: String?) {
        session.onCalibrationResult(accepted)
        _ui.value = _ui.value.copy(calibrationMessage = message)
        publish()
    }

    fun updateThresholds(t: GateThresholds) {
        _ui.value = _ui.value.copy(thresholds = t)
    }

    /** Called from the camera thread for every gated frame's metrics. */
    fun onCoachMetrics(
        blur: Double,
        contrast: Double,
        motion: Double,
        fps: Double,
        decodeMs: Double,
        gatePass: Boolean,
        reason: String,
        motionStable: Boolean,
    ) {
        session.updateCoach(blur, contrast, motion, fps, decodeMs, gatePass, reason)
        _ui.value = _ui.value.copy(
            coach = session.coach,
            motionStable = motionStable,
        )
    }

    fun onRoiChanged(left: Float, top: Float, width: Float, height: Float) {
        _ui.value = _ui.value.copy(
            roiLeft = left, roiTop = top, roiWidth = width, roiHeight = height,
        )
    }

    /**
     * Every decoded optical frame goes here. The SAS gate is enforced inside
     * [ReceiveSession.ingestFrame], not here — the UI must not be the thing
     * standing between an attacker and the fountain.
     */
    fun onFrameDecoded(result: DecodeResult) {
        session.ingestFrame(result)
        if (session.state == SessionState.COMPLETE) exportVerified()
        publish()
    }

    fun confirmSasMatch() {
        session.confirmSasLocal()
        publish()
    }

    /** Wired to the other device when a back-channel exists; otherwise human-driven. */
    fun confirmSasRemote(remoteSas: String? = null) {
        session.confirmSasRemote(remoteSas)
        publish()
    }

    fun reportSasMismatch() {
        session.reportSasMismatch()
        publish()
    }

    fun abort() {
        session.abort()
        publish()
    }

    /**
     * Applies a new thermal budget. Called only when the EFFECTIVE level changes
     * (the platform monitor applies hysteresis first), so this is not a hot path.
     *
     * The state-machine coupling is the point: SEVERE drives the domain into
     * PAUSED via THERMAL_PAUSE and CRITICAL into ABORTED, rather than merely
     * slowing the UI down. A governor that throttles pacing but leaves the
     * session nominally RECEIVING would keep the camera and decoder running,
     * which is most of the power draw it is trying to shed.
     */
    fun onThermalBudget(b: ThermalGovernor.Budget) {
        thermalBudget = b
        when {
            b.aborted -> session.abort()
            b.paused -> session.pauseThermal()
            // Only auto-resume a session that thermal actually paused. Hysteresis
            // in ThermalTracker means this cannot flap.
            session.state == SessionState.PAUSED -> session.resume()
        }
        _ui.value = _ui.value.copy(
            thermalLevel = b.level,
            thermalMessage = b.userMessage,
            thermallyPaused = b.paused,
        )
        publish()
    }

    /**
     * Gate thresholds actually handed to the vision pipeline: the learned values
     * tightened by the current thermal budget. Rejecting a marginal frame costs
     * ~0.15 ms; attempting to decode it costs 40-120 ms.
     */
    fun effectiveThresholds(): GateThresholds {
        val base = _ui.value.thresholds
        val (blur, contrast) = ThermalGovernor.scaledThresholds(
            base.blurMin, base.contrastMin, thermalBudget,
        )
        return GateThresholds(blur, contrast, base.contrastRefuse)
    }

    /** ROI decimation for the decode path, per the current budget. */
    fun roiDownsample(): Int = thermalBudget.roiDownsample

    /**
     * Whether the decode pipeline should process frames right now. The camera
     * keeps streaming during an idle phase (restarting a capture session costs
     * more than it saves and would drop the AE/AF lock); the expensive gate and
     * ZXing work is what gets skipped.
     */
    fun shouldProcessFrame(elapsedInSessionMs: Long): Boolean =
        ThermalGovernor.isWorkPhase(thermalBudget, elapsedInSessionMs)

    /**
     * Export runs ONLY after the domain reached COMPLETE, which itself requires
     * [ExportGate] to have approved the bytes. Two independent gates, both of
     * which must pass before anything touches Downloads.
     */
    private fun exportVerified() {
        val decision = session.exportDecision
        val bytes = session.verifiedBytes()
        if (decision !is ExportGate.Decision.Publish || bytes == null) {
            _ui.value = _ui.value.copy(
                exportMessage = (decision as? ExportGate.Decision.Refuse)?.detail
                    ?: "Nothing was written.",
                exportSucceeded = false,
            )
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                MediaStoreExporter.export(getApplication(), decision, bytes)
            }
            _ui.value = when (result) {
                is MediaStoreExporter.Result.Success -> _ui.value.copy(
                    exportMessage = "Saved to Downloads. SHA-256 verified.",
                    exportedName = result.displayName,
                    exportSucceeded = true,
                )
                is MediaStoreExporter.Result.Failure -> _ui.value.copy(
                    exportMessage = result.reason,
                    exportSucceeded = false,
                )
            }
        }
    }

    private fun publish() {
        val gate = session.sasGate
        _ui.value = _ui.value.copy(
            sessionState = session.state,
            coach = session.coach,
            sas = gate?.localSas,
            sasState = gate?.state ?: SasGate.State.IDLE,
            sasPrompt = gate?.prompt() ?: "",
            framesBlockedBySas = session.framesBlockedBySas,
        )
    }
}
