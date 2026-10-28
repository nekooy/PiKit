package pi.kit.mob.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiToastHost
import pi.kit.mob.ui.kit.PiToastHostLayer
import pi.kit.mob.ui.kit.PiToastProvider
import pi.kit.mob.ui.kit.rememberPiToastHost
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The four destinations, in the bar's order.
 *
 * The order is by how often the app is opened for each: the conversation first,
 * then the shell it drives, then what it changed, and the settings last. A
 * destination that moves because the bar was redesigned is a destination the
 * reader has to find twice.
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
    // dates and byte sizes follow the interface. `LaunchedEffect` rather than
    // `remember(language)`'s calculation: applying a locale is a write, and a
    // `remember` block must not have one — a discarded calculation re-runs it and
    // the block returns Unit, which the lint is right to flag.
    LaunchedEffect(language) { applyLocale(language) }

    // One transient message for the whole app. It is created here rather than
    // inside the language key because a language switch disposes everything under
    // that key, and a message raised just before the switch still has to finish
    // being read rather than vanishing with the page that asked for it.
    val snackbar = rememberPiToastHost()

    CompositionLocalProvider(
        LocalStrings provides stringsFor(language),
        LocalLanguage provides language,
    ) {
        PiToastProvider(snackbar) {
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
                RootContent(
                    session = session,
                    language = language,
                    snackbar = snackbar,
                )
            }
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
 * The dialogs are Miuix's window dialogs, which do not need a `Scaffold` above
 * them — the point of asking here, before the runtime is ready, is that there is
 * no page to attach an overlay to yet.
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
        PiConfirm(
            show = true,
            title = text.settings.storageAskTitle,
            text = text.settings.storageAskBody,
            confirm = text.settings.storageAskOpen,
            dismiss = text.settings.storageAskLater,
            // Neither an outside tap nor the back gesture may dismiss this one:
            // "later" is an answer, and it records that the question was asked.
            onDismissRequest = null,
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
        PiConfirm(
            show = true,
            title = text.settings.keepAliveTitle,
            // The row on the agent page says the same two things, from the same
            // key as this title: the state there and the ask here are one subject.
            text = text.settings.keepAliveAskBody,
            confirm = text.settings.keepAliveAskOpen,
            dismiss = text.settings.keepAliveAskLater,
            onDismissRequest = null,
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

@Composable
private fun RootContent(
    session: PiAgentSession,
    language: Lang,
    snackbar: PiToastHost,
) {
    val runtime by session.runtime.collectAsState()
    val agent by session.agent.collectAsState()
    var destination by remember { mutableStateOf(Destination.Chat) }
    val scope = rememberCoroutineScope()
    val text = strings

    // The chat page's composer, owned here rather than inside `ChatScreen`.
    //
    // The tab crossfade removes the destination that left from the composition
    // and its whole subtree with it. A draft, the images attached to it, and the
    // transcript's scroll position would all be destroyed by a look at the
    // terminal. Hoisted to the one scope that outlives every destination — see
    // [ChatComposerState] for why `rememberSaveable` is not the fix.
    val composer = rememberChatComposerState()

    // The conversation is *not* read here, and that is a performance decision rather
    // than a style one: a streaming answer changes it several times a second, and a
    // value read in this body invalidates the whole body — the navigation bar and
    // its four items, the sheet layer — for a change only the chat page can see.
    // The page that shows the transcript collects it instead (see [ChatTab]).
    //
    // The one part of it the root does need is a dialog from pi, which can arrive
    // over any destination. Collected as its own flow so the root recomposes when a
    // dialog appears or is answered and at no other time. The `initial` reads
    // `.value` once, before the first frame — the only place a `.value` is read in
    // composition here, and the one the collector's own first emission replaces.
    val dialogFlow = remember(session) {
        session.conversation.map { it.pendingDialog }.distinctUntilChanged()
    }
    val pendingDialog by dialogFlow.collectAsState(
        initial = remember(session) { session.conversation.value.pendingDialog },
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

    Scaffold(
        // The pages own their top inset (every one of them is a `Scaffold`
        // with a top bar of its own), so the root asks for no content insets
        // and takes only the bottom bar's height out of the content. Asking
        // for `systemBars` here would pad every page's top bar a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MiuixTheme.colorScheme.surface,
        snackbarHost = { PiToastHostLayer(snackbar) },
        bottomBar = {
            // `showDivider = false`: the bar is the page's floor, not a panel
            // laid over it. Miuix's own divider is a hairline the same colour as
            // the card outlines *inside* the page, so a bar that drew one read as
            // one more card edge at the bottom of every screen — a line across the
            // interface that divides nothing, because the surface above and the
            // surface below are the same surface.
            NavigationBar(showDivider = false) {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = entry.icon(),
                        label = entry.label(text),
                    )
                }
            }
        },
    ) { padding ->
        // `padding` is the bar's height. `consumeWindowInsets` makes the
        // keyboard inset below subtract it instead of adding to it, so the page
        // ends at `max(bar, keyboard)` — the bar's height is not left as a dead
        // band above the keyboard, and with the keyboard down the page ends
        // exactly at the bar's top edge.
        //
        // The modifiers are on the crossfade itself and there is no wrapper `Box`:
        // the scaffold already paints `surface`, and a `Box` around a single child
        // that paints the same colour again was a third copy of one background.
        Crossfade(
            targetState = destination,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            label = "tab",
        ) { shown ->
            when (shown) {
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

    // The installer's warnings, raised once and dismissible.
    //
    // It used to be `show = true` with an empty `onDismissRequest` and an OK button
    // whose `onClick` was also empty: `RuntimeStatus.Ready.warnings` is a property of
    // the *process*, not of a moment, so nothing could ever close it and one missing
    // tool held the whole app behind a modal until it was killed. The answer is a
    // local "have I read this", which is also what makes the button's own word true.
    val warnings = (runtime as? RuntimeStatus.Ready)?.warnings.orEmpty()
    var warningsDismissed by rememberSaveable { mutableStateOf(false) }
    if (warnings.isNotEmpty()) {
        val dismiss = { warningsDismissed = true }
        WindowDialog(
            show = !warningsDismissed,
            title = text.root.environmentIncomplete,
            onDismissRequest = dismiss,
        ) {
            Text(
                text = warnings.joinToString("\n\n"),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // Bounded and scrolled inside the bound: this body is the
                // installer's own warnings, which run past a screen, and an
                // unbounded one pushed the acknowledgement off the bottom.
                modifier = Modifier
                    .heightIn(max = DIALOG_BODY_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            )
            TextButton(
                text = text.common.ok,
                onClick = dismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // The pi dialog, held while it leaves.
    //
    // `pendingDialog` is null the instant it is answered, and composing the host from
    // it meant the window was removed from the hierarchy with its content already
    // gone — Miuix's window animates out through `show`, so the exit ran over an empty
    // panel and the host's own `onDismissFinished` never fired. The last value is
    // therefore kept and `show` is what the answer changes.
    var shownDialog by remember { mutableStateOf(pendingDialog) }
    if (pendingDialog != null) shownDialog = pendingDialog
    val dialog = shownDialog
    if (dialog != null) {
        PiDialogHost(
            show = pendingDialog != null,
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

private fun Destination.icon(): ImageVector = when (this) {
    Destination.Chat -> MiuixIcons.Messages
    Destination.Terminal -> TerminalGlyph
    Destination.Files -> MiuixIcons.Folder
    Destination.Settings -> MiuixIcons.Settings
}

/**
 * The shell's glyph: a chevron and an underscore.
 *
 * Miuix carries no terminal icon, and this is the one destination mark the
 * library cannot supply. It is drawn rather than borrowed from another set
 * because a mark from another set carries that set's weight, and a heavier glyph
 * beside three Miuix icons reads as a different control — the rule that also
 * sized ⌘ down at its call site. Two strokes at the library's own line weight on
 * a 24-unit grid, tinted by the bar: a chevron at the left, a bar at the right.
 */
private val TerminalGlyph: ImageVector by lazy {
    val stroke = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "Terminal",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = stroke,
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5.5f, 7.5f)
            lineTo(10.5f, 12.5f)
            lineTo(5.5f, 17.5f)
        }
        path(
            stroke = stroke,
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12.5f, 17.5f)
            lineTo(19f, 17.5f)
        }
    }.build()
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
        animationSpec = tween(durationMillis = 320),
        label = "fraction",
    )

    Surface(Modifier.fillMaxSize(), color = SETUP_BACKGROUND) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(SETUP_MARK_FIELD),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_pi_mark),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        // A slow breath between 88% and 100% opacity. Enough to say
                        // "working" without pulling the eye off the number.
                        .alpha(0.88f + pulse * 0.12f),
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = text.root.settingUp,
                style = MiuixTheme.textStyles.title3,
                color = SETUP_PRIMARY,
                textAlign = TextAlign.Center,
            )
            Text(
                text = text.root.setupSubtitle,
                style = MiuixTheme.textStyles.body2,
                color = SETUP_SECONDARY,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(36.dp))

            Text(
                text = "${(shown * 100).toInt()}%",
                style = MiuixTheme.textStyles.title1,
                color = SETUP_PRIMARY,
            )

            Spacer(Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = shown,
                modifier = Modifier.fillMaxWidth(),
                // The screen is the one dark surface in an app that may be light, so
                // the bar cannot take the scheme's own `primary` on
                // `secondaryContainer`: in light mode that is a pale blue on a pale
                // track, drawn on near-black. These two colours are the setup field's.
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = SETUP_PRIMARY,
                    backgroundColor = SETUP_MARK_FIELD,
                ),
            )

            Spacer(Modifier.height(14.dp))

            // Fixed height, so the title and number above do not shift as the
            // message changes length from "Unpacking bootstrap.zip" to
            // "Activating environment".
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = SETUP_SECONDARY,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 36.dp),
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = text.root.setupNote,
                style = MiuixTheme.textStyles.footnote1,
                color = SETUP_TERTIARY,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The screen a runtime that could not be prepared lands on.
 *
 * The body is the installer's own stderr, which runs to well over a screen: it is
 * bounded by what is left of the page and scrolled inside that bound, because an
 * unbounded one pushed the retry button off the bottom — the screen said neither
 * of the two things that fixes. `fill = false` keeps a short message the height it
 * needs rather than the height available.
 *
 * ## It is a surface and it owns its insets
 *
 * This screen is returned *before* the root `Scaffold`, so nothing above it paints a
 * background or takes the system bars out of the content. Left as a bare `Column` it
 * therefore drew the scheme's `onSurface` and `error` ink on whatever the window's
 * own background is — near-black in this app's launch theme — and put the retry
 * button under the gesture area. Both are fixed here rather than at the call site
 * because a failure screen that is unreadable is the one state the user cannot
 * navigate out of.
 */
@Composable
private fun SetupFailure(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = SETUP_BACKGROUND) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(SETUP_MARK_FIELD),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pi_mark),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                // The scheme's error role, not its container pair: this screen is
                // always the dark setup field, so a container from a light scheme
                // would be a near-white disc around the mark. The error ink is
                // legible on both — it is the one role that reads as red either way.
                tint = MiuixTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            textAlign = TextAlign.Center,
            // The setup screen's own inks, for the reason its palette is fixed:
            // `onSurface` is black in the light scheme and this page is never light.
            color = SETUP_PRIMARY,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MiuixTheme.textStyles.body2,
            color = SETUP_SECONDARY,
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(20.dp))
        TextButton(
            text = actionLabel,
            onClick = onAction,
            modifier = Modifier.fillMaxWidth(),
            // The one control on a page with no theme of its own: the setup field's
            // ink on the field's own fill, so it is the button the page is built for
            // rather than a pale blue pill from a scheme this screen does not use.
            colors = ButtonDefaults.textButtonColors(
                color = SETUP_MARK_FIELD,
                textColor = SETUP_PRIMARY,
            ),
        )
        }
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

/** The tallest a window dialog's body may be before it scrolls inside itself. */
private val DIALOG_BODY_MAX_HEIGHT = 320.dp

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

/**
 * The setup screen's palette, fixed rather than taken from the theme.
 *
 * This screen is the only one that is dark while the rest of the app may be light,
 * so it cannot use `colorScheme` without depending on the system's dark-mode
 * setting — and a user in light mode would then get a light setup screen again. It
 * is the launcher icon's black and the launcher icon's white, and nothing else: a
 * mark on the field it was drawn for. The greys below are the same white at lower
 * opacities.
 */
private val SETUP_BACKGROUND = Color(0xFF0B0E13)
private val SETUP_MARK_FIELD = Color(0xFF151A22)
private val SETUP_PRIMARY = Color(0xFFF2F4F8)
private val SETUP_SECONDARY = Color(0xFF9AA3B2)
private val SETUP_TERTIARY = Color(0xFF69717F)
