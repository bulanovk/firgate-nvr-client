package com.frigate.android

import android.os.Bundle
import android.graphics.Rect
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.ui.platform.LocalContext
import com.frigate.android.feature.player.ImmersivePlayerWindow
import com.frigate.android.feature.player.enterVideoPictureInPicture
import com.frigate.android.feature.player.rememberPictureInPictureMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.frigate.android.ui.icons.CameraAlt
import com.frigate.android.ui.icons.Event
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.AndroidEncryptedCredentialsStore
import com.frigate.android.core.server.AndroidEncryptedSessionCookieStore
import com.frigate.android.core.server.AndroidServerPreferences
import com.frigate.android.core.server.DefaultServerStore
import com.frigate.android.core.server.FrigateCamera
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateCredentials
import com.frigate.android.core.server.FrigateEvent
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateNetworkException
import com.frigate.android.core.server.FrigateProfile
import com.frigate.android.core.server.FrigateRepository
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.LiveStreamEndpoint
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.feature.events.*
import com.frigate.android.feature.history.HistoryNavigation
import com.frigate.android.feature.history.HistoryPanelMode
import com.frigate.android.feature.history.HistoryRange
import com.frigate.android.feature.history.HistoryScreen
import com.frigate.android.feature.review.ReviewUiState
import com.frigate.android.feature.review.rememberReviewSession
import com.frigate.android.ui.theme.FrigateTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrigateTheme {
                FrigateApp()
            }
        }
    }
}

internal enum class AppScreen { Live, Events, Settings }
internal sealed interface ConnectionState {
    data object Loading : ConnectionState
    data class LoginRequired(val profile: ServerProfile, val message: String? = null) : ConnectionState
    data class Connected(
        val profile: ServerProfile,
        val user: FrigateProfile,
        val cameras: List<FrigateCamera>,
        val sessionCookie: String,
        val uiTimezone: String? = null,
    ) : ConnectionState
}

