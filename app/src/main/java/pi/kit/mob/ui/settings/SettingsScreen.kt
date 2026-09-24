package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pi.kit.mob.BuildConfig
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.PiInstallation
import pi.kit.mob.pi.CatalogueUpdater
import pi.kit.mob.pi.StorageSelfTest
import pi.kit.mob.ui.components.PageBackHandler
import pi.kit.mob.ui.components.PageSwap
import pi.kit.mob.ui.components.PiIcons
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.PickerRow

/** Screens reachable from the Settings tab. Navigation state lives here only. */
internal sealed interface SettingsPage {
    data object Root : SettingsPage

    data object Model : SettingsPage

    /** [profileId] is blank when the form is creating a new profile. */
    data class ModelEdit(val profileId: String) : SettingsPage

    data object Agent : SettingsPage

    data object Maintenance : SettingsPage

    /** Which of the user's folders the agent may reach. */
    data object Storage : SettingsPage

    /**
     * What the agent is told before it starts, and the one part of it that is a
     * document the user can edit.
     *
     * Its own page rather than a section of the storage page: it is the answer to
     * "what does the agent know?", which spans the model, the folders, the search
     * configuration and the guard, and it belongs between the two rows whose
     * subjects it also contains — the storage switches below it and the agent
     * process above it.
     */
    data object AgentContext : SettingsPage

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
            encoded == "Storage" -> SettingsPage.Storage
            encoded == "AgentContext" -> SettingsPage.AgentContext
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
    SettingsPage.Storage -> "Storage"
    SettingsPage.AgentContext -> "AgentContext"
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
 * Each sub-page is a full page with its own header and back action instead.
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

    // Which way the *last* move went. Read by PageSwap on the frame the page
    // changes, and deliberately left alone afterwards: the value is a property of
    // the move, so nothing has to reset it once the slide has finished.
    var forward by remember { mutableStateOf(true) }

    fun open(destination: SettingsPage) {
        forward = destination.depth > page.depth
        page = destination
    }

    // Re-read when the user comes back from the system's "All files access"
    // page. There is no result callback for that screen, so the lifecycle is
    // what tells us to look again.
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

    // Without this the system back button falls through to the Activity and
    // closes the app, which from a sub-page is indistinguishable from a crash.
    // Disabled at the root, so back from the Settings tab root still leaves the
    // app as it always did.
    //
    // The direction is decided here rather than inside PageSwap because the
    // system's back gesture can arrive while a previous slide is still running,
    // and by then AnimatedContent's own `targetState` is the destination of that
    // slide, not the page on screen. Walking the `parent` chain from the page we
    // are actually showing is the only answer that stays right.
    //
    // `PageBackHandler` and not `BackHandler`: a sheet is drawn over this page and
    // the back gesture belongs to it first. See that function for why the ordering
    // cannot be relied on.
    PageBackHandler(enabled = page != SettingsPage.Root) {
        val parent = page.parent ?: return@PageBackHandler
        forward = false
        page = parent
    }

