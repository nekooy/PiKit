package pi.kit.mob.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.R
import pi.kit.mob.env.BatteryOptimisation
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.LocalLanguage
import pi.kit.mob.locales.LocalStrings
import pi.kit.mob.locales.applyLocale
import pi.kit.mob.locales.strings
import pi.kit.mob.locales.stringsFor
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.RuntimeStatus
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.SheetHost
import pi.kit.mob.ui.components.SheetLayer
import pi.kit.mob.ui.design.PiAgentMark
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiMotion
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiProgress
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiTabFade
import pi.kit.mob.ui.design.PiTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The four destinations, in the bar's order.
 *
 * The order is by how often the app is opened for each: the conversation first,
 * then the shell it drives, then what it changed, and the settings last. It is the
 * order the old navigation bar used and it was arrived at the same way, so it is
 * kept — a destination that moves because the bar was redesigned is a destination
 * the reader has to find twice.
 */
private enum class Destination {
    Chat,
    Terminal,
    Files,
    Settings,
}

@Composable
fun PiKitRoot(session: PiAgentSession) {
    val settings by session.settingsStore.settings.collectAsState()
    val language = settings.language

    // Applies the choice to the default locale as well as to the catalogs, so
    // dates and byte sizes follow the interface.
    remember(language) { applyLocale(language) }

    // One sheet for the whole app. It is created here rather than inside the
    // language key because a language switch disposes everything under that key,
    // and a sheet that was open when it happened still has to animate away.
    val sheets = remember { SheetHost() }

    CompositionLocalProvider(
        LocalStrings provides stringsFor(language),
        LocalLanguage provides language,
        // Held *above* the language key on purpose. Switching the language
        // rebuilds every screen under this key — including the page that asked
        // for a sheet — and the sheet it was switched from must still be able to
        // finish sliding out rather than blinking off the screen.
        LocalSheetHost provides sheets,
    ) {
        // The first-launch prompts sit *above* the language key, not inside it.
        // That is the fix for a measured bug rather than tidiness: a subtree
        // restart disposes the `rememberLauncherForActivityResult` registration
        // while its request is in flight, and the platform then closes the dialog
        // the request had opened. Everything under `key(language)` is rebuilt when
        // the language arrives, which is exactly long enough after launch to hit
        // that window. See `PermissionPrompts`.
        PermissionPrompts(session = session)

        // Keyed on the language so that switching it rebuilds every screen. The
        // catalogs are plain objects, not observable state, so without this the
        // pages that are already composed would keep the old text.
        key(language) {
            RootContent(session = session, language = language, sheets = sheets)
        }
    }
}

/**
 * Whether this process has already asked for the notification permission.
 *
 * A `remember`d flag would not do. `PiKitRoot` rebuilds everything under
 * `key(language)` when the language arrives, and a subtree restart disposes the
 * launcher registration while the request is in flight: the platform then answers
 * the *next* request with `Can request only one set of permissions at a time` and
 * closes the dialog the first one had opened. Measured on the emulator, from the
 * platform's own log: request at 13:10:10.146, that warning at 13:10:10.212, the
 * dialog's CLOSE transition at 13:10:10.506 — 360 ms of dialog, which is why the
 * report read as "the prompt only appeared on the second launch".
 *
 * A process-wide claim rather than state means an activity or composition restart
 * cannot ask twice; a user who denies is not asked again during the same run,
 * which is what the platform does anyway.
 */
private object NotificationPrompt {
    private val asked = AtomicBoolean(false)

    /** True for the first caller only. */
    fun claim(): Boolean = asked.compareAndSet(false, true)
}

/**
 * The first-launch prompts, in order, driven by answers.
 *
 * Notifications first (a system dialog), then the storage explanation (an in-app
 * dialog that offers the system settings page, because "all files access" has no
 * runtime dialog), then the battery-optimisation exemption (an in-app dialog in
 * front of the platform's own yes/no box). Each is raised from the previous one's
 * answer *or* its result callback, so the order cannot invert and none of them can
 * be skipped by a lost effect.
 *
 * Raised only once the runtime is ready, and only once per process: asking over
 * the unpacking screen means asking while the app's own window is still settling,
 * and asking from a subtree that is rebuilt asks twice. It sits above
 * `key(language)` in [PiKitRoot] for the second half of that. A prompt the platform
 * closes on its own frame — which is what happens on the emulator — is asked again
 * at most twice; see the callback.
 *
 * The keep-alive step was added last and it is the only one of the three whose
 * absence was invisible: the other two gate features the user tries immediately,
 * while a process reclaimed with the screen off looks like the answer simply
 * stopping. It used to be reachable only from the agent page, three taps in.
 */
