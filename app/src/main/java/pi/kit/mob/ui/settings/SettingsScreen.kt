package pi.kit.mob.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pi.kit.mob.BuildConfig
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.CatalogueUpdater
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.PiInstallation
import pi.kit.mob.pi.StorageSelfTest
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.piOverScroll
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** Screens reachable from the Settings tab. Navigation state lives here only. */
internal sealed interface SettingsPage {
    data object Root : SettingsPage

    data object Model : SettingsPage

    /** [profileId] is blank when the form is creating a new profile. */
    data class ModelEdit(val profileId: String) : SettingsPage

    data object Maintenance : SettingsPage

    /**
     * Backing the app's own data up, and putting it back.
     *
     * A page of its own rather than a card on [Maintenance]: that page repairs a
     * runtime rebuilt from the APK, where the worst outcome is a reinstall, and
     * this one moves the only copy of the user's data. See `BackupPage`.
     */
    data object Backup : SettingsPage

    /** Which of the user's folders the agent may reach. */
    data object Storage : SettingsPage

    /**
     * Working directory, what the model is given, and the process controls.
     *
     * Context used to be its own page between the storage row and this one. It
     * is a section here now — the answer to "what does the agent know?" sits
     * with the workspace and the process — and its five points stay points. One
     * page fewer for the same content and the same one editable file.
     */
    data object Agent : SettingsPage

    data object Manual : SettingsPage

    data object About : SettingsPage

    /** The bundled web-access extension's options, in pi's own config file. */
    data object Search : SettingsPage

    /** Cold-start conversation behaviour and the interface theme. */
    data object Personalization : SettingsPage

    /**
     * Where the system back button goes from here. Root has no parent.
     *
     * The environment, the agent process and About PiKit used to be a page of
     * their own under an "Advanced" row, which made the tab root's list shorter
     * at the cost of a level nobody wanted: three rows that are each one fact,
     * one tap further away than every other fact in the app. They are rows on the
     * root now, and their parent is the root.
     */
    val parent: SettingsPage?
        get() = when (this) {
            Root -> null
            // The form was opened from the list, so it goes back to the list
            // rather than all the way to the tab root.
            is ModelEdit -> Model
            else -> Root
        }
}

/**
 * `SettingsPage` survives a configuration change as a short string.
 *
 * The page used to be a plain `remember`, so a rotation — or anything else that
 * saves and restores the composition — dropped the user from a form back to the
 * tab root, losing the half-filled model edit with it. A [Saver] rather than
 * `Parcelable`/`Serializable` because the value has to cross the save boundary as
 * something the platform already knows how to write, and a page is one word plus
 * at most one id.
 *
 * [SettingsPage.ModelEdit] is the only page carrying data, so it is the only one
 * with a suffix. The id is appended whole, without escaping: `substringAfter`
 * with a missing delimiter returns the string unchanged, so a profile id that
 * contains a colon still round-trips.
 *
 * An unrecognised name restores as [SettingsPage.Root]. That is reachable by a
 * downgrade — a build that knows a page this one does not — and opening the tab
 * root beats throwing out of `restore`, which Compose does not catch.
 */
private val SettingsPageSaver: Saver<SettingsPage, String> = Saver(
    save = { it.encode() },
    restore = { encoded ->
        when {
            encoded == "Model" -> SettingsPage.Model
            encoded.startsWith("ModelEdit:") -> SettingsPage.ModelEdit(encoded.substringAfter("ModelEdit:"))
            encoded == "Agent" -> SettingsPage.Agent
            // The environment's facts are rows of About PiKit now. A state saved by a
            // build that had the page restores to the root rather than to a page this
            // one does not have.
            encoded == "Runtime" -> SettingsPage.Root
            // "Advanced" was the page the three facts below the storage row used
            // to sit behind. A state saved by a build that had it restores to the
            // root rather than to a page this one does not have.
            encoded == "Advanced" -> SettingsPage.Root
            encoded == "Maintenance" -> SettingsPage.Maintenance
            encoded == "Backup" -> SettingsPage.Backup
            encoded == "Storage" -> SettingsPage.Storage
            // Context is a section of the agent process page now. A state saved by
            // a build that had its own page lands there rather than at the root.
            encoded == "AgentContext" -> SettingsPage.Agent
            encoded == "Manual" -> SettingsPage.Manual
            encoded == "About" -> SettingsPage.About
            encoded == "Search" -> SettingsPage.Search
            encoded == "Personalization" -> SettingsPage.Personalization
            else -> SettingsPage.Root
        }
    },
)

