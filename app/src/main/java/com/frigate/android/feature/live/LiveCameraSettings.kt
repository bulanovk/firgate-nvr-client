package com.frigate.android.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigate.android.core.server.FrigateCamera

@Composable
internal fun LiveCameraSettingsContent(
    camera: FrigateCamera,
    activity: CameraActivity,
    role: String,
    pending: Set<CameraControl>,
    onControl: (CameraControl, Boolean) -> Unit,
    onStreamChange: (String) -> Unit,
    onSnapshot: () -> Unit,
    recording: Boolean,
    recordingBusy: Boolean,
    onRecording: () -> Unit,
    showStatistics: Boolean,
    onStatisticsChange: (Boolean) -> Unit,
    showDebug: Boolean,
    onDebugChange: (Boolean) -> Unit,
) {
    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        cameraControls(camera, role).forEach { control ->
            CameraSettingsSwitch(control.label, activity.controlValue(control, camera),
                enabled = control !in pending,
                onChange = { onControl(control, it) })
        }
        CameraSettingsLabel("Stream")
        CameraSettingsSelect("Camera stream", camera.streams.entries.firstOrNull { it.value == camera.streamName }?.key ?: camera.streamName,
            camera.streams.map { CameraSettingOption(it.key, it.value) }, onStreamChange)
        CameraSettingsLabel("Mode")
        // The PWA lists the engines of the product and dims the one that cannot be used: WebRTC is
        // not a playback path of the app yet, MSE is the engine that plays.
        CameraSettingsSelect("Stream mode", "MSE", liveEngineOptions(), {})
        CameraSettingsLabel("Manual recording")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraSettingsAction("Take a snapshot", Modifier.weight(1f), onClick = onSnapshot)
            if (role == "admin") {
                CameraSettingsAction(if (recording) "Stop recording" else "Start recording", Modifier.weight(1f),
                    enabled = !recordingBusy && (activity.enabled ?: camera.enabled), onClick = onRecording)
            }
        }
        CameraSettingsSwitch("Statistics", showStatistics, onChange = onStatisticsChange)
        CameraSettingsSwitch("Debug view", showDebug, onChange = onDebugChange)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun CameraSettingsLabel(text: String) {
    Text(text, color = Color(0xFF8B8F96), fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
internal fun CameraSettingsSwitch(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .semantics { contentDescription = label }
        .toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else .45f), fontSize = 14.sp)
        Box(Modifier.size(38.dp, 22.dp).background(if (value) Color(0xFFFB923C) else Color(0xFF3A3D43), RoundedCornerShape(11.dp)).padding(2.dp)) {
            Box(Modifier.size(18.dp).align(if (value) Alignment.CenterEnd else Alignment.CenterStart)
                .background(Color.White, RoundedCornerShape(9.dp)))
        }
    }
}

@Composable
internal fun CameraSettingsSelect(
    description: String,
    value: String,
    options: List<CameraSettingOption>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .background(Color(0xFF212226), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF3A3D43), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { open = !open }
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(value, color = Color.White, fontSize = 14.sp)
            Text("▾", color = Color(0xFF8B8F96), fontSize = 11.sp)
        }
        if (open) {
            Column(Modifier.padding(top = 6.dp).background(Color(0xFF212226), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF3A3D43), RoundedCornerShape(8.dp))) {
                options.forEach { option ->
                    Column(Modifier.fillMaxWidth()
                        .clickable(enabled = option.available) { onSelect(option.value); open = false }
                        .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(option.label,
                            color = when {
                                !option.available -> Color.White.copy(alpha = .45f)
                                option.label == value -> Color(0xFFFB923C)
                                else -> Color.White
                            }, fontSize = 14.sp)
                        option.reason?.let {
                            Text(it, color = Color(0xFF8B8F96), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraSettingsAction(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp)
        .background(Color(0xFF212226), RoundedCornerShape(8.dp))
        .border(1.dp, Color(0xFF414349), RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else .45f), fontSize = 14.sp)
    }
}