@Composable
private fun FrigateApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { DefaultServerStore(AndroidServerPreferences(context)) }
    val sessionStore = remember { AndroidEncryptedSessionCookieStore(context) }
    val credentialsStore = remember { AndroidEncryptedCredentialsStore(context) }
    val renewal = remember { SessionRenewal(credentialsStore) }
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(store.load()) }
    var connection by remember { mutableStateOf<ConnectionState>(ConnectionState.Loading) }
    var activeScreen by rememberSaveable { mutableStateOf(AppScreen.Live) }
    // One renewal at a time: several transports can report the expired session at once.
    var renewing by remember { mutableStateOf(false) }

    /**
     * Signs in again with the stored credentials and keeps the session alive.
     *
     * The session of the server expires on its own; the credentials of the user are the way back
     * in, so the user does not have to type the password again. Only a login that the server
     * refuses itself ends at the sign in screen.
     */
    suspend fun renewSessionOf(profile: ServerProfile) {
        try {
            val renewed = renewSession(profile, renewal)
            if (renewed == null) {
                connection = ConnectionState.LoginRequired(
                    profile,
                    "Session expired. Please sign in again.",
                )
            } else {
                sessionStore.save(profile, renewed.sessionCookie)
                connection = renewed
            }
        } catch (error: Throwable) {
            connection = ConnectionState.LoginRequired(profile, error.userMessage())
        }
    }

    LaunchedEffect(server) {
        connection = server?.let { profile ->
            val legacyCookie = store.loadSession(profile)
            val sessionCookie = sessionStore.load(profile) ?: legacyCookie
            if (legacyCookie != null && sessionCookie == legacyCookie) {
                sessionStore.save(profile, legacyCookie)
                store.clearSession(profile)
            }
            scope.launch {
                try {
                    val connected = if (sessionCookie.isNullOrBlank()) {
                        // Without a cookie the credentials of the user are the only way in.
                        val cookie = renewal.renew(profile) { credentials ->
                            signIn(profile, credentials)
                        } ?: throw FrigateHttpException(401, "Login required")
                        loadConnectedState(profile, FrigateClient(profile, cookie))
                    } else {
                        try {
                            loadConnectedState(profile, FrigateClient(profile, sessionCookie))
                        } catch (error: Throwable) {
                            if (!isUnauthorized(error)) throw error
                            // The session of the server expired: the app signs in again by itself.
                            val cookie = renewal.renew(profile) { credentials ->
                                signIn(profile, credentials)
                            } ?: throw error
                            loadConnectedState(profile, FrigateClient(profile, cookie))
                        }
                    }
                    sessionStore.save(profile, connected.sessionCookie)
                    connection = connected
                } catch (error: Throwable) {
                    if (isUnauthorized(error)) {
                        sessionStore.clear(profile)
                        store.clearSession(profile)
                        // The credentials of the user are dropped by the renewal itself, and only
                        // when the server refuses the login.
                    }
                    connection = ConnectionState.LoginRequired(profile, error.userMessage())
                }
            }
            ConnectionState.Loading
        } ?: ConnectionState.Loading
    }

    when (val state = connection) {
        ConnectionState.Loading -> if (server == null) {
            SetupScreen { selectedServer ->
                store.save(selectedServer)
                server = selectedServer
            }
        } else {
            LoadingScreen()
        }
        is ConnectionState.LoginRequired -> LoginScreen(
            profile = state.profile,
            initialMessage = state.message,
            onLogin = { user, password ->
                connection = ConnectionState.Loading
                scope.launch {
                    try {
                        val client = FrigateClient(state.profile)
                        val repository = FrigateRepository(client)
                        val credentials = FrigateCredentials(user, password)
                        repository.login(credentials.username, credentials.password)
                        val connected = loadConnectedState(state.profile, client)
                        store.save(state.profile)
                        credentialsStore.save(state.profile, credentials)
                        sessionStore.save(state.profile, connected.sessionCookie)
                        connection = connected
                    } catch (error: Throwable) {
                        connection = ConnectionState.LoginRequired(state.profile, error.userMessage())
                    }
                }
            },
            onChangeServer = {
                sessionStore.clear(state.profile)
                store.clear()
                server = null
                connection = ConnectionState.Loading
            },
        )
        is ConnectionState.Connected -> ConnectedApp(
            state = state,
            activeScreen = activeScreen,
            onScreenChange = { activeScreen = it },
            onDisconnect = {
                sessionStore.clear(state.profile)
                store.clear()
                server = null
                connection = ConnectionState.Loading
            },
            onUnauthorized = expired@{
                // The session of the server expired: the cookie is spent, but the credentials of
                // the user are still in the encrypted store. The app signs in again by itself and
                // the user keeps the session.
                if (renewing) return@expired
                renewing = true
                sessionStore.clear(state.profile)
                scope.launch {
                    try {
                        renewSessionOf(state.profile)
                    } finally {
                        renewing = false
                    }
                }
            },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = FrigateColors.accent)
    }
}

@Composable
private fun LoginScreen(
    profile: ServerProfile,
    initialMessage: String?,
    onLogin: (String, String) -> Unit,
    onChangeServer: () -> Unit,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf(initialMessage) }

    Surface(Modifier.fillMaxSize(), color = FrigateColors.canvas) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("FRIGATE", style = MaterialTheme.typography.labelLarge, color = FrigateColors.accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Sign in to\nyour cameras.", style = MaterialTheme.typography.displaySmall, color = FrigateColors.ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(profile.baseUrl, style = MaterialTheme.typography.bodyMedium, color = FrigateColors.muted)
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(user, { user = it; message = null }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                password,
                { password = it; message = null },
                Modifier.fillMaxWidth(),
                label = { Text("Password") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = message != null,
                supportingText = { message?.let { Text(it) } },
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onLogin(user, password) },
                enabled = user.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Sign in", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onChangeServer, modifier = Modifier.fillMaxWidth()) { Text("Change server") }
        }
    }
}

