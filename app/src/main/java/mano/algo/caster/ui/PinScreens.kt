package mano.algo.caster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mano.algo.caster.security.PinLock
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * A number pad for typing a PIN: dots show how many digits are in, [onEntered] gets the PIN when the owner taps OK. [error] is
 * shown under the dots (a wrong PIN, for example).
 */
@Composable
fun PinPad(prompt: String, error: String?, okLabel: String = "OK", scroll: Boolean = false, onEntered: (String) -> Unit) {
    var pin by remember { mutableStateOf("") } // not saved: a PIN must not end up in a saved screen state
    Column(
        modifier = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier, // a dialog in landscape is shorter than the pad
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(prompt, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(18.dp)) {
            repeat(pin.length) { Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
        }
        Text(
            error.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(20.dp),
        )
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", okLabel)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    FilledTonalButton(
                        onClick = {
                            when (key) {
                                "⌫" -> pin = pin.dropLast(1)
                                okLabel -> if (PinLock.isValid(pin)) { val entered = pin; pin = ""; onEntered(entered) }
                                else -> if (pin.length < PinLock.MAX_LENGTH) pin += key
                            }
                        },
                        modifier = Modifier.size(width = 76.dp, height = 56.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/** What stands in front of the app while a PIN is set and it has not been typed. Wrong tries make the owner wait. */
@Composable
fun LockScreen(attempt: (String) -> PinLock.Attempt, onUnlocked: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), // scrolls: a phone in landscape is short
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Caster is locked", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        PinPad("Enter your PIN", error) { pin ->
            when (val result = attempt(pin)) {
                PinLock.Attempt.Ok -> onUnlocked()
                PinLock.Attempt.Wrong -> error = "That PIN is wrong"
                is PinLock.Attempt.Wait -> error = "Too many tries. Wait ${result.ms / 1000 + 1} s"
            }
        }
    }
}

/** Asks for the new PIN twice. */
@Composable
fun SetPinDialog(title: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            PinPad(
                prompt = if (first == null) "Choose a PIN of ${PinLock.MIN_LENGTH} to ${PinLock.MAX_LENGTH} digits" else "Type it once more",
                error = error,
                okLabel = if (first == null) "Next" else "Save",
                scroll = true,
            ) { pin ->
                val firstPin = first
                if (firstPin == null) {
                    first = pin
                    error = null
                } else if (firstPin == pin) {
                    onDone(pin)
                } else {
                    first = null
                    error = "The two did not match. Start again."
                }
            }
        },
    )
}

/** Asks for the PIN that is set now (before it is changed or removed). */
@Composable
fun VerifyPinDialog(title: String, attempt: (String) -> PinLock.Attempt, onOk: () -> Unit, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            PinPad("Enter the current PIN", error, scroll = true) { pin ->
                when (val result = attempt(pin)) {
                    PinLock.Attempt.Ok -> onOk()
                    PinLock.Attempt.Wrong -> error = "That PIN is wrong"
                    is PinLock.Attempt.Wait -> error = "Too many tries. Wait ${result.ms / 1000 + 1} s"
                }
            }
        },
    )
}
