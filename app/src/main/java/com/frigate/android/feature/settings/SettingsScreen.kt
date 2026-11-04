package com.frigate.android.feature.settings

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.frigate.android.core.network.Endpoint
import com.frigate.android.core.network.NetworkSnapshot
import com.frigate.android.core.server.HomeNetwork
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.feature.events.eventsColorScheme
import com.frigate.android.ui.PillAccent
import com.frigate.android.ui.PillButton
import com.frigate.android.ui.PillColor
import com.frigate.android.ui.PillMuted

/** What the settings need to show and edit the server, and how to report the result. */
internal class ServerSettings(
    val server: ServerProfile,
    val endpoint: Endpoint,
    val snapshot: NetworkSnapshot,
    val username: String,
    val role: String,
    /** [mainChanged]: the session of the old main address is gone and the user signs in again. */
    val onSave: (profile: ServerProfile, mainChanged: Boolean) -> Unit,
    val onPermissionChanged: () -> Unit,
    val onSignOut: () -> Unit,
)

private val muted = PillMuted
private val pill = PillColor
private val accent = PillAccent
private val warning = Color(0xFFE9B44C)

@Composable
internal fun SettingsScreen(settings: ServerSettings, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = eventsColorScheme) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            if (editing) {
                SettingsEdit(settings, onClose = { editing = false })
            } else {
                SettingsView(settings, onEdit = { editing = true })
            }
        }
    }
}

@Composable
private fun SettingsTitle(title: String, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleLarge)
        actions()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp), color = Color.White,
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color.White) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 9.dp)) {
        Text(label, color = muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider(color = Color(0xFF1C1C1C))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsView(settings: ServerSettings, onEdit: () -> Unit) {
    val server = settings.server
    SettingsTitle("Settings") { PillButton("Edit", onEdit) }
    SectionTitle("Server")
    InfoRow("Name", server.label)
    InfoRow("Main address", server.primaryUrl)
    InfoRow("Home address", server.homeUrl ?: "Not set")
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 9.dp)) {
        Text("Home Wi-Fi", color = muted, style = MaterialTheme.typography.bodySmall)
        if (server.homeNetworks.isEmpty()) {
            Text("None", color = Color.White, style = MaterialTheme.typography.bodyLarge)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                server.homeNetworks.forEach { network ->
                    androidx.compose.material3.Surface(color = pill, shape = RoundedCornerShape(10.dp)) {
                        Text(network.label(), Modifier.padding(horizontal = 11.dp, vertical = 4.dp), color = Color.White)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Color(0xFF1C1C1C))
    InfoRow("Using now", if (settings.endpoint == Endpoint.Home) "Home address" else "Main address", accent)
    SectionTitle("Account")
    InfoRow("Signed in as", settings.username)
    InfoRow("Role", settings.role)
    Spacer(Modifier.height(18.dp))
    Row(Modifier.padding(horizontal = 4.dp)) { PillButton("Sign out", settings.onSignOut) }
    Spacer(Modifier.height(24.dp))
}

private fun HomeNetwork.label() = bssid?.let { "$ssid ($it)" } ?: ssid

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsEdit(settings: ServerSettings, onClose: () -> Unit) {
    val server = settings.server
    val snapshot = settings.snapshot
    var main by rememberSaveable { mutableStateOf(server.primaryUrl) }
    var home by rememberSaveable { mutableStateOf(server.homeUrl.orEmpty()) }
    val networks = remember { mutableStateListOf<HomeNetwork>().apply { addAll(server.homeNetworks) } }
    var ssid by rememberSaveable { mutableStateOf("") }
    var mainError by remember { mutableStateOf<String?>(null) }
    var homeError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        settings.onPermissionChanged()
    }
    fun add(network: HomeNetwork?) {
        if (network != null && network !in networks) networks.add(network)
    }
    BackHandler(onBack = onClose)

    SettingsTitle("Edit server") {
        PillButton("Cancel", onClose)
        Spacer(Modifier.padding(start = 8.dp))
        PillButton("Save", primary = true, onClick = {
            val form = server.submit(main, home, networks.toList())
            mainError = form.mainError
            homeError = form.homeError
            form.profile?.let { profile ->
                onClose()
                settings.onSave(profile, form.mainChanged)
            }
        })
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        main,
        { main = it; mainError = null },
        Modifier.fillMaxWidth(),
        label = { Text("Main address") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        isError = mainError != null,
        supportingText = {
            if (mainError != null) Text(mainError.orEmpty())
            else Text("Changing it signs you out: sign in again at the new address.", color = warning)
        },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        home,
        { home = it; homeError = null },
        Modifier.fillMaxWidth(),
        label = { Text("Home address") },
        placeholder = { Text("http://192.168.1.10:5000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        isError = homeError != null,
        supportingText = { Text(homeError ?: "Used on the Wi-Fi networks below. Empty = always the main address.", color = if (homeError == null) muted else Color.Unspecified) },
    )
    SectionTitle("Wi-Fi networks")
    if (networks.isEmpty()) Text("None yet", Modifier.padding(horizontal = 4.dp, vertical = 4.dp), color = muted)
    networks.toList().forEach { network ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(network.label(), Modifier.weight(1f).padding(horizontal = 4.dp), color = Color.White)
            PillButton("Remove", { networks.remove(network) })
        }
        HorizontalDivider(color = Color(0xFF1C1C1C))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        ssid,
        { ssid = it },
        Modifier.fillMaxWidth(),
        label = { Text("Wi-Fi name (SSID)") },
        singleLine = true,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        PillButton("Add", {
            add(NetworkSnapshot(ssid, null, true).asHomeNetwork(pinAccessPoint = false))
            ssid = ""
        })
        if (snapshot.locationGranted) {
            snapshot.asHomeNetwork(pinAccessPoint = false)?.let { current ->
                PillButton("Add current network (${current.ssid})", { add(current) })
            }
            snapshot.asHomeNetwork(pinAccessPoint = true)?.let { current ->
                PillButton("Add current access point only", { add(current) })
            }
        }
    }
    if (!snapshot.locationGranted) {
        Text(
            "Android shows the Wi-Fi name only to apps with the location permission. Without it the main address is always used. If the dialog does not appear, allow location for the app in the system settings.",
            Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            color = muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.padding(horizontal = 4.dp)) {
            PillButton("Allow location", { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) })
        }
    }
    Spacer(Modifier.height(24.dp))
}
