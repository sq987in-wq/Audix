package app.candela.app

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.candela.protocol.Crypto
import app.candela.protocol.SasGate

/**
 * The blocking SAS confirmation screen (audit section 5.2).
 *
 * This screen is a hard stop in the flow. Nothing behind it renders and no data
 * frame is ingested until [SasGate] reaches UNLOCKED, which requires BOTH humans
 * to confirm. PSR section 2.7 records that the web POC "displays SAS and
 * continues" — that regression dies here.
 *
 * UX choices that are security decisions, not aesthetics:
 *
 *  - The digits are enormous and monospaced with a mid-string gap. People compare
 *    "4413 4121" reliably; they skim "44134121" and miss a transposition.
 *  - "They match" is NOT the visually dominant button. A user tapping the biggest
 *    green thing without reading is the entire attack. Both choices are given
 *    equal weight and the confirm button is deliberately not pre-focused.
 *  - The mismatch path is one tap, phrased as a normal outcome rather than an
 *    error, because a user who suspects something must not feel they are
 *    "breaking" the transfer by saying so.
 *  - Progress is shown explicitly ("you confirmed, waiting for them") so neither
 *    party assumes the other has acted.
 */
@Composable
fun SasPairingScreen(
    sas: String,
    gateState: SasGate.State,
    prompt: String,
    onConfirmMatch: () -> Unit,
    onReportMismatch: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rejected = gateState == SasGate.State.REJECTED

    Column(
        modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (rejected) "Pairing rejected" else "Compare these digits",
            color = if (rejected) Color(0xFFFF5252) else Color(0xFFE8EAF0),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = if (rejected) prompt else
                "Both phones must show the same eight digits. Read them aloud to " +
                    "each other. If they differ, a third device is in the light path.",
            color = Color(0xFF9AA3B2),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(32.dp))

        if (!rejected) {
            // The digits. Grouped 4+4 because that is how humans verify.
            Text(
                text = Crypto.sasPretty(sas),
                color = Color(0xFFE8EAF0),
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171A21), RoundedCornerShape(16.dp))
                    .padding(vertical = 24.dp),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            ConfirmationStatus(gateState, prompt)

            Spacer(Modifier.height(32.dp))

            val localDone = gateState == SasGate.State.LOCAL_CONFIRMED ||
                gateState == SasGate.State.UNLOCKED

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Equal visual weight. The confirm action must not be the path of
                // least resistance for someone who is not actually looking.
                OutlinedButton(
                    onClick = onReportMismatch,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text("They differ", fontSize = 15.sp, color = Color(0xFFFF8A80))
                }
                Button(
                    onClick = onConfirmMatch,
                    enabled = !localDone,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2F6DF6),
                        disabledContainerColor = Color(0xFF232733),
                    ),
                ) {
                    Text(
                        if (localDone) "You confirmed" else "They match",
                        fontSize = 15.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = onAbort, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (rejected) "Start over" else "Cancel transfer", color = Color(0xFF9AA3B2))
        }

        if (!rejected) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "No data is being received yet. The transfer starts only after " +
                    "both of you confirm.",
                color = Color(0xFF6F7787),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Shows exactly who has confirmed, so neither party assumes the other acted. */
@Composable
private fun ConfirmationStatus(state: SasGate.State, prompt: String) {
    val (localOk, remoteOk) = when (state) {
        SasGate.State.UNLOCKED -> true to true
        SasGate.State.LOCAL_CONFIRMED -> true to false
        SasGate.State.REMOTE_CONFIRMED -> false to true
        else -> false to false
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PartyChip("This phone", localOk)
            PartyChip("Other phone", remoteOk)
        }
        Spacer(Modifier.height(12.dp))
        Text(prompt, color = Color(0xFF9AA3B2), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PartyChip(label: String, confirmed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (confirmed) "\u2713" else "\u25CB",
            color = if (confirmed) Color(0xFF3DDC84) else Color(0xFF6F7787),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(0.dp))
        Text(
            text = "  $label",
            color = if (confirmed) Color(0xFFE8EAF0) else Color(0xFF9AA3B2),
            fontSize = 13.sp,
        )
    }
}
