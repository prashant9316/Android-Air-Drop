package com.caremate.assistant.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.caremate.assistant.AssistantEngine
import com.caremate.assistant.CareMateApp
import com.caremate.assistant.Prefs
import com.caremate.assistant.memory.MemoryCategory
import com.caremate.assistant.reminders.Reminders
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Page(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            }
            content()
        }
    }
}

/** Re-reads permission state whenever the user comes back from a Settings screen. */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

// ------------------------------------------------------------------ setup

@Composable
fun SetupScreen(onDone: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val items = remember(tick, refresh) { Permissions.items(ctx) }
    val prefs = CareMateApp.instance.prefs

    Page("Set up CareMate", null) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Give CareMate the access it needs. Each switch is explained. You (or a family member) only need to do this once.",
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.size(8.dp))
                Card(Modifier.fillMaxWidth().clickable { onSettings() }) {
                    Column(Modifier.padding(16.dp)) {
                        val ok = prefs.apiKey.isNotBlank()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline)
                            Text("  Claude API key, names & family numbers", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Open settings to paste the API key from console.anthropic.com and add emergency contacts.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(items) { it ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (it.granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (it.granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline)
                            Text("  " + it.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (!it.granted || it.settingsIntent != null && it.runtime.isEmpty()) OutlinedButton(onClick = {
                                if (it.runtime.isNotEmpty()) launcher.launch(it.runtime.toTypedArray())
                                else it.settingsIntent?.let { i -> runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
                            }) { Text(if (it.granted) "Open" else "Allow") }
                        }
                        Text(it.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Button(onClick = onDone, Modifier.fillMaxWidth().padding(16.dp)) { Text("Done", style = MaterialTheme.typography.titleLarge) }
    }
}

// ------------------------------------------------------------------ settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(label: String, options: List<String>, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onChange(o); open = false }) }
        }
    }
}

@Composable
private fun Toggle(label: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val p = CareMateApp.instance.prefs
    var apiKey by remember { mutableStateOf(p.apiKey) }
    var model by remember { mutableStateOf(p.model) }
    var effort by remember { mutableStateOf(p.effort) }
    var userName by remember { mutableStateOf(p.userName) }
    var assistantName by remember { mutableStateOf(p.assistantName) }
    var language by remember { mutableStateOf(p.language) }
    var rate by remember { mutableStateOf(p.speechRate) }
    var large by remember { mutableStateOf(p.largeText) }
    var confirm by remember { mutableStateOf(p.confirmActions) }
    var keep by remember { mutableStateOf(p.keepListening) }
    var about by remember { mutableStateOf(p.aboutUser) }
    var contacts by remember { mutableStateOf(p.emergencyContactsRaw) }
    var careSms by remember { mutableStateOf(p.careSmsEnabled) }
    var caregivers by remember { mutableStateOf(p.caregiverNumbersRaw) }
    var pin by remember { mutableStateOf(p.carePin) }

    fun save() {
        p.apiKey = apiKey; p.model = model; p.effort = effort; p.userName = userName.trim()
        p.assistantName = assistantName.trim().ifBlank { "CareMate" }; p.language = language.trim().ifBlank { Locale.getDefault().toLanguageTag() }
        p.speechRate = rate; p.largeText = large; p.confirmActions = confirm; p.keepListening = keep
        p.aboutUser = about.trim(); p.emergencyContactsRaw = contacts.trim()
        p.careSmsEnabled = careSms && pin.length >= 4; p.caregiverNumbersRaw = caregivers.trim(); p.carePin = pin.trim()
        AssistantEngine.newConversation()
    }

    Page("Settings", { save(); onBack() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Claude", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Claude API key (sk-ant-…)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("Stored encrypted on this phone only.", style = MaterialTheme.typography.bodySmall)
            Choice("Model", Prefs.MODELS, model) { model = it }
            Choice("Thinking effort (low = fastest replies)", Prefs.EFFORTS, effort) { effort = it }

            HorizontalDivider()
            Text("Voice & display", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(userName, { userName = it }, Modifier.fillMaxWidth(), label = { Text("What should I call the user? (e.g. Mum, Amma)") }, singleLine = true)
            OutlinedTextField(assistantName, { assistantName = it }, Modifier.fillMaxWidth(), label = { Text("Assistant's name") }, singleLine = true)
            Choice("Language", listOf("en-IN", "en-GB", "en-US", "hi-IN", "bn-IN", "ta-IN", "te-IN", "mr-IN", "gu-IN", "kn-IN", "ml-IN", "pa-IN", "ur-PK", "ne-NP", "es-ES", "fr-FR", "de-DE", "ar-SA"), language) { language = it }
            Text("Speaking speed: ${"%.1f".format(rate)}x")
            Slider(rate, { rate = it }, valueRange = 0.5f..1.5f, steps = 9)
            Toggle("Extra large text", "Bigger letters everywhere in the app", large) { large = it }
            Toggle("Ask before calling or sending", "Confirms out loud before any call or message", confirm) { confirm = it }
            Toggle("Keep listening after a question", "Answer without pressing the button again", keep) { keep = it }
            OutlinedTextField(about, { about = it }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("About the user (for the assistant)") },
                placeholder = { Text("e.g. 72, lives alone in Pune, speaks Marathi and some English, hard of hearing, diabetic, likes old Lata songs") })

            HorizontalDivider()
            Text("Family & emergency contacts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(contacts, { contacts = it }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("One per line: Name, number") }, placeholder = { Text("Rahul (son), +91 98xxxxxxx\nPriya (daughter), +44 7xxxxxxxx") })
            Text("Shown under 'Call family'. SOS texts all of them with the location and calls the first one.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("Remote check-in by SMS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Toggle("Allow caregiver SMS commands", "Lets the numbers below text the phone, e.g. \"CAREMATE 4821 WHERE\"", careSms) { careSms = it }
            OutlinedTextField(caregivers, { caregivers = it }, Modifier.fillMaxWidth(), label = { Text("Caregiver numbers (comma separated)") })
            OutlinedTextField(pin, { if (it.length <= 8 && it.all(Char::isDigit)) pin = it }, Modifier.fillMaxWidth(),
                label = { Text("Secret PIN (4-8 digits)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Text("Commands: WHERE (location link), STATUS (battery, network), RING (loud alarm to find the phone), NOTE <text> (adds to memory), REMIND 18:30 <text>.",
                style = MaterialTheme.typography.bodySmall)

            Button(onClick = { save(); onBack() }, Modifier.fillMaxWidth()) { Text("Save") }
            OutlinedButton(onClick = { save(); CareMateApp.instance.prefs.onboarded = false; onBack() }, Modifier.fillMaxWidth()) { Text("Open setup checklist") }
        }
    }
}

// ------------------------------------------------------------------ memory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = CareMateApp.instance.store
    var tab by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    val fmt = remember { SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()) }

    Page("Memory", onBack) {
        TabRow(tab) {
            listOf("What I know", "Reminders", "Activity").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> {
                val memories = remember(version) { store.allMemories() }
                var cat by remember { mutableStateOf(MemoryCategory.PERSON) }
                var subject by remember { mutableStateOf("") }
                var details by remember { mutableStateOf("") }
                LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("Things the assistant remembers. It adds to this as you talk; you can also add facts here.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice("Category", MemoryCategory.entries.map { it.label }, cat.label) { l -> cat = MemoryCategory.entries.first { it.label == l } }
                            Text(cat.hint, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("About (e.g. Rahul (son))") }, singleLine = true)
                            OutlinedTextField(details, { details = it }, Modifier.fillMaxWidth(), label = { Text("Details") })
                            Button(onClick = {
                                if (subject.isNotBlank() && details.isNotBlank()) { store.remember(cat, subject, details); subject = ""; details = ""; version++ }
                            }) { Text("Add") }
                        } }
                    }
                    memories.groupBy { it.category }.forEach { (c, list) ->
                        item { Text(c.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                        items(list, key = { it.id }) { m ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.subject, style = MaterialTheme.typography.titleMedium)
                                        Text(m.details, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    IconButton(onClick = { store.forget(m.id); version++ }) { Icon(Icons.Default.Delete, "Delete") }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                val reminders = remember(version) { store.activeReminders() }
                LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (reminders.isEmpty()) item { Text("No reminders. Say \"Remind me to take my tablet every day at 9\".") }
                    items(reminders, key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.text, style = MaterialTheme.typography.titleMedium)
                                    Text(fmt.format(Date(r.triggerAt)) + if (r.repeat != "none") " · ${r.repeat}" else "", style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { Reminders.cancel(ctx, r.id); version++ }) { Icon(Icons.Default.Delete, "Cancel") }
                            }
                        }
                    }
                }
            }
            else -> {
                val log = remember(version) { store.recentLog(200) }
                LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { TextButton(onClick = { version++ }) { Text("Refresh") } }
                    items(log) { l ->
                        Column {
                            Text("${fmt.format(Date(l.ts))} · ${l.role}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(l.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