    Column(Modifier.fillMaxSize()) {
        PageSwap(
            key = page,
            forward = forward,
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            when (val shown = current as SettingsPage) {
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

                SettingsPage.Storage -> StoragePage(
                    session = session,
                    onBack = { open(SettingsPage.Root) },
                )

                SettingsPage.AgentContext -> AgentContextPage(
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
 * column rather than behind an arrow.
 *
 * Thinking level is deliberately *not* here. It is a property of the model
 * answering — pi combines the level with that model's own reasoning capability,
 * and neither means anything without the other — so it lives on the model page,
 * beside the profile it applies to.
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
    val piVersion = PiInstallation.installedVersion(session.env)
    val text = strings

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(title = text.settings.title)

        SettingsBody {
            SettingsSection(text.settings.essentials) {
                SettingsRow(
                    title = text.settings.manageProfiles,
                    subtitle = text.settings.manageProfilesSubtitle,
                    // The app's own model mark rather than `SmartToy`: a robot
                    // head is a picture of an assistant, and this row is about
                    // *which* model answers. See `PiIcons`.
                    icon = PiIcons.Model,
                    value = activeProfile?.displayName ?: text.settings.none,
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.Model) },
                )
                SettingsDivider()
                // Search, personalization and language sit together because they
                // are the three settings that decide what the agent *is* to the
                // user: what it may reach for, how a launch lands, and which
                // language the app speaks. Personalization is between search and
                // language so launch behaviour is beside the other two things a
                // user changes before their first question. Search used to be
                // last in the group, which read as an afterthought to a feature
                // that ships switched on and working.
                SettingsRow(
                    title = text.settings.searchTitle,
                    subtitle = text.settings.searchSubtitle,
                    icon = Icons.Filled.Search,
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.Search) },
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.personalization,
                    subtitle = text.settings.personalizationSubtitle,
                    icon = Icons.Filled.Palette,
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.Personalization) },
                )
                SettingsDivider()
                PickerRow(
                    title = text.settings.language,
                    subtitle = text.settings.languageSubtitle,
                    value = settings.language.label,
                    icon = Icons.Filled.Translate,
                    options = Lang.entries.map { entry ->
                        PickerOption(id = entry.code, label = entry.label)
                    },
                    selectedId = settings.language.code,
                    onPick = { code ->
                        // The root of the tree is keyed on this field, so writing
                        // it rebuilds the whole interface in the chosen language.
                        Lang.fromCode(code).let { chosen ->
                            session.settingsStore.update { it.copy(language = chosen) }
                        }
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.userManual,
                    subtitle = text.settings.userManualSubtitle,
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.Manual) },
                )
            }

            SettingsSection(text.settings.advancedSection) {
                SettingsRow(
                    title = text.settings.storageTitle,
                    subtitle = text.settings.storageSubtitle,
                    icon = Icons.Filled.SdCard,
                    value = when {
                        !storageGranted -> text.settings.storageMissing
                        storageSummary.isEmpty -> text.settings.storageLevelNone
                        storageSummary.isUnrestricted -> text.settings.storageLevelAll
                        else -> text.settings.storageLevelSelected
                    },
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.Storage) },
                )
                SettingsDivider()
                // Between the folders and the process, which is also where its
                // subject sits: what the agent may reach is the row above, and what
                // pi is launched with is the row below. This row is what the two of
                // them *add up to* as far as the model is concerned — plus the one
                // document that is neither.
                SettingsRow(
                    title = text.settings.agentContextTitle,
                    subtitle = text.settings.agentContextSubtitle,
                    icon = Icons.Filled.Psychology,
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.AgentContext) },
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.agentProcess,
                    subtitle = agentDescription(agent, text),
                    icon = Icons.Filled.PlayArrow,
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.Agent) },
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.updateAndRepair,
                    subtitle = text.settings.updateAndRepairSubtitle,
                    icon = Icons.Filled.Build,
                    value = piVersion ?: text.settings.notFound,
                    // The pi version, in the monospace face it is written in
                    // everywhere else (`npm ls`, About PiKit): the value
                    // column already reserves a share for it, and three lines
                    // means a version string can never be the thing that is cut.
                    monospaceValue = true,
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.Maintenance) },
                )
                SettingsDivider()
                // Last, and with the app's own version in the value column: it is
                // the one row on this page whose fact is about the app rather than
                // about the agent, and it is the fact a bug report is read against.
                // The page behind it carries the licence as well.
                SettingsRow(
                    title = text.settings.about,
                    subtitle = text.settings.aboutSubtitle,
                    icon = Icons.Filled.Info,
                    value = BuildConfig.VERSION_NAME,
                    monospaceValue = true,
                    showChevron = true,
                    onClick = { onOpen(SettingsPage.About) },
                )
            }

            SettingsNote(text.settings.bundlesNote)
        }
    }
}

internal fun agentDescription(agent: AgentStatus, text: Strings): String = when (agent) {
    AgentStatus.Running -> text.settings.running
    AgentStatus.Starting -> text.settings.starting
    AgentStatus.Stopped -> text.settings.stopped
    is AgentStatus.Failed -> text.settings.failedWith(agent.message.lineSequence().first())
}

