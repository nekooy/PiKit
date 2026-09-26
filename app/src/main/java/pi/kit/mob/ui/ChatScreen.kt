package pi.kit.mob.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.rememberedThinkingLevels
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.pi.ConversationState
import pi.kit.mob.pi.ImageAttachment
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.clampThinkingLevel
import pi.kit.mob.pi.thinkingLevelsFor
import pi.kit.mob.pi.SessionMetadata
import pi.kit.mob.ui.chat.NoticeCard
import pi.kit.mob.ui.chat.ReasoningBlock
import pi.kit.mob.ui.chat.ReasoningToggle
import pi.kit.mob.ui.chat.ToolCard
import pi.kit.mob.ui.chat.TurnSummaryData
import pi.kit.mob.ui.chat.TurnSummaryRow
import pi.kit.mob.ui.chat.FollowTailController
import pi.kit.mob.ui.chat.rememberFollowTail
import pi.kit.mob.ui.chat.scrollToEnd
import pi.kit.mob.ui.components.PageHeader
import pi.kit.mob.ui.components.CopyButton
import pi.kit.mob.ui.components.CopyButtonInkInset
import pi.kit.mob.ui.components.PageBackHandler
import pi.kit.mob.ui.components.PiIcons
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PageSwap
import pi.kit.mob.ui.components.PickerBody
import pi.kit.mob.ui.components.ReadOnlyBody
import pi.kit.mob.ui.components.ReadOnlySheetRow
import pi.kit.mob.ui.components.thinkingLevelFootnote
import pi.kit.mob.ui.components.thinkingLevelOptions
import pi.kit.mob.ui.components.Sheet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a picked file is until it is actually sent. */
internal data class PendingImage(val id: String, val uri: Uri, val name: String)

/**
 * Everything the chat page owns that has to survive a tab switch.
 *
 * ## Why this is not `remember`ed inside the page
 *
 * The four tabs are drawn by a `Crossfade` (`components/Transitions.kt`), and when the
 * fade finishes the tab that left is taken out of the composition — so `ChatScreen` is
 * *destroyed* on every switch away, and every plain `remember` in it starts again on the
 * way back. That was reported as the input box losing its draft, and it was never only
 * the draft: the attached images, the notice about them, and the transcript's scroll
 * position went with it.
 *
 * `rememberSaveable` does not fix it either. `Crossfade` wraps its content in
 * `AnimatedContent`, which has no `SaveableStateHolder`, so the saved state is dropped
 * along with the composition rather than parked and restored. Hoisting is the fix that
 * does not depend on how the host animation is implemented: this object is `remember`ed
 * in [RootContent], above the crossfade, and only its *readers* come and go.
 *
 * The page's own dialogs (`showSessions`, `confirmNewSession`) deliberately stay in the
 * page: they are modal over content that is not on screen any more, so a tab switch is a
 * reasonable way to answer them.
 *
 * `listState` is the transcript's scroll position and the anchor for the follow rule.
 * [ChatScreen] hands it to `rememberFollowTail`, which is remembered per composition — so
 * the scroll offset survives a tab switch and the follow rule's own "did the reader scroll
 * away" flag does not. That is the safe half of the pair: a switched-back-to chat page that
 * starts out following the answer is the behaviour the user asked for by looking at the
 * chat tab again.
 */
internal class ChatComposerState {
    /** The draft prompt. */
    var input by mutableStateOf("")

    /** Images and documents picked but not sent. */
    var attachments by mutableStateOf<List<PendingImage>>(emptyList())

    /** What the composer's one-line warning under the field is saying, if anything. */
    var notice by mutableStateOf<String?>(null)

    /** The transcript's scroll position. */
    val listState = LazyListState()
}

/** Creates the one [ChatComposerState] per app run, in the scope that outlives the tabs. */
@Composable
internal fun rememberChatComposerState(): ChatComposerState = remember { ChatComposerState() }

/** Refused above this size; pi would take it, but the JSONL line becomes absurd. */
private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

/**
 * How many attachments one prompt may carry.
 *
 * Not a protocol limit — pi takes a list. It is a memory limit, and the number comes
 * from the cost of *sending*: each attachment is live three times at once (the raw
 * bytes, the base64 string, and the JSON document the base64 is pasted into), so a
 * 4 MB picture is ~20 MB while the prompt is built. Four of them is ~80 MB against a
 * 128–256 MB heap, which is the whole budget on a low-end device. The cap is on the
 * composer rather than at send time so the user finds out while the tiles are still
 * on screen with something to remove.
 */
private const val MAX_ATTACHMENTS = 4

/** Longest edge of the thumbnail shown in the composer, in pixels. */
private const val THUMBNAIL_EDGE = 160

/**
 * Which of the composer's sheets is being asked for.
 *
 * One enum rather than five booleans: the sheets are mutually exclusive by
 * construction (each is modal that covers the others), and a name per sheet keeps
 * the five bodies one `when` apart from the controls that open them.
 *
 * There is no matching `sheet` state: the open sheet lives in the app's one
 * [SheetHost] (`components/Sheets.kt`), which is also what the back gesture and
 * the scrim close. A second copy of "which sheet is open" here would be a second
 * answer to a question that already has one.
 */
private enum class ComposerSheet { Thinking, Model, Context, Commands, Attach, Shell }