private fun SettingsPage.encode(): String = when (this) {
    SettingsPage.Root -> "Root"
    SettingsPage.Model -> "Model"
    is SettingsPage.ModelEdit -> "ModelEdit:$profileId"
    SettingsPage.Agent -> "Agent"
    SettingsPage.Maintenance -> "Maintenance"
    SettingsPage.Backup -> "Backup"
    SettingsPage.Storage -> "Storage"
    SettingsPage.Manual -> "Manual"
    SettingsPage.About -> "About"
    SettingsPage.Search -> "Search"
    SettingsPage.Personalization -> "Personalization"
}

/**
 * The Settings tab.
 *
 * Sub-pages are local state rather than routes: the app has four fixed tabs and
 * no navigation library, so a back stack here would be the only one in the app
 * and the system back button would have to be taught about it to be correct.
 * Each sub-page is a full Miuix page with its own top bar and back action.
 *
 * Back is **per page** now rather than one handler above them all: `PiPage`
 * registers it whenever it is given an `onBack`, which is every page but the tab
 * root. That is also what lets a page intercept its own back — the model form asks
 * before discarding edits by passing a lambda rather than by owning a second,
 * deeper handler. A Miuix dialog or sheet is its own window and takes the gesture
 * before the page behind it, which is the ordering the old shared handler had to
 * argue for at length.
 */
@Composable
fun SettingsScreen(session: PiAgentSession) {
    val settings by session.settingsStore.settings.collectAsState()
    val agent by session.agent.collectAsState()
    val context = LocalContext.current

    // Held here rather than inside the Maintenance page: a catalogue refresh runs for
    // seconds to a minute, and leaving that page — to check the version, or because the
    // result arrived — must not throw away the run or its result.
    //
    // It cannot update pi itself, and that is deliberate: the agent is an input of the
    // runtime image the APK carries, and an on-device `npm install -g` that was
    // interrupted left a tree no agent start could read. See `CatalogueUpdater`.
    val catalogue = remember { CatalogueUpdater(session) }

    // Held here for the same reason as the catalogue: the self-test spawns a
    // process, and leaving the page while it runs must not throw the result away.
    val storageSelfTest = remember { StorageSelfTest(context, session.env) }

    var page by rememberSaveable(stateSaver = SettingsPageSaver) {
        mutableStateOf<SettingsPage>(SettingsPage.Root)
    }

    // Which way the *last* move went. Read by the transition on the frame the page
    // changes, and deliberately left alone afterwards: the value is a property of
    // the move, so nothing has to reset it once the slide has finished.
    var forward by remember { mutableStateOf(true) }

    fun open(destination: SettingsPage) {
        forward = destination.depth > page.depth
        page = destination
    }

    // Re-read when the user comes back from the system's "All files access"
    // page, and when they return from the storage sub-page: the folder toggles
    // live only in `session.storagePolicy()`, and a summary snapshotted on the
    // way in kept saying "none" after the user had already chosen folders.
    var storageGranted by remember { mutableStateOf(StorageAccess.isGranted()) }
    var storageSummary by remember { mutableStateOf(session.storagePolicy()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = StorageAccess.isGranted()
                storageGranted = granted
                storageSummary = session.storagePolicy()
                // The links are created here rather than only at unpack time:
                // this is the moment they can first succeed.
                if (granted) session.syncStorageLinks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // The lifecycle does not fire for a move between settings sub-pages, so the
    // root re-reads the two facts its storage row prints whenever it is shown.
    LaunchedEffect(page) {
        if (page == SettingsPage.Root) {
            storageGranted = StorageAccess.isGranted()
            storageSummary = session.storagePolicy()
        }
    }

    // The direction is decided by `open` rather than by the transition because the
    // system's back gesture can arrive while a previous slide is still running, and
    // by then the running transition's own target is the destination of that slide,
    // not the page on screen. Comparing the page we are actually showing is the only
    // answer that stays right.
    //
    // A fade with a short horizontal slide: a page arrives from the trailing edge and
    // gives back an eighth of its width when it leaves. The old `PiPageSwap` did the
    // same thing with the design system's motion constants; the numbers here are the
    // short ones on purpose, because these are settings pages — a row list a reader
    // moves through quickly — and a 300ms slide on every tap is a page that feels slow
    // to open.
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (forward) 1 else -1
            (
                slideInHorizontally(animationSpec = tween(220)) { full -> direction * full / 4 } +
                    fadeIn(animationSpec = tween(160))
                ).togetherWith(
                slideOutHorizontally(animationSpec = tween(220)) { full -> -direction * full / 8 } +
                    fadeOut(animationSpec = tween(140)),
            )
        },
        label = "settings-page",
    ) { current ->
        when (val shown = current) {
            SettingsPage.Root -> RootPage(
                session = session,
                onOpen = ::open,
                storageGranted = storageGranted,
                storageSummary = storageSummary,
                agent = agent,
            )

            SettingsPage.Model -> ModelPage(
                session = session,
                onBack = { open(SettingsPage.Root) },
                onEdit = { id -> open(SettingsPage.ModelEdit(id)) },
            )

            is SettingsPage.ModelEdit -> ModelEditPage(
                session = session,
                profileId = shown.profileId,
                onBack = { open(SettingsPage.Model) },
            )

            SettingsPage.Agent -> AgentPage(
                session = session,
                agent = agent,
                workingDir = settings.workingDir,
                onBack = { open(SettingsPage.Root) },
            )

            SettingsPage.Maintenance -> MaintenancePage(
                session = session,
                catalogue = catalogue,
                selfTest = storageSelfTest,
                onBack = { open(SettingsPage.Root) },
            )

            SettingsPage.Backup -> BackupPage(
                session = session,
                onBack = { open(SettingsPage.Root) },
            )

            SettingsPage.Storage -> StoragePage(
                session = session,
                onBack = { open(SettingsPage.Root) },
            )

            SettingsPage.Manual -> ManualPage(onBack = { open(SettingsPage.Root) })

            SettingsPage.Search -> SearchPage(
                session = session,
                onBack = { open(SettingsPage.Root) },
            )

            SettingsPage.Personalization -> PersonalizationPage(
                session = session,
                onBack = { open(SettingsPage.Root) },
            )

            SettingsPage.About -> AboutPage(
                session = session,
                onBack = { open(SettingsPage.Root) },
            )
        }
    }
}