@Composable
private fun PermissionPrompts(session: PiAgentSession) {
    val text = strings
    val context = LocalContext.current
    val runtime by session.runtime.collectAsState()
    val ready = runtime is RuntimeStatus.Ready

    var step by remember { mutableStateOf(PromptStep.None) }
    var attempts by remember { mutableIntStateOf(0) }
    var askedAt by remember { mutableLongStateOf(0L) }

    // What is still owed after the storage dialog is answered, in one place: the
    // answer arrives from two buttons and the sequence has to continue the same way
    // from either of them.
    fun afterStorage(): PromptStep =
        if (BatteryOptimisation.needsAsk(context)) PromptStep.KeepAlive else PromptStep.None

    // Asking for the notification permission is left to the app rather than to the
    // platform. This app targets SDK 28, and for such an app Android 13+ is
    // *supposed* to show the prompt itself when the first notification channel is
    // created — `PiKitApp.onCreate` creates one on every launch. Measured on this
    // emulator (Android 16), it does not: with this request removed, no prompt
    // appeared at all across a cleared first launch, so the request stays.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val elapsed = SystemClock.elapsedRealtime() - askedAt
        when {
            // An answer that arrives in under a second cannot be a decision — a
            // person needs longer than that to read the dialog. It is the platform
            // closing a prompt it did not want to show, which is what happens on
            // this emulator every time: seven runs across every app state tried —
            // requested from the first composition, from the first *usable* frame,
            // and from a fully idle app twenty seconds later — 60-360 ms from
            // display to CLOSE, no user input, no permission state change
            // (`granted=false`, no `USER_SET`). Retrying is the only thing the app
            // can do about it, and it is bounded to two retries. The cost of being
            // wrong is small: a user who really does refuse in under a second is
            // asked once more.
            !granted && elapsed < INSTANT_ANSWER_MS && attempts < MAX_NOTIFICATION_ATTEMPTS ->
                attempts += 1

            // Answered, however it was answered. The two explanations behind it are
            // each owed only if they are still owed — the storage grant may already
            // be held, since the app-op survives a reinstall.
            needsStoragePrompt(context) -> step = PromptStep.Storage
            else -> step = afterStorage()
        }
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        step = when {
            needsNotificationPermission(context) && NotificationPrompt.claim() ->
                PromptStep.Notification

            needsStoragePrompt(context) -> PromptStep.Storage
            else -> afterStorage()
        }
    }

    // Keyed on `attempts` as well as the step, so a retry re-runs it; the first
    // attempt goes immediately, a retry waits for the window to settle again.
    LaunchedEffect(step, attempts) {
        if (step != PromptStep.Notification) return@LaunchedEffect
        if (attempts > 0) delay(NOTIFICATION_RETRY_DELAY_MS)
        askedAt = SystemClock.elapsedRealtime()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (step == PromptStep.Storage) {
        PiPromptDialog(
            title = text.settings.storageAskTitle,
            body = text.settings.storageAskBody,
            confirm = text.settings.storageAskOpen,
            dismiss = text.settings.storageAskLater,
            onConfirm = {
                StorageAccess.markPrompted(context)
                step = afterStorage()
                runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
            },
            onDismiss = {
                StorageAccess.markPrompted(context)
                step = afterStorage()
            },
        )
    }

    if (step == PromptStep.KeepAlive) {
        PiPromptDialog(
            title = text.settings.keepAliveTitle,
            // The row on the agent page says the same two things, from the same
            // key as this title: the state there and the ask here are one subject.
            body = text.settings.keepAliveAskBody,
            confirm = text.settings.keepAliveAskOpen,
            dismiss = text.settings.keepAliveAskLater,
            icon = { Icon(Icons.Filled.BatterySaver, contentDescription = null) },
            onConfirm = {
                // Recorded before the system box is opened, not after: there is
                // no result callback for it — the user may answer it, ignore it,
                // or come back through the recents list — and an app that asked
                // again on the next launch would be nagging about a question the
                // user has already seen.
                BatteryOptimisation.markAsked(context)
                step = PromptStep.None
                BatteryOptimisation.request(context)
            },
            onDismiss = {
                BatteryOptimisation.markAsked(context)
                step = PromptStep.None
            },
        )
    }
}

