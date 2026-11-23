package pi.kit.mob.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pi.kit.mob.BuildConfig
import pi.kit.mob.data.PiSettings
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.CatalogueUpdater
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.PiInstallation
import pi.kit.mob.pi.StorageSelfTest
import pi.kit.mob.ui.kit.AvatarSpeaker
import pi.kit.mob.ui.kit.LocalPiHaptics
import pi.kit.mob.ui.kit.PiAvatar
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiDropdownPreference
import pi.kit.mob.ui.kit.PiMotion
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiTone
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

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

    /**
     * The agent itself: its process, the workspace and instruction documents work
     * mode reads, and chat mode's presets.
     *
     * Context used to be its own page between the storage row and this one; it is
     * a sheet from this page now, because "what does the model receive" is a
     * question about the two instruction rows beside it rather than a destination
     * of its own.
     */
    data object Agent : SettingsPage

    data object About : SettingsPage

    /** The licence text, opened from the About page's legal group. */
    data object Licence : SettingsPage

    /** The credits, opened from the About page's legal group. */
    data object Credits : SettingsPage

    /** The bundled web extension's settings, and a new chat's web-access default. */
    data object Search : SettingsPage

    /** Cold-start conversation behaviour and the interface theme. */
    data object Personalization : SettingsPage

    /**
     * Where the system back button goes from here. Root has no parent.
     *
     * The environment, Agent settings and About PiKit used to be a page of
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
            // The licence and the credits are read from the About page, so back
            // returns there rather than to the tab root.
            SettingsPage.Licence, SettingsPage.Credits -> About
            // The search page's former second level is gone: all of its controls are
            // rows of the one page now, and a saved "SearchAdvanced" is a downgrade
            // from a build that had it.
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
 * something the platform already knows how to write, and a page is one word plus at
 * most one id.
 *
 * [SettingsPage.ModelEdit] is the only page carrying data, so it is the only one
 * with a suffix. The id is appended whole, without escaping: `substringAfter`
 * with a missing delimiter returns the string unchanged, so a profile id that
 * contains a colon still round-trips.
 *
 * The encode and the decode are two plain functions rather than two lambdas inside
 * the `Saver`, so `SettingsPageSaverTest` can pin both: a saved page has to restore,
 * and a name this build does not know — one saved by a newer build, or by a page
 * that has since been removed — has to land somewhere rather than throw.
 */
private val SettingsPageSaver: Saver<SettingsPage, String> = Saver(
    save = { it.encode() },
    restore = ::decodeSettingsPage,
)

/**
 * An unrecognised name restores as [SettingsPage.Root]. That is reachable by a
 * downgrade — a build that knows a page this one does not — and opening the tab
 * root beats throwing out of `restore`, which Compose does not catch.
 */
internal fun decodeSettingsPage(encoded: String): SettingsPage = when {
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
    // The storage page was removed once the permission tiers took over what it
    // decided (§14); its stored state lands at the root.
    encoded == "Storage" -> SettingsPage.Root
    // Context is a sheet on the Agent settings page now. A state saved by
    // a build that had its own page lands there rather than at the root.
    encoded == "AgentContext" -> SettingsPage.Agent
    // The user manual is gone: the app is meant to be read by using it, and the page
    // was a second, long document that could drift from the interface. A state saved
    // by a build that had it lands at the root.
    encoded == "Manual" -> SettingsPage.Root
    encoded == "About" -> SettingsPage.About
    encoded == "Licence" -> SettingsPage.Licence
    encoded == "Credits" -> SettingsPage.Credits
    encoded == "Search" -> SettingsPage.Search
    // The search page's former second level: a build that had it restores to the page
    // that now holds every control it had.
    encoded == "SearchAdvanced" -> SettingsPage.Search
    encoded == "Personalization" -> SettingsPage.Personalization
    else -> SettingsPage.Root
}