@Composable
internal fun ConnectedApp(
    state: ConnectionState.Connected,
    activeScreen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    onDisconnect: () -> Unit,
    onUnauthorized: () -> Unit = {},
) {
    val navigation = remember { HistoryNavigation() }
    // The grid lives above the branches of the screen, so the position of the cameras survives
    // the archive and comes back when the user returns from it.
    val scroll = rememberScrollState()
    var liveCameraId by remember(state.profile, state.sessionCookie) { mutableStateOf<String?>(null) }
    var liveFullscreen by remember(state.profile, state.sessionCookie) { mutableStateOf(false) }
    val host = LocalContext.current as? ComponentActivity
    val pictureInPicture = rememberPictureInPictureMode(host)
    val cameraOpen = activeScreen == AppScreen.Live && !navigation.isOpen && liveCameraId != null
    val hideChrome = cameraOpen && (liveFullscreen || pictureInPicture)
    val supportsPictureInPicture = host is MainActivity && host.packageManager
        .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    ImmersivePlayerWindow(host, cameraOpen && liveFullscreen && !pictureInPicture)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    val reviewSession = rememberReviewSession(
        profile = state.profile,
        cookie = state.sessionCookie,
        active = activeScreen == AppScreen.Live && !navigation.isOpen,
        cameraIds = state.cameras.map { camera -> camera.id }.toSet(),
        onUnauthorized = onUnauthorized,
    )
    val reviews by reviewSession.state.collectAsState()
    val initialEventsQuery = remember(state.profile, state.uiTimezone) {
        val timezone = state.uiTimezone ?: java.time.ZoneId.systemDefault().id
        EventsQuery(state.cameras.map { it.id }, eventsRange(null, timezone, System.currentTimeMillis() / 1000.0), timezone)
    }
    val eventsSession = rememberEventsSession(state.profile, state.sessionCookie,
        activeScreen == AppScreen.Events && !navigation.isOpen, initialEventsQuery, onUnauthorized)
    val currentUnauthorized by androidx.compose.runtime.rememberUpdatedState(onUnauthorized)
    val eventsInteraction = remember(eventsSession) {
        EventsInteraction(eventsSession.controller, eventsSession.repository, eventsSession.scope, { currentUnauthorized() },
            onMutation = reviewSession::refresh)
    }
    val events by eventsSession.state.collectAsState()
    LaunchedEffect(navigation.isOpen, events.message, eventsInteraction) {
        val message = events.message
        if (activeScreen == AppScreen.Events && navigation.isOpen && message != null && eventsInteraction.canRetry) {
            val result = snackbarHostState.showSnackbar(message, actionLabel = "Retry",
                duration = androidx.compose.material3.SnackbarDuration.Indefinite)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                eventsInteraction.dispatch(EventsAction.RetryFailedAction)
            }
        }
    }
    val eventsGrid = rememberLazyGridState()
    val motionPlayback = remember(eventsSession) { MotionPlaybackState(initialEventsQuery.range.after) }
    val activity = com.frigate.android.feature.live.rememberCameraActivity(
        profile = state.profile,
        cookie = state.sessionCookie,
        onReviewsMessage = { raw -> reviewSession.onReviewsMessage(raw); eventsSession.onReviewsMessage(raw) },
        onReconnect = { reviewSession.onReconnect(); eventsSession.onReconnect() },
    )
    // Any tab of the session closes the archive: the live screen is the home of the session.
    val selectScreen: (AppScreen) -> Unit = { screen ->
        navigation.close()
        onScreenChange(screen)
    }
    val darkChrome = activeScreen != AppScreen.Settings || navigation.isOpen
    Scaffold(
        containerColor = if (darkChrome) {
            FrigatePwaColors.background
        } else {
            FrigateColors.canvas
        },
        contentWindowInsets = if (hideChrome) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { if (!pictureInPicture) SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!hideChrome) NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = if (darkChrome) {
                    FrigatePwaColors.surface
                } else {
                    FrigateColors.surface
                },
            ) {
                NavigationBarItem(
                    selected = activeScreen == AppScreen.Live,
                    onClick = { selectScreen(AppScreen.Live) },
                    icon = { Icon(Icons.Outlined.CameraAlt, null) },
                    label = { Text("Live") },
                    colors = navigationItemColors(darkChrome),
                )
                NavigationBarItem(
                    selected = activeScreen == AppScreen.Events,
                    onClick = { selectScreen(AppScreen.Events) },
                    icon = { Icon(Icons.Outlined.Event, null) },
                    label = { Text("Events") },
                    colors = navigationItemColors(darkChrome),
                )
                NavigationBarItem(
                    selected = activeScreen == AppScreen.Settings,
                    onClick = { selectScreen(AppScreen.Settings) },
                    icon = { Icon(Icons.Outlined.Settings, null) },
                    label = { Text("Settings") },
                    colors = navigationItemColors(darkChrome),
                )
            }
        },
    ) { padding ->
        if (navigation.isOpen) {
            HistoryScreen(
                profile = state.profile,
                cookie = state.sessionCookie,
                cameras = state.cameras,
                navigation = navigation,
                backDescription = if (activeScreen == AppScreen.Events) "Back to Events" else "Back to Live",
                onBack = { navigation.close() },
                onLive = { camera ->
                    navigation.openLive()
                    liveCameraId = camera
                    selectScreen(AppScreen.Live)
                },
                onUnauthorized = onUnauthorized,
                modifier = Modifier.padding(padding),
            )
        } else {
            when (activeScreen) {
                AppScreen.Live -> LiveScreen(
                    state = state,
                    activity = activity.activity,
                    onSetControl = { cameraId, control, value ->
                        val camera = state.cameras.firstOrNull { it.id == cameraId }
                        camera != null && control in com.frigate.android.feature.live.cameraControls(camera, state.user.role) &&
                            activity.setControl(cameraId, control, value)
                    },
                    reviews = reviews,
                    selectedCameraId = liveCameraId,
                    onSelectedCameraChange = { liveCameraId = it; liveFullscreen = false },
                    fullscreen = liveFullscreen,
                    onFullscreenChange = { liveFullscreen = it },
                    pictureInPicture = pictureInPicture,
                    onPictureInPicture = if (!supportsPictureInPicture) null else { aspect, bounds ->
                        if (host != null && !enterVideoPictureInPicture(host, aspect, bounds)) {
                            showMessage("Picture in picture is unavailable")
                        }
                    },
                    onOpenHistory = { camera -> navigation.openCamera(camera) },
                    onMessage = showMessage,
                    onUnauthorized = onUnauthorized,
                    scroll = scroll,
                    onOpenReview = { review ->
                        navigation.openReview(review)
                        // The event is marked as seen when the archive opens, not when the video
                        // of the archive is ready.
                        reviewSession.markViewed(review.id)
                    },
                    onMarkViewed = reviewSession::markViewed,
                    onRetryReviews = reviewSession::refresh,
                    modifier = Modifier.padding(padding),
                )
                AppScreen.Events -> {
                    EventsFeature(eventsSession, eventsInteraction, state.profile, state.sessionCookie,
                    eventsCatalog(state.cameras, state.user.role, events.query.cameraIds),
                    eventsGrid, motionPlayback, onUnauthorized,
                    onOpen = { navigation.openEvent(it, System.currentTimeMillis() / 1000.0) },
                    onMotion = { camera, time, grid -> navigation.openMotion(camera, time, eventsSession.state.value.query.range,
                        System.currentTimeMillis() / 1000.0, grid) }, modifier = Modifier.padding(padding))
                }
                AppScreen.Settings -> SettingsScreen(state, onDisconnect, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun navigationItemColors(isLive: Boolean) =
    if (isLive) {
        androidx.compose.material3.NavigationBarItemDefaults.colors(
            selectedIconColor = FrigatePwaColors.foreground,
            selectedTextColor = FrigatePwaColors.foreground,
            unselectedIconColor = FrigatePwaColors.muted,
            unselectedTextColor = FrigatePwaColors.muted,
            indicatorColor = FrigatePwaColors.indicator,
        )
    } else {
        androidx.compose.material3.NavigationBarItemDefaults.colors()
    }

@androidx.media3.common.util.UnstableApi
@Composable
private fun LiveScreen(
    state: ConnectionState.Connected,
    activity: Map<String, com.frigate.android.feature.live.CameraActivity>,
    onSetControl: (String, com.frigate.android.feature.live.CameraControl, Boolean) -> Boolean,
    reviews: ReviewUiState,
    selectedCameraId: String?,
    onSelectedCameraChange: (String?) -> Unit,
    onOpenHistory: (String) -> Unit,
    onMessage: (String) -> Unit,
    onUnauthorized: () -> Unit,
    scroll: ScrollState,
    onOpenReview: (FrigateReview) -> Unit,
    onMarkViewed: (String) -> Unit,
    onRetryReviews: () -> Unit,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    pictureInPicture: Boolean,
    onPictureInPicture: ((Float, Rect) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    com.frigate.android.feature.live.LiveCameraScreen(
        profile = state.profile,
        cookie = state.sessionCookie,
        cameras = state.cameras,
        modifier = modifier,
        activity = activity,
        role = state.user.role,
        onSetControl = onSetControl,
        reviews = reviews,
        selectedCameraId = selectedCameraId,
        onSelectedCameraChange = onSelectedCameraChange,
        onOpenHistory = onOpenHistory,
        onMessage = onMessage,
        onUnauthorized = onUnauthorized,
        scroll = scroll,
        onOpenReview = onOpenReview,
        onMarkViewed = onMarkViewed,
        onRetryReviews = onRetryReviews,
        fullscreen = fullscreen,
        onFullscreenChange = onFullscreenChange,
        pictureInPicture = pictureInPicture,
        onPictureInPicture = onPictureInPicture,
    )
}

@Composable
private fun SettingsScreen(state: ConnectionState.Connected, onDisconnect: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Header("SETTINGS", "Connection")
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = FrigateColors.surface)) {
            Column(Modifier.padding(20.dp)) {
                Text(state.profile.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(state.profile.baseUrl, style = MaterialTheme.typography.bodyMedium, color = FrigateColors.muted)
                Spacer(Modifier.height(14.dp))
                Text("Signed in as ${state.user.username}", style = MaterialTheme.typography.bodyMedium, color = FrigateColors.ink)
                Text("Role: ${state.user.role}", style = MaterialTheme.typography.bodySmall, color = FrigateColors.muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun SetupScreen(onConnect: (ServerProfile) -> Unit) {
    var label by rememberSaveable { mutableStateOf("Home server") }
    var url by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = FrigateColors.canvas) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp), verticalArrangement = Arrangement.Center) {
            Text("FRIGATE", style = MaterialTheme.typography.labelLarge, color = FrigateColors.accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Your cameras,\nwithout the noise.", style = MaterialTheme.typography.displaySmall, color = FrigateColors.ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Text("Connect to your Frigate server to see live cameras and recent events.", style = MaterialTheme.typography.bodyLarge, color = FrigateColors.muted)
            Spacer(Modifier.height(34.dp))
            OutlinedTextField(label, { label = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Server name") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(url, { url = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, placeholder = { Text("https://frigate.example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), supportingText = { Text(error ?: "Use the address you normally open in the PWA.") }, isError = error != null)
            Spacer(Modifier.height(22.dp))
            Button(onClick = { ServerProfile.fromInput(label, url).onSuccess(onConnect).onFailure { error = it.message } }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Continue", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Header(eyebrow: String, title: String) {
    Column {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = FrigateColors.accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, color = FrigateColors.ink, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyFeatureCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FrigateColors.surface), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(52.dp).background(FrigateColors.accentSoft, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(icon, null) }
            Spacer(Modifier.width(16.dp))
            Column { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.height(7.dp)); Text(body, style = MaterialTheme.typography.bodyMedium, color = FrigateColors.muted) }
        }
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is FrigateHttpException -> if (code == 401) "Invalid username or password." else "Frigate returned HTTP $code."
    is FrigateNetworkException -> "Could not reach Frigate. Check the URL and network."
    else -> message ?: "Something went wrong."
}


private object FrigateColors {
    val canvas = Color(0xFFF4F1EA)
    val surface = Color(0xFFFFFCF6)
    val ink = Color(0xFF20241F)
    val muted = Color(0xFF6B7068)
    val accent = Color(0xFFB64B2C)
    val accentSoft = Color(0xFFF2D7C9)
}

private object FrigatePwaColors {
    val background = Color.Black
    val surface = Color(0xFF1F1F1F)
    val foreground = Color.White
    val muted = Color(0xFF6B6B6B)
    val indicator = Color(0xFF303030)
    val recording = Color(0xFFEF4444)
    val detection = Color(0xFFEA580C)
}
