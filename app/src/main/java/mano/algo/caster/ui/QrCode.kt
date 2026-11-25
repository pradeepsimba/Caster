package mano.algo.caster.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import mano.algo.caster.server.ServerState

/** Black-on-white QR code for [text]; white margin included so phone cameras can find the edges. */
private fun qrBitmap(text: String, sizePx: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 2),
    )
    val pixels = IntArray(sizePx * sizePx) { i ->
        if (matrix.get(i % sizePx, i / sizePx)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Everything about reaching this phone from another screen, kept out of the Home page: a QR code for a TV, tablet or laptop
 * camera, and the address to type, with the few notes that matter (a hotspot, a name or port that had to change).
 */
@Composable
fun ConnectScreenDialog(state: ServerState.Running, onDismiss: () -> Unit) {
    // Always the numbers: a ".local" name can't be opened by Android or Fire TV, the numbers work everywhere. (The address is never
    // written out here: the QR code carries it, and Settings shows it.)
    val url = state.baseUrl
    val image = remember(url) { qrBitmap(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Connect a screen") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Scan this with the camera of a TV, tablet or computer. To copy or type the address instead, open Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Image(
                    bitmap = image,
                    contentDescription = "QR code to open Caster",
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(6.dp),
                )
                if (state.nameChanged || state.portChanged) {
                    Text(
                        "The usual name or port was busy on this network, so another one is used. Details are in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    "Both devices must be on the same Wi-Fi. You approve each new screen on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
    )
}
