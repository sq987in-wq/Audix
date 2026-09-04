package com.candela.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(onSend: () -> Unit, onReceive: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0908))
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
    ) {
        Text("CANDELA", color = Color(0xFFC49A4A), fontSize = 12.sp, letterSpacing = 4.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Text("Move a file as light.", color = Color(0xFFF4EFE6), fontSize = 34.sp, lineHeight = 38.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Air-gapped optical transfer. Fountain codes, per-block Ed25519, SHA-256. No radio.",
            color = Color(0xFFB7AEA0),
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4EFE6), contentColor = Color(0xFF0A0908)),
        ) { Text("Send a file") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onReceive,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF4EFE6)),
        ) { Text("Receive") }
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x22F4EFE6), RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text("OPERATING ENVELOPE", color = Color(0xFF7D7468), fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            EnvelopeRow("Distance", "15–40 cm · < 20°")
            EnvelopeRow("Hold still", "desk or planted elbows")
            EnvelopeRow("Light", "indoor / no direct sun")
            EnvelopeRow("Screen", "clean glass, no privacy film")
            EnvelopeRow("Payload", "≤ 1 MB · 100–500 KB rec.")
            EnvelopeRow("Integrity", "CRC32 · Ed25519 · SHA-256")
        }
    }
}

@Composable
private fun EnvelopeRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = Color(0xFF7D7468), fontSize = 13.sp)
        Text(v, color = Color(0xFFF4EFE6), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