internal fun SettingsPage.encode(): String = when (this) {
    SettingsPage.Root -> "Root"
    SettingsPage.Model -> "Model"
    is SettingsPage.ModelEdit -> "ModelEdit:$profileId"
    SettingsPage.Agent -> "Agent"
    SettingsPage.Maintenance -> "Maintenance"
    SettingsPage.Backup -> "Backup"
    SettingsPage.About -> "About"
    SettingsPage.Licence -> "Licence"
    SettingsPage.Credits -> "Credits"
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
fun SettingsScreen(session: PiAgentSession, onBack: () -> Unit) {
    val settings by session.settingsStore.settings.collectAsStateWithLifecycle()
    val agent by session.agent.collectAsStateWithLifecycle()
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

    // Android's "all files access" is asked for from the workspace picker now, which is
    // the only place that needs it (§14): a folder on the phone becomes a workspace, and
    // the grant is what makes it readable. The farm follows the grant, and the agent
    // re-reads it before every launch.

    // The direction is decided by `open` rather than by the transition because the
    // system's back gesture can arrive while a previous slide is still running, and
    // by then the running transition's own target is the destination of that slide,
    // not the page on screen. Comparing the page we are actually showing is the only
    // answer that stays right.
    //
    // The app's one page move (`PiMotion`): a Miuix spring carries the incoming page
    // a quarter of the width while the outgoing one gives back an eighth the other
    // way. The old local `tween`s here were the settings stack's own copy of a move
    // the root and the files page also had, and the three copies disagreed.
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (forward) 1 else -1
            PiMotion.page(direction)
        },
        label = "settings-page",
    ) { current ->
        when (val shown = current) {
            SettingsPage.Root -> RootPage(
                session = session,
                onOpen = ::open,
                agent = agent,
                onBack = onBack,
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
                onOpen = ::open,
            )

            SettingsPage.Licence -> NotePage(
                title = strings.notes.licenceTitle,
                body = strings.notes.licence,
                onBack = { open(SettingsPage.About) },
            )

            SettingsPage.Credits -> NotePage(
                title = strings.notes.creditsTitle,
                body = strings.notes.credits,
                onBack = { open(SettingsPage.About) },
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
    agent: AgentStatus,
    onBack: () -> Unit,
) {
    // Collected here rather than read off the store inside the rows, so a saved
    // profile is reflected the moment the store publishes it.
    val settings by session.settingsStore.settings.collectAsStateWithLifecycle()
    val config by session.settingsStore.profiles.snapshots.collectAsStateWithLifecycle()
    val activeProfile = config.activeProfile
    // Read on the IO dispatcher inside the effect. `initialValue` used to call
    // `installedVersion` itself, and a `produceState` argument is evaluated on every
    // recomposition of a page that recomposes on every store emission — a file open and
    // a JSON parse on the main thread for each one. The null first frame draws the
    // "not found" placeholder that `piVersion ?:` already handles; the effect replaces
    // it as soon as the read lands.
    val piVersion by produceState<String?>(
        initialValue = null,
        session.env,
        agent,
    ) {
        value = withContext(Dispatchers.IO) { PiInstallation.installedVersion(session.env) }
    }
    val text = strings
    val haptics = LocalPiHaptics.current

    // The tab root is a page like every other page: it has Miuix's top bar, and the bar
    // is where the tab's name lives. `onBack` is null, which is what makes the system
    // back gesture leave the app from here rather than doing nothing.
    // The settings root left-aligns its title for the reason the other three tab
    // roots do — see `PiPage`'s *Why a root can be left-aligned*.
    val pageScroll = rememberScrollState()
    PiPage(
        title = text.settings.title,
        onBack = onBack,
        contentScrolled = pageScroll.canScrollBackward,
    ) { padding ->
        Column(Modifier.settingsPageScroll(padding, pageScroll)) {
            // The page opens with the thing it configures, not with a list of nouns:
            // the agent's mark, the name the transcript gives it, its process state and
            // the profile answering right now. The card is a way into Agent settings,
            // which is the page every one of those facts belongs to.
            SettingsIdentityCard(
                settings = settings,
                agent = agent,
                profileName = activeProfile?.displayName,
                text = text,
                onClick = { onOpen(SettingsPage.Agent) },
            )

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
                    onClick = { onOpen(SettingsPage.Search) },
                )
                ArrowPreference(
                    title = text.settings.personalization,
                    summary = text.settings.personalizationSubtitle,
                    onClick = { onOpen(SettingsPage.Personalization) },
                )
                // A dropdown row, like every other value with a handful of options: the
                // current language sits at the row's end and the three answers open in a
                // panel anchored there. It has been a picker sheet, a floating menu and a
                // dialog — the dialog because the *menu* read as a toolbar control's menu
                // borrowed for a settings list — and the dropdown row is the shape that
                // answers both objections: it is a settings row, and it opens over itself
                // rather than over the page.
                PiDropdownPreference(
                    items = Lang.entries.map { it.label },
                    selectedIndex = settings.language.ordinal,
                    title = text.settings.language,
                    summary = text.settings.languageSubtitle,
                    onSelectedIndexChange = { index ->
                        val entry = Lang.entries[index]
                        if (entry != settings.language) {
                            // The library says nothing when a row is picked; this is the
                            // documented "a row of a set became the chosen one" moment.
                            haptics.choose()
                            // The root of the tree is keyed on this field, so writing it
                            // rebuilds the whole interface in the chosen language.
                            session.settingsStore.update { it.copy(language = entry) }
                        }
                    },
                )
            }

            PiSection(title = text.settings.agentSection) {
                // Where it works, what it is told, and the process that carries both.
                // The storage row used to lead this section; the workspace row on the
                // agent page answers the same question now, with one answer instead of
                // seven switches (§14).
                ArrowPreference(
                    title = text.settings.agentConfig,
                    summary = agentDescription(agent, text),
                    onClick = { onOpen(SettingsPage.Agent) },
                )
                // Between Agent settings and the maintenance pair, and that is
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
            }

            // The app's own facts are a group of their own: the version in this row's
            // value slot is what a bug report is read against, and putting it beside
            // the agent's process would make two kinds of fact share one card.
            PiSection(title = text.settings.aboutSection) {
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

}

/**
 * The settings root's opening card: the agent the rest of the page configures.
 *
 * It answers the three questions a reader has before touching a settings list —
 * which agent is this, is it running, and what is answering — and it is the first
 * place the avatar chosen on the personalization page is seen outside the
 * transcript. The card is the way into Agent settings, so the identity is not
 * decoration: it is the row for the page that owns every fact on it.
 */
@Composable
private fun SettingsIdentityCard(
    settings: PiSettings,
    agent: AgentStatus,
    profileName: String?,
    text: Strings,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        cornerRadius = PiCorner.radius,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiAvatar(
                speaker = AvatarSpeaker.AGENT,
                color = settings.aiAvatarColor.seed,
                imagePath = settings.aiAvatarImage.ifBlank { null },
                size = 56.dp,
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = settings.aiAvatarName.ifBlank { "PiKit" },
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when (agent) {
                                    is AgentStatus.Running -> PiTone.live()
                                    is AgentStatus.Starting -> MiuixTheme.colorScheme.primary
                                    is AgentStatus.Stopped -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    is AgentStatus.Failed -> PiTone.error()
                                },
                            ),
                    )
                    Text(
                        text = agentDescription(agent, text),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = profileName ?: text.settings.noModelChosen,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

internal fun agentDescription(agent: AgentStatus, text: Strings): String = when (agent) {
    AgentStatus.Running -> text.settings.running
    AgentStatus.Starting -> text.settings.starting
    AgentStatus.Stopped -> text.settings.stopped
    is AgentStatus.Failed -> text.settings.failedWith(agent.message.lineSequence().first())
}