/**
 * How many `parent` steps a page is from the tab root.
 *
 * Used only to decide whether the next move is deeper or shallower, so the number
 * itself does not matter — only the comparison. It terminates because
 * [SettingsPage.parent] bottoms out at `Root`, whose parent is null.
 */
private val SettingsPage.depth: Int
    get() = generateSequence(this) { it.parent }.count() - 1

/**
 * The tab root: four things a beginner has to find, then the facts and the
 * maintenance actions.
 *
 * The previous flat list gave ten rows the same weight, so the API key — the one
 * thing that stops the agent from working — looked exactly like the licence
 * text. Those rows were then moved behind an "Advanced" row, which turned out to
 * be one level too many: opening the environment's paths or the agent's restart
 * button cost a tap into a page whose whole content was three rows, and the back
 * arrow out of it went to a page the user had never seen. The three are rows
 * here again, and the maintenance pair above them state their fact in the value
 * slot rather than behind an arrow.
 *
 * The value sits in `ArrowPreference`'s own trailing slot, so a row keeps its arrow —
 * it is a way into a page, not a control — and the fact about that page reads beside
 * it, which is the shape every MIUI settings list uses.
 *
 * Thinking level is deliberately *not* here. It is a property of the model
 * answering — pi combines the level with that model's own reasoning capability,
 * and neither means anything without the other — so the control is the
 * composer's chip, the one place a conversation changes it while talking.
 */
