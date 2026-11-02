package com.caremate.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caremate.assistant.AssistantEngine
import com.caremate.assistant.CareMateApp
import com.caremate.assistant.Phase
import com.caremate.assistant.device.PhoneActions
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit) {
    val state by AssistantEngine.state.collectAsStateWithLifecycle()
    val prefs = CareMateApp.instance.prefs
    val ctx = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    var showFamily by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { clock = Date(); delay(15_000) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            // Header: greeting + clock
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(clock),
                        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(clock), style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = { onNavigate(Screen.MEMORY) }) { Icon(Icons.Default.Psychology, "Memory", Modifier.size(32.dp)) }
                IconButton(onClick = { onNavigate(Screen.SETTINGS) }) { Icon(Icons.Default.Settings, "Settings", Modifier.size(32.dp)) }
            }

            // Conversation
            val listState = rememberLazyListState()
            LaunchedEffect(state.lines.size) { if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.lines.isEmpty()) item {
                    val name = prefs.userName.ifBlank { "there" }
                    Text("Hello $name!\nTap the big button and talk to me.", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 24.dp))
                    Text("Try: \"Call my son\", \"Remind me to take my tablet at 8\", \"Read my WhatsApp messages\", \"Play old Hindi songs on YouTube\".",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                items(state.lines) { line ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(line.text, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                .background(if (line.fromUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
                                .padding(14.dp))
                    }
                }
            }

            // Live status
            val statusText = when {
                state.partial.isNotBlank() -> "\"${state.partial}\""
                state.status.isNotBlank() -> state.status
                state.phase == Phase.SPEAKING -> "Speaking…"
                else -> ""
            }
            Text(statusText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp))

            if (typing) {
                var text by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Type a request") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { AssistantEngine.submitText(text); text = "" }))
                    IconButton(onClick = { AssistantEngine.submitText(text); text = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }

            // Big talk button
            val busy = state.phase != Phase.IDLE
            val pulse by animateFloatAsState(if (state.phase == Phase.LISTENING) 1f + state.level * 0.15f else 1f, label = "pulse")
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { if (busy) AssistantEngine.cancel() else AssistantEngine.listen() },
                    modifier = Modifier.size(170.dp).scale(pulse).semantics { contentDescription = if (busy) "Stop" else "Talk" },
                    shape = CircleShape,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (busy) Icons.Default.Stop else Icons.Default.Mic, null, Modifier.size(72.dp))
                        Text(when (state.phase) {
                            Phase.IDLE -> "Talk"; Phase.LISTENING -> "Listening"; Phase.THINKING -> "Thinking"
                            Phase.ACTING -> "Working"; Phase.SPEAKING -> "Stop"
                        }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = { typing = !typing }, Modifier.align(Alignment.CenterEnd)) {
                    Icon(if (typing) Icons.Default.Close else Icons.Default.Keyboard, "Type instead", Modifier.size(32.dp))
                }
            }

            // Quick actions
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(onClick = { showFamily = true }, modifier = Modifier.weight(1f).height(80.dp)) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Call, null, Modifier.size(32.dp)); Spacer(Modifier.size(8.dp))
                        Text("Call family", style = MaterialTheme.typography.titleLarge)
                    }
                }
                // SOS needs a long press so it can't be triggered by accident.
                Card(
                    modifier = Modifier.weight(1f).height(80.dp).clip(CardDefaults.shape)
                        .combinedClickable(onClick = { AssistantEngine.progress("Hold the SOS button to call for help") },
                            onLongClick = { AssistantEngine.sos() }),
                    colors = CardDefaults.cardColors(containerColor = Sos, contentColor = Color.White)
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SOS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        Text("hold", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (showFamily) {
        val contacts = prefs.emergencyContacts
        AlertDialog(
            onDismissRequest = { showFamily = false },
            title = { Text("Who do you want to call?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (contacts.isEmpty()) Text("No family numbers yet. Add them in Settings > Family & emergency contacts.")
                    contacts.forEach { c ->
                        Button(onClick = { showFamily = false; PhoneActions(ctx).call(c.number) }, Modifier.fillMaxWidth().height(64.dp)) {
                            Icon(Icons.Default.Call, null); Spacer(Modifier.size(8.dp)); Text(c.name, fontSize = 22.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFamily = false }) { Text("Close") } }
        )
    }

    state.confirmQuestion?.let { q ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(q, style = MaterialTheme.typography.headlineSmall) },
            confirmButton = {
                Button(onClick = { AssistantEngine.answerConfirm(true) }, Modifier.height(64.dp).padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("YES", fontSize = 24.sp) }
            },
            dismissButton = {
                Button(onClick = { AssistantEngine.answerConfirm(false) }, Modifier.height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Sos)) { Text("NO", fontSize = 24.sp) }
            }
        )
    }
}