@Composable
internal fun ChatScreen(
    session: PiAgentSession,
    agent: AgentStatus,
    state: ConversationState,
    composer: ChatComposerState,
) {
    val text = strings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Owned by `RootContent`, above the tab crossfade, so a tab switch does not destroy
    // it — see [ChatComposerState] for what that cost and why `rememberSaveable` is not
    // the fix.
    val listState = composer.listState
    var showSessions by remember { mutableStateOf(false) }
    // A new session asked for while the agent is answering. Same question as the
    // history list's rows ask before a switch, and the same dialog: pi's
    // `new_session` aborts the running turn exactly as `switch_session` does.
    var confirmNewSession by remember { mutableStateOf(false) }
    val sheets = LocalSheetHost.current

    /** Adds picked documents or images to the composer, refusing oversized images. */
    fun accept(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> prepareImage(context, uri, text) }
            }
            // Capped here rather than by refusing the pick: the picker is the
            // platform's and has no "up to four" mode, so a pick of ten images means
            // ten URIs arrive at once. The first four that fit are kept and the rest
            // are dropped with a notice — silently keeping them is how the app would
            // be killed while building the JSON instead.
            val room = (MAX_ATTACHMENTS - composer.attachments.size).coerceAtLeast(0)
            val taken = accepted.take(room)
            // The image is attached either way; the notice says what will happen
            // to it. pi drops an attachment for a model it believes is text-only,
            // and a model pi does not know inherits its provider's default
            // capabilities — so a hand-typed model id silently loses the picture.
            // `Settings -> Model & provider` has the switch that declares it.
            composer.notice = when {
                accepted.size > taken.size -> text.chat.imageLimit(MAX_ATTACHMENTS)
                accepted.size < uris.size -> text.chat.imageTooLarge
                taken.isNotEmpty() && state.model?.supportsImages == false ->
                    text.chat.imageNotSupported

                // Cleared rather than left standing from the last pick: a warning
                // about a file the user has since replaced is worse than none.
                else -> null
            }
            composer.attachments = composer.attachments + taken
        }
    }

    // The picker itself is the system's "recent images + any app that can supply
    // one" sheet on Android 13+, and a normal file picker below that, so the gallery
    // half needs no permission and no menu of its own.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> accept(uris) }

    // The same pipeline for a document, so the `+` menu's second entry needs no
    // second code path: `prepareImage` only reads a display name and a size, and
    // an image picked here is simply one that happens to decode as a thumbnail.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> accept(uris) }

    // ---- taking a photo ----------------------------------------------------
    //
    // The one pick the system does not do for us. `TakePicture` writes into a file
    // the app supplies, so the app has to supply one: a file in this app's own cache
    // directory, exposed through the `FileProvider` the Files tab already declares
    // (`res/xml/file_paths.xml` has the `cache-path` root).
    //
    // The file lives in the cache rather than in the conversation's own storage
    // because it is a *staging* area: `accept` copies what it needs out of it when
    // the prompt is sent, and a picture the user took and then removed should not be
    // a file that survives in the app for ever. The cache directory is also the one
    // place the platform is willing to prune on its own.
    //
    // The URI is remembered alongside the launcher because the result callback is
    // handed only `success: Boolean` — the photo's location is the app's business,
    // not the camera's answer — so the picture that was just written has to be the
    // one whose URI this app already holds.
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }

    // The launcher first, and the two functions that drive it after, because both of
    // them have to reach it: a Kotlin local function cannot see a `val` declared
    // below it, and the order these three are written in is the order they depend on
    // each other in, not the order they were written in.
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = pendingCapture
        pendingCapture = null
        if (saved && uri != null) {
            accept(listOf(uri))
        } else if (uri != null) {
            // A cancelled capture leaves the empty file behind, which would show up
            // as a zero-byte attachment the next time the cache was scanned.
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /**
     * Writes the photo into the app's own cache and hands it to the camera.
     *
     * The file lives in the cache rather than in the conversation's own storage
     * because it is a *staging* area: [accept] reads what it needs from it when the
     * prompt is sent, and a picture the user took and then removed should not be a
     * file that survives in the app for ever. The cache directory is also the one
     * place the platform is willing to prune on its own, and the `FileProvider`
     * that exposes it is the one the Files tab already declares
     * (`res/xml/file_paths.xml` has the `cache-path` root).
     */
    fun startCapture() {
        val uri = runCatching { newCaptureUri(context) }.getOrNull()
        if (uri == null) {
            composer.notice = text.chat.cameraUnavailable
            return
        }
        pendingCapture = uri
        // `runCatching` around the *launch* as well as around the file: a device with
        // no app that handles `ACTION_IMAGE_CAPTURE` makes this throw
        // `ActivityNotFoundException` on the main thread, which is a crash where the
        // reader was expecting a photo. The notice is the same sentence the missing
        // file gets, because it is the same fact from the user's side: nothing here
        // can take a picture.
        runCatching { takePicture.launch(uri) }.onFailure {
            pendingCapture = null
            composer.notice = text.chat.cameraUnavailable
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startCapture() else composer.notice = text.chat.cameraDenied }

    /**
     * Opens the camera, asking for the permission first when it is not held.
     *
     * `CAMERA` is requested rather than assumed: on this app's own `targetSdk` the
     * declaration alone would be enough for the platform, but the *user* still has a
     * switch, and a capture that fails silently because it was turned off is the kind
     * of dead tap this page keeps having to fix. A refusal puts a notice on the
     * composer, which is where this page says everything else it has to say.
     */
    fun capture() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startCapture()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // Interrupting a programmatic scroll to look at something above must not be
    // mistaken for "the reader scrolled away". `programmatic` marks the frames
    // this file's own scrolling owns. It belongs to the follow rule, which cannot
    // tell a snap it asked for from a drag by looking at the offset — so it is
    // declared here and handed *to* the rule rather than kept privately inside it,
    // where nothing would ever set it.
    val programmatic = remember { mutableStateOf(false) }

    // The agent is what the follow rule is about: while a turn runs, the tail is
    // where the reader wants to be unless they have said otherwise. The rule
    // itself is a pure state machine in `chat/Transcript.kt`, so it can be tested
    // on the JVM; this is only the wiring.
    val follow = rememberFollowTail(listState, programmatic)

    // The conversation list is a page, not a dialog: it needs room for search,
    // multi-select and per-conversation actions. `PageBackHandler`, so a sheet
    // opened over this page closes on the first back rather than this page
    // closing behind it.
    PageBackHandler(enabled = showSessions) { showSessions = false }

    // The open sheet is drawn by the root's modal layer rather than here, and the
    // back gesture that closes it is registered there too — one handler for every
    // sheet in the app instead of one per call site.
    fun openSheet(which: ComposerSheet) {
        when (which) {
            ComposerSheet.Thinking -> sheets.show(
                Sheet(key = "chat-thinking") {
                    // The level is read live rather than captured: the sheet is a
                    // menu over a conversation that is still running, and the
                    // tick on it must be the level pi is on *now*. See [Sheet].
                    val live by session.conversation.collectAsState()
                    val saved by session.settingsStore.settings.collectAsState()
                    // Live levels once the agent has answered for the model that is
                    // running, the remembered ones otherwise — see
                    // `thinkingLevelsFor`. The menu and the tick come from the one
                    // answer, so the menu cannot offer a list the tick is not in.
                    val available = thinkingLevelsFor(
                        live.availableThinkingLevels,
                        // The saved model id as the fallback, for the same reason
                        // `thinkingLevelOf` does it: before `get_state` answers there
                        // is no live model, and a lookup by null finds nothing.
                        saved.rememberedThinkingLevels(live.model?.id ?: saved.modelId),
                    )
                    PickerBody(
                        title = text.chat.thinkingLevelLabel,
                        // The same list the settings page's row shows; built once so
                        // the two cannot disagree about a level or its description.
                        // `available` is what pi says this model has — see
                        // `thinkingLevelOptions` for why the seven are not offered
                        // over it.
                        options = thinkingLevelOptions(text, available),
                        selectedId = thinkingLevelOf(live, saved),
                        footnote = thinkingLevelFootnote(text, available),
                        onPick = { level ->
                            // Applied to the running process immediately: pi
                            // accepts the change over RPC, so unlike the provider
                            // and model it needs no restart.
                            session.settingsStore.update { it.copy(thinkingLevel = level) }
                            session.setThinkingLevel(level)
                        },
                    )
                },
            )

            ComposerSheet.Model -> sheets.show(
                Sheet(key = "chat-model") {
                    // Every model of every profile — one heading per provider, its
                    // models under it — so the picker is the whole configuration
                    // rather than one entry per provider.
                    //
                    // Read live, *inside* the sheet: the profile store is state and
                    // the sheet is drawn from it on every open, so a model changed
                    // on the settings page moves the tick too. Reading it from the
                    // click handler's closure was the bug — the chip was rebuilt
                    // from the current store while the sheet still ticked the model
                    // from the previous composition, and the two disagreed.
                    val config by session.settingsStore.profiles.snapshots.collectAsState()
                    val profiles = config.profiles
                    val active = config.activeProfile
                    PickerBody(
                        title = text.chat.switchModel,
                        options = profiles.flatMap { profile ->
                            val heading = providerLabel(profile, text)
                            profile.selectableModels.map { model ->
                                PickerOption(
                                    id = modelOptionId(profile.id, model),
                                    label = model,
                                    description = modelRowHint(profile, text),
                                    group = heading,
                                    // A provider this build does not know cannot be
                                    // sent to pi at all, so its rows are shown but
                                    // not offered.
                                    enabled = profile.providerEntry != null,
                                )
                            }
                        },
                        // The tick is the profile's own choice of model — the same
                        // value the chip shows — so the two cannot disagree.
                        selectedId = active
                            ?.takeIf { it.modelId.isNotBlank() }
                            ?.let { modelOptionId(it.id, it.modelId) },
                        onPick = { id ->
                            // Matched rather than parsed: a model id may contain any
                            // character at all, so the option id is only ever
                            // compared.
                            profiles.firstNotNullOfOrNull { profile ->
                                profile.selectableModels
                                    .firstOrNull { modelOptionId(profile.id, it) == id }
                                    ?.let { profile to it }
                            }?.let { (profile, model) -> session.selectProfile(profile, model) }
                        },
                    )
                },
            )

            ComposerSheet.Context -> sheets.show(
                Sheet(key = "chat-context") { ContextSheet(session = session, text = text) },
            )

            ComposerSheet.Commands -> sheets.show(
                Sheet(key = "chat-commands") {
                    CommandSheet(
                        text = text,
                        onRun = { command ->
                            when (command.id) {
                                "/new" -> {
                                    // Asked, not refused: pi's `new_session` aborts
                                    // the running answer as its first step, so the
                                    // tap costs the answer on screen. The dialog is
                                    // the same one the history list raises for a
                                    // switch, because it is the same action.
                                    if (session.turnInFlight) {
                                        confirmNewSession = true
                                    } else {
                                        session.newSession()
                                        composer.attachments = emptyList()
                                    }
                                }

                                "/compact" -> session.compact()

                                // The same stop the composer's button performs, queue
                                // and all, with the same answer to "where did my
                                // queued message go".
                                "/stop" -> session.abort { dropped ->
                                    if (composer.input.isBlank()) composer.input = dropped
                                }

                                // Both of these are pi's own operations and both
                                // report their outcome as a row in the transcript —
                                // a path for the export, an error for a refusal —
                                // because neither has a return value a tap could
                                // show any other way.
                                "/clone" -> session.cloneSession()
                                "/export" -> session.exportHtml()

                                // The picker, not a model: this page has no way to
                                // know which model the reader wants, and the sheet
                                // that does is one key away.
                                "/model" -> openSheet(ComposerSheet.Model)

                                "/clear" -> composer.input = ""

                                else -> Unit
                            }
                        },
                        onShell = { openSheet(ComposerSheet.Shell) },
                    )
                },
            )

            ComposerSheet.Shell -> sheets.show(
                Sheet(key = "chat-shell") {
                    ShellCommandSheet(
                        text = text,
                        onRun = { line ->
                            when {
                                // A shell line that runs a command this page asked
                                // for: `runBash` puts the output in the transcript
                                // as a notice, which is the same path the `!` prefix
                                // in the composer takes.
                                line.isNotBlank() -> {
                                    follow.enable()
                                    session.runBash(line)
                                }
                            }
                        },
                    )
                },
            )

            ComposerSheet.Attach -> sheets.show(
                Sheet(key = "chat-attach") {
                    PickerBody(
                        title = text.chat.attachTitle,
                        options = listOf(
                            // First, because it is the one row this sheet actually adds: the
                            // other two hand the job to the system picker, and a photo of a
                            // whiteboard is not something the picker can offer at all. The
                            // reader's report — "拍照应该放在图片上面" — is that the row worth
                            // opening the sheet for should not be the third one.
                            PickerOption(
                                id = "camera",
                                label = text.chat.attachCamera,
                                description = text.chat.attachCameraHint,
                                leading = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            ),
                            PickerOption(
                                id = "image",
                                label = text.chat.attachImage,
                                description = text.chat.attachImageHint,
                                leading = { Icon(Icons.Filled.Image, contentDescription = null) },
                            ),
                            PickerOption(
                                id = "file",
                                label = text.chat.attachFile,
                                description = text.chat.attachFileHint,
                                leading = { Icon(Icons.Filled.Description, contentDescription = null) },
                            ),
                        ),
                        selectedId = null,
                        onPick = { kind ->
                            when (kind) {
                                "image" -> pickImages.launch("image/*")
                                "camera" -> capture()
                                else -> pickFiles.launch(arrayOf("*/*"))
                            }
                        },
                    )
                },
            )
        }
    }

    // Which turns the user has opened. Absent means folded, which is the default:
    // the answer is what a finished turn is for, and the steps that produced it
    // are one tap away. Keyed by turn index and saved, so a rotation or a tab
    // switch does not re-fold a turn the user just opened.
    //
    // Held outside the page swap below: the history list is a page, and a fold the
    // user opened must still be open when they come back from it.
    var expandedTurns by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    val rows = remember(state.items, state.turns, expandedTurns) {
        transcriptRows(state, expandedTurns)
    }

    // The history list is the chat tab's second level, so it moves like one — it
    // used to replace the conversation between two frames, with no transition at
    // all, while every Settings sub-page slid. `forward` is the direction of the
    // move itself: opening the list goes deeper, and the back arrow or the system
    // gesture that closes it comes back.
    PageSwap(
        key = showSessions,
        forward = showSessions,
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        if (shown as Boolean) {
            SessionsScreen(
                session = session,
                onBack = { showSessions = false },
                onOpened = { showSessions = false },
            )
        } else {
            ChatPage(
                session = session,
                agent = agent,
                state = state,
                text = text,
                rows = rows,
                listState = listState,
                follow = follow,
                programmatic = programmatic,
                expandedTurns = expandedTurns,
                onToggleTurn = { turn ->
                    expandedTurns = if (turn in expandedTurns) {
                        expandedTurns - turn
                    } else {
                        expandedTurns + turn
                    }
                },
                input = composer.input,
                onInputChange = { composer.input = it },
                attachments = composer.attachments,
                onRemoveImage = { id ->
                    composer.attachments = composer.attachments.filterNot { it.id == id }
                },
                notice = composer.notice,
                onDismissNotice = { composer.notice = null },
                onOpenSheet = ::openSheet,
                onOpenSessions = { showSessions = true },
                onNewSession = {
                    // Same question as `/new` above and as the history list's rows:
                    // pi's `new_session` ends the running answer before it starts the
                    // new session, so the reader is asked what that costs. It used to
                    // be a flat refusal with a notice, which was a tap that produced
                    // a sentence and no result.
                    if (session.turnInFlight) {
                        confirmNewSession = true
                    } else {
                        session.newSession()
                        composer.attachments = emptyList()
                        composer.notice = null
                    }
                },
                onSend = {
                    val prompt = composer.input
                    val images = composer.attachments
                    composer.input = ""
                    composer.attachments = emptyList()
                    // The reader's own action overrides whatever the scroll was
                    // doing: a prompt is the one thing that always wants the tail.
                    follow.enable()
                    if (prompt.startsWith("!") && images.isEmpty()) {
                        // Escape hatch: run a shell command without the model, the
                        // same convention pi's own editor uses.
                        session.runBash(prompt.removePrefix("!").trim())
                    } else {
                        scope.launch {
                            val payload = withContext(Dispatchers.IO) {
                                images.mapNotNull { encodeImage(context, it) }
                            }
                            session.sendPrompt(prompt, payload)
                        }
                    }
                },
            )
        }
    }

    // Drawn over whichever of the two pages above is showing — the history list is
    // where the same question is asked about a saved conversation, and an
    // `AlertDialog` is a window of its own, so it belongs to the screen rather than
    // to either page the swap holds.
    if (confirmNewSession) {
        InterruptTurnDialog(
            text = text,
            onDismiss = { confirmNewSession = false },
            onConfirm = {
                confirmNewSession = false
                session.newSession(interrupt = true)
                composer.attachments = emptyList()
                composer.notice = null
            },
        )
    }
}

/**
 * The conversation page: the header, the transcript and the composer.
 *
 * Pulled out of [ChatScreen] so that the page swap above can hold two of its
 * destinations at once — the history list and this — without either of them having
 * to be written twice or to re-own the other's state. Everything that must survive
 * a visit to the history list (the draft, the attachments, the transcript's scroll
 * position, which turns are open) is still owned by [ChatScreen] and passed down.
 */
@Composable
private fun ChatPage(
    session: PiAgentSession,
    agent: AgentStatus,
    state: ConversationState,
    text: Strings,
    rows: List<TranscriptRow>,
    listState: LazyListState,
    follow: FollowTailController,
    programmatic: MutableState<Boolean>,
    expandedTurns: Set<Int>,
    onToggleTurn: (Int) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    attachments: List<PendingImage>,
    onRemoveImage: (String) -> Unit,
    notice: String?,
    onDismissNotice: () -> Unit,
    onOpenSheet: (ComposerSheet) -> Unit,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    onSend: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Rows that would compose to nothing are dropped before the list measures them.
    //
    // A turn's assistant rows include the one that carried only a tool call and has
    // no text of its own, and an empty row is not free: it costs its own `rowGap`,
    // so an expanded turn had 22dp of blank space where 8dp belonged — between the
    // fold band and the first step, and again between two steps. The fold already
    // counts these rows as hidden; this only stops them being measured.
    val drawn = remember(rows) { rows.filterNot { it.drawsNothing() } }

    // Follow the tail of the transcript, including while text streams into the
    // last item (whose identity does not change, only its length).
    //
    // A snap rather than an animation: this runs on every streamed token, and a
    // new animation cancelling the previous one every few milliseconds is what
    // made a long answer feel like it was fighting the finger during a scroll.
    // Whether it runs at all is the follow rule's answer, and that rule lives in
    // `chat/Transcript.kt` so it can be tested.
    //
    // It stands aside while a scroll of our own is in flight: the button below
    // enables the follow *and* animates to the end, and this effect re-runs on the
    // flag that flips — a snap here cancelled the slide and the reader saw the
    // transcript jump instead of travel.
    //
    val tailKey = transcriptTailKey(state.items)
    LaunchedEffect(tailKey, state.turns.size, follow.follows) {
        if (state.items.isNotEmpty() && follow.follows && !programmatic.value) {
            programmatic.value = true
            try {
                listState.scrollToEnd(itemCount = drawn.size, animate = false)
            } finally {
                programmatic.value = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            // Tapping anywhere that is not a control gives up the composer's
            // focus, and clearing focus is what closes the text toolbar — the
            // floating "粘贴 / 全选" strip Compose shows over a long-pressed field.
            //
            // Nothing else did. `BasicTextField` deselects and hides its toolbar
            // when it *loses* focus, but Compose does not move focus on a tap: a
            // tap on the transcript is delivered to the `LazyColumn`, which has no
            // opinion about focus, so the field stayed focused with its selection
            // live and the toolbar stayed on screen. The report was exactly that —
            // long-press the input, get the paste button, tap the empty area, and
            // it will not go away.
            //
            // `detectTapGestures` sees the Main pass, which runs **bottom-up**, so
            // every button, chip and text-selection handle has already consumed its
            // own press by the time this runs; and because only the *down* is
            // consumed here, a drag still reaches the list and scrolls it. A tap on
            // the field itself never reaches this: the text field consumes it.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
    ) {
        AgentHeader(
            session = session,
            agent = agent,
            // The *title*, not the whole `ConversationState`.
            //
            // This page reads the conversation, and `ConversationState` is a new object for
            // every record pi sends — several times a second while an answer streams, and
            // again for every `toolcall_delta`. Passing the state meant passing a value that
            // is never equal to the last one, so `AgentHeader` could not be skipped: the
            // header re-ran for every token even though the two things it draws — the
            // conversation's title and the agent's status — change once per conversation and
            // once per process lifecycle. The title is a `String`, so the same argument
            // comparison now skips the whole row, its `InlineTextContent` dot and its three
            // action buttons for every token that does not change it.
            //
            // `state.items` is read for it here, one expression wide, which is what keeps
            // the transcript's own scrolling and folding decisions out of this call path.
            title = conversationTitle(state),
            onOpenSessions = onOpenSessions,
            onNewSession = onNewSession,
            onCompact = { session.compact() },
        )

        // One container for the whole transcript, not one per row.
        //
        // Compose keeps the 复制/全选 toolbar up for as long as a selection
        // exists, and it clears the selection when a tap lands inside the same
        // container but outside the selected text. With a container per row the
        // only tap that dismissed the toolbar was a tap on the row that produced
        // it; tapping any other message left it on screen.
        //
        // The weight sits on this Box rather than on the container: a
        // SelectionContainer sizes itself to its content instead of taking the
        // space it is offered, so a weight applied to it is ignored and the
        // transcript collapses to nothing.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // The transcript is capped and centred on a window wide enough to need
            // it, and uncapped on a phone.
            //
            // A line of `bodyMedium` on a 411dp phone inside the 12dp gutters is
            // about 55 Latin characters — inside the 45–75 band, so nothing needs
            // fixing there. A tablet at 800dp is about 107, and for this app the
            // count that matters is the CJK one: at 14sp a Han glyph is a full em, so
            // 800dp is 57 characters against the 40 that WCAG's visual-presentation
            // criterion allows. 560dp is where 80 Latin characters and 40 Han
            // characters coincide, so it is the one number that satisfies the rule
            // in both scripts.
            //
            // The cap used to sit on a `Box` that also held the turn rail beside the
            // list; with the rail gone the wrapper had one child and no job of its
            // own, so the cap is on the selection container itself and the tree is a
            // level shorter.
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                SelectionContainer(
                    modifier = if (maxWidth >= WIDE_TRANSCRIPT_WINDOW) {
                        Modifier
                            .widthIn(max = MAX_TRANSCRIPT_WIDTH)
                            .transcriptContextMenu()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .transcriptContextMenu()
                    },
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The transcript is a log the reader is watching fill
                            // up. Without this every streamed token is a silent
                            // change; with it the platform announces the row once it
                            // stops growing, which is the one moment the
                            // announcement is useful.
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        // The bottom inset is the transcript's breathing room against
                        // the composer: the chips row above the field is a band of the
                        // same colour as the page, and an answer that ended flush
                        // against it read as cut off rather than as finished. Two
                        // lines of the body style is what the report asked for, and it
                        // is also what keeps the last line of an answer clear of the
                        // jump-to-latest button when that is on screen.
                        //
                        // The right inset used to reserve the turn rail's column;
                        // the rail is gone, so the transcript keeps its own gutter
                        // on both sides and the answer gets that 26dp of width back.
                        contentPadding = PaddingValues(
                            start = TRANSCRIPT_GUTTER,
                            end = TRANSCRIPT_GUTTER,
                            top = 10.dp,
                            bottom = TRANSCRIPT_TAIL_SPACE,
                        ),
                        // No uniform gap. See `rowGap`: the space between two rows is
                        // a statement about what the two rows *are*, and one number
                        // for every pair is what made a prompt, its band, its steps
                        // and its answer read as one undifferentiated stream.
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        itemsIndexed(drawn, key = { _, row -> row.key }) { index, row ->
                            // No `animateItem` here, and that is a fix rather
                            // than an omission. `Modifier.animateItem` replays
                            // its appearance animation **every time an item
                            // enters composition** — which for a lazy list is
                            // every time a row scrolls back into view — so the
                            // transcript flickered while scrolling, and its
                            // default placement and disappearance springs fought
                            // the follow rule, which is scrolling the same list
                            // on every streamed token. A message list is the one
                            // list where an entrance animation cannot be afforded:
                            // the reader is watching it change continuously.
                            Column(
                                Modifier.padding(top = rowGap(drawn.getOrNull(index - 1), row)),
                            ) {
                                when (row) {
                                    is TranscriptRow.Message -> when (val item = row.item) {
                                        is ChatItem.User -> UserBubble(item, text)
                                        is ChatItem.Assistant ->
                                            AssistantBubble(item, text, row.showReasoning, row.showMeta)

                                        is ChatItem.Tool -> ToolCard(item = item, text = text)

                                        is ChatItem.Notice -> NoticeCard(item)
                                    }

                                    is TranscriptRow.TurnSummary -> TurnSummaryRow(
                                        summary = row.summary,
                                        expanded = row.turnIndex in expandedTurns,
                                        text = text,
                                        onToggle = { onToggleTurn(row.turnIndex) },
                                    )
                                }
                            }
                        }
                        if (state.isCompacting) {
                            item(key = "compacting") {
                                Row(
                                    modifier = Modifier.padding(top = MODULE_GAP),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(text.chat.compacting, Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }

                // The turn rail used to stand here, on the right edge of the
                // transcript's own column. It is gone — see ARCHITECTURE.md,
                // "The turn rail was removed" — so this box now holds only the list,
                // and the jump-to-latest button below is the one control over it.
            }

            // The control appears whenever the end of the transcript is not on
            // screen — the position decides, and nothing else.
            //
            // It used to require `!follow.follows` as well, which hid it exactly
            // when the reader needed it: the follow rule can be on while the
            // viewport is still short of the tail — a follow that landed short, an
            // answer that grew after the last snap, an interrupted animation — and
            // the button vanished with no way back to the bottom. `canScrollForward`
            // is the honest test of "not at the bottom": it is false precisely when
            // the viewport shows the end of the content.
            //
            // Reading it here is what a scroll gesture writes to and nothing else
            // does, so this recomposes only at the two ends of the range rather
            // than once per frame.
            if (listState.canScrollForward) {
                FilledTonalIconButton(
                    onClick = {
                        // The flag is raised *before* the follow flips, so the
                        // effect above cannot slip a snap in between: on the
                        // composition that follows this write the follow is on and
                        // this is already true, and the effect stands aside for the
                        // animation that is about to run.
                        programmatic.value = true
                        follow.enable()
                        scope.launch {
                            try {
                                listState.scrollToEnd(itemCount = drawn.size, animate = true)
                            } finally {
                                programmatic.value = false
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // Clear of the transcript's own gutter, and of the scrollbar
                        // the platform draws in the last few dp of the window: the
                        // button is drawn over the list, so it has to sit inside the
                        // list's content margin rather than on the edge itself.
                        .padding(end = 10.dp, bottom = 10.dp)
                        .size(38.dp),
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = text.chat.jumpToLatest,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // One banner for "the agent is not there". A `Failed` agent and a
        // `lastError` are usually the same event — `runCommand`'s catch writes
        // both — and two stacked bars made one failure look like two. The agent's
        // own message is the better half of the pair: it carries the exit code and
        // pi's stderr.
        //
        // Retry is the action the banner needs and did not have. A failed launch
        // also disables the composer (`enabled = agent is Running`), so there was
        // no control anywhere on the chat page that would bring the agent back:
        // the only recovery was to change a model or a provider and hope something
        // called `startAgent` — which is exactly the report that switching
        // provider "does not load it" until the app is restarted.
        val failure = (agent as? AgentStatus.Failed)?.message
        if (failure != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        failure,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = FAILURE_BANNER_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { session.restartAgent() }) {
                        Text(
                            text.common.retry,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        } else {
            state.lastError?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { session.clearError() },
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        notice?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismissNotice),
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Composer(
            session = session,
            text = text,
            value = input,
            onValueChange = onInputChange,
            attachments = attachments,
            onRemoveImage = onRemoveImage,
            isStreaming = state.isStreaming,
            // Two flags, because they answer two different questions. The actions
            // wait for `Running`: `sendPrompt` is `client?.request(…)` and `client`
            // is null until the handshake is done, so a prompt sent during start-up
            // is dropped silently — and `onSend` has already cleared the draft by
            // then. The *field* does not: typing is local, touches no process, and
            // making the user wait 1.9 s (measured: `launching:` to `handshake
            // get_state: success`) before they can put their question down is a
            // delay with nothing behind it.
            enabled = agent is AgentStatus.Running,
            inputEnabled = agent is AgentStatus.Running || agent is AgentStatus.Starting,
            agent = agent,
            state = state,
            onOpenSheet = onOpenSheet,
            onSend = onSend,
            onStop = {
                // The queue behind the run is cleared with it (see
                // `PiAgentSession.abort`), and the text of what was dropped comes
                // back here rather than disappearing: a prompt typed while the agent
                // was streaming is never answered once it is cleared, and the reader
                // is the only one who can decide what to do with it. It goes back
                // into the field only when the field is empty — a draft being typed
                // is what the reader is looking at, and overwriting it would be a
                // second surprise on top of the first.
                session.abort { dropped ->
                    if (input.isBlank()) onInputChange(dropped)
                }
            },
        )
    }
}

/**
 * The thinking level to show, preferring what pi reports over what was saved.
 *
 * pi reports the level once it is running, and that is the truthful answer even
 * when it differs from what was saved: pi clamps a level the model does not have
 * (`clampThinkingLevel`, forwards), so what the chip shows and what the model is
 * actually doing are the same thing. Before pi has answered, the saved *preference*
 * is shown, clamped to the levels the model was last seen to support — which is what
 * stops the chip from displaying `medium` for the second it takes pi to say `high`.
 * [saved] is passed in rather than read off the store here, so the caller collects it
 * and a level changed elsewhere can move the tick while the sheet is open.
 *
 * ## Why the clamp has something to clamp against
 *
 * It is `thinkingLevelsFor`, and the list it remembers is what replaced a bug. The
 * app used to persist **pi's answer** instead, on the theory that the saved value
 * should be brought up to date rather than left to jump: `reconcileThinkingLevel`
 * wrote whatever `get_state` reported back into the settings. But the level is a
 * *preference over models* — one setting, every model the user switches between —
 * while pi's answer is about the one model that is loaded. So a model that clamps
 * rewrote the user's choice permanently, and the worst case is not subtle: pi answers
 * `off` for a model that does not reason at all
 * (`getSupportedThinkingLevels` → `["off"]`), and the next launch then passed
 * `--thinking off` for a *reasoning* model. Using a non-reasoning model once turned
 * thinking off for good.
 *
 * The remembered *levels* fix the jump the persistence was written for without
 * touching the preference: the chip pre-clamps against the last list pi gave, and
 * the level that reaches the command line is still the one the user chose.
 *
 * ## Why the model id falls back to the saved one
 *
 * That fix was not enough, and this is the other half. On the frame the app opens,
 * `state.model` is null because `get_state` has not answered yet, so the id this
 * looked the remembered levels up by was null, `rememberedThinkingLevels` correctly
 * refused to answer, the list fell back to pi's full seven, and the chip showed the
 * raw preference — `medium` — until `get_state` landed and moved it to `high`. That
 * is the report "每次打开app，思考等级chip会直接显示模型没有的medium，然后再跳回模型有的
 * high" exactly, and it is a lookup against the wrong key rather than a missing one:
 * [PiSettings.modelId] is the profile's model, i.e. the model the agent was
 * *launched with*, so it is the right key on the very first frame.
 */
private fun thinkingLevelOf(state: ConversationState, saved: PiSettings): String =
    state.thinkingLevel?.takeIf { it.isNotBlank() }
        ?: clampThinkingLevel(
        saved.thinkingLevel,
        thinkingLevelsFor(
            state.availableThinkingLevels,
            saved.rememberedThinkingLevels(state.model?.id ?: saved.modelId),
        ),
    )

/**
 * A profile's provider, as the heading it groups its models under.
 *
 * The fallback is the raw provider id rather than nothing: a profile written by a
 * build that knew a provider this one does not still has to be listed somewhere,
 * and its id is the only name it has.
 */
private fun providerLabel(profile: ModelProfile, text: Strings): String =
    profile.providerEntry?.label
        ?: profile.provider.ifBlank { text.settings.noProfileProvider }

/**
 * The line under a model in the picker.
 *
 * One thing only, and the more useful one when there are two: a profile with no
 * key cannot answer at all, and saying so on the row is what stops the user
 * picking a model that will fail; a named profile is shown otherwise, because two
 * profiles may share a provider — the same account on two endpoints, or two
 * accounts — and the name is the only thing that tells those rows apart.
 */
private fun modelRowHint(profile: ModelProfile, text: Strings): String? = when {
    profile.apiKey.isBlank() -> text.settings.noKey
    profile.name.isNotBlank() -> profile.name
    else -> null
}

/** The picker's option id for one model of one profile. Never parsed; only compared. */
private fun modelOptionId(profileId: String, model: String): String = "$profileId|$model"

/**
 * One row of the transcript, after folding.
 *
 * The fold is computed into rows rather than applied inside the item composable
 * so the list can filter before it measures: a folded turn's hidden rows must not
 * be composed at all, or a long tool-using turn would still cost its full height
 * on every scroll frame.
 */
internal sealed interface TranscriptRow {
    val key: String

    data class Message(
        val item: ChatItem,
        /**
         * Whether this row's own reasoning control belongs on screen.
         *
         * False for the one row a folded turn still shows. The reply is visible
         * when its turn is closed, so leaving its 查看思考过程 row on screen put a
         * second, contradicting control on a turn the user had just collapsed —
         * the fold control said "open" while a reasoning control sat below it
         * already inviting a tap. Reasoning is part of what a folded turn hides,
         * so it comes back with the turn.
         */
        val showReasoning: Boolean = true,
        /**
         * Whether this row draws the copy button and the timestamp under it.
         *
         * False for the step replies in the middle of a turn, and only ever for
         * those: nothing reads it but the assistant row, so a prompt (which always
         * wears both) and a row that draws no meta row at all — a tool card, a
         * notice — are left at its default. A tool-using turn is several assistant
         * messages — "let me look", a tool card, "here it is" — and the sentence in
         * the middle is a fragment of the answer rather than an answer, so a copy
         * glyph and a time under each of them made one answer read as three. The row
         * that keeps both is the turn's own
         * [pi.kit.mob.pi.ChatTurn.finalReplyKey], which is what folding already
         * collapses a finished turn to: a collapsed turn and an expanded one then
         * agree about which message is the answer.
         *
         * See [MessageMeta] for what that costs.
         */
        val showMeta: Boolean = true,
    ) : TranscriptRow {
        override val key: String get() = item.key
    }

    data class TurnSummary(
        val turnIndex: Int,
        val summary: TurnSummaryData,
    ) : TranscriptRow {
        // Prefixed so it cannot collide with an item key.
        override val key: String get() = "turn-summary-$turnIndex"
    }
}

/**
 * Flattens the transcript into the rows to render, hiding finished turns.
 *
 * A finished turn shows its prompt, a summary row and its final reply; everything
 * in between is dropped unless the turn is in [expanded].
 *
 * The summary row is emitted **above** the turn, immediately under its prompt,
 * rather than after the reply. Two reasons, and the first one is why it was moved:
 *
 *  - A control at the end of a turn cannot be reached without scrolling past the
 *    whole answer, which on a phone is several screens. At the top it is where the
 *    eye already is when a turn starts.
 *  - A turn's height changes on every streamed token. A control that lives at the
 *    end of one therefore moves under the finger that is trying to tap it; one
 *    anchored to the prompt never moves at all.
 *
 * Notices are never hidden: they come from out-of-band actions — a `!command`
 * typed into the composer, a retry, a compaction — and are not part of the
 * reasoning the fold is meant to collapse.
 */
internal fun transcriptRows(
    state: ConversationState,
    expanded: Set<Int>,
): kotlin.collections.List<TranscriptRow> {
    if (state.turns.isEmpty()) return state.items.map { TranscriptRow.Message(it) }

    val hidden = state.foldedKeys()
    val items = state.items

    // One pass to attribute every item to a turn, rather than searching the list
    // per item: a tool-using turn is hundreds of streamed rows, and an O(n²) scan
    // here would run on every recomposition.
    val turnOfItem = IntArray(items.size) { -1 }
    var current = -1
    val promptIndices = HashMap<String, Int>(state.turns.size)
    state.turns.forEachIndexed { index, turn ->
        turn.promptKey?.let { promptIndices[it] = index }
    }
    items.forEachIndexed { index, item ->
        promptIndices[item.key]?.let { current = it }
        turnOfItem[index] = current
    }

    // Per turn: how many rows it hides, and how many of them failed. Both are
    // properties of the turn, so computing them once beats recomputing them on
    // the row that happens to end it.
    //
    // Only a failed *tool* and an error-level notice count. A warning is not a
    // failure: "compaction will retry" and "retrying after a transient error" are
    // the agent working as intended, and counting them put a red mark on turns
    // that succeeded.
    val hiddenPerTurn = IntArray(state.turns.size)
    val failedPerTurn = IntArray(state.turns.size)
    items.forEachIndexed { index, item ->
        val turn = turnOfItem[index]
        if (turn < 0) return@forEachIndexed
        if (item.key in hidden) hiddenPerTurn[turn]++
        if (item is ChatItem.Tool && item.state == pi.kit.mob.pi.ToolState.Failed) failedPerTurn[turn]++
        if (item is ChatItem.Notice && item.kind == pi.kit.mob.pi.NoticeKind.Error) failedPerTurn[turn]++
    }

    val now = System.currentTimeMillis()
    val rows = ArrayList<TranscriptRow>(items.size + state.turns.size)

    // The rows that may draw the copy button and the timestamp: every turn's own
    // answer, which is the key folding already collapses to. Nothing else about the
    // turn is needed for the decision — `finalReplyKey` is by definition the last
    // message with text inside the turn — so this is a set lookup on the row rather
    // than a scan of the turn's items per row.
    val finalReplies = state.turns.mapNotNullTo(HashSet()) { it.finalReplyKey }

    // One summary per turn.
    val summarised = BooleanArray(state.turns.size)
    fun summaryRow(turn: Int) = TranscriptRow.TurnSummary(
        turnIndex = turn,
        summary = TurnSummaryData(
            stepCount = hiddenPerTurn[turn],
            // A turn restored from a session file may carry no timestamps, so a
            // zero here means "unknown", not "instant".
            elapsedMs = state.turns[turn].elapsed(now).takeIf { it > 0 },
            failedSteps = failedPerTurn[turn],
        ),
    )

    items.forEachIndexed { index, item ->
        val turn = turnOfItem[index]
        val record = if (turn >= 0) state.turns[turn] else null
        val isPrompt = record != null && record.promptKey == item.key

        // A turn is "folded" when it is closed, the user has not opened it, and it
        // actually hides something. The last condition matters: a turn that ended
        // without a reply hides nothing, and suppressing its reasoning rows there
        // would leave no way to reach them at all.
        val folded = record != null && record.isComplete &&
            turn !in expanded && hiddenPerTurn[turn] > 0

        // A turn opened by a prompt gets its summary immediately *after* that
        // prompt — the fold control belongs at the top of the turn, where the eye
        // already is, rather than at the end of an answer several screens long
        // that also grows under the finger on every streamed token.
        //
        // A turn that began without one (`agent_start` with no user message) gets
        // its summary *before* its first row instead, because that row may itself
        // be hidden and would otherwise swallow the control.
        if (record != null && record.isComplete && !summarised[turn] && !isPrompt) {
            summarised[turn] = true
            rows += summaryRow(turn)
        }

        // Hidden rows are dropped here, not inside the composable: a folded turn
        // must not be measured at all, or it still costs its height on every
        // scroll frame.
        if (item.key in hidden && (turn < 0 || turn !in expanded)) {
            return@forEachIndexed
        }

        rows += TranscriptRow.Message(
            item,
            showReasoning = !folded,
            // A prompt always, a reply only when it is what its turn settled on. A
            // message attributed to no turn at all — the leading assistant greeting
            // a resumed session can open with (`replaceWithMessages` deliberately
            // leaves those outside every turn) — is nobody's step, so it keeps the
            // row: with one assistant message and no question, dropping it would
            // leave no way to copy the only thing on the page.
            showMeta = item !is ChatItem.Assistant || turn < 0 || item.key in finalReplies,
        )

        if (record != null && record.isComplete && isPrompt && !summarised[turn]) {
            summarised[turn] = true
            rows += summaryRow(turn)
        }
    }

    return rows
}

/**
 * The transcript's text-selection menu, with "Select all" taken out of it.
 *
 * ## The flash this exists for
 *
 * Tapping an empty part of the transcript to give up a selection made a **"Select
 * all" button appear by itself and vanish again**. It is not this app's rendering:
 * on the tap, Compose's `SelectionManager` clears the selection
 * (`onClearSelectionRequested` → `onRelease`), and then that same tap's selection
 * update *ends*, which sets `showToolbar = true`. The toolbar is requested with an
 * empty selection, so the only item left enabled is "Select all" — "Copy" is
 * included disabled, and the platform drops it — and it is drawn over the transcript
 * for a frame or two before the manager hides it again. Reproduced on the emulator
 * with a 40-frame capture of one tap: the selection is on screen for ten frames, the
 * "Select all" button alone for the next, and the dismissed transcript after that.
 *
 * The selection itself is not readable from here — `SelectionContainer`'s controlled
 * form and `Selection` are both internal in this version of Compose — so the menu is
 * what this app can change. Dropping the item is therefore the fix *and* the
 * argument below.
 *
 * ## Why "Select all" is dropped outright
 *
 * The transcript is a `LazyColumn`, and Compose's own documentation for
 * `SelectionContainer` says a select-all cannot reach items that are not composed:
 * it would select the part of the conversation that happens to be on screen and look
 * as if it had selected the whole thing. A control that quietly copies half of what
 * it names is worse than one that is not there — and every message carries its own
 * copy button, which copies that message's *source*, Markdown and all.
 *
 * What is left is "Copy" and the platform's own classifier items (Translate, Read
 * aloud), which act on the selection the reader actually made.
 */
private fun Modifier.transcriptContextMenu(): Modifier =
    filterTextContextMenuComponents { component ->
        component.key != TextContextMenuKeys.SelectAllKey
    }

/**
 * The page title is the conversation, plus whatever the user can do to it.
 *
 * The model, the agent's state and the thinking level used to be a three-row
 * table here. They are controls now, and they live above the composer — where the
 * question "which model is about to answer this" is actually asked, next to the
 * message that will be sent. What is left is the title and three actions.
 *
 * The state keeps the marker it had as a table row: a dot in the state's own
 * colour, in front of the words. It is the only thing on the page that says the
 * agent is alive without being read, and dropping it with the table left a subtitle
 * that looked like every other page's.
 */
@Composable
private fun AgentHeader(
    session: PiAgentSession,
    agent: AgentStatus,
    /**
     * Already computed by the caller, as a `String` rather than as the state it came from.
     *
     * `ConversationState` is a fresh object per record, so a header that took one could
     * never be skipped — see the call site for the whole argument. What the header needs
     * from it is one title.
     */
    title: String,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    onCompact: () -> Unit,
) {
    val text = strings
    val subtitle = agentSubtitle(agent, text)
    val color = statusColor(agent)

    PageHeader(
        title = title,
        subtitleContent = { AgentStatusLine(subtitle, color) },
        actions = {
            // Compress, history, then new — the order the report asked for. It reads
            // left-to-right as "do something to this conversation, look at the others,
            // start another", which is also the order of how much they disturb what is on
            // screen: a compaction is applied in place, the session list is a page away,
            // and a new session replaces the transcript.
            IconButton(onClick = onCompact) {
                Icon(Icons.Filled.Compress, contentDescription = text.chat.compact)
            }
            IconButton(onClick = onOpenSessions) {
                Icon(Icons.Filled.History, contentDescription = text.chat.sessions)
            }
            IconButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = text.chat.newSession)
            }
        },
    )
}

/**
 * The header's state line, with the dot in front of it.
 *
 * The dot is an *inline* composable inside the text rather than a `Box` beside it,
 * and that is the whole reason this is not a `Row`: a `Row` of a `Box` and a
 * `Text` gives the box no baseline, so at bodySmall the 5dp dot sat 3px below the
 * words it belongs to. An `InlineTextContent` placeholder is measured against the
 * text it sits in, so the dot shares the line's own centre whatever the font scale,
 * and the text keeps the whole width for wrapping — measured at font scale 1.8,
 * "Agent 已就绪" plus a failure message still uses both of its two lines.
 */
@Composable
private fun AgentStatusLine(message: String, color: Color) {
    val style = MaterialTheme.typography.bodySmall
    // In em units, so the dot follows the user's font scale rather than being a
    // fixed dp that would swallow a line at a large scale. 0.42em is the size the
    // header table's 6dp dot worked out to at bodySmall.
    val dot = style.fontSize * 0.42f
    val box = style.fontSize * 0.95f

    Text(
        text = buildAnnotatedString {
            appendInlineContent(STATUS_DOT, "\u25CF")
            append(message)
        },
        inlineContent = mapOf(
            STATUS_DOT to InlineTextContent(
                Placeholder(
                    width = box,
                    height = dot,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Box(
                    Modifier
                        .size(with(LocalDensity.current) { dot.toDp() })
                        .clip(CircleShape)
                        .background(color),
                )
            },
        ),
        style = style,
        color = color,
        // One line, because the header's band is a fixed two-line height: a state
        // that wrapped would push the transcript down and back again every time
        // the agent started or stopped. A failure message is pi's own stderr and
        // its first line is the one that names the cause; the rest is on the Agent
        // page.
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The id of the inline status marker inside the header's state line. */
private const val STATUS_DOT = "chat-status-dot"

/** The agent's state, in one line. Never blank: the subtitle slot is always there. */
private fun agentSubtitle(agent: AgentStatus, text: Strings): String = when (agent) {
    is AgentStatus.Running -> text.chat.agentReady
    is AgentStatus.Starting -> text.chat.agentStarting
    // The failure message is pi's own stderr and can be several lines; the first
    // one names the cause, the rest is a stack.
    is AgentStatus.Failed -> agent.message.lineSequence().first()
    AgentStatus.Stopped -> text.chat.agentStopped
}

/** Stopped shares the error colour: from the user's side both mean "not answering". */
@Composable
private fun statusColor(agent: AgentStatus): Color = when (agent) {
    is AgentStatus.Failed, AgentStatus.Stopped -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

/**
 * pi does not generate titles, so an unnamed conversation is labelled with its
 * first user message — the same rule pi's own session picker uses. Reusing
 * [SessionMetadata.asTitle] keeps the two identical.
 *
 * A conversation with nothing in it yet is labelled with the application's name
 * rather than with `chat.newConversation`. The header is where the reader looks to
 * see *what* they are looking at, and "新对话" answers a question nobody asked: the
 * page is a new conversation because that is what an empty transcript is. The name
 * is a literal because it is a proper noun and not interface text — the same
 * spelling in all three catalogs, and the same one the About page writes.
 */
private fun conversationTitle(state: ConversationState): String {
    state.sessionName?.takeIf { it.isNotBlank() }?.let { return it }
    val firstUser = state.items.firstOrNull { it is ChatItem.User && it.text.isNotBlank() }
    val message = (firstUser as? ChatItem.User)?.text
    return if (message.isNullOrBlank()) APP_NAME else SessionMetadata.asTitle(message)
}

/** The application's name, as it is written wherever a proper noun is wanted. */
private const val APP_NAME = "PiKit"

/**
 * One prompt from the user.
 *
 * ## The width is the content's, capped, and it sits at the end
 *
 * `fillMaxWidth(0.86f)` was the previous shape and it was wrong in the way a reader
 * notices first: a two-word prompt — "好", "continue", "yes please" — was drawn in
 * a bubble 86% of the screen wide, so the page read as a column of identical bars
 * and the reader's own words looked like a form field.
 *
 * ## Why the cap is a `widthIn` on a `BoxWithConstraints` and not a `wrapContentWidth`
 *
 * This is the part that took a wrong turn, so the reasoning is written out. The cap
 * used to sit on a `Box(Modifier.fillMaxWidth(0.86f))` with the bubble below it
 * asking for `wrapContentWidth`. That does not work, and Material3 is why:
 * `Surface` puts its content in a `Box(modifier = modifier..., propagateMinConstraints = true)`.
 * `propagateMinConstraints` makes that inner `Box` **size itself to the minimum
 * width it was handed and pass that minimum on to the content** — so the `0.86f`
 * cap travelled straight through `wrapContentWidth` and every prompt came out an
 * 86%-wide bar again, with its text at the start of it. That is one bug wearing two
 * descriptions: "气泡是固定宽度" and "用户消息靠左了" are the same 86% bar.
 *
 * `wrapContentWidth` cannot win that argument, because it only rewrites the
 * constraint it is *given*; `Surface` then inflates its own child back to the
 * minimum. So the cap is expressed where `Surface` cannot see it: the `Box` around
 * the bubble is capped with `widthIn(max = …)`, and `fillMaxWidth()` on the
 * `Surface` *is* the bubble. A `Surface` with a loose `0..cap` constraint and
 * `propagateMinConstraints = true` settles at `max(minWidth, contentWidth)`, which
 * is the content's width — and the box's `contentAlignment` then puts that against
 * the end edge.
 *
 * A previous revision of this comment blamed the *alignment* instead, claiming
 * `Alignment.End` centres its content. That is false — `Alignment.End` is
 * `BiasAlignment.Horizontal(1f)`, whose `align(size, space)` is `space - size`, the
 * same offset `Alignment.CenterEnd` and `Alignment.End` both produce. The alignment
 * was never the bug.
 */
@Composable
private fun UserBubble(item: ChatItem.User, text: Strings) {
    // The prompt is the last thing the reader wrote; what follows it is the other
    // speaker. The space *below* it is `rowGap`'s job now — it is the only place
    // that knows whether an answer, a fold band or a tool card comes next.
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // `BoxWithConstraints` is the only place the cap can be computed: a dp
            // width needs the constraints, and `widthIn(max = …)` needs a dp.
            val cap = maxWidth * PROMPT_MAX_SHARE
            Box(Modifier.widthIn(max = cap)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    // All four corners at the same radius, which is a decision rather than a
                    // default: this bubble used to square its bottom-end corner (`PROMPT_TAIL`,
                    // 6dp) on the argument that the corner nearest the answer is what says which
                    // side of the conversation it came from. The reader's verdict was that the
                    // irregular shape reads as a rendering fault, and the two other ways the
                    // bubble is marked as the user's — right alignment and the
                    // `primaryContainer` fill — all survive the change. See `PROMPT_CORNER`.
                    shape = RoundedCornerShape(PROMPT_CORNER),
                    // **No `fillMaxWidth` here**, and that is the whole of this
                    // widget's layout. `Modifier.fillMaxWidth()` sets the *minimum*
                    // width to the incoming maximum, so inside the capped `Box` above
                    // it asked for exactly `cap` and got it: every prompt, "ok"
                    // included, was an 86%-wide bar with its text at one end. The
                    // bubble is sized by its own content, and the `Box` above is what
                    // stops that content from being wider than the cap — its
                    // `widthIn(max = cap)` leaves the maximum in place and clears the
                    // minimum, which is the pair of constraints a shrink-wrapping
                    // child needs.
                    //
                    // `wrapContentWidth` on this modifier was the other candidate and
                    // is a no-op in this position: it rewrites the minimum it is
                    // *given*, and the minimum arriving here is already 0.
                    modifier = Modifier,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        if (item.imageCount > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = if (item.text.isBlank()) 0.dp else 6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    text.chat.attachmentCount(item.imageCount),
                                    modifier = Modifier.padding(start = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        if (item.text.isNotBlank()) {
                            Text(
                                item.text,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
        MessageMeta(
            item = item,
            text = text,
            alignment = Alignment.End,
        )
    }
}

/**
 * How much of the transcript's width a prompt's bubble may claim.
 *
 * The cap the bubble always had, kept as the *maximum* rather than as the width:
 * past 0.86 there is no gap between the prompt and the other speaker's column, and
 * a reader loses the right-alignment cue that says which side of the conversation
 * this is. See [UserBubble] for where it is applied and why not on the bubble.
 */
private const val PROMPT_MAX_SHARE = 0.86f

/**
 * What a message carries under it: when it was sent, and a copy button.
 *
 * ## Which messages wear it
 *
 * A prompt and **the reply its turn settled on** — nothing else. Every message used
 * to, and a tool-using turn is five or six assistant messages: "let me look at the
 * file", a tool card, "found it", another card, the answer. Under each of those sat
 * a copy glyph and a time, so one answer read as five, and the furniture was denser
 * than the text it belonged to. The row that keeps both is the one folding already
 * treats as the turn's answer (`ChatTurn.finalReplyKey`), which is what makes a
 * collapsed turn and an expanded one agree about which message is the answer —
 * expanding a turn adds the steps, it does not move the button.
 *
 * The prompt keeps both: it is a message the reader wrote, and its time is the only
 * record of when the question was asked.
 *
 * What that costs is worth naming. [transcriptContextMenu] drops the platform's own
 * "Select all" — a `LazyColumn`'s uncomposed items cannot be reached by it — and
 * the per-message button was the compensation. A *turn* still has one, at its
 * answer, so copying what was asked and what was answered is unchanged; copying a
 * mid-turn fragment, or a leading assistant greeting that belongs to no turn, means
 * dragging a selection over it.
 *
 * ## Why the row is not always drawn
 *
 * A message with no timestamp — a restored session whose records carried none, or a
 * row the reducer created with its default zero — shows the copy button alone, and
 * a *streaming* answer shows neither: it has not been sent yet in any sense the
 * reader cares about, and a copy button beside a paragraph that is still growing is
 * copying a sentence the model has not finished writing. The caller decides that
 * with [ChatItem.isStreaming], and this returns without drawing anything rather
 * than leaving 20dp of blank space under every token.
 *
 * ## Why the timestamp is computed once
 *
 * `remember(at)` rather than a call to the catalog on every composition: an answer
 * re-renders on every streamed token, and `messageTime` reads a `Calendar`. Nothing
 * here changes with the clock — a message's time is a fact about the message — so
 * re-deriving it per frame would be work whose answer is always the same.
 *
 * The button is 28dp rather than Material's 48dp minimum, and that is a deliberate
 * exception: it sits *under* a message, next to text that is not a control, and a
 * 48dp disc there would be a second bubble.
 *
 * ## Why the row is pulled out by the glyph's inset
 *
 * A row is aligned by its **ink**, and the button's ink is smaller than its box: an
 * 18dp glyph centred in a 28dp button leaves 5dp of the button on each side that the
 * reader cannot see. Left alone, the box lines up with the message and the *glyph*
 * does not — which is the report "ai消息回复气泡下面复制按钮最左侧没对齐气泡". Measured with
 * `uiautomator dump` on the emulator at density 2.625: the message's own text starts
 * at x=32px and the copy glyph's node at x=46px, the 14px (5.33dp) in the report.
 * [CopyButtonInkInset] is that 5dp, taken from the button's own geometry rather than
 * written down twice, and the row is moved by it so the glyph lands on the column the
 * message's text starts at. The timestamp keeps the distance from the button it had.
 *
 * Which side is pulled depends on what actually ends the row: a left-aligned row ends
 * with the button, so it moves left; a right-aligned one ends with the timestamp when
 * there is one — its own ink is already flush — and with the button when there is not.
 *
 * ## Why the row is the accent colour
 *
 * The glyph and the timestamp are both `primary`. The reader's report — "把工作几秒、
 * 几个步骤、消息时间、消息复制按钮等都变成主题蓝色，代码块复制按钮不变" — is that the three pieces of
 * furniture a finished turn wears (`工作 X 秒 · N 个步骤`, a message's time, its copy
 * button) should read as one accent, and that the code block's copy button, which is
 * body content rather than furniture, should not. The accent is also what the two ways
 * to copy a message now share: `SelectionContainer`'s own handles are the theme's
 * primary, so tapping the button and dragging a selection no longer look like two
 * different features.
 */
@Composable
private fun MessageMeta(
    item: ChatItem,
    text: Strings,
    alignment: Alignment.Horizontal,
) {
    if (item is ChatItem.Assistant && item.isStreaming) return
    if (item is ChatItem.Assistant && item.text.isBlank()) return

    val accent = MaterialTheme.colorScheme.primary
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    val stamp = remember(item.createdAt, now) {
        if (item.createdAt > 0L) text.chat.messageTime(item.createdAt, now) else null
    }

    // See "Why the row is pulled out by the glyph's inset" above.
    val pull = when {
        alignment != Alignment.End -> -CopyButtonInkInset
        stamp == null -> CopyButtonInkInset
        else -> 0.dp
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = pull)
            .padding(top = 2.dp),
        horizontalArrangement = if (alignment == Alignment.End) {
            Arrangement.End
        } else {
            Arrangement.Start
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CopyButton(
            onClick = { scope.launch { clipboard.setClipEntry(clipEntryFor(messageSource(item))) } },
            contentDescription = text.chat.copyMessage,
            tint = accent,
        )
        if (stamp != null) {
            Text(
                text = stamp,
                modifier = Modifier.padding(start = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
            )
        }
    }
}

/**
 * What a message's copy button puts on the clipboard.
 *
 * The source text, and only the source: what the reader selected is what the model
 * wrote, not the rendering of it, so copying a reply that contains a table or a
 * formula gives back the Markdown that produced them. That is the same choice the
 * code block's own button makes, which is the one precedent in the app.
 */
private fun messageSource(item: ChatItem): String = when (item) {
    is ChatItem.User -> item.text
    is ChatItem.Assistant -> item.text
    is ChatItem.Tool -> item.output.ifBlank { item.argumentsJson }
    is ChatItem.Notice -> item.text
}

/**
 * One assistant message: its reasoning control, its answer, its failure.
 *
 * ## One module, three optional parts
 *
 * The row is stacked, and the `rowGap` rule in `chat/Transcript.kt` treats it as
 * **one** row however many of the three parts are present — which is why the parts
 * are not separated by the transcript's own gap numbers. Everything *inside* here
 * belongs to the same message: the reasoning control opens a block that belongs to
 * the answer under it, so the distances here are one step smaller than the gaps
 * between modules and are written down as constants with the pair they separate in
 * mind ([REASONING_ANSWER_GAP], and the 6dp the block itself opens with).
 *
 * ## Why there is no live cursor any more
 *
 * This row used to draw a 2dp caret — an alpha tween, then a 2 Hz blink — for as
 * long as an answer was streaming and there was nothing to show yet. It is gone at
 * the reader's request ("对话页输出时不要那个闪烁光标"), and the reasoning row is
 * what replaces it as a progress signal: its label carries a character count that
 * moves as the model thinks (`思考 · 1.2k 字`), and it is on screen for the whole of
 * the wait. Nothing is lost with the cursor — the row that draws nothing is dropped
 * by `drawsNothing()` rather than holding a blank line open, and the composer's own
 * stop button is the "something is running" control.
 *
 * A *static* bar was the other candidate and is worse than nothing here: it is the
 * same caret with the only thing that made it read as a caret taken away, sitting
 * on a page whose whole point is that it does not decorate bare rows.
 */
@Composable
private fun AssistantBubble(
    item: ChatItem.Assistant,
    text: Strings,
    showReasoning: Boolean = true,
    /** See [TranscriptRow.Message.showMeta]: false for a turn's intermediate steps. */
    showMeta: Boolean = true,
) {
    // `rememberSaveable`: a `LazyColumn` disposes the rows that scroll out of its
    // viewport, so a plain `remember` lost the opened reasoning block — the reader
    // scrolled down, came back, and the block they had opened was folded again.
    // Lazy items are wrapped in a `SaveableStateProvider` keyed by the item's own
    // key, so a saveable value comes back with the item.
    //
    // Folded until the reader opens it, always. The block used to open *itself* while
    // the reasoning streamed and fold itself a second after the answer began, on the
    // theory that watching the model think is the point of having the block. It is
    // not: it made every turn taller by a screenful of grey reasoning that the reader
    // had not asked for, it moved everything below it twice per turn — once when it
    // opened, once when it closed — and it fought the reader who was scrolling while
    // it did. The row's own label carries the progress (`思考 · 1.2k 字`), and the
    // block is one tap away.
    var showThinking by rememberSaveable(item.key) { mutableStateOf(false) }

    // `isStreaming` is deliberately not part of this any more. It was, because the
    // row drew a live cursor for as long as the answer was on its way and was
    // therefore never empty; with the cursor removed (see below) a streaming row
    // with nothing in it would compose to a blank `Column` — and the list would
    // still measure it and pay its `rowGap`. Same test as `drawsNothing`, which is
    // what the row filter uses before this is ever called.
    val hasBody = item.thinking.isNotBlank() || item.text.isNotBlank() || item.error != null
    if (!hasBody) return

    Column(Modifier.fillMaxWidth()) {
        if (item.thinking.isNotBlank() && showReasoning) {
            ReasoningToggle(
                expanded = showThinking,
                chars = item.thinking.length,
                text = text,
                onToggle = { showThinking = !showThinking },
            )
            if (showThinking) {
                // The gap is the point: previously the control and the block it
                // opened were flush against each other, so the first line of the
                // reasoning read as part of the button.
                Spacer(Modifier.height(6.dp))
                ReasoningBlock(item.thinking)
            }
            // Only when something follows it. This spacer separates the reasoning
            // control from the answer under it, and it used to be unconditional —
            // which put 5dp of nothing at the bottom of every step row that had
            // thinking and no text, which is *every* step that calls a tool: the
            // model thinks, calls the tool, and is answered with the result without
            // writing a sentence. The reader's report was that the space between a
            // tool call and the thinking above it did not match the space between the
            // thinking and the tool call below it, and it did not: the gap above the
            // call was `MODULE_GAP` (8dp) and the gap below the thinking was that
            // plus this 5dp. Nothing about the rows justified the difference — the
            // spacer has to belong to the pair it separates, not to the thinking.
            if (item.text.isNotBlank() || item.error != null) {
                Spacer(Modifier.height(REASONING_ANSWER_GAP))
            }
        }

        if (item.text.isNotBlank()) {
            MarkdownText(item.text, modifier = Modifier.fillMaxWidth())
        }

        item.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // Under everything the message has, and after the error: a failed answer's
        // copy button is how the reader gets the partial reply and its error out of
        // the app, which is exactly when they want it. Only the reply that ends the
        // turn draws it — the steps in between are fragments, and their own buttons
        // were what made a turn read as three separate answers (see
        // [TranscriptRow.Message.showMeta]).
        if (showMeta) {
            MessageMeta(
                item = item,
                text = text,
                alignment = Alignment.Start,
            )
        }
    }
}

/**
 * The composer.
 *
 * ## What this replaced, and why
 *
 * The previous bar was a stats line, a pill holding an attachment button and the
 * field, and a send button beside it — and above it, a three-row table in the
 * header carrying the state, the model and the thinking level as *text*. So the
 * three things a user changes most often while talking to the agent were either
 * read-only or nowhere near the message they applied to.
 *
 * They are controls now, on a line of their own above the field:
 *
 * ```
 *  ╭─────────╮ ╭──────────────╮ ╭────────────╮ ╭─────────╮
 *  │ 🧠 中   │ │ 🤖 packyapi… │ │ ⤓ 上下文…  │ │ ⚡ 缓存… │
 *  ╰─────────╯ ╰──────────────╯ ╰────────────╯ ╰─────────╯
 *  ┌──────────────────────────────────────┐
 *  │ Ask Pi…                              │
 *  │  ( / )        📎              ( ↑ )  │
 *  └──────────────────────────────────────┘
 * ```
 *
 * ## Why the controls are above the field and not below it
 *
 * A chip below the field sits between the thing being typed and the keyboard,
 * which is the busiest strip on a phone screen, and it reads as part of the
 * message being written. Above it they read as what they are: the settings the
 * next message will be sent with. They also scroll horizontally once there are
 * four of them, so a long profile name cannot squeeze the last one off screen.
 *
 * The agent's **state** is not one of them. It is a fact, not a control, and a row
 * of tappable chips with one inert chip at its head makes the eye check all four
 * every time; it stays in the header's subtitle, which is where a status belongs.
 *
 * ## Why the field is inside the card and the buttons are on its last line
 *
 * A phone keyboard is one-handed, the row a thumb reaches is the bottom one, and
 * the field above it grows upward as the message gets longer instead of pushing
 * the buttons off screen. The card keeps the navigation bar's colour so the two
 * read as one surface, and a hairline at the top separates it from the transcript.
 */
@Composable
private fun Composer(
    session: PiAgentSession,
    text: Strings,
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<PendingImage>,
    onRemoveImage: (String) -> Unit,
    isStreaming: Boolean,
    /** Whether the actions are available: send, the command list and `+`. */
    enabled: Boolean,
    /** Whether the text field takes input. True from the first frame. */
    inputEnabled: Boolean,
    agent: AgentStatus,
    state: ConversationState,
    onOpenSheet: (ComposerSheet) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // The saved configuration, collected so the model chip follows a change made
    // on the settings page. A plain read of the store here was the report that
    // "changing the model on the model page does not change the chat page": the
    // store was a snapshot with no notification, so this row was only rebuilt when
    // something else happened to recompose it — the agent's handshake, a tab
    // switch, or the picker being opened again.
    val config by session.settingsStore.profiles.snapshots.collectAsState()

    // The saved settings as well, for the thinking chip: the level is persisted in
    // SharedPreferences, so it is readable synchronously on the first frame and the
    // chip never has to wait for pi to answer `get_state`.
    val saved by session.settingsStore.settings.collectAsState()

    // Derived from the agent's actual state rather than from `enabled`. A
    // disabled field used to say "Starting the agent…" whatever the reason, so a
    // stopped agent produced a header reading 已停止 above a composer reading
    // 正在启动 — two statements that cannot both be true.
    //
    // `Starting` is no longer one of the states reported here, and that is the
    // point: the field takes input from the first frame, so a line about the
    // process coming up would describe a wait the user does not have. The header
    // still says 正在启动 while it is true. `Stopped` is named rather than reached
    // through `!is Running`, which is what would otherwise catch `Starting`.
    val placeholder = when {
        agent is AgentStatus.Failed -> text.chat.agentFailed
        agent is AgentStatus.Stopped -> text.chat.agentStopped
        attachments.isNotEmpty() -> text.chat.placeholderAttachment
        else -> text.chat.placeholder
    }

    val shape = RoundedCornerShape(22.dp)

    // Read here rather than taken as a parameter: it is only this card's own
    // dismissal that needs it, and the page's `focusManager` is the same object
    // either way — a `LocalFocusManager` is one per window.
    val fieldFocus = LocalFocusManager.current

    // Whether the field currently holds the focus, which is what tells a *first*
    // tap on it apart from a second one. See the composer `Surface`'s pointer
    // handler: the first tap has to be allowed to focus the field and raise the
    // keyboard, and the second has to put away the selection toolbar the first one
    // made possible.
    var fieldFocused by remember { mutableStateOf(false) }

    // The boundary between the transcript and the composer is a shadow, not a
    // hairline. A 1dp `outlineVariant` rule across the full width was the first
    // version and it was the wrong tool: a rule is a *drawn line*, so it competes
    // with the transcript's own dividers and reads as the top of a box rather
    // than as the bottom of the page. A 8dp elevation shadow says "this bar is
    // above that one" without adding a third line weight to a screen that already
    // has tool cards and hairlines.
    //
    // `Modifier.shadow` ahead of the `Surface` rather than the `Surface`'s own
    // `shadowElevation`, which would also tint the bar with `surfaceTint` and
    // lift it a step in the palette. The flag sets `clip = false` so the shadow
    // is not clipped at the composer's rounded top edge; `clip` there is about
    // the *background* draw, not the shadow.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.shadow(elevation = 8.dp, clip = false),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments, key = { it.id }) { image ->
                        AttachmentChip(
                            image = image,
                            removeLabel = text.chat.removeAttachment,
                            onRemove = { onRemoveImage(image.id) },
                        )
                    }
                }
            }

            // The four controls, on a line of their own above the field.
            //
            // Above, not below, and that ordering is the whole point of the
            // layout: a chip below the field sits between the thing you are
            // typing and the keyboard, which is the busiest strip on a phone
            // screen, and it reads as part of the message you are writing. Above
            // it they read as what they are — the settings the next message will
            // be sent with.
            //
            // The agent's state is deliberately *not* one of them. It is a fact,
            // not a control, and a row of tappable chips with one inert chip at
            // the head of it makes the eye check all four every time. It stays in
            // the header's subtitle, which is where a status belongs.
            //
            // Horizontally scrollable so four chips whose values can each be long
            // stay on one line: the alternative is squeezing them, which turns the
            // last one into `缓存命…`. A `LazyRow` rather than a `Row` with
            // `horizontalScroll`, because a plain scrollable `Row` under a
            // `fillMaxWidth` measures its children against the *screen*'s width and
            // then draws the overflow past the edge — measured, the cache chip came
            // out at x=966..1048 and was sliced by the window rather than scrolled
            // to, which looks like a layout bug and is not scrollable either. The
            // content padding is what makes a partly-visible chip read as "there is
            // more to the right" instead of as clipped.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    ControlChip(
                        glyph = {
                            Icon(
                                PiIcons.Thinking,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        // The persisted level, not pi's report alone. pi reports it
                        // only once `get_state` has answered, so a label built from
                        // `state.thinkingLevel` alone was empty for the whole of the
                        // agent's start-up and the chip then grew by the width of its
                        // own text — measured from the code, 37dp of glyph and padding
                        // became ~89dp with "Medium". The same value the picker ticks
                        // and the settings row shows.
                        label = thinkingLevelOf(state, saved),
                        contentDescription = text.chat.thinkingLevelLabel,
                        onClick = { onOpenSheet(ComposerSheet.Thinking) },
                    )
                }
                item {
                    ControlChip(
                        glyph = {
                            Icon(
                                // The app's own model mark — the same stack of
                                // layers the settings row and the profile's model
                                // list use. It was `Hub`, and before that the
                                // `SmartToy` face: a hub's three spokes at 15dp read
                                // as a five-pointed star, and a robot head is a
                                // picture of an assistant rather than of *which*
                                // model is about to answer. See `PiIcons`.
                                PiIcons.Model,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        // The model that will answer, not the profile's name: this
                        // is the "which model" control, and once one provider can
                        // hold several models the profile name stops saying which
                        // one is about to reply.
                        //
                        // The profile's choice wins over pi's report, and the store
                        // is collected rather than read: it is what the picker's
                        // tick shows and what the next launch will use, so a change
                        // made on the settings page appears here at once instead of
                        // waiting for the agent to come back up.
                        label = config.activeProfile?.modelId?.takeIf { it.isNotBlank() }
                            ?: state.model?.id,
                        contentDescription = text.chat.switchModel,
                        onClick = { onOpenSheet(ComposerSheet.Model) },
                    )
                }
                item {
                    ControlChip(
                        glyph = {
                            Icon(
                                Icons.Filled.Compress,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        label = contextPercentLabel(state, text),
                        contentDescription = text.chat.contextDetails,
                        onClick = { onOpenSheet(ComposerSheet.Context) },
                    )
                }
                // Always shown, so the row has the same shape whether or not the
                // provider caches anything. It was hidden while nothing had been
                // cached, which meant the control people asked for appeared and
                // disappeared as the conversation went on; a `0%` is the honest
                // reading and it is what makes the number's *movement* visible.
                item {
                    ControlChip(
                        glyph = {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        label = text.chat.cacheHit(cacheHitPercent(state)),
                        contentDescription = text.chat.cacheHitLabel,
                        onClick = { onOpenSheet(ComposerSheet.Context) },
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = shape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp)
                    // Dismissing the selection toolbar the field's own long-press
                    // raises — the 粘贴 / 全选 strip — is the one thing the field
                    // cannot do for itself: `BasicTextField` consumes a tap on its own
                    // bounds, because that is how it moves its caret, so the
                    // transcript's `detectTapGestures` underneath never sees a tap
                    // inside the field. The report was exactly that: long-press the
                    // input for the paste button, tap the input again, and the button
                    // stays.
                    //
                    // ## Why this is a tap detector and not a per-event `clearFocus`
                    //
                    // It was, and that is the report "现在打开app点击输入框无反应，无法
                    // 输入文字". Clearing on *every* pointer event includes the down
                    // event of the tap that focuses the field, so the sequence for a
                    // first tap was: the field takes the focus from the down event, this
                    // handler clears it at the end of the same pass, and the field is
                    // left unfocused — no caret, no keyboard, and every subsequent tap
                    // repeating it. Confirmed from the emulator's accessibility dump
                    // rather than from the code: after `input tap` on the field, no node
                    // in the window had `focused="true"` and there was no `InputMethod`
                    // window at all.
                    //
                    // So the clear happens after a *completed* tap, and only when the
                    // field already had the focus when the finger went down. The first
                    // tap therefore focuses and stops; a second one dismisses. Asking
                    // `awaitFirstDown` for the `Initial` pass is what makes "already
                    // had" true without a frame of lag — the initial pass runs before
                    // the field's own gesture detector, so `fieldFocused` still holds
                    // the state from before this gesture began, and the assignment to
                    // it that this tap is about to cause has not been recomposed yet.
                    //
                    // A hand-rolled tap rather than `detectTapGestures`, because that
                    // helper owns the `awaitPointerEventScope` it is given and loops
                    // inside it forever — so there is no point at which a caller can
                    // read the field's focus before the tap that is about to change
                    // it, and no way to place one gesture's worth of work around it.
                    // `clickable` is the other candidate and is wrong for a different
                    // reason: it would give this card a ripple and a click semantics
                    // node, and this is a region, not a button.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                val hadFocus = fieldFocused
                                // Watched, never consumed — the event still travels on
                                // to the field, which is what places the caret and
                                // raises the keyboard. `pressed` going false is the
                                // lift, and the loop then ends so the next `while`
                                // iteration waits for the next finger.
                                while (down.pressed) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                }
                                if (hadFocus) fieldFocus.clearFocus()
                            }
                        }
                    },
            ) {
                Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = inputEnabled,
                        maxLines = 8,
                        // `weight` has to be on the field itself, not on a Box
                        // wrapped around it. `BasicTextField` measures its
                        // decoration box with `minWidth = 0` and reports *that*
                        // size, so a chain of `fillMaxWidth` around it is undone:
                        // measured, the field came out 312px wide inside a 780px
                        // pill — exactly the width of the placeholder — and the
                        // rest of the composer did not respond to a tap. The
                        // fixed width a Column gives it survives because it
                        // arrives as a *minimum*.
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            // 16dp above and 8dp below rather than 10/4. Measured
                            // against the reference layout: at 10/4 the field's
                            // own line box was 42dp and the whole card 97dp, so
                            // the box read as a one-line toolbar with a stray
                            // glyph in it. The taller inset is what makes it a
                            // place to write.
                            .padding(top = 16.dp, bottom = 8.dp)
                            // The field's own focus, published for the card's tap
                            // handler below. `onFocusChanged` rather than
                            // `interactionSource`: focus is what the handler asks
                            // about, and a press interaction would report a finger
                            // down on the field even when the field did not take the
                            // focus.
                            .onFocusChanged { fieldFocused = it.isFocused },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.TopStart) {
                                if (value.isEmpty()) {
                                    Text(
                                        // One short line. The previous hint
                                        // explained the `!` convention as well,
                                        // which wrapped to two lines in the field
                                        // and looked like a rendering fault.
                                        placeholder,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The command list, at the field's *left* corner, mirrored by the
                        // send button at its right, and sharing the `+`'s own style —
                        // three plain `IconButton`s on one line, with the space between
                        // the first and the other two held by a weighted spacer so the
                        // layout does not depend on how wide the field's text is.
                        //
                        // It is the same control as the `+` beside it, deliberately.
                        // It *was* a filled circle carrying the `/` character, and that
                        // was wrong twice over: the character was a description of the
                        // list rather than of the button — a reader who has not opened it
                        // does not know what a slash means — and a filled 40dp disc
                        // opposite a 40dp plain button made two controls that do the same
                        // *kind* of thing look like two different kinds.
                        //
                        // [PiIcons.Commands] and **not** `Icons.Filled.Terminal`, which is
                        // the bottom tab strip's terminal tab: a terminal glyph six
                        // centimetres above it in the composer read as a second way into
                        // the terminal tab rather than as the command list. It is also no
                        // longer `Icons.Filled.Code`, which is what this was for two
                        // rounds and is the report "目前 </> 没有正常显示" — that glyph is
                        // drawn as an open *stroked* polyline whose stroke width lands on
                        // zero, so it measured, reported a node to a screen reader, and
                        // painted nothing. See `PiIcons.Commands` for the read of the
                        // artefact and the drawable for the replacement geometry.
                        IconButton(
                            onClick = { onOpenSheet(ComposerSheet.Commands) },
                            enabled = enabled,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                PiIcons.Commands,
                                contentDescription = text.chat.commands,
                                // 17dp, against the 23dp of the `+` beside it, and that is a
                                // property of the glyph rather than of the button: Lucide's
                                // `command` draws its loops out to 21 of its 24 units, where
                                // Material's `Add` keeps its ink inside 14 — so the same size
                                // reads as a much bigger mark, which is the report "常用命令图标
                                // 太大了". Scaled here, at the call site: the vector itself is
                                // the library's and is not edited (see `PiIcons.Commands`).
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { onOpenSheet(ComposerSheet.Attach) },
                            enabled = enabled,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = text.chat.attachTitle,
                                modifier = Modifier.size(23.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        if (isStreaming) {
                            FilledIconButton(
                                onClick = onStop,
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = text.chat.stop,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            FilledIconButton(
                                onClick = onSend,
                                enabled = enabled && (value.isNotBlank() || attachments.isNotEmpty()),
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.outline,
                                ),
                            ) {
                                Icon(
                                    Icons.Filled.ArrowUpward,
                                    contentDescription = text.chat.send,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The character that runs a shell command in the composer, instead of the agent. */
private const val SHELL_SIGIL = "!"

/** The transcript's side margin, which the composer's own inset matches. */
private val TRANSCRIPT_GUTTER = 12.dp

/**
 * The transcript's vertical rhythm: two boundaries and one module gap.
 *
 * The list used to space everything by 8dp. That is the reason the conversation had
 * no visible structure: the gap between a prompt and the answer it produced was the
 * same as the gap between two tool calls of the same step, so the only thing
 * separating one turn from the next was the text itself. The fix was to vary the
 * gap by what the two rows *are* — and the first version of it went too far the other
 * way, with five numbers and a different one on each side of a tool call.
 *
 * What is left is the smallest set that still says something:
 *
 *  - [TURN_GAP] (20dp) — the end of one turn to the start of the next. The largest
 *    number on the page, because it is the only one that means "new subject".
 *  - [PROMPT_GAP] (12dp) — a prompt to whatever answers it. Tight enough to read as
 *    one unit, loose enough that the bubble does not touch the answer.
 *  - [MODULE_GAP] (8dp) — everything else.
 */
private val TURN_GAP = 20.dp
private val PROMPT_GAP = 12.dp

/**
 * The one gap between two things *inside* a turn: a thought, a tool call, the answer,
 * a notice, the fold band.
 *
 * It used to be three numbers — 4dp between two tool calls, 8dp after one, 12dp
 * before one — so the same pair of modules was laid out differently depending on
 * which came first. A turn reading "thinking → bash → thinking" showed 12dp above the
 * call and 8dp below it, and two adjacent calls showed 4dp, which is the reader's
 * report that the spacing between the model's calls and its thinking was not
 * consistent. Nothing about those rows justifies three numbers: they are all one
 * thing after another within one turn.
 *
 * The turn's own boundaries are the two numbers above, and they are the only places a
 * different distance still means something — a prompt opening a turn, and what
 * answers it. They stay distinct from this one on purpose: one number for *every*
 * pair is what made the conversation read as an undifferentiated stream.
 *
 * Positive rather than zero: the rows are bare lines now, so a zero here would stack
 * two of them with nothing between them at all.
 */
private val MODULE_GAP = 8.dp

/**
 * When the transcript starts being capped, and at what.
 *
 * 600dp is Material's own small/medium window boundary, and 560dp is where a line
 * of `bodyMedium` is 80 Latin characters *and* 40 Han characters — the two halves
 * of WCAG's visual-presentation limit, which happen to coincide at 14sp. See the
 * note at the transcript's own `BoxWithConstraints`.
 */
private val WIDE_TRANSCRIPT_WINDOW = 600.dp
private val MAX_TRANSCRIPT_WIDTH = 560.dp

/**
 * What the follow-the-tail effect keys on: everything that grows while an answer streams,
 * and nothing else.
 *
 * A compose `LaunchedEffect` re-runs when its keys *change*, and the last item of a
 * streaming answer never changes identity — only its text does. So the key has to be the
 * lengths, and it has to be **both** of them.
 *
 * Leaving `thinking` out is a bug with a reader behind it. A reasoning model streams its
 * thinking first and its answer afterwards, and during the whole of that phase
 * `Assistant.text` is empty: the key did not move, the effect did not re-run, the list was
 * never snapped to the end, `LazyListState.canScrollForward` went true, and the "back to
 * the bottom" button was drawn — for as long as the thinking lasted, over a list that was
 * doing nothing but growing. The button's own condition is right and is not the thing to
 * change: it is honest at every moment, and it was the *scrolling* that had stopped.
 *
 * Pure and top-level so the rule is pinned on the JVM rather than by watching a phone: the
 * failure is invisible in a still screenshot and only appears while an answer streams.
 */
internal fun transcriptTailKey(items: List<ChatItem>): Triple<Int, Int, Int> {
    val last = items.lastOrNull() as? ChatItem.Assistant
    return Triple(items.size, last?.text?.length ?: 0, last?.thinking?.length ?: 0)
}

/**
 * The prompt bubble's corner radius — one value, all four corners.
 *
 * A second constant lived here, `PROMPT_TAIL` (6dp), applied to the bottom-end corner
 * alone so the bubble pointed at the answer below it. It was removed on request: the app's
 * own prompt is the one shape a reader compares against every other rounded box on the
 * page, and one square corner among eighteen rounded ones was read as a fault rather than
 * as a signal. What identifies the bubble is right alignment, its `primaryContainer` fill
 * and its width cap, in that order.
 */
private val PROMPT_CORNER = 18.dp

/**
 * True for a row whose composable would return without drawing anything.
 *
 * Only an assistant row can: [AssistantBubble] draws nothing when a message has no
 * text, no reasoning and no error — which is exactly the row pi opens for the
 * message that carried a tool call and nothing else.
 *
 * `isStreaming` used to be excluded, because a streaming row drew the live cursor
 * and was therefore never empty. The cursor is gone — see [AssistantBubble] — so a
 * row with nothing in it draws nothing whether or not it is streaming, and the
 * transcript no longer pays 8dp of `MODULE_GAP` for a blank row while the first
 * token is on its way.
 */
internal fun TranscriptRow.drawsNothing(): Boolean {
    val item = (this as? TranscriptRow.Message)?.item as? ChatItem.Assistant ?: return false
    return item.thinking.isBlank() && item.text.isBlank() && item.error == null
}

/**
 * The space above [row], given the row before it.
 *
 * A pure function of the two rows rather than a constant on the list, and computed
 * here rather than in `transcriptRows`: the fold rules are about what is *shown*, and
 * which of two adjacent rows is a boundary is about how they are drawn. Keeping it
 * out of the fold also keeps `ChatFoldTest` — which compares rows — about the fold.
 *
 * Three cases and no more. The rows themselves no longer decide the distance —
 * whether a tool call follows a thought or a thought follows a tool call is not a
 * fact about the transcript's structure, it is only the order the model happened to
 * work in, so both get [MODULE_GAP]. What is left that *is* structural is a prompt
 * opening a turn ([TURN_GAP]) and the turn that answers it ([PROMPT_GAP]).
 */
internal fun rowGap(previous: TranscriptRow?, row: TranscriptRow): Dp {
    if (previous == null) return 0.dp
    val before = (previous as? TranscriptRow.Message)?.item
    val now = (row as? TranscriptRow.Message)?.item
    return when {
        // A prompt always opens a turn, whatever came before it.
        now is ChatItem.User -> TURN_GAP
        // The prompt is its own statement; whatever answers it is one unit below.
        before is ChatItem.User -> PROMPT_GAP
        // Everything inside a turn: a thought, a call, its answer, a notice, the fold
        // band. One distance, whichever of them comes first.
        else -> MODULE_GAP
    }
}

/**
 * Between a message's reasoning control and the answer under it.
 *
 * Named rather than inlined because it is one half of a pair: the other half is
 * `MODULE_GAP` in `chat/Transcript.kt`, the distance between this row and the row
 * above it. Making this one conditional is what fixed the reader's report that a
 * tool call sat closer to the thinking above it than to the thinking below it.
 */
private val REASONING_ANSWER_GAP = 5.dp

/**
 * How much of a failed launch's message the banner shows.
 *
 * pi's stderr is the second line and is the only part that says *why*; four lines
 * is enough for the usual one-line error plus a stack frame or two, and past that
 * the banner would take the transcript's height to report something the terminal
 * tab shows in full.
 */
private const val FAILURE_BANNER_LINES = 4

/**
 * How much blank transcript sits under the last line of an answer.
 *
 * Two lines of `bodyMedium`, which is 20sp each: the report was that the answer's
 * last line and the top of the composer were "too close together" — the composer
 * begins with a row of chips on the page's own colour, so there was no edge to
 * read the end of the answer against. Roughly two lines is enough for the eye to
 * land on the last line as something that ended.
 */
private val TRANSCRIPT_TAIL_SPACE = 40.dp

/**
 * How full the context window is.
 *
 * A percentage only. Integer division, so 99.6% does not read as "full" a message
 * early, and an unknown window reports as 0% rather than hiding: the chip is
 * always there, so it may as well be the same shape before and after the model
 * reports its size. The raw counts are one tap away, in the sheet.
 */
private fun contextPercentLabel(state: ConversationState, text: Strings): String {
    val window = state.model?.contextWindow ?: 0
    val used = state.usage.context.total
    val percent = if (window > 0) (used * 100 / window).toInt() else 0
    return text.chat.contextUsage(percent)
}

/**
 * What share of the last request's input came back from the provider's cache.
 *
 * Measured against the last finalized message rather than the session total: the
 * cache is a property of *one* request — the provider keeps the longest prefix it
 * has seen and charges a tenth for it — so a ratio accumulated over a whole
 * session would report the average of a conversation that has changed shape
 * several times, and would move on every turn even when nothing about the caching
 * had changed.
 *
 * Zero when nothing has been cached, which is a reading and not an absence: the
 * chip is always on the row, so a conversation on a provider with no prompt cache
 * reports `0%` rather than changing the row's shape. The alternative — hiding it —
 * was tried and is worse, because the control people reach for disappears exactly
 * when they are looking for it.
 */
private fun cacheHitPercent(state: ConversationState): Int {
    val context = state.usage.context
    if (context.cacheRead <= 0 || context.input <= 0) return 0
    // `input` is the *uncached* remainder on pi's OpenAI-compatible path, so the
    // request's whole input is the two together. Clamped because a provider is
    // free to report a cacheRead larger than the input it accompanies.
    val total = context.input + context.cacheRead
    return ((context.cacheRead * 100) / total).toInt().coerceIn(0, 100)
}

/**
 * One of the composer's small controls.
 *
 * Sized to be read as a control rather than as a label: a filled, 34dp-tall pill
 * with 12dp of horizontal padding and a 15dp glyph. Two earlier versions were
 * wrong in opposite directions and the colour is what settled it:
 *
 *  - 26dp with a hairline `outlineVariant` border on `surface`. Too small, and a
 *    border says "box" rather than "button", so the row read as tags.
 *  - 34dp filled with `surfaceContainerHigh` and no border. The right shape, but
 *    the fill is one step off the composer's own `surfaceContainerHighest`, and at
 *    that distance the row looked washed out — the user's word for it. Measured
 *    against the theme: `surfaceContainerHigh` is `#E6EBF1` on a `#EDEEF2` bar, a
 *    2% difference.
 *
 * `surfaceVariant` (`#E4E9EF`) is the next real step down and is still inside the
 * light end of the palette, which is what "a little darker, not dark" needs.
 * Nothing else on the row changed with it, so the chips still take the same
 * `onSurface` text the theme gives every other filled control.
 *
 * ## The light palette's version of that step was still one short
 *
 * Reported again, and from the light theme only: on the composer's `#EDEEF2` bar a
 * `#E4E9EF` chip is a 9/5/3-per-channel difference, which at 34dp reads as a printed
 * label rather than as a button. [composerChipContainer] takes one more step in that
 * palette — `surfaceContainer`, `#DCE3EB`, another 8/6/4 down — and *no* step in the dark
 * one, where the same move would put the chip at `#1A2028` on a `#171C23` bar and erase
 * it. The threshold is the fill's own luminance rather than a boolean carried down from
 * the theme, because the colour is the question being asked.
 *
 * ## Why the label is bounded in dp and not in characters
 *
 * A character cap was the first attempt and it clipped the wrong language: at 14
 * characters `缓存命中 0%` — seven Han glyphs and three Latin ones, which is
 * *inside* the cap — measured wider than the cap's own 96dp and drew as
 * `缓存命…`, while the same cap left a Latin model name with room to spare. The
 * width a label needs is a property of its glyphs, not of how many there are, so
 * the bound is a width and `maxLines = 1` does the rest. The row still cannot
 * change height as values change, and it scrolls horizontally rather than
 * squeezing its last chip when the values are long.
 */
@Composable
private fun ControlChip(
    glyph: @Composable () -> Unit,
    label: String?,
    onClick: (() -> Unit)?,
    contentDescription: String? = null,
) {
    val shown = label?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(composerChipContainer())
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(CHIP_HEIGHT)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph()
        if (shown != null) {
            Text(
                text = shown,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .widthIn(max = 124.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 34dp: two thirds of the 48dp touch minimum, at the height of a real chip. */
private val CHIP_HEIGHT = 34.dp

/**
 * What a composer chip is filled with: one step darker than its bar in the light palette,
 * and unchanged in the dark one.
 *
 * See [ControlChip] for the measurement. The test is the palette's own luminance because
 * the two schemes need opposite answers from the same one-step move: in the light scheme
 * the chip sits on `surfaceContainerHighest` (`#EDEEF2`) and `surfaceVariant` (`#E4E9EF`)
 * is too close to it, while in the dark scheme that bar is `#171C23` and `surfaceContainer`
 * (`#1A2028`) is too close to *it*. `surfaceVariant` is above 0.5 luminance in the light
 * scheme and far below it in the dark one, so the fill itself answers the question.
 */
@Composable
private fun composerChipContainer(): Color {
    val fill = MaterialTheme.colorScheme.surfaceVariant
    return if (fill.luminance() > 0.5f) MaterialTheme.colorScheme.surfaceContainer else fill
}

/**
 * The conversation's numbers, in full.
 *
 * The chip that opens this shows one percentage; everything else a user can want
 * to know about the session — what it cost, how much of it was cache, which file
 * it is being written to — has no room on a 26dp chip and no business in a
 * header. It lives one tap away, read-only.
 *
 * The numbers are read from the session's own flow *here*, inside the sheet,
 * rather than passed in from the page: a turn keeps running while this is open,
 * and a sheet that showed the totals as they were on the frame the chip was
 * tapped would be a snapshot pretending to be a meter.
 *
 * One label per row rather than the two-column table this started as: the label
 * column has to be a fixed width (112dp was measured), which leaves 210dp for the
 * value, and a session file name needs 480px. Stacked, the value gets the sheet's
 * full width and a second line.
 */
@Composable
private fun ContextSheet(session: PiAgentSession, text: Strings) {
    val state by session.conversation.collectAsState()
    val saved by session.settingsStore.settings.collectAsState()
    val thinking = thinkingLevelOf(state, saved)
    val usage = state.usage
    val context = usage.context
    val window = state.model?.contextWindow ?: 0
    val model = state.model

    // Read-only rows, so the sheet's body is a lazy list for the scrolling and
    // each fact is one `item`. Nothing here is tappable: the cache and context
    // chips both open this sheet, and a sheet that answered a tap by opening
    // itself again would be a loop.
    ReadOnlyBody(title = text.chat.contextDetails) {
        item {
            ReadOnlySheetRow(
                label = text.chat.detailModel,
                value = when {
                    model == null -> text.chat.loadingModel
                    !model.isUsable -> text.chat.noModel
                    else -> model.id.ifBlank { model.name }
                },
            )
        }
        item {
            ReadOnlySheetRow(
                label = text.chat.detailProvider,
                value = model?.provider?.ifBlank { text.settings.none },
            )
        }
        item { ReadOnlySheetRow(text.chat.thinkingLabel, thinking) }
        item {
            ReadOnlySheetRow(
                label = text.chat.detailContext,
                value = if (window > 0 && context.total > 0) {
                    text.chat.contextOfWindow(
                        percent = (context.total * 100 / window).toInt(),
                        windowK = window / 1000,
                    )
                } else {
                    text.chat.contextEmpty
                },
            )
        }
        item { ReadOnlySheetRow(text.chat.detailUsed, text.chat.tokens(context.total)) }
        item { ReadOnlySheetRow(text.chat.detailInput, text.chat.tokens(context.input)) }
        item { ReadOnlySheetRow(text.chat.detailOutput, text.chat.tokens(context.output)) }
        item { ReadOnlySheetRow(text.chat.detailCacheRead, text.chat.tokens(context.cacheRead)) }
        item { ReadOnlySheetRow(text.chat.detailCacheWrite, text.chat.tokens(context.cacheWrite)) }
        if (context.reasoning > 0) {
            item { ReadOnlySheetRow(text.chat.detailReasoning, text.chat.tokens(context.reasoning)) }
        }
        item {
            ReadOnlySheetRow(
                label = text.chat.cacheHitLabel,
                value = text.chat.cacheHit(cacheHitPercent(state)),
            )
        }
        item {
            ReadOnlySheetRow(
                label = text.chat.detailCost,
                value = if (usage.total.costUsd > 0) "$%.4f".format(usage.total.costUsd) else null,
            )
        }
        item { ReadOnlySheetRow(text.chat.detailTotal, text.chat.tokens(usage.total.total)) }
        item { ReadOnlySheetRow(text.chat.detailTurns, text.chat.turnCount(state.turns.size)) }
        // The file's *name*: the sheet is 360dp wide and the container path is
        // 48 characters before the name. Which conversation this is, is the
        // question; where it lives on disk is not.
        item {
            ReadOnlySheetRow(
                label = text.chat.detailSession,
                value = state.sessionFile?.let { java.io.File(it).name },
                monospaceValue = true,
                valueLines = 2,
            )
        }
    }
}

/**
 * The commands this page can actually run.
 *
 * ## Why the rows pi contributes are not in this list
 *
 * The list used to be pi's own `get_commands` registry plus [BUILT_IN_COMMANDS],
 * on the theory that the registry is an extension point and a hard-coded list goes
 * stale. The theory is right and the rows were still wrong, and the reader named
 * them: `/websearch`, `/curator`, `/google-account`, `/search`, `/llama` — the ones
 * the bundled web-access extension registers. They are not commands this app can
 * run. Several of them open a *review page* in pi's own terminal UI and never
 * return over RPC; one manages a `llama.cpp` router this app does not ship. Tapping
 * one sent the line to pi as a prompt and the answer was, at best, a paragraph
 * explaining that the command needs an interactive terminal — which is a worse
 * answer than not offering the row. The report was "点击之后不仅不会直接执行，还带来了
 * 其他问题", and a list of controls that mostly do not act on the tap is exactly that.
 *
 * So the list is what this page implements, and pi's registry is left out. What
 * that costs is the extension point: an extension the user installs will not appear
 * here. That is the honest trade — the alternative is a row that cannot keep its
 * promise — and every row that *is* here runs the moment it is tapped:
 *
 * - **[BUILT_IN_COMMANDS] are run by this app** — `clone`, `export`, `compact` and
 *   the rest go out over RPC through their own method, and the outcomes land in the
 *   transcript.
 * - **The `!` row opens a field**, because the `!` sigil is not a command but a
 *   *prefix* for a shell line and the composer's handling of it needs the whole
 *   line. It gets pi's own description of what it does, a field, and a Run button —
 *   the one place a field is the right answer, because the whole point of `!` is
 *   that the reader has a shell line in mind.
 *
 * ## Why `agentCommands()` is still on the session
 *
 * It is not called from here any more and it is deliberately not deleted: the
 * filter is the *page's* decision, and a future surface that shows the registry —
 * the search page, or a settings list with a warning on the rows that need a
 * terminal — should read it from the session rather than re-implement the RPC.
 */
@Composable
private fun CommandSheet(
    text: Strings,
    onRun: (BuiltInCommand) -> Unit,
    onShell: () -> Unit,
) {
    ReadOnlyBody(title = text.chat.commands) {
        item {
            ReadOnlySheetRow(
                label = SHELL_SIGIL,
                value = null,
                description = text.chat.shellCommandHint,
                onClick = onShell,
            )
        }
        items(BUILT_IN_COMMANDS, key = { it.id }) { command ->
            ReadOnlySheetRow(
                label = command.id,
                value = null,
                description = command.description(text),
                onClick = { onRun(command) },
            )
        }
    }
}

/**
 * One command this page implements itself.
 *
 * [id] is both the row's label and what [`PiKitRoot`][pi.kit.mob.ui.PiKitRoot]'s
 * dispatch matches on — one spelling, so a row and the thing it runs cannot drift
 * apart the way a label and a handler can.
 */
internal class BuiltInCommand(
    val id: String,
    val description: (Strings) -> String,
)

/**
 * The commands PiKit runs itself, because pi has no equivalent or has one the app
 * can reach more directly.
 *
 * All four are executed by the app rather than delivered to the model:
 *
 * - `/new` and `/compact` are pi operations the app owns a page for, and `/new`
 *   has to ask before it throws away a running answer.
 * - `/stop` is the composer's own stop button, and there is exactly one answer to
 *   "where did my queued message go" — the composer's.
 * - `/clone` is pi's `clone`, which duplicates the conversation into a session of
 *   its own.
 * - `/export` is pi's `export_html`, written into `$HOME/export` where the Files
 *   tab can open it.
 * - `/model` opens the model picker instead of switching to a model the user has
 *   not chosen.
 * - `/clear` empties the composer. It is the only *local* command in the list, and
 *   it earns its row: the field keeps its draft across a tab switch and there was
 *   no way to throw one away except by selecting all of it and deleting.
 *
 * What is deliberately not here. `/steer` and `/follow-up` need message text, which
 * is what the composer is for. `/settings` and the other built-in *terminal*
 * commands pi documents are explicitly not commands pi's RPC mode handles: pi's own
 * documentation says they "are handled only in interactive mode and would not
 * execute if sent via `prompt`", so offering them would be a row that types text
 * into a field where it does nothing.
 */
private val BUILT_IN_COMMANDS: List<BuiltInCommand> = listOf(
    BuiltInCommand("/new") { it.chat.commandNew },
    BuiltInCommand("/compact") { it.chat.commandCompact },
    BuiltInCommand("/stop") { it.chat.commandStop },
    BuiltInCommand("/clone") { it.chat.commandClone },
    BuiltInCommand("/export") { it.chat.commandExport },
    BuiltInCommand("/model") { it.chat.commandModel },
    BuiltInCommand("/clear") { it.chat.commandClear },
)

/**
 * A picked file: its own thumbnail, its name, and its own remove button.
 *
 * The two overlays on the tile share one strip along its foot. The ✕ used to be a
 * 22dp opaque disc pinned to the tile's *top-right* corner and therefore sitting on
 * the picture — a quarter of a 64dp thumbnail with something solid over it, which
 * is the report that "the × covers part of the image". The name band already
 * crosses that bottom edge and is translucent by design, so putting the control at
 * its end costs no further pixel of the picture and turns a 22dp circle into a
 * 26×20 segment of a band that reads as part of the chip.
 */
@Composable
private fun AttachmentChip(
    image: PendingImage,
    removeLabel: String,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    // Decoded off the main thread: a 12-megapixel photo takes long enough to
    // decode that doing it during composition is a visible stall.
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, image.id) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, image.uri) }
    }

    val shape = RoundedCornerShape(10.dp)
    // The tile's size is fixed on the *Box*, and that is load-bearing rather than
    // tidiness: a `LazyRow` measures its items with an unbounded main axis, so
    // `fillMaxWidth` inside one is a no-op and a weighted child is resolved against
    // a target width of zero. With the size only on the `Surface` the name band ran
    // the item as wide as the file's own name, and the ✕ anchored to the item's end
    // therefore sat past the picture it belongs to.
    Box(Modifier.size(ATTACHMENT_TILE)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
            modifier = Modifier.size(ATTACHMENT_TILE),
        ) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = image.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(ATTACHMENT_TILE),
                )
            } else {
                Box(Modifier.size(ATTACHMENT_TILE), contentAlignment = Alignment.Center) {
                    // A document has no thumbnail, and a spinner that never
                    // resolves would claim otherwise: the name beside the tile is
                    // what identifies it.
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        bottomStart = 10.dp,
                        bottomEnd = 10.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f))
                .height(ATTACHMENT_BAND_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = image.name,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Its own band segment rather than an `IconButton`: the button's
            // minimum interactive size is 40dp, which is two thirds of the tile, so
            // a disc pinned to the corner was the layout this component was
            // fighting. A clickable segment is exactly the size it looks, and
            // `onClickLabel` is what a screen reader announces.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(ATTACHMENT_REMOVE_WIDTH)
                    .clickable(onClickLabel = removeLabel, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The shell-line sheet: a command, run against the runtime, without the model.
 *
 * ## Why this is a sheet and not a row
 *
 * The composer's `!` prefix is a *whole line* convention — pi runs everything after
 * it as a shell command — and a command list is the wrong place for one, because
 * every other row of that list is a word. What the list can do is say the
 * convention exists and open the field that accepts the line, which is what the
 * `!` row does and what this draws.
 *
 * The output is left in the conversation as a notice by
 * [`PiAgentSession.runBash`][pi.kit.mob.pi.PiAgentSession.runBash], which is the
 * same path a `!` typed into the composer takes — so the two spellings of one
 * feature cannot disagree about what they show.
 *
 * ## The field
 *
 * Monospace and with autocorrect off, because a command is machine text: an
 * autocorrected `git` is a shell error the reader cannot see coming. It is a
 * single-line `OutlinedTextField` rather than a `BasicTextField` because this is a
 * form, not a composer — it exists for one line and then leaves.
 */
@Composable
private fun ShellCommandSheet(
    text: Strings,
    onRun: (String) -> Unit,
) {
    val host = LocalSheetHost.current
    var line by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = text.chat.shellCommandHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = line,
            onValueChange = { line = it },
            placeholder = { Text(text.chat.shellCommandPlaceholder) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                // Refused while the line is blank: `bash` with an empty command is a
                // shell error the user would be reading for a reason they caused.
                enabled = line.isNotBlank(),
                onClick = {
                    host.dismiss()
                    onRun(line.trim())
                },
            ) {
                Text(text.common.ok)
            }
            TextButton(onClick = { host.dismiss() }) { Text(text.common.cancel) }
        }
    }
}

/** One attachment tile's edge — square, as the composer's other tiles are. */
private val ATTACHMENT_TILE = 64.dp
/** The foot band: the file name, and the remove control at its end. */
private val ATTACHMENT_BAND_HEIGHT = 20.dp
private val ATTACHMENT_REMOVE_WIDTH = 26.dp

// ------------------------------------------------------------------ attachments

/** Where a photo the camera is about to take is staged, under the app's cache. */
private const val CAPTURE_DIRECTORY = "captures"

/**
 * A `content://` URI for a new photo, in the app's own cache directory.
 *
 * The file is *created* here rather than by the camera, and that is the contract
 * `ActivityResultContracts.TakePicture` works to: the camera opens what it is given
 * for writing, and this app keeps the URI because the result it hands back is a
 * boolean. A camera that refuses to write into the file (some do, for a path they
 * cannot resolve through the provider) leaves a zero-byte file behind, which is why
 * the caller deletes it when the capture does not come back.
 */
private fun newCaptureUri(context: Context): Uri {
    val directory = java.io.File(context.cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }
    val file = java.io.File(directory, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Validates a picked file and gives it a name.
 *
 * Refused here rather than at send time so the user finds out while the tile is
 * still on screen, with something they can remove.
 *
 * A provider that reports no size is *measured* rather than trusted: the check used
 * to read `statSize ?: -1` and compare it, which let every cloud document through
 * the 4 MB cap and made the cap a comment — the file was then read whole at send
 * time. Reading the stream until it passes the limit is the only answer a provider
 * that does not declare a length allows, and it costs one bounded read of a file
 * that is about to be read in full anyway.
 */
private fun prepareImage(context: Context, uri: Uri, text: Strings): PendingImage? = runCatching {
    val declared = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    val size = if (declared >= 0) declared else measure(context, uri)
    if (size > MAX_IMAGE_BYTES) return@runCatching null
    PendingImage(
        id = uri.toString() + "#" + System.nanoTime(),
        uri = uri,
        name = queryName(context, uri) ?: text.chat.imageAttached,
    )
}.getOrNull()

/** How much of a stream can be read, when the provider does not say. */
private fun measure(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val chunk = ByteArray(STREAM_CHUNK)
        var total = 0L
        while (total <= MAX_IMAGE_BYTES) {
            val read = stream.read(chunk)
            if (read <= 0) return@use total
            total += read
        }
        total
    } ?: 0L
}.getOrDefault(0L)

private fun queryName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

/**
 * The attachment as pi expects it: raw base64 plus the mime type.
 *
 * Read through a bounded buffer rather than `readBytes()`: a provider that lies
 * about its size, or one whose file grew between the pick and the send, would
 * otherwise put an unbounded array on the heap on the way to a base64 string twice
 * its size. Past the cap the attachment is dropped, which is the same answer the
 * pick would have given.
 */
private fun encodeImage(context: Context, image: PendingImage): ImageAttachment? = runCatching {
    val mime = context.contentResolver.getType(image.uri) ?: "image/jpeg"
    val bytes = context.contentResolver.openInputStream(image.uri)?.use { readAtMost(it) }
        ?: return@runCatching null
    ImageAttachment(
        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        mimeType = mime,
    )
}.getOrNull()

/** Read size of every bounded stream read on the attachment path. */
private const val STREAM_CHUNK = 64 * 1024

/** The whole stream, or null once it is larger than [MAX_IMAGE_BYTES]. */
private fun readAtMost(stream: java.io.InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(STREAM_CHUNK)
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        if (out.size() + read > MAX_IMAGE_BYTES) return null
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

private fun loadThumbnail(context: Context, uri: Uri): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
    }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null

    var sample = 1
    while (longest / (sample * 2) >= THUMBNAIL_EDGE) sample *= 2

    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null
    bitmap.asImageBitmap()
}.getOrNull()