@Composable
private fun RootPage(
    session: PiAgentSession,
    onOpen: (SettingsPage) -> Unit,
    storageGranted: Boolean,
    storageSummary: StorageAccess.Policy,
    agent: AgentStatus,
) {
    // Collected here rather than read off the store inside the rows, so a saved
    // profile is reflected the moment the store publishes it.
    val settings by session.settingsStore.settings.collectAsState()
    val config by session.settingsStore.profiles.snapshots.collectAsState()
    val activeProfile = config.activeProfile
    // Read on the IO dispatcher and remembered: `installedVersion` opens and parses pi's
    // `package.json`, which is a filesystem read on every recomposition of a page that
    // recomposes on every store emission. Keyed on the profile so a reinstall behind the
    // page is picked up when the agent's own state moves, not on every frame.
    val piVersion by produceState(
        initialValue = PiInstallation.installedVersion(session.env),
        session.env,
        agent,
    ) {
        value = withContext(Dispatchers.IO) { PiInstallation.installedVersion(session.env) }
    }
    val text = strings
    var choosingLanguage by remember { mutableStateOf(false) }

    // The tab root is a page like every other page: it has Miuix's top bar, and the bar
    // is where the tab's name lives. `onBack` is null, which is what makes the system
    // back gesture leave the app from here rather than doing nothing.
    // The settings root left-aligns its title for the reason the other three tab
    // roots do — see `PiPage`'s *Why a root can be left-aligned*.
    PiPage(title = text.settings.title, alignStart = true) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The page's elastic end: the root is short on a first run and full
                // after it, and it has to answer a drag the same way at both sizes.
                // See `piOverScroll`.
                .piOverScroll()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            PiSection(title = text.settings.essentials) {
                ArrowPreference(
                    title = text.settings.manageProfiles,
                    summary = text.settings.manageProfilesSubtitle,
                    endActions = {
                        SettingsValue(activeProfile?.displayName ?: text.settings.none)
                    },
                    onClick = { onOpen(SettingsPage.Model) },
                )
                // Search, personalization and language sit together because they
                // are the three settings that decide what the agent *is* to the
                // user: what it may reach for, how a launch lands, and which
                // language the app speaks. Personalization is between search and
                // language so launch behaviour is beside the other two things a
                // user changes before their first question.
                ArrowPreference(
                    title = text.settings.searchTitle,
                    summary = text.settings.searchSubtitle,
                    // The bundled `pi-web-access` extension's version, which is a
                    // file in the runtime image and otherwise a terminal away. Absent
                    // — the image carries no extension — leaves the slot empty rather
                    // than printing a placeholder, which is what the search page
                    // itself does.
                    endActions = {
                        val version = session.webSearch.extension?.version
                        if (version != null) SettingsValue(version, monospace = true)
                    },
                    onClick = { onOpen(SettingsPage.Search) },
                )
                ArrowPreference(
                    title = text.settings.personalization,
                    summary = text.settings.personalizationSubtitle,
                    onClick = { onOpen(SettingsPage.Personalization) },
                )
                // A dialog, not a menu: the row asks "which language should the
                // interface speak" and its answers are the whole of the question.
                // The floating menu this replaced read as a dropdown off a
                // toolbar control — a habit from a place this row does not live
                // in — and the reader asked for 弹窗 instead.
                ArrowPreference(
                    title = text.settings.language,
                    summary = text.settings.languageSubtitle,
                    endActions = { SettingsValue(settings.language.label) },
                    onClick = { choosingLanguage = true },
                )
                ArrowPreference(
                    title = text.settings.userManual,
                    summary = text.settings.userManualSubtitle,
                    onClick = { onOpen(SettingsPage.Manual) },
                )
            }

            PiSection(title = text.settings.advancedSection) {
                ArrowPreference(
                    title = text.settings.storageTitle,
                    summary = text.settings.storageSubtitle,
                    endActions = {
                        SettingsValue(
                            when {
                                !storageGranted -> text.settings.storageMissing
                                storageSummary.isEmpty -> text.settings.storageLevelNone
                                storageSummary.isUnrestricted -> text.settings.storageLevelAll
                                else -> text.settings.storageLevelSelected
                            },
                        )
                    },
                    onClick = { onOpen(SettingsPage.Storage) },
                )
                // What the agent may reach is the row above; this one is where it
                // works, what it is told, and the process that carries both.
                ArrowPreference(
                    title = text.settings.agentProcess,
                    summary = agentDescription(agent, text),
                    onClick = { onOpen(SettingsPage.Agent) },
                )
                // Between the agent process and the maintenance pair, and that is
                // where it belongs rather than a matter of taste: the two rows either
                // side of it change things the APK can put back — the runtime, the
                // process, the pi install — and this one is the only row on this page
                // whose subject does not survive an uninstall.
                ArrowPreference(
                    title = text.settings.backupTitle,
                    summary = text.settings.backupSubtitle,
                    onClick = { onOpen(SettingsPage.Backup) },
                )
                ArrowPreference(
                    title = text.settings.updateAndRepair,
                    summary = text.settings.updateAndRepairSubtitle,
                    // The pi version, in the monospace face it is written in
                    // everywhere else (`npm ls`, About PiKit).
                    endActions = {
                        SettingsValue(
                            piVersion ?: text.settings.notFound,
                            monospace = true,
                        )
                    },
                    onClick = { onOpen(SettingsPage.Maintenance) },
                )
                // Last, and with the app's own version in the value slot: it is the
                // one row on this page whose fact is about the app rather than about
                // the agent, and it is the fact a bug report is read against. The page
                // behind it carries the licence as well.
                ArrowPreference(
                    title = text.settings.about,
                    summary = text.settings.aboutSubtitle,
                    endActions = {
                        SettingsValue(BuildConfig.VERSION_NAME, monospace = true)
                    },
                    onClick = { onOpen(SettingsPage.About) },
                )
            }
        }
    }

    // Always composed, and told whether it is open — not wrapped in `if (choosingLanguage)`.
    // A Miuix window is dismissed by animating itself out, and a window that is only
    // composed while the flag is true has nothing to animate: it vanished on the tap and
    // the rest of the app jumped into the hole. This is the same shape `PiConfirm` call
    // sites use (`show = …`).
    LanguageDialog(
        show = choosingLanguage,
        text = text,
        current = settings.language,
        onDismiss = { choosingLanguage = false },
        onPick = { entry ->
            choosingLanguage = false
            // The root of the tree is keyed on this field, so writing it
            // rebuilds the whole interface in the chosen language.
            session.settingsStore.update { it.copy(language = entry) }
        },
    )
}