/**
 * One of the first-launch explanations.
 *
 * An `AlertDialog` still, and deliberately: these are the platform's own
 * conversations — a permission the system will also ask about, and a system
 * settings page about to open — so they are drawn in the platform's dialog shape
 * with the app's colours rather than as a sheet in the app's own modal layer. A
 * sheet that covers the screen to explain a system dialog is a second frame in
 * front of the first one.
 */
@Composable
private fun PiPromptDialog(
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { },
        shape = PiShapes.panel,
        icon = icon,
        title = { Text(title, style = MaterialTheme.typography.headlineSmallEmphasized) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RootContent(session: PiAgentSession, language: Lang, sheets: SheetHost) {
    val runtime by session.runtime.collectAsState()
    val agent by session.agent.collectAsState()
    var destination by remember { mutableStateOf(Destination.Chat) }
    val scope = rememberCoroutineScope()
    val text = strings

    // The chat page's composer, owned here rather than inside `ChatScreen`.
    //
    // `PiTabFade` is a `Crossfade`: when the fade finishes, the destination that
    // left is removed from the composition and its whole subtree with it. So a
    // draft, the images attached to it, and the transcript's scroll position were
    // all destroyed by a look at the terminal, and were reported as exactly that.
    // Hoisted to the one scope that outlives every destination — see
    // [ChatComposerState] for why `rememberSaveable` is not the fix.
    val composer = rememberChatComposerState()

    // The conversation is *not* read here, and that is a performance decision rather
    // than a style one: a streaming answer changes it several times a second, and a
    // value read in this body invalidates the whole body — the insets, the
    // navigation bar and its four items, the sheet layer — for a change only the
    // chat page can see. The page that shows the transcript collects it instead
    // (see [ChatTab]).
    //
    // The one part of it the root does need is a dialog from pi, which can arrive
    // over any destination. Collected as its own flow so the root recomposes when a
    // dialog appears or is answered and at no other time.
    val dialogFlow = remember(session) {
        session.conversation.map { it.pendingDialog }.distinctUntilChanged()
    }
    val pendingDialog by dialogFlow.collectAsState(
        initial = session.conversation.value.pendingDialog,
    )

    // Unpacking is a one-time, ~200MB operation, so it runs behind a blocking
    // gate rather than letting the user into a half-installed environment.
    LaunchedEffect(Unit) {
        session.ensureEnvironment()
        session.startAgent()
    }

    when (val status = runtime) {
        is RuntimeStatus.NotInstalled, is RuntimeStatus.Installing -> {
            InstallProgress(
                text = text,
                fraction = (status as? RuntimeStatus.Installing)?.fraction ?: 0f,
                message = (status as? RuntimeStatus.Installing)?.message ?: text.root.preparing,
            )
            return
        }

        is RuntimeStatus.Failed -> {
            SetupFailure(
                title = text.root.couldNotPrepare,
                body = status.message,
                actionLabel = text.common.retry,
                onAction = { scope.launch { session.ensureEnvironment() } },
            )
            return
        }

        is RuntimeStatus.Ready -> Unit
    }

    LaunchedEffect(language) {
        // Keyed on the language, so it runs once per launch and again whenever the
        // user switches: the shell's banner is a file, and it should follow the
        // interface rather than waiting for the next launch.
        session.refreshTerminalBanner()
    }

    // enableEdgeToEdge() sets decorFitsSystemWindows=false, so the window no
    // longer shrinks for the soft keyboard: the keyboard is drawn *over* the app,
    // the system bars become insets the app has to consume, and the app is
    // responsible for keeping its own content out from under both.
    //
    // ## Why the navigation bar is outside the keyboard's inset
    //
    // The bar is pinned to the window's own bottom edge, and the *page* — not the
    // bar — is padded by the keyboard's inset. So when the keyboard rises it
    // slides up over the bar and hides it, and the page above shrinks to end
    // exactly at the keyboard's top edge.
    //
    // That is the whole mechanism, and it is worth spelling out because three
    // earlier designs got it wrong in ways that all produced the same complaint —
    // "the tab bar is not smooth" — for three different reasons:
    //
    //  1. `AnimatedVisibility` driven by `WindowInsets.isImeVisible`. That flag
    //     flips several times while one keyboard animation plays, as the IME's
    //     insets settle, so the bar expanded and collapsed repeatedly — a visible
    //     flicker — and each flip re-measured the entire page above it.
    //  2. `if (!imeVisible)`, a second mechanism layered on top of the padding
    //     that already moved the bar: the bar appeared and the page resized in
    //     opposite directions.
    //  3. The bar inside the keyboard's inset, so it rode *up* the screen on top
    //     of the keyboard instead of getting out of the way. That is where it was
    //     until now, and it is why the bar never actually disappeared.
    //
    // With the bar outside that inset there is no visibility state, no animation
    // and no second clock: the bar's disappearance *is* the keyboard's own
    // motion, so the two can never disagree or drift apart. Swiping the keyboard
    // down reveals the bar progressively, which is the opposite of a jump.
    //
    // One consequence worth naming: while the keyboard is up the bar is still
    // composed, merely covered. That is deliberate — the alternative is removing
    // it from the layout, which is exactly the re-measure this avoids.
    val density = LocalDensity.current
    var barHeightPx by remember { mutableIntStateOf(0) }
    val navBarInset = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
    val pageBottom = navBarInset
        .union(WindowInsets.ime.only(WindowInsetsSides.Bottom))
        .union(WindowInsets(bottom = with(density) { barHeightPx.toDp() }))

    // The bar takes the colour of the page it belongs to, and the root Surface
    // underneath uses the same colour. The bar's container does not reach the
    // gesture strip below it — that area is filled by the window background — so
    // without matching them the strip showed through as a paler band under the bar.
    val barColor = MaterialTheme.colorScheme.surfaceContainer

    Surface(Modifier.fillMaxSize(), color = barColor) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    // `union` takes the larger inset, which is the point: with the
                    // keyboard down the page ends at the bar's top edge, and with
                    // it up the page ends at the keyboard's top edge. Summing them
                    // instead would leave a dead band one bar tall above the
                    // keyboard.
                    .windowInsetsPadding(pageBottom)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                // The crossfade runs inside this box rather than around it, so
                // that its own `.background(...)` above stays put: for the whole
                // of the fade both destinations are composed at once, and with a
                // transparent host the outgoing page would read through the
                // incoming one instead of being replaced by it.
                PiTabFade(key = destination, modifier = Modifier.fillMaxSize()) { shown ->
                    when (shown as Destination) {
                        Destination.Terminal -> TerminalScreen(session = session)

                        Destination.Chat -> ChatTab(
                            session = session,
                            agent = agent,
                            composer = composer,
                        )

                        Destination.Files -> FilesScreen(session = session)
                        Destination.Settings -> SettingsScreen(session = session)
                    }
                }
            }

            ShortNavigationBar(
                containerColor = barColor,
                // The gesture strip only, so the bar's background reaches the
                // bottom of the window while its items stay clear of the gesture
                // area — and the page above it is padded by the bar's full
                // measured height rather than by a constant that would have to be
                // kept in step with Material's.
                windowInsets = navBarInset,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { barHeightPx = it.height },
            ) {
                Destination.entries.forEach { entry ->
                    val selected = destination == entry
                    ShortNavigationBarItem(
                        selected = selected,
                        onClick = { destination = entry },
                        // Filled when it is the destination being shown, outlined
                        // when it is not. The bar's own indicator already says
                        // which one is active; the weight of the glyph says it a
                        // second time, in the form a reader notices without
                        // reading the labels.
                        icon = {
                            Icon(
                                imageVector = if (selected) entry.filledIcon() else entry.icon(),
                                contentDescription = null,
                            )
                        },
                        label = {
                            // One line, always. The item measures its label with
                            // loose constraints, so a label wider than the item
                            // *wraps* rather than ellipsising: measured on the
                            // emulator in Japanese at font scale 1.8, `ターミナル`
                            // took two lines and pushed the bar's contents out of
                            // it. Every other label in the app is capped; this one
                            // was the exception.
                            Text(
                                entry.label(text),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        // No `colors`: the library's own defaults for this bar are
                        // already the spec's — the active label on `secondary`, the
                        // indicator a pill on `secondaryContainer`, the rest on
                        // `onSurfaceVariant` — and naming them here would be a second
                        // place for the same four roles to drift out of step with it.
                    )
                }
            }

            // The modal layer, over everything — the navigation bar included, which
            // is what makes a sheet modal. Drawn here rather than inside the page it
            // belongs to because the page is inset above that bar, and a panel
            // anchored to the page's bottom edge would float a bar's height above
            // the window's.
            SheetLayer(host = sheets, modifier = Modifier.fillMaxSize())
        }
    }

    if (runtime is RuntimeStatus.Ready && (runtime as RuntimeStatus.Ready).warnings.isNotEmpty()) {
        val warnings = (runtime as RuntimeStatus.Ready).warnings
        AlertDialog(
            onDismissRequest = { },
            shape = PiShapes.panel,
            title = { Text(text.root.environmentIncomplete) },
            text = {
                Text(
                    warnings.joinToString("\n\n"),
                    // Bounded and scrolled inside the bound: this body is the
                    // installer's own warnings, which run past a screen, and an
                    // unbounded one pushed the acknowledgement off the bottom.
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { }) { Text(text.common.ok) } },
        )
    }

    pendingDialog?.let { dialog ->
        PiDialogHost(
            title = dialog.title ?: dialog.method,
            message = dialog.message,
            options = dialog.options,
            placeholder = dialog.placeholder,
            prefill = dialog.prefill,
            onValue = { session.answerDialog(it) },
            onConfirmed = { session.answerDialogConfirmed(it) },
            onCancel = { session.cancelDialog() },
        )
    }
}

/**
 * The chat destination, which is the one place the conversation state is read.
 *
 * A composable of its own rather than three lines inside [RootContent]'s `when`:
 * the transcript changes several times a second while an answer streams, and the
 * read has to happen below the window's chrome — the insets, the navigation bar,
 * the sheet layer — or every token re-runs all of it. `Crossfade`'s content lambda
 * is re-invoked when the state it reads changes, so the read belongs in a child
 * whose scope is the page.
 */
@Composable
private fun ChatTab(session: PiAgentSession, agent: AgentStatus, composer: ChatComposerState) {
    val state by session.conversation.collectAsState()
    WarmFormulaRenderer()
    ChatScreen(session = session, agent = agent, state = state, composer = composer)
}

private fun Destination.label(text: pi.kit.mob.locales.Strings): String = when (this) {
    Destination.Chat -> text.tabs.chat
    Destination.Terminal -> text.tabs.terminal
    Destination.Files -> text.tabs.files
    Destination.Settings -> text.tabs.settings
}

/** The outlined glyph, for a destination that is not being shown. */
private fun Destination.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    Destination.Chat -> Icons.AutoMirrored.Outlined.Chat
    Destination.Terminal -> Icons.Outlined.Terminal
    Destination.Files -> Icons.Outlined.Folder
    Destination.Settings -> Icons.Outlined.Settings
}

