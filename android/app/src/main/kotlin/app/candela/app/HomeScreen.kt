package app.candela.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The role picker.
 *
 * An optical link needs two devices doing OPPOSITE things, so the first screen
 * must make that choice explicit. The previous build launched straight into the
 * receiver, which made the sender look like it did not exist.
 */
@Composable
fun HomeScreen(
    onSend: () -> Unit,
    onReceive: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Candela",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Air-gapped file transfer over light. No network, no pairing, " +
                "no radio — one screen shows codes, the other camera reads them.",
            color = Color(0xFF9AA3B2),
            fontSize = 15.sp,
        )

        Spacer(Modifier.height(40.dp))

        RoleCard(
            title = "Send a file",
            subtitle = "Pick a file, then hold this screen up to the other device.",
            accent = Color(0xFF4C8DFF),
            onClick = onSend,
        )
        Spacer(Modifier.height(16.dp))
        RoleCard(
            title = "Receive a file",
            subtitle = "Point this camera at the sending device's screen.",
            accent = Color(0xFF35C48A),
            onClick = onReceive,
        )

        Spacer(Modifier.height(32.dp))
        Text(
            "Both devices must confirm the same 8-digit code before any data " +
                "moves. That check is what makes the link trustworthy.",
            color = Color(0xFF6B7385),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun RoleCard(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF171A21))
            .border(1.dp, Color(0xFF262B36), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Color(0xFF9AA3B2), fontSize = 14.sp)
        }
        Text("›", color = accent, fontSize = 28.sp)
    }
}
