package app.candela.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.candela.protocol.Constants
import app.candela.protocol.SasGate
import app.candela.protocol.SendSession
import app.candela.protocol.SessionState
import app.candela.render.HoldTimePlan
import app.candela.render.ThermalGovernor
import app.candela.render.ThermalLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter between [SendSession] (pure, tested) and the sender UI.
 *
 * Holds no transfer rules of its own. Whether a frame may be displayed is
 * decided by SendSession.frameFor, so a UI mistake cannot put payload on screen
 * before the SAS is confirmed.
 */
class SendViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val sessionState: SessionState = SessionState.IDLE,
        val fileName: String? = null,
        val sizeBytes: Int = 0,
        val symbolCount: Int = 0,
        val k: Int = 0,
        val sha256Hex: String? = null,
        val sas: String? = null,
        val sasState: SasGate.State = SasGate.State.IDLE,
        val sasPrompt: String = "",
        val error: String? = null,
        val loading: Boolean = false,
        val symbolsShown: Long = 0,
        val holdMs: Double = 0.0,
        val effectiveFps: Double = 0.0,
        val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        val thermalMessage: String? = null,
    )

    private var session = SendSession()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Exposed so the SurfaceView can pull frames without going through Compose. */
    val sendSession: SendSession get() = session

    @Volatile
    var plan: HoldTimePlan.Plan = HoldTimePlan.compute()
        private set

    /**
     * Read the picked file and build the fountain encoder.
     *
     * Reading happens on the IO dispatcher because a 1 MB read plus a SHA-256
     * plus k block splits is not main-thread work, and the picker returns on the
     * main thread.
     */
    fun loadFile(uri: Uri) {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { readUri(uri) }
            if (result == null) {
                _ui.value = _ui.value.copy(
                    loading = false,
                    error = "Could not read that file.",
                )
                return@launch
            }
            val (bytes, name, mime) = result
            session = SendSession()
            when (val p = session.prepare(bytes, name, mime)) {
                is SendSession.Prepared.Refused ->
                    _ui.value = UiState(error = "${p.reason} — ${p.detail}")

                is SendSession.Prepared.Ready -> {
                    session.startCalibration()
                    session.onCalibrationComplete()
                    _ui.value = UiState(
                        sessionState = session.state,
                        fileName = p.payload.fileName,
                        sizeBytes = p.payload.sizeBytes,
                        symbolCount = p.payload.recommendedSymbols,
                        k = p.payload.k,
                        sha256Hex = p.payload.sha256Hex,
                        sas = p.sas,
                        sasState = session.sasGate?.state ?: SasGate.State.IDLE,
                        sasPrompt = session.sasGate?.prompt().orEmpty(),
                        holdMs = plan.holdMs,
                        effectiveFps = plan.effectiveFps,
                    )
                }
            }
        }
    }

    private fun readUri(uri: Uri): Triple<ByteArray, String, String>? = try {
        val cr = getApplication<Application>().contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "file.bin"
        cr.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
        }
        // Bounded read: MAX_FILE_BYTES + 1 is enough to detect "too large"
        // without pulling an arbitrarily huge file into memory first.
        val bytes = cr.openInputStream(uri)?.use { input ->
            input.readNBytes(Constants.MAX_FILE_BYTES + 1)
        } ?: return null
        Triple(bytes, name, mime)
    } catch (_: Exception) {
        null
    }

    fun confirmSasMatch() {
        session.confirmSasLocal()
        publish()
    }

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

    fun reset() {
        session = SendSession()
        _ui.value = UiState()
    }

    fun onSlotAdvanced() {
        _ui.value = _ui.value.copy(symbolsShown = session.symbolsEmitted)
    }

    /**
     * Thermal derating for the sender: slow the symbol rate, and pause outright
     * at SEVERE. Slowing is always safe — the fountain just needs longer.
     */
    fun onThermalBudget(b: ThermalGovernor.Budget) {
        plan = HoldTimePlan.derate(HoldTimePlan.compute(), b.level)
        when {
            b.aborted -> session.abort()
            b.paused -> session.pauseThermal()
            session.state == SessionState.PAUSED -> session.resume()
        }
        _ui.value = _ui.value.copy(
            thermalLevel = b.level,
            thermalMessage = b.userMessage,
            holdMs = plan.holdMs,
            effectiveFps = plan.effectiveFps,
            sessionState = session.state,
        )
    }

    private fun publish() {
        val g = session.sasGate
        _ui.value = _ui.value.copy(
            sessionState = session.state,
            sas = g?.localSas,
            sasState = g?.state ?: SasGate.State.IDLE,
            sasPrompt = g?.prompt().orEmpty(),
            symbolsShown = session.symbolsEmitted,
        )
    }
}