/** The filled glyph, for the destination being shown. */
private fun Destination.filledIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    Destination.Chat -> Icons.AutoMirrored.Filled.Chat
    Destination.Terminal -> Icons.Filled.Terminal
    Destination.Files -> Icons.Filled.Folder
    Destination.Settings -> Icons.Filled.Settings
}

/**
 * The first-launch screen, while the bundled runtime is unpacked.
 *
 * ## What this replaced
 *
 * The previous version was four plain `Text`s and a full-width progress bar on
 * the app's *light* content background, with nothing on it that identified the
 * app. On a cold start that is the only thing a user sees for a minute or two —
 * measured: about 110 MB of archives stream out of the APK — and a grey bar on
 * near-white reads as a stall rather than as work being done.
 *
 * Three changes, and each is for a specific reason:
 *
 *  - **A dark surface with the app's own mark.** The window background is already
 *    near-black (it matches the launcher icon), so the very first frame is dark;
 *    painting the setup screen the same colour removes the light flash that used
 *    to happen as Compose took over, and the mark is the shape the user just
 *    tapped — in the same white, which is what a report asked for: the tap and the
 *    screen it opens are one moment, and a mark that changed colour between them
 *    read as a different logo. See `ic_pi_mark.xml`.
 *  - **The percentage is the headline.** The old layout put "Setting up PiKit" in
 *    the largest type and the number in the smallest. The number is the only
 *    thing that changes, so it is what the eye should land on.
 *  - **The file being unpacked is shown.** It comes from the installer's own
 *    progress messages, so the screen reports real work instead of a bar that
 *    appears frozen while a 76 MB archive is written.
 */