/**
 * The interface language, as a dialog: the question, its three answers under it,
 * and the two words that close it.
 *
 * It has been a picker sheet and a floating menu, and the menu was the wrong
 * habit: a dropdown anchored to a settings row borrows the shape of a toolbar
 * control's menu, and a settings list is not a toolbar. The dialog is the shape
 * the question already has elsewhere in the app — a question with its answers
 * under it, a confirm and a cancel (§13) — and the reader asked for it by name
 * (`语言选择改为弹窗选择的形式`).
 *
 * The marker moves under the finger and nothing is written until the confirm is
 * tapped: this is a dialog, and a dialog's answer is committed by its confirm
 * button. The mark is a radio rather than a tick in a row of the app's own — it is
 * Miuix's own "one of these", and it says which one is chosen without a second
 * glyph being drawn for the question.
 *
 * ## The rows are full-bleed, and the answers are the app's own pair
 *
 * The three languages are one full-width list: Miuix's `Card` is *not* used, because a
 * card is a surface inset from the dialog's own edge and a row's press feedback is
 * drawn inside whatever bounds it has — so the tap highlight stopped 24dp short of the
 * dialog on each side and read as a highlight of the row rather than of the choice. The
 * rows are `RadioButtonPreference`s directly on the dialog's surface, which is where
 * their indication reaches the dialog's own rounded corner.
 *
 * The two answers are [PiDialogAnswers], the app's one dialog-button shape — the same
 * two filled halves the *rebuild the runtime* question draws. This dialog used to pass
 * a transparent container, on the argument that `TextButton`'s `secondaryVariant` fill is
 * the same near-white as the card above it and the three blocks merged. That card is
 * gone with the full-bleed list, so the argument went with it — and the flat pair was
 * the last dialog in the app whose answers did not match every other one's.
 */
@Composable
private fun LanguageDialog(
    show: Boolean,
    text: Strings,
    current: Lang,
    onDismiss: () -> Unit,
    onPick: (Lang) -> Unit,
) {
    // Keyed on `show` as well as on the stored language, so reopening the question starts
    // at the language that is actually in effect rather than at whatever the last
    // opening's finger left selected and then cancelled.
    var chosen by remember(show, current) { mutableStateOf(current) }

    WindowDialog(
        show = show,
        title = text.settings.language,
        // No horizontal inset for the *content*, so the list spans the dialog: the
        // title and the answer row are centred and already have their own margins from
        // the library, and a row's press indication is clipped to the bounds it is
        // given — see this function's KDoc.
        insideMargin = DpSize(0.dp, DialogDefaults.insideMargin.height),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Lang.entries.forEach { entry ->
                RadioButtonPreference(
                    title = entry.label,
                    selected = entry == chosen,
                    onClick = { chosen = entry },
                )
            }
            PiDialogAnswers(
                dismiss = text.common.cancel,
                onDismiss = onDismiss,
                confirm = text.common.confirm,
                onConfirm = { onPick(chosen) },
                // The answers keep the dialog's own gutter even though the list above
                // them does not: a button's fill has to stop short of the panel's edge,
                // where a row's highlight has to reach it.
                modifier = Modifier.padding(horizontal = DIALOG_ANSWER_GUTTER, vertical = 12.dp),
            )
        }
    }
}

/**
 * The answers' gutter inside a dialog whose content is full-bleed.
 *
 * `DialogDefaults.insideMargin`'s own width, so a dialog that removes the content inset
 * to let its list reach the edges puts the same number back on the buttons — the two are
 * one measurement and stating it as a named constant is what says so.
 */
private val DIALOG_ANSWER_GUTTER = 24.dp

internal fun agentDescription(agent: AgentStatus, text: Strings): String = when (agent) {
    AgentStatus.Running -> text.settings.running
    AgentStatus.Starting -> text.settings.starting
    AgentStatus.Stopped -> text.settings.stopped
    is AgentStatus.Failed -> text.settings.failedWith(agent.message.lineSequence().first())
}