@Composable
private fun InstallProgress(text: pi.kit.mob.locales.Strings, fraction: Float, message: String) {
    // The mark breathes rather than spins: there is no bounded work to indicate
    // per-frame, and a spinner next to a real percentage reads as two competing
    // progress indicators.
    val transition = rememberInfiniteTransition(label = "setup")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    // The bar is animated between reported steps, so a jump from 41% to 42% reads
    // as motion rather than as a redraw. `animateFloatAsState` also absorbs the
    // installer's throttled updates — it reports whole percent, which would
    // otherwise arrive in visible lurches.
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = PiMotion.defaultSpatial(),
        label = "fraction",
    )

    Surface(Modifier.fillMaxSize(), color = SETUP_BACKGROUND) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_pi_mark),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    // A slow breath between 88% and 100% opacity. Enough to say
                    // "working" without pulling the eye off the number.
                    .alpha(0.88f + pulse * 0.12f),
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = text.root.settingUp,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = SETUP_PRIMARY,
                textAlign = TextAlign.Center,
            )
            Text(
                text = text.root.setupSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = SETUP_SECONDARY,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(36.dp))

            Text(
                text = "${(shown * 100).toInt()}%",
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = SETUP_PRIMARY,
            )

            Spacer(Modifier.height(16.dp))

            PiProgress(
                fraction = shown,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PiShapes.pill),
            )

            Spacer(Modifier.height(14.dp))

            // Fixed height, so the title and number above do not shift as the
            // message changes length from "Unpacking bootstrap.zip" to
            // "Activating environment".
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = SETUP_SECONDARY,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 36.dp),
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = text.root.setupNote,
                style = MaterialTheme.typography.bodySmall,
                color = SETUP_TERTIARY,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The setup screen's palette, fixed rather than taken from the theme.
 *
 * This screen is the only one that is dark while the rest of the app may be light,
 * so it cannot use `colorScheme` without depending on the system's dark-mode
 * setting — and a user in light mode would then get a light setup screen again. It
 * is the launcher icon's black and the launcher icon's white, and nothing else: the
 * mark and the progress bar used to be amber, which made the first thing the app
 * shows the one surface whose colours did not come from the icon. The greys below
 * are the same white at lower opacities, and the one departure from monochrome is
 * the progress bar, which takes the app's own violet because it is the only thing
 * on the screen that is *moving*.
 */
private val SETUP_BACKGROUND = Color(0xFF0B0E13)
private val SETUP_PRIMARY = Color(0xFFF2F4F8)
private val SETUP_SECONDARY = Color(0xFF9AA3B2)
private val SETUP_TERTIARY = Color(0xFF69717F)

/**
 * The screen a runtime that could not be prepared lands on.
 *
 * The body is the installer's own stderr, which runs to well over a screen: it is
 * bounded by what is left of the page and scrolled inside that bound, because an
 * unbounded one pushed the retry button off the bottom — the screen said neither
 * of the two things that fixes. `fill = false` keeps a short message the height it
 * needs rather than the height available.
 */
@Composable
private fun SetupFailure(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PiAgentMark(size = 64.dp, container = MaterialTheme.colorScheme.errorContainer)
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        PiNotice(
            text = body,
            tone = PiTone.Danger,
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(20.dp))
        PiButton(
            text = actionLabel,
            onClick = onAction,
            kind = PiButtonKind.Filled,
        )
    }
}

/**
 * The first-launch sequence, in the order it is shown.
 *
 * A step rather than three booleans because each prompt is owed to the answer of the
 * one before it: see the block in [PermissionPrompts] for the measurement that made
 * the ordering necessary. [KeepAlive] is last because it is the only one of the three
 * that is not about reaching something — the other two gate features the user tries
 * in the first minute, and this one is about a run that gets cut short later.
 */
private enum class PromptStep { None, Notification, Storage, KeepAlive }

/** An answer faster than this is the platform, not a person. See the retry. */
private const val INSTANT_ANSWER_MS = 1_200L

/** Two retries: enough to survive a prompt the platform closed, not a nag. */
private const val MAX_NOTIFICATION_ATTEMPTS = 2

/** How long to let the window settle before asking again. */
private const val NOTIFICATION_RETRY_DELAY_MS = 1_500L

/** Whether the notification prompt is still owed to the user. */
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/**
 * Whether the storage explanation has still to be shown.
 *
 * Asked of [StorageAccess], which asks `Environment.isExternalStorageManager()` —
 * the app-op, not the manifest permission. That distinction is deliberate (the
 * app-op is what decides whether the mount is readable) and it has a visible
 * consequence: the app-op survives `pm clear` and even a reinstall, so a "fresh"
 * install can read as already granted and is then not asked at all.
 */
private fun needsStoragePrompt(context: Context): Boolean =
    !StorageAccess.isGranted() && !StorageAccess.hasPrompted(context)
