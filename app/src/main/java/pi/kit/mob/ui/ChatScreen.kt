package pi.kit.mob.ui

import android.Manifest
import android.graphics.BitmapFactory
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import pi.kit.mob.R
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.rememberedThinkingLevels
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.pi.ChatTurn
import pi.kit.mob.pi.ConversationState
import pi.kit.mob.pi.ImageAttachment
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.PiLaunchOptions
import pi.kit.mob.pi.clampThinkingLevel
import pi.kit.mob.pi.thinkingLevelsFor
import pi.kit.mob.pi.SessionMetadata
import pi.kit.mob.ui.chat.ChatStatusPill
import pi.kit.mob.ui.chat.ChatTone
import pi.kit.mob.ui.chat.NoticeCard
import pi.kit.mob.ui.chat.ReasoningBlock
import pi.kit.mob.ui.chat.ReasoningToggle
import pi.kit.mob.ui.chat.ROW_SHAPE
import pi.kit.mob.ui.chat.ToolCallRow
import pi.kit.mob.ui.chat.TurnSummaryData
import pi.kit.mob.ui.chat.TurnSummaryRow
import pi.kit.mob.ui.chat.FollowTailController
import pi.kit.mob.ui.components.CopyButton
import pi.kit.mob.ui.components.CopyButtonInkInset
import pi.kit.mob.ui.chat.rememberFollowTail
import pi.kit.mob.ui.chat.scrollToEnd
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiMenuRow
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.PiTone
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * What a picked file is until it is actually sent.
 *
 * [workspacePath] marks a file attachment: the bytes have been copied into a
 * temporary folder and the AI is told that path instead of receiving the file
 * as an image. That is the channel for *any* format — a PDF, a zip, a binary
 * the model could never take as an image — and it is why the `+` sheet has a
 * separate row for it: the image path only ever carried images.
 *
 * The copy lands in the cache rather than in the agent's workspace on purpose:
 * an attachment is staging, not project content, and a workspace that fills up
 * with every document the user ever mentioned is a workspace the agent has to
 * wade through. The path is absolute and the agent runs as this app's uid, so
 * the model can open it from wherever it is working.
 */
internal data class PendingImage(
    val id: String,
    val uri: Uri,
    val name: String,
    val workspacePath: String? = null,
)

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
 * Which of the composer's menus or sheets is being asked for.
 *
 * One name for all six rather than six booleans: they are mutually exclusive by
 * construction (each is modal, and each covers the others), and a name per one keeps
 * the six bodies one `when` apart from the controls that open them.
 *
 * The *state* lives in [ChatScreen] and this enum is passed down to the composer,
 * because two of the six are not the composer's: the context sheet is opened from
 * two chips, and the shell sheet is opened from the command menu. A sheet the
 * composer owned privately would be a second answer to "which one is open" — the
 * question the app's one sheet host used to answer for every screen.
 */
private enum class ComposerSheet { Thinking, Model, Context, Commands, Attach, Shell }

/**
 * How long the history list takes to slide over the conversation.
 *
 * Short enough that the page is usable while it lands, long enough that the two
 * pages are readable as one moving over the other rather than as a cut.
 */
private const val PAGE_SLIDE_MILLIS = 220

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

    // Which of the composer's menus or sheets is open, and the prompt-extras sheet.
    //
    // One state per *kind* rather than a `SheetHost` at the root, because Miuix's
    // menus are anchored to the composable that asks for them: a `WindowListPopup`
    // opens at its own parent's bounds, so the model menu has to be composed at the
    // model chip even when the `/model` command row opened it. The state is read by
    // the chip and by the renderer, which is what lets one open land on the other's
    // anchor.
    //
    // The prompt's extras are a sheet of their own rather than a composer sheet: it
    // is opened from a message in the transcript, and it must be able to outlive a
    // scroll of the row that raised it.
    var openSheet by remember { mutableStateOf<ComposerSheet?>(null) }
    var promptExtras by remember { mutableStateOf<PromptExtras?>(null) }
    // The extras sheet keeps its value while it leaves: a Miuix sheet animates out
    // with the content it was showing, so clearing the value on dismissal would play
    // the exit over an empty panel.
    var showPromptExtras by remember { mutableStateOf(false) }

    fun closeSheet() {
        openSheet = null
    }

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

    /**
     * Copies picked files into a temporary folder and attaches their *paths*.
     *
     * This is the `文件` row: any format at all. The image channel is images
     * only, and a document stuffed into it becomes an attachment pi drops or
     * misreads. The copy lands in the cache rather than the agent's workspace
     * — an attachment is staging, not project content — and the prompt names
     * the path when the message is sent, so the model opens it with its own
     * tools.
     */
    fun acceptPaths(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> copyToTemp(context, uri, text) }
            }
            val room = (MAX_ATTACHMENTS - composer.attachments.size).coerceAtLeast(0)
            val taken = copied.take(room)
            composer.notice = when {
                copied.isEmpty() -> text.chat.addressCopyFailed
                copied.size > taken.size -> text.chat.imageLimit(MAX_ATTACHMENTS)
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

    // The file row: any type at all. The result is a copy in a temp folder plus a
    // path — never an image attachment. It is the only non-image channel, which
    // is why the sheet no longer carries a second "file" row beside it.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> acceptPaths(uris) }

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
    // multi-select and per-conversation actions. It registers its own back handler
    // ([PiPage]'s `onBack`), and a Miuix sheet or popup registers one inside the
    // window it opens — which is what makes the first back close the sheet rather
    // than the page behind it, the ordering the app's one sheet host used to provide
    // centrally.

    /**
     * Starts a new conversation, asking first when an answer is running.
     *
     * The same question the history list's rows ask before a switch, and the same
     * dialog: pi's `new_session` ends the running answer as its first step. It used
     * to be a flat refusal with a notice, which was a tap that produced a sentence
     * and no result.
     */
    fun newSession() {
        if (session.turnInFlight) {
            confirmNewSession = true
        } else {
            session.newSession()
            composer.attachments = emptyList()
            composer.notice = null
        }
    }

    /**
     * Runs one of the command list's rows.
     *
     * The list is what this page implements — see [BUILT_IN_COMMANDS] for why pi's
     * own registry is not in it — so every row is a call into state this page owns,
     * and this is the only place that dispatch exists: the row that was tapped knows
     * its id and nothing else.
     */
    fun runCommand(command: BuiltInCommand) {
        when (command.id) {
            // Asked, not refused: see [newSession].
            "/new" -> newSession()

            "/compact" -> session.compact()

            // The same stop the composer's button performs, queue and all, with the
            // same answer to "where did my queued message go".
            "/stop" -> session.abort { dropped ->
                if (composer.input.isBlank()) composer.input = dropped
            }

            // Both of these are pi's own operations and both report their outcome as
            // a row in the transcript — a path for the export, an error for a refusal
            // — because neither has a return value a tap could show any other way.
            "/clone" -> session.cloneSession()
            "/export" -> session.exportHtml()

            // The picker, not a model: this page has no way to know which model the
            // reader wants, and the menu that does is one key away — composed at the
            // model chip, which is where it opens from every path.
            "/model" -> openSheet = ComposerSheet.Model

            "/clear" -> composer.input = ""

            else -> Unit
        }
    }

    /** The `+` menu's three rows: the system pickers, and the one capture. */
    fun pickAttachment(kind: String) {
        when (kind) {
            "image" -> pickImages.launch("image/*")
            "camera" -> capture()
            else -> pickFiles.launch(arrayOf("*/*"))
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
    // `isStreaming` is a key of its own: `transcriptRows` reads it through
    // `turnSettled`, and `applySessionState` can change it while `items` and `turns`
    // stay the same instances — a turn that settles would otherwise keep
    // `showMeta = false` and never grow its time and copy button.
    val rows = remember(state.items, state.turns, state.isStreaming, expandedTurns) {
        transcriptRows(state, expandedTurns)
    }

    // The history list is the chat tab's second level, so it moves like one — it
    // used to replace the conversation between two frames, with no transition at
    // all, while every Settings sub-page slid. `AnimatedContent` is the whole of the
    // transition now, and the two pages travel different distances — the one that
    // arrives the full width, the one that leaves a quarter of it — so their text
    // never parks on the same pixels mid-slide. Both fade as they travel, which is
    // what keeps a page from reading as a card dragged over another.
    AnimatedContent(
        targetState = showSessions,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val entering = slideInHorizontally(tween(PAGE_SLIDE_MILLIS)) { width ->
                if (targetState) width else -width / 4
            } + fadeIn(tween(PAGE_SLIDE_MILLIS))
            val leaving = slideOutHorizontally(tween(PAGE_SLIDE_MILLIS)) { width ->
                if (targetState) -width / 4 else width
            } + fadeOut(tween(PAGE_SLIDE_MILLIS))
            entering togetherWith leaving
        },
        label = "session-page",
    ) { shown ->
        if (shown) {
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
                openSheet = openSheet,
                onOpenSheet = { openSheet = it },
                onCloseSheet = ::closeSheet,
                onRunCommand = ::runCommand,
                onPickAttachment = ::pickAttachment,
                onShowPromptExtras = { extras ->
                    promptExtras = extras
                    showPromptExtras = true
                },
                onOpenSessions = { showSessions = true },
                onNewSession = ::newSession,
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
                            // Two channels: images go in as bytes, a file attachment
                            // goes in as the path its copy already has under the
                            // cache dir. The path is prepended to the prompt rather
                            // than sent as an attachment — pi's wire format has
                            // images only — and is worded so the model treats it as
                            // something to open, not as prose about a file. Front of
                            // the prompt rather than the tail: the model reads the
                            // paths before the question that needs them, and the
                            // bubble's extras chip is where the reader sees them.
                            val (pathed, pictured) = images.partition { it.workspacePath != null }
                            val pathNote = if (pathed.isEmpty()) {
                                prompt
                            } else {
                                buildString {
                                    append(text.chat.addressHeader)
                                    pathed.forEach { append('\n').append(it.workspacePath) }
                                    if (prompt.isNotBlank()) {
                                        append("\n\n")
                                        append(prompt)
                                    }
                                }
                            }
                            val payload = withContext(Dispatchers.IO) {
                                pictured.mapNotNull { encodeImage(context, it) }
                            }
                            session.sendPrompt(pathNote, payload)
                        }
                    }
                },
            )
        }
    }

    // Drawn over whichever of the two pages above is showing — the history list is
    // where the same question is asked about a saved conversation, and a Miuix
    // dialog is a window of its own, so it belongs to the screen rather than to
    // either page the swap holds.
    PiConfirm(
        show = confirmNewSession,
        title = text.sessions.switchInterruptTitle,
        text = text.sessions.switchInterruptBody,
        confirm = text.sessions.switchInterruptConfirm,
        dismiss = text.common.cancel,
        // The one dialog in the app whose confirm throws work away: an answer that is
        // still arriving dies with the session, and the two buttons should not look
        // like equivalent choices.
        destructive = true,
        onConfirm = {
            confirmNewSession = false
            session.newSession(interrupt = true)
            composer.attachments = emptyList()
            composer.notice = null
        },
        onDismiss = { confirmNewSession = false },
    )

    // The composer's two report sheets and the prompt's extras, in Miuix's own
    // window sheets. They are drawn here rather than inside the band that opens
    // them: a sheet is a window of its own, and one composed inside the composer
    // would be measured against the band's bounds instead of the screen's.
    //
    // The extras sheet is closed by `showPromptExtras` rather than by clearing the
    // value, so the panel still has its own content for the length of its exit.
    PiSheet(
        show = openSheet == ComposerSheet.Context,
        title = text.chat.contextDetails,
        onDismiss = ::closeSheet,
    ) {
        ContextSheet(session = session, text = text)
    }

    PiSheet(
        show = openSheet == ComposerSheet.Shell,
        title = null,
        onDismiss = ::closeSheet,
    ) {
        ShellCommandSheet(
            text = text,
            onRun = { line ->
                closeSheet()
                if (line.isNotBlank()) {
                    // A shell line that runs a command this page asked for:
                    // `runBash` puts the output in the transcript as a notice,
                    // which is the same path the `!` prefix in the composer takes.
                    follow.enable()
                    session.runBash(line)
                }
            },
        )
    }

    PiSheet(
        show = showPromptExtras,
        title = text.chat.extrasTitle,
        onDismiss = { showPromptExtras = false },
    ) {
        promptExtras?.let { PromptExtrasSheet(extras = it, text = text) }
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
 *
 * ## The header
 *
 * The page frame is [PiPage] with the shell's one fixed header: this page's title is
 * the first thing the reader asked, and the whole of it is a line the header
 * ellipsises rather than a band that grows and shrinks with it. The header used to be
 * the large *collapsing* bar, on the argument that a moving title should give its
 * height back to the transcript as the reader scrolled — the price was that this page's
 * chrome was 36dp taller than the settings pages' and moved while the others did not,
 * and §9.2 has the tokens that made the two heights unequal. Miuix's small bar is
 * pinned by construction, which is the same decision the rest of the app already made.
 *
 * The title is drawn in the one size every Miuix page's title is drawn in — the top
 * bar owns that decision, and a page that styled its own title was how the chat page
 * ended up with a header a size smaller than the Terminal's, Files' and Settings'
 * when its title was derived from the reader's first sentence rather than from a
 * session name.
 *
 * The **agent's mark** used to stand in the header's action row, drawn by a component
 * of the old design system that nothing else used; it is gone, because to the reader
 * it was a placeholder before the compress button with nothing to say that the
 * subtitle does not already say in words. What remains is the three actions, ordered compress, history,
 * new: left to right as "do something to this conversation, look at the others, start
 * another", which is also the order of how much each disturbs what is on screen.
 *
 * ## The composer sits under the transcript
 *
 * The composer and its notices are the page column's own last children, below the box
 * the transcript is drawn in rather than over it, so nothing overlaps and nothing has
 * to be measured to be cleared: the list ends where the band begins and its tail
 * padding is one line of the body style ([TRANSCRIPT_TAIL_SPACE]). The panel this
 * replaced was an overlay with a measured height; §7.1 has the whole of why it is a
 * band again and where the field's frame came back from.
 *
 * The *title* is passed as a `String` rather than as the state it came from, and
 * that is a performance decision: `ConversationState` is a new object for every
 * record pi sends — several times a second while an answer streams — so a header that
 * took the state could never be skipped and would re-run for every token, while the
 * two things it draws change once per conversation and once per process lifecycle.
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
    /** Which composer menu or sheet is open, or null. Read by the chip that owns it. */
    openSheet: ComposerSheet?,
    onOpenSheet: (ComposerSheet) -> Unit,
    onCloseSheet: () -> Unit,
    onRunCommand: (BuiltInCommand) -> Unit,
    onPickAttachment: (String) -> Unit,
    onShowPromptExtras: (PromptExtras) -> Unit,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    onSend: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // The two avatars the transcript draws, rebuilt only when one of the settings
    // behind them moves. Collected here rather than passed in so a change on the
    // personalization page reaches this one without a restart.
    val saved by session.settingsStore.settings.collectAsState()
    val avatars = remember(
        saved.showAvatars,
        saved.aiAvatarLabel,
        saved.aiAvatarImage,
        saved.aiAvatarColor,
        saved.userAvatarLabel,
        saved.userAvatarImage,
        saved.userAvatarColor,
    ) {
        if (!saved.showAvatars) {
            null
        } else {
            ChatAvatars(
                // Blank is the mark, not a word: the agent's fallback is the app's own
                // Pi logo, drawn by `ChatAvatar`'s `mark` flag.
                aiLabel = saved.aiAvatarLabel.ifBlank { null },
                aiImage = saved.aiAvatarImage.ifBlank { null },
                aiColor = saved.aiAvatarColor.seed,
                // The reader's blank is the silhouette for the same reason: the two
                // speakers must not fall back to the same figure.
                userLabel = saved.userAvatarLabel.ifBlank { null },
                userImage = saved.userAvatarImage.ifBlank { null },
                userColor = saved.userAvatarColor.seed,
            )
        }
    }

    // Rows that would compose to nothing are dropped before the list measures them.
    //
    // A turn's assistant rows include the one that carried only a tool call and has
    // no text of its own, and an empty row is not free: it costs its own `rowGap`,
    // so an expanded turn had 22dp of blank space where 8dp belonged — between the
    // fold band and the first step, and again between two steps. The fold already
    // counts these rows as hidden; this only stops them being measured.
    val drawn = remember(rows) { rows.filterNot { it.drawsNothing() } }

    // One card per subject, computed here rather than inside the item so the list
    // measures one item per turn instead of one per message. See [transcriptCards].
    val cards = remember(drawn) { transcriptCards(drawn) }

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
                listState.scrollToEnd(itemCount = cards.size, animate = false)
            } finally {
                programmatic.value = false
            }
        }
    }

    PiPage(
        title = conversationTitle(state),
        subtitle = agentSubtitle(agent, text),
        actions = {
            IconButton(onClick = { session.compact() }) {
                Icon(
                    imageVector = MiuixIcons.Merge,
                    contentDescription = text.chat.compact,
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onOpenSessions) {
                Icon(
                    imageVector = MiuixIcons.Recent,
                    contentDescription = text.chat.sessions,
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onNewSession) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = text.chat.newSession,
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
    ) { pagePadding ->
        Column(
            Modifier
                .fillMaxSize()
                // The page's own insets: the top bar's height at the top and the
                // navigation strip at the bottom. Applied here rather than to the
                // transcript alone, so the composer ends above the strip instead of
                // under it.
                .padding(pagePadding)
                // Tapping anywhere that is not a control gives up the composer's
                // focus, and clearing focus is what closes the text toolbar — the
                // floating "粘贴 / 全选" strip Compose shows over a long-pressed field.
                //
                // Nothing else did. The text field deselects and hides its toolbar
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
                // A line of the reading style (Miuix's `paragraph`, 17sp) on a 411dp
                // phone inside the 12dp gutters is about 45 Latin characters — the
                // bottom of the 45–75 band, so nothing needs fixing there. A tablet at
                // 800dp is about 88, and for this app the count that matters is the
                // CJK one: a Han glyph is a full em, so 800dp is 47 of them against the
                // 40 that WCAG's visual-presentation criterion allows.
                // [MAX_TRANSCRIPT_WIDTH] caps the column well inside that, and the cap
                // has to be the *prose* measure rather than the column's width, which is
                // why the answer card's own inset is added to it: an answer that
                // measured the cap in text inside a 16dp frame would be that much text
                // one step too wide.
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
                        // A conversation nothing has been sent to is not a blank
                        // area: it is the one place the page can say what it is for.
                        // The mark is the app's own logo, and the line under the
                        // title is a tip from the catalog's set rather than the same
                        // sentence every time — see `Strings.Chat.tips`.
                        //
                        // A conversation that is *being opened* is neither: it is
                        // empty for the length of one round trip to pi, and drawing
                        // the new-conversation page over it is the flash this
                        // replaces. `loadingSession` is the difference between "this
                        // conversation has nothing in it" and "this conversation is
                        // not here yet".
                        if (cards.isEmpty()) {
                            if (state.loadingSession) {
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ChatStatusPill(
                                        label = text.chat.loadingConversation,
                                        tone = ChatTone.Live,
                                    )
                                }
                            } else {
                                ChatEmptyState(
                                    title = text.chat.newConversation,
                                    body = remember(cards) { text.chat.tips.random() },
                                )
                            }
                        } else {
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
                                // the composer. An answer that ended flush against the band
                                // read as cut off rather than as finished, which is the report
                                // that asked for two lines of the body style's air; one line is
                                // what is left, because the band's own edge and the fill step
                                // carry the separation and the last line only has to not touch
                                // it.
                                //
                                // The right inset used to reserve the turn rail's column;
                                // the rail is gone, so the transcript keeps its own gutter
                                // on both sides and the answer gets that 26dp of width back.
                                contentPadding = PaddingValues(
                                    start = TRANSCRIPT_GUTTER,
                                    end = TRANSCRIPT_GUTTER,
                                    top = 10.dp,
                                    // One line of the body style's air, and nothing
                                    // else: the composer is a band in this page's
                                    // column now rather than a panel over the list, so
                                    // the list's own box already ends above it. The
                                    // measured height the floating panel needed is gone.
                                    bottom = TRANSCRIPT_TAIL_SPACE,
                                ),
                                // No uniform gap. See `rowGap`: the space between two rows is
                                // a statement about what the two rows *are*, and one number
                                // for every pair is what made a prompt, its band, its steps
                                // and its answer read as one undifferentiated stream.
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
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
                                    //
                                    // The gap is between two *cards*, which is the same
                                    // question `rowGap` answers between two rows: the two
                                    // rows that meet here are the last of the previous card
                                    // and the first of this one, and the three numbers a
                                    // conversation is readable through are unchanged.
                                    Column(
                                        Modifier.padding(
                                            top = rowGap(
                                                cards.getOrNull(index - 1)?.rows?.lastOrNull(),
                                                card.rows.first(),
                                            ),
                                        ),
                                    ) {
                                        TranscriptCardView(
                                            card = card,
                                            text = text,
                                            expandedTurns = expandedTurns,
                                            onToggleTurn = onToggleTurn,
                                            avatars = avatars,
                                            onShowExtras = onShowPromptExtras,
                                        )
                                    }
                                }
                                if (state.isCompacting) {
                                    // A labelled pill rather than a spinner: it says
                                    // *what* is running as well as *that* something is,
                                    // and it is the same shape the tool rows wear while
                                    // they have not come back yet.
                                    item(key = "compacting") {
                                        ChatStatusPill(
                                            label = text.chat.compacting,
                                            tone = ChatTone.Live,
                                            modifier = Modifier.padding(top = MODULE_GAP),
                                        )
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
                    IconButton(
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
                                    listState.scrollToEnd(itemCount = cards.size, animate = true)
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
                            //
                            // Inside the list's own box, which the composer's band no
                            // longer overlaps: the 10dp is the gutter's own figure and
                            // not a clearance from a floating panel.
                            .padding(end = 10.dp, bottom = 10.dp)
                            // 40dp, the same drawn size as the composer's send control
                            // at the other end of the page — Miuix's icon button *is* a
                            // 40dp pill by default, so the two controls the thumb reaches
                            // for are the same shape without either of them stating a
                            // radius of its own.
                            .size(40.dp),
                        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.ExpandMore,
                            contentDescription = text.chat.jumpToLatest,
                            // 20dp, matching the send/stop glyphs in the composer.
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // The notices and the composer, under the transcript in the page's own
            // column rather than stacked over it. The band is part of the page's
            // layout, so nothing here has to clear a floating panel's measured height
            // any more, and a message scrolls to the band rather than behind it.
            //
            // The notices are in the same stack because both are things said *to* the
            // reader about what they are doing rather than part of the conversation.
            //
            // One notice for "the agent is not there". A `Failed` agent and a
            // `lastError` are usually the same event — `runCommand`'s catch writes
            // both — and two stacked bars made one failure look like two. The agent's
            // own message is the better half of the pair: it carries the exit code and
            // pi's stderr.
            //
            // Retry is the action the notice needs and did not have. A failed launch
            // also disables the composer (`enabled = agent is Running`), so there was
            // no control anywhere on the chat page that would bring the agent back:
            // the only recovery was to change a model or a provider and hope something
            // called `startAgent` — which is exactly the report that switching
            // provider "does not load it" until the app is restarted. It is the *filled*
            // kind of button because while the agent is down it is the page's only
            // available action.
            //
            // The body is cut to [NOTICE_LINES] lines: pi's stderr is a process dump
            // measured at well over a screen, and a notice that grows to the whole page
            // buries the composer under the report it is making. The first three lines
            // carry the reason and a stack frame or two, and the rest is the Terminal
            // tab's job.
            val failure = (agent as? AgentStatus.Failed)?.message
            if (failure != null) {
                ChatNotice(
                    text = noticeLines(failure),
                    tone = ChatTone.Danger,
                    icon = MiuixIcons.Info,
                    action = {
                        TextButton(
                            text = text.common.retry,
                            onClick = { session.restartAgent() },
                            // A notice is a strip, not a form: the button sits inside
                            // it at the strip's own scale rather than at a button's
                            // 40dp minimum, which would make the notice as tall as the
                            // field under it.
                            minHeight = 28.dp,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            textStyle = MiuixTheme.textStyles.footnote1,
                        )
                    },
                )
            } else {
                state.lastError?.let { error ->
                    ChatNotice(
                        text = noticeLines(error),
                        tone = ChatTone.Danger,
                        icon = MiuixIcons.Info,
                        // Tapping the strip puts it away, which is the behaviour the
                        // banner this replaced had: a failure that has been read is
                        // clutter, and a close button on a notice that is itself the
                        // message would be a second control for one action.
                        onClick = { session.clearError() },
                    )
                }
            }

            notice?.let { message ->
                // The composer's own warnings — an image a text-only model will drop, a
                // pick over the cap — are not failures: the accent tone rather than the
                // error one, which is what stops a warning and a failure from training
                // the eye the same way.
                ChatNotice(
                    text = message,
                    tone = ChatTone.Accent,
                    icon = MiuixIcons.Info,
                    onClick = onDismissNotice,
                )
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
                openSheet = openSheet,
                onOpenSheet = onOpenSheet,
                onCloseSheet = onCloseSheet,
                onRunCommand = onRunCommand,
                onPickAttachment = onPickAttachment,
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
}

/**
 * A sentence about the page, in the page: Miuix's own card shape, at the strip's
 * height rather than a panel's.
 *
 * The fill is the [ChatTone]'s container and the text and mark take that container's
 * own `on…` role — the palette's pair, not a second colour computed for the fill — so a
 * failure and a warning are told apart by the palette rather than by a second
 * component. There is no dismiss button: the whole strip dismisses where the caller
 * passes an `onClick`, which is the behaviour the banner this replaced had.
 */
@Composable
private fun ChatNotice(
    text: String,
    tone: ChatTone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            // The tap area is put on *after* the margin, so what answers a tap is
            // exactly the strip the reader can see: a dismissable notice whose
            // gesture started in the gap beside it would be a control a few
            // millimetres larger than it looks. The clip comes first for the same
            // reason the chip's does — the press tint must not square off the corners.
            .clip(ROW_SHAPE)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = ROW_SHAPE,
        color = tone.container(),
        contentColor = tone.onContainer(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                style = MiuixTheme.textStyles.footnote1,
            )
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                action()
            }
        }
    }
}

/**
 * The page with nothing in it yet: the app's own mark, what this page is, and one
 * tip out of the catalog's set.
 *
 * A conversation nothing has been sent to is not a blank area — it is the one place
 * the page can say what it is for, which is why the mark here is the launcher icon's
 * own geometry rather than a generic speech bubble.
 */
@Composable
private fun ChatEmptyState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_pi_mark),
            contentDescription = null,
            modifier = Modifier.size(EMPTY_MARK_SIZE),
            tint = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = title,
            modifier = Modifier.padding(top = 20.dp),
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            modifier = Modifier.padding(top = 8.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

/** How large the mark is on an empty conversation. */
private val EMPTY_MARK_SIZE = 64.dp

/**
 * The navigation strip's height, for a sheet's own bottom edge.
 *
 * Miuix's bottom sheet is a window of its own with `decorFitsSystemWindows = false`, and its
 * inside margin is 24dp across and **zero** down — the panel's bottom edge *is* the window's,
 * which is the gesture bar. So a sheet's last row would sit under the gesture bar unless the
 * content clears it, and the inset belongs to the content rather than to the panel: on a
 * scrolling body it goes inside the scroll (after `verticalScroll`), so the text travels
 * through it and the panel's own fill still reaches the window's edge.
 */
@Composable
private fun sheetBottomInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * One speaker's mark: a circle in the colour the reader chose, holding a label, a
 * picture, or the fallback figure for that speaker.
 *
 * The two fallbacks are deliberately different figures — the agent's is the app's own
 * Pi logo, the reader's is a person — because a null label means "the built-in mark",
 * and two speakers falling back to the same figure is a transcript with one speaker.
 *
 * ## The two shares, and why they differ
 *
 * They were one number (0.62) until the Pi mark was looked at beside the silhouette it
 * shares a slot with: a framework icon's box is not its ink. A person glyph draws
 * between 4 and 20 of its 24 units, so at 62% of the circle its *ink* is
 * 0.62 × 16/24 = **41%** of the diameter; `ic_pi_mark.xml`'s viewport **is** the
 * glyph's bounding box — the path runs 29.9→67.5 in x and 40→77.6 in y of a 37.6-unit
 * viewport — so the same 62% drew an ink twice the silhouette's and the agent's avatar
 * read as a logo with a ring of colour around it rather than as a mark in a circle.
 *
 * [AVATAR_MARK_SHARE] is therefore set to the silhouette's *ink* (0.46 × 34dp = about
 * 16dp of a 34dp circle) rather than to its box. It is still the heavier of the two at
 * equal ink, because the mark is a solid blocky figure where the silhouette is an
 * outline of one, which is what keeps the two speakers tellable apart at a glance.
 */
@Composable
private fun ChatAvatar(
    color: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    imagePath: String? = null,
    mark: Boolean = false,
    size: Dp = 28.dp,
) {
    // The ink is decided by the circle's own luminance rather than by the theme: the
    // colour is the reader's choice and may be any hue at any lightness, so the only
    // thing that can be known about it is how bright it is.
    val ink = if (color.luminance() > 0.5f) Color.Black else Color.White
    val picture = rememberAvatarBitmap(imagePath)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        when {
            picture != null -> Image(
                bitmap = picture,
                contentDescription = null,
                // Crop rather than fit: a portrait photo in a circle should fill it.
                // `Alignment.Center` is the crop's own anchor, so the middle of the
                // picture is what survives.
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            label != null -> Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = ink,
                maxLines = 1,
            )

            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(size * AVATAR_GLYPH_SHARE),
            )

            mark -> Icon(
                painter = painterResource(R.drawable.ic_pi_mark),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(size * AVATAR_MARK_SHARE),
            )
        }
    }
}

private const val AVATAR_GLYPH_SHARE = 0.62f
private const val AVATAR_MARK_SHARE = 0.46f

/**
 * The custom avatar picture at [path], decoded once and downscaled to avatar size.
 *
 * ## Why not an image loader
 *
 * PiKit has no image-loading dependency, and this is the one picture in the app: a
 * `BitmapFactory` decode off the main thread in a `produceState` is smaller than the
 * dependency would be, and it is the same file the reader picked rather than a cache
 * with its own eviction to reason about.
 *
 * The decode is **downscaled** on the way in. The file is whatever the user picked —
 * a 12-megapixel photo is 48 MB as an `ARGB_8888` bitmap, drawn into a 34dp circle —
 * so `inSampleSize` is solved from the file's own bounds until the longest side is
 * near [AVATAR_DECODE_PX]. The bounds pass reads the header only, which is why it is
 * two `decodeFile` calls rather than one.
 *
 * A path that no longer resolves (the file was deleted, the export was restored
 * without it) decodes to null, and the avatar falls back to the label or the mark
 * exactly as if no picture had been chosen — a broken reference costs the picture and
 * nothing else.
 */
@Composable
private fun rememberAvatarBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (
                    bounds.outWidth / (sample * 2) >= AVATAR_DECODE_PX &&
                    bounds.outHeight / (sample * 2) >= AVATAR_DECODE_PX
                ) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }.value
}

/** The longest side a decoded avatar is allowed to be, in pixels. 34dp at 4x, rounded up. */
private const val AVATAR_DECODE_PX = 160

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
         * False for the step replies in the middle of a turn, and false for every
         * assistant row while the turn is still streaming — including the one
         * `finalReplyKey` currently names, which is the newest *finished* fragment
         * and not yet the answer. Nothing reads it but the assistant row, so a
         * prompt (which always wears both) and a row that draws no meta row at all
         * — a tool card, a notice — are left at its default. A tool-using turn is
         * several assistant messages — "let me look", a tool card, "here it is" —
         * and the sentence in the middle is a fragment of the answer rather than an
         * answer, so a copy glyph and a time under each of them made one answer read
         * as three. The row that keeps both is the turn's own
         * [pi.kit.mob.pi.ChatTurn.finalReplyKey], once the turn has settled (or the
         * agent stopped), which is what folding already collapses a finished turn to:
         * a collapsed turn and an expanded one then agree about which message is the
         * answer.
         *
         * See [MessageMeta] for what that costs.
         */
        val showMeta: Boolean = true,
        /**
         * The turn this row belongs to, or [UNGROUPED] for a row that belongs to none.
         *
         * Read by [transcriptCards] and by nothing else, and it is what makes the
         * cards exact: the rule "a user row opens a card and everything else joins
         * the card it is already in" would put the answer of a turn that began
         * *without* a prompt — an `agent_start` with no user message, or the leading
         * assistant greeting a restored session opens with — inside the previous
         * turn's card. Grouping by the turn the row is attributed to is the same
         * attribution the fold already computes, so the two cannot disagree about
         * where one turn ends and the next begins.
         *
         * The default is for the rows a caller builds by hand (the tests) and for the
         * transcript with no turns at all, where every row is its own card anyway.
         */
        val turn: Int = UNGROUPED,
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

/** A row that belongs to no turn, or to turns that have not been recorded. */
internal const val UNGROUPED = -1

/** The turn a row belongs to, or [UNGROUPED]. */
internal fun turnOfRow(row: TranscriptRow): Int = when (row) {
    is TranscriptRow.Message -> row.turn
    is TranscriptRow.TurnSummary -> row.turnIndex
}

/**
 * One card of the transcript.
 *
 * ## Why the transcript is cards and not a column of rows
 *
 * The design language's rule for this page is that a **card** is one subject with
 * its own body, and that the transcript is the one place a card per turn is right —
 * "because a turn is a subject". So the card is the turn, and the three things a
 * turn is made of — the question, the steps the agent took, and the answer — are
 * inside it rather than floating in the list:
 *
 *  - [Prompt] is the reader's own message. One row, one card, and it is the *narrow*
 *    one: an answer is a body of text laid out as a document, so its card is the
 *    transcript's full measure, while a prompt is a sentence, and a sentence drawn
 *    inside a full-width slab is the 86%-wide bar the reader already complained
 *    about once — arriving from the other direction.
 *  - [Answer] is everything one turn produced: the fold band, the reasoning, the
 *    tool calls, the notices and the answer, in one frame. A tool-using turn is
 *    five or six rows, and drawing each of them as its own surface is what makes a
 *    turn read as a stack of unrelated slabs rather than as one reply.
 *
 * The two are the same rounded rectangle in the same surface step, and what
 * separates them is width and position rather than a second colour: the question
 * and its answer are two halves of one exchange, and giving either its own palette
 * step would say they are two kinds of thing.
 *
 * ## Why its key is the turn and not the first row
 *
 * A card's rows are recomputed on every streamed token, and the *first* row of a
 * running turn changes as its steps arrive: the assistant row that carried nothing
 * but a tool call is dropped by [drawsNothing] until it has text, so a card keyed
 * by its first row would change identity once per step — which a `LazyColumn` reads
 * as a different item, disposing and rebuilding the state the reader had opened
 * inside it. The turn's own index is stable for the whole life of the turn, so the
 * key is derived from that wherever there is one.
 */
internal sealed interface TranscriptCard {
    val key: String
    val rows: List<TranscriptRow>

    /** The reader's own message: one card, one row. */
    data class Prompt(val row: TranscriptRow.Message) : TranscriptCard {
        override val key: String get() = row.key
        override val rows: List<TranscriptRow> get() = listOf(row)
    }

    /** Everything one turn produced, inside one frame. */
    data class Answer(override val rows: List<TranscriptRow>, val turn: Int) : TranscriptCard {
        override val key: String
            get() = if (turn >= 0) "turn-$turn-answer" else rows.first().key
    }
}

/**
 * Groups the transcript's rows into the cards they are drawn in.
 *
 * Two rules, and they are the whole of it: **a user row opens a prompt card**, and
 * **a row whose turn differs from the row before it opens a new answer card**. The
 * second is what stops two turns from sharing a frame when a turn began without a
 * prompt, and it is the reason the turn is carried on the row at all.
 *
 * Pure and top-level so the rule can be pinned on the JVM: what it decides is where
 * one frame is drawn and the next begins, which is invisible in a screenshot of a
 * conversation that happens to be one card tall.
 */
internal fun transcriptCards(rows: kotlin.collections.List<TranscriptRow>): kotlin.collections.List<TranscriptCard> {
    val cards = ArrayList<TranscriptCard>(rows.size)
    val group = ArrayList<TranscriptRow>()
    var groupTurn = UNGROUPED

    fun flush() {
        if (group.isEmpty()) return
        cards += TranscriptCard.Answer(group.toList(), groupTurn)
        group.clear()
    }

    rows.forEach { row ->
        val message = row as? TranscriptRow.Message
        if (message?.item is ChatItem.User) {
            flush()
            cards += TranscriptCard.Prompt(message)
            return@forEach
        }
        val turn = turnOfRow(row)
        if (group.isNotEmpty() && turn != groupTurn) flush()
        groupTurn = turn
        group += row
    }
    flush()
    return cards
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
    //
    // The key moves *while* a turn is still open: `message_end` records the newest
    // text-bearing message so a fold is right even if `agent_settled` never arrives,
    // which is also what let an intermediate step wear the furniture the moment it
    // finished and before the next one started. A reply therefore counts only once
    // the turn is closed, or once the agent is no longer streaming (a stop, a killed
    // process) — "输出完成后或者停止后" is when the last answer is known.
    val finalReplies = state.turns.mapNotNullTo(HashSet()) { it.finalReplyKey }
    val turnSettled = { record: ChatTurn? -> record == null || record.isComplete || !state.isStreaming }

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
            // A prompt always, a reply only when the turn has settled on it — not
            // while the turn is still streaming, when `finalReplyKey` names the
            // newest finished fragment rather than the answer. A message attributed
            // to no turn at all — the leading assistant greeting a resumed session
            // can open with (`replaceWithMessages` deliberately leaves those outside
            // every turn) — is nobody's step, so it keeps the row: with one assistant
            // message and no question, dropping it would leave no way to copy the
            // only thing on the page.
            showMeta = item !is ChatItem.Assistant || turn < 0 ||
                (item.key in finalReplies && turnSettled(record)),
            // Which card this row is drawn in. See [TranscriptCard].
            turn = turn,
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
 * How many lines of a failure a notice shows.
 *
 * Three: enough for the reason plus a stack frame or two, short enough that the
 * notice stays one strip. pi's stderr is a process dump measured at well over a
 * screen, and the Terminal tab shows it in full.
 */
private const val NOTICE_LINES = 3

/**
 * The first [NOTICE_LINES] lines of a process dump, marked as cut.
 *
 * The notice's own style carries no line limit — a notice wraps rather than
 * ellipsising, which is right for a sentence and wrong for stderr — so the bound is
 * applied to the text. The ellipsis is what keeps a truncated stack
 * from reading as the whole failure.
 */
private fun noticeLines(message: String): String {
    val lines = message.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.size <= NOTICE_LINES) return message
    return lines.take(NOTICE_LINES).joinToString("\n") + "\u2026"
}

/** The agent's state, in one line. Never blank: the subtitle slot is always there. */
private fun agentSubtitle(agent: AgentStatus, text: Strings): String = when (agent) {
    is AgentStatus.Running -> text.chat.agentReady
    is AgentStatus.Starting -> text.chat.agentStarting
    // The failure message is pi's own stderr and can be several lines; the first
    // one names the cause, the rest is a stack.
    is AgentStatus.Failed -> agent.message.lineSequence().first()
    AgentStatus.Stopped -> text.chat.agentStopped
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
 *
 * A name and a derived title are the same slot and no longer different styles: the
 * top bar owns that decision now, where before this page styled its own title and
 * drew a derived one (`titleMedium`) smaller than a session's name (`titleLarge`).
 * The distinction the two facts above still make is *what* is shown, not how big it
 * is: a name is a name, and the app's own name on an empty conversation is a proper
 * noun.
 */
private fun conversationTitle(state: ConversationState): String {
    state.sessionName?.takeIf { it.isNotBlank() }?.let { return it }
    // The reader's words, not the machinery pi travels with them: a prompt sent with a
    // file starts with the address header ("These files are ready; read them by path:")
    // and one sent with a picture carries an `[Image: …]` note, and either would
    // otherwise become the page's title verbatim. [parsePromptExtras] is the same
    // parser the bubble's extras chip uses, so the title and the chip cannot disagree
    // about what the prose is.
    val prose = state.items
        .asSequence()
        .filterIsInstance<ChatItem.User>()
        .map { parsePromptExtras(it.text).displayText }
        .firstOrNull { it.isNotBlank() }
    return if (prose == null) APP_NAME else SessionMetadata.asTitle(prose)
}

/** The application's name, as it is written wherever a proper noun is wanted. */
private const val APP_NAME = "PiKit"

/**
 * The two avatars the transcript draws over its cards, or null when they are off.
 *
 * A null label (and no image) is the built-in mark rather than a word: the agent's is
 * the app's own Pi logo and the reader's is the person silhouette, which is why both
 * labels may be null and why the two fallbacks are different figures. The colours are
 * the two `AvatarColor` seeds the personalization page offers, and an image is a file
 * path the settings store holds.
 */
private data class ChatAvatars(
    val aiLabel: String?,
    val aiImage: String?,
    val aiColor: Color,
    val userLabel: String?,
    val userImage: String?,
    val userColor: Color,
)

/**
 * The avatar's drawn size and the air between it and the card under it.
 *
 * 34dp, up from 26. The avatar is a *name* for the speaker, and at 26dp a
 * two-character label was legible but the figure was not — the transcript is read at
 * arm's length, and the mark has to be tellable from the other speaker's at a glance
 * rather than by looking for it. 34dp is the size the agent's mark is drawn at in the
 * process row (`PiAgentMark`'s 28dp is inside a container; this is the whole badge),
 * and it clears the answer card's own 16dp inset without competing with the text's
 * first line.
 *
 * The 8dp gap is the inside-module gap — it belongs to the card's own top edge, not
 * to the transcript's `rowGap`, which is the space between two turns.
 */
private val AVATAR_SIZE = 34.dp
private val AVATAR_GAP = 8.dp

/**
 * One card of the transcript, drawn: the prompt, or the answer it produced.
 *
 * ## Two widths, and that is the whole of the difference between them
 *
 * A **prompt** is sized to what the reader wrote and sits at the *end* edge. The
 * card is a Miuix [Card], whose own `Box` passes its minimum constraint through
 * (`propagateMinConstraints`), so under a loose `0..max` it settles at its content's
 * width; the cap and the side come from a `BoxWithConstraints` around it, and there
 * is deliberately no `fillMaxWidth` anywhere inside it — `fillMaxWidth` sets the
 * *minimum*, so one on the card or on a child of it is what turns "ok" into a
 * full-width slab. That is the same defect this page has already been through once —
 * a two-word prompt drawn as an 86%-wide bar — and the shape of the fix is that the
 * *parent* leaves the minimum at zero and the cap sits on the parent. (The component
 * changed from a `Surface` to a Miuix `Card`; the measurement and the trap did not.)
 *
 * `MessageMeta` used to carry a `fillMaxWidth` of its own, which was the same defect
 * one level down while the row was drawn *inside* the frame: the meta row's minimum
 * became the card's maximum, so every prompt *was* a full-width card, in the reader's
 * own words but with no side to read. The row is drawn outside the frame now and asks
 * for no width at all — the column that holds it decides the side — so the defect
 * cannot come back from there.
 *
 * An **answer** is a body of text laid out as a document, so its card is the
 * transcript's full measure: `fillMaxWidth` on that card means "the column is the
 * measure", which is a statement about the answer rather than about its content.
 *
 * The two cards are the same rounded rectangle and differ in surface step, width and
 * side: a question and its answer are two halves of one exchange, and the reader's
 * own words take `secondaryContainer` at the end edge — a container the page's own
 * controls also wear (the composer's chips, the jump-to-latest button), so the
 * question reads as *the reader's* half by the colour a control on this page already
 * uses rather than by a role nothing else on the page has. The side and the width cap do
 * the rest, and they are what the "heading of a section" treatment could not replace:
 * a question that is not distinguishable from its answer at a glance makes the
 * transcript unreadable at speed.
 *
 * ## Each card's own furniture is drawn under it
 *
 * A card holds what was *said*; the time and the copy glyph are drawn under the frame
 * by the card view itself, mirrored to the side the message sits on. See [MessageMeta].
 *
 * ## The rhythm inside a card is the rhythm between cards
 *
 * Every row is spaced by `rowGap` — the same pure function the list used between
 * two bare rows, and the same three numbers, so nothing about the transcript's
 * vertical rhythm changed when the frames arrived. What changed is where the rows
 * are drawn: the modules of a turn (a fold band, a thought, a call, its answer, a
 * notice) are now inside one card instead of loose in the list.
 */
@Composable
private fun TranscriptCardView(
    card: TranscriptCard,
    text: Strings,
    expandedTurns: Set<Int>,
    onToggleTurn: (Int) -> Unit,
    avatars: ChatAvatars?,
    onShowExtras: (PromptExtras) -> Unit,
) {
    when (card) {
        // The prompt is measured against the transcript's own width so the cap can
        // be a share of it: a dp width is not knowable before the constraint, and
        // `widthIn(max = …)` needs a dp. `contentAlignment = CenterEnd` is what
        // puts the card against the end edge without a second full-width child —
        // the inner `Box` holds the cap, the card holds the content.
        is TranscriptCard.Prompt -> BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.widthIn(max = maxWidth * PROMPT_MAX_SHARE)) {
                Column(horizontalAlignment = Alignment.End) {
                    // The reader's own mark, over the bubble and on the reader's
                    // side — the two things that say who is speaking without a name
                    // line. See [ChatAvatar]; a null label draws the silhouette and a
                    // stored picture replaces both.
                    avatars?.let { marks ->
                        ChatAvatar(
                            color = marks.userColor,
                            label = marks.userLabel,
                            icon = MiuixIcons.ContactsCircle,
                            imagePath = marks.userImage,
                            size = AVATAR_SIZE,
                            modifier = Modifier.padding(bottom = AVATAR_GAP),
                        )
                    }
                    Card(
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = PROMPT_CARD_PADDING,
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.secondaryContainer,
                            contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        TranscriptCardRow(
                            row = card.row,
                            text = text,
                            expandedTurns = expandedTurns,
                            onToggleTurn = onToggleTurn,
                            onShowExtras = onShowExtras,
                        )
                    }
                    // Outside the card, and that is the rule for both sides: the time
                    // and the copy glyph are furniture *under* a message rather than
                    // part of what was said, and drawn inside the fill they read as a
                    // second line of the message. [MessageMeta] has the placement and
                    // the mirroring between the two sides.
                    MessageMeta(item = card.row.item, text = text, alignment = Alignment.End)
                }
            }
        }

        is TranscriptCard.Answer -> Column(Modifier.fillMaxWidth()) {
            // The agent's mark, over the turn's card at the start edge. One per turn
            // rather than per row: the card *is* the turn, and the folded steps inside
            // it are the same speaker.
            avatars?.let { marks ->
                ChatAvatar(
                    color = marks.aiColor,
                    label = marks.aiLabel,
                    imagePath = marks.aiImage,
                    // The agent's fallback is the app's own mark, so the speaker
                    // answering is named by the product's logo rather than by two
                    // letters standing in for one.
                    mark = true,
                    size = AVATAR_SIZE,
                    modifier = Modifier.padding(bottom = AVATAR_GAP),
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = CardDefaults.CornerRadius,
                insideMargin = PaddingValues(ANSWER_CARD_INSET),
                // The answer's card is the palette's standard surface step — one step
                // off the page behind it — because it is *the page's* content rather
                // than a bubble, and the reader's own half is the one that wears a
                // container.
                colors = CardDefaults.defaultColors(),
            ) {
                card.rows.forEachIndexed { index, row ->
                    Column(Modifier.padding(top = rowGap(card.rows.getOrNull(index - 1), row))) {
                        TranscriptCardRow(
                            row = row,
                            text = text,
                            expandedTurns = expandedTurns,
                            onToggleTurn = onToggleTurn,
                            onShowExtras = onShowExtras,
                        )
                    }
                }
            }
            // The turn's own answer is the one row that wears the pair (see
            // `TranscriptRow.Message.showMeta`), so the card asks which row that is and
            // draws it once, under the frame, mirrored against the prompt's row.
            metaItemOf(card.rows)?.let { answered ->
                MessageMeta(item = answered, text = text, alignment = Alignment.Start)
            }
        }
    }
}

/**
 * The row of an answer card that carries the time and the copy button, or null.
 *
 * The last such row rather than the first: a turn's intermediate steps are fragments
 * and `showMeta` is false for them, so there is normally exactly one — and taking the
 * last is what makes a turn whose final reply is still streaming fall back to nothing
 * rather than to the step above it.
 */
private fun metaItemOf(rows: List<TranscriptRow>): ChatItem? = rows
    .lastOrNull { it is TranscriptRow.Message && it.showMeta && it.item is ChatItem.Assistant }
    ?.let { (it as TranscriptRow.Message).item }

/**
 * The most of the transcript's width a prompt card may take.
 *
 * A share rather than a dp value so the cap survives a narrower phone and a tablet's
 * capped column: 0.86 leaves a visible strip of the page down the start edge, which is
 * what makes the *side* readable as a side rather than as a card that happens to be
 * short. It is measured against the transcript's own width — the row the card is laid
 * out in — so on a wide window the cap is a share of the prose measure and not of the
 * screen.
 */
private const val PROMPT_MAX_SHARE = 0.86f

/**
 * The prompt card's inset.
 *
 * Tighter than the answer card's own 16dp inset, which is sized for a paragraph: a
 * prompt is a sentence or two, and a frame wider than its content is a box with a
 * word in it.
 * 14dp is still more than the copy glyph's own invisible margin, which is what the
 * meta row is pulled out by at its foot.
 */
private val PROMPT_CARD_PADDING = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

/**
 * The answer card's own inset, on every side.
 *
 * [Card]'s default inside margin is zero, so the card states its own; and
 * [MAX_TRANSCRIPT_WIDTH] has to add it back, because the transcript's cap is a
 * statement about the *prose* measure and the card's frame is around that measure
 * rather than out of it.
 */
private val ANSWER_CARD_INSET = 16.dp

/** One row of a card, whichever kind it is. */
@Composable
private fun TranscriptCardRow(
    row: TranscriptRow,
    text: Strings,
    expandedTurns: Set<Int>,
    onToggleTurn: (Int) -> Unit,
    onShowExtras: (PromptExtras) -> Unit,
) {
    when (row) {
        is TranscriptRow.Message -> when (val item = row.item) {
            is ChatItem.User -> PromptBody(item, text, onShowExtras)

            is ChatItem.Assistant ->
                AssistantBlock(item, text, row.showReasoning)

            is ChatItem.Tool -> ToolCallRow(item = item, text = text)

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

// ------------------------------------------------------------------ prompt extras

/**
 * What a prompt carries besides the words the reader typed.
 *
 * File paths and the `[Image: …]` notes pi stamps on a picture are *machinery*:
 * they travel with the prompt so the model can open the file or map coordinates,
 * and they are not prose the reader wrote. They used to sit in the bubble as
 * plain text — the paths as a trailing list, the image note as a bracketed
 * English sentence in the middle of the message — which is the report that the
 * bubble is "a wall of paths". Parsed out here, drawn as one chip, and shown in
 * full only when that chip is tapped.
 */
internal data class PromptExtras(
    val displayText: String,
    val filePaths: List<String> = emptyList(),
    val imageNotes: List<String> = emptyList(),
) {
    val hasExtras: Boolean get() = filePaths.isNotEmpty() || imageNotes.isNotEmpty()
}

/**
 * `[Image: original 1200x2670, displayed at 899x2000. Multiply coordinates by …]`
 * and its cousins. pi writes these in English whatever the interface language;
 * the `图像`/`画像` alternates are there so a note that was translated does not
 * leak into the bubble either.
 */
private val IMAGE_NOTE_REGEX = Regex("""\[(?:Image|图像|画像)[^\]]*\]""")

/** One attachment path as the prompt prints it: an absolute path with no spaces. */
private val ABSOLUTE_PATH_REGEX = Regex("""^/\S+$""")

/**
 * The heading each catalog puts above a file attachment's paths.
 *
 * Matched by the three strings the app itself writes, not by a shape: a reader
 * who typed a sentence ending in a colon and a path on the next line would
 * otherwise lose that sentence to the parser. A session restored in another
 * language still matches, because all three are checked.
 */
private val ADDRESS_HEADERS = setOf(
    "以下文件已就绪，请按路径读取：",
    "These files are ready; read them by path:",
    "次のファイルは準備できました。パスで読んでください：",
)

/**
 * Splits a stored prompt into the sentence the reader wrote and the machinery
 * around it.
 *
 * Image notes are removed wherever they sit. The file-path block is the app's
 * own `addressHeader` followed by one absolute path per line — at the front of
 * the prompt now, at the end of one written before that change — and the header
 * goes with the paths, so the display text is only what the reader typed.
 */
internal fun parsePromptExtras(raw: String): PromptExtras {
    if (raw.isBlank()) return PromptExtras("")

    val imageNotes = mutableListOf<String>()
    val withoutNotes = IMAGE_NOTE_REGEX.replace(raw) { match ->
        imageNotes += match.value.trim()
        ""
    }
        // The notes leave holes; collapse the blank runs they opened so the
        // display text does not grow a ragged edge around every picture.
        .replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")

    val lines = withoutNotes.lines()
    val remove = BooleanArray(lines.size)
    val filePaths = mutableListOf<String>()

    var i = 0
    while (i < lines.size) {
        if (lines[i].trim() in ADDRESS_HEADERS) {
            remove[i] = true
            var j = i + 1
            while (j < lines.size && ABSOLUTE_PATH_REGEX.matches(lines[j].trim())) {
                filePaths += lines[j].trim()
                remove[j] = true
                j++
            }
            // One blank line on each side of the block is the separator, not
            // the reader's paragraph spacing.
            if (j < lines.size && lines[j].isBlank()) remove[j] = true
            if (i > 0 && lines[i - 1].isBlank()) remove[i - 1] = true
            i = j
        } else {
            i++
        }
    }

    val displayText = lines.filterIndexed { index, _ -> !remove[index] }
        .joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    return PromptExtras(displayText, filePaths, imageNotes)
}

/**
 * One prompt from the reader, inside its card.
 *
 * ## The end edge, which came back
 *
 * It was a `primaryContainer` bubble against the end edge; it became a quiet card at
 * the *start* edge on the answer's own surface step, on the argument that the
 * transcript is a document rather than a conversation between two people. The start
 * edge lost that argument: with the side, the fill and the width cap all removed, a
 * question and its answer were one surface step and two cards of nearly the same
 * width, so they were told apart only by reading them. So the card is sized to its own
 * content and sits at the *end* edge again, on `secondaryContainer` — the container the
 * page's own controls wear — and the two lines that side costs (a `BoxWithConstraints`
 * for the cap, a `widthIn` to hold it) are paid in [TranscriptCardView], not here.
 *
 * ## What identifies it
 *
 * Three things, and none of them is a name line: it is the only card sized to its
 * content, it opens the turn, and it carries the reader's own furniture — the image
 * count and the extras chip, which nothing the model says has.
 *
 * ## Why the column has no `fillMaxWidth`
 *
 * The card is a Miuix [Card], which measures its content with `propagateMinConstraints`,
 * so anything asking for the full width inside it makes the *card* full width: a
 * one-word prompt would be a slab, arriving from the other side. Each child takes the
 * width it needs and wraps at the card's maximum, which is the transcript's measure.
 */
@Composable
private fun PromptBody(
    item: ChatItem.User,
    text: Strings,
    onShowExtras: (PromptExtras) -> Unit,
) {
    // The prompt is the last thing the reader wrote; what follows it is the other
    // speaker. The space *between* the two cards is `rowGap`'s job — it is the only
    // place that knows whether an answer, a fold band or a tool card comes next.
    val extras = remember(item.key, item.text) { parsePromptExtras(item.text) }
    Column {
        // The machinery first: file paths and image notes are one chip above the
        // words, the same position the image-count chip has always held. Tapping it
        // opens the full paths and the `[Image: …]` notes in a sheet — neither
        // belongs in the sentence the reader typed.
        if (extras.hasExtras) {
            PromptExtrasChip(
                extras = extras,
                text = text,
                onClick = { onShowExtras(extras) },
            )
        }
        if (item.imageCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    top = if (extras.hasExtras) 6.dp else 0.dp,
                    bottom = if (extras.displayText.isBlank()) 0.dp else 6.dp,
                ),
            ) {
                Icon(
                    imageVector = MiuixIcons.Photos,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    // Furniture rather than prose: the count of pictures the reader
                    // attached is a fact about the message, not part of the sentence
                    // they typed, so it takes the muted ink and the words under it
                    // take the card's own.
                    tint = ChatTone.Neutral.ink(),
                )
                Text(
                    text = text.chat.attachmentCount(item.imageCount),
                    modifier = Modifier.padding(start = 4.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = ChatTone.Neutral.ink(),
                )
            }
        }
        if (extras.displayText.isNotBlank()) {
            Text(
                text = extras.displayText,
                modifier = Modifier.padding(
                    top = when {
                        extras.hasExtras || item.imageCount > 0 -> 6.dp
                        else -> 0.dp
                    },
                ),
                // The transcript's reading style, and the same one `MarkdownText`
                // draws an answer in: the question and its answer are one block of
                // text, and a prompt a size smaller than its reply reads as a caption.
                //
                // No colour: the card provides its own content colour
                // (`onSecondaryContainer`), and the reader's own words are the one
                // thing on this page that must never be muted.
                style = MiuixTheme.textStyles.paragraph,
            )
        }
    }
}

/**
 * The chip that replaces a prompt's file-path list and its image notes.
 *
 * A 14dp glyph and a small label, like the image count beside it, and the chevron is
 * what says this one opens something. The label is a count rather than a title
 * (`2 files · 1 image note`) because that is the fact the reader wants before
 * deciding whether to look inside.
 *
 * ## Why it is a pill on a step of its own
 *
 * This is the one control inside a prompt: tapping it opens the paths and the image
 * notes in a sheet, so it is an action and it takes the fully round shape — the
 * page's shape for an action, the same one the composer's send button wears. Its
 * fill is the palette's own *variant* of the container it sits on: the prompt card is
 * `secondaryContainer`, and drawing the chip in the card's own colour would be a
 * control the reader has to be told about. The height is 28dp — the transcript's own
 * pill, the size the tool card's status uses — so the row of machinery above a prompt
 * cannot change the card's height; and it is a minimum, because the app's text-size
 * setting draws the label larger and a fixed height would clip it.
 */
@Composable
private fun PromptExtrasChip(
    extras: PromptExtras,
    text: Strings,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            // A minimum: the label grows with the app's text-size setting rather than
            // being clipped by a fixed height.
            .heightIn(min = 28.dp)
            // Clip, then click: the press indication is drawn by the node that owns
            // the gesture, so the clip has to be outside it.
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                onClickLabel = text.chat.extrasOpen,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(percent = 50),
        color = MiuixTheme.colorScheme.secondaryContainerVariant,
        contentColor = MiuixTheme.colorScheme.onSecondaryContainerVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 6.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.File,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = text.chat.extrasCount(extras.filePaths.size, extras.imageNotes.size),
                modifier = Modifier.padding(start = 4.dp),
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(16.dp),
            )
        }
    }
}

/** The gutter every sheet body's rows and headings stand on. */
private val SHEET_GUTTER = 16.dp

/** A sheet's group heading on that gutter, with the air a heading needs above it. */
private val SHEET_HEADING_INSET = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)

/**
 * The sheet a [PromptExtrasChip] opens: every path and every note, in full.
 *
 * The two headings are Miuix's [SmallTitle] — the library's own name for a group
 * inside a page: small, secondary, and deliberately quiet, so a heading is never
 * mistaken for something tappable. The rows under them are the sheet's body: a plain
 * scrolling column rather than a lazy list, because a prompt's machinery is a handful
 * of paths and the sheet's own window already bounds the height.
 */
@Composable
private fun PromptExtrasSheet(extras: PromptExtras, text: Strings) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // Inside the scroll, not outside it: the inset is scrolled past rather than shown
            // as a band of the panel's own fill under the text.
            .padding(bottom = sheetBottomInset()),
    ) {
        if (extras.filePaths.isNotEmpty()) {
            // `SmallTitle` measures its own text with 28dp on each side, which puts the
            // heading 12dp right of the rows it names; the sheet's own gutter is 16dp and
            // the heading takes it.
            SmallTitle(
                text = text.chat.extrasFiles,
                insideMargin = SHEET_HEADING_INSET,
            )
            extras.filePaths.forEach { path ->
                Text(
                    text = path,
                    style = MiuixTheme.textStyles.footnote1,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = SHEET_GUTTER, vertical = 2.dp),
                )
            }
        }
        if (extras.imageNotes.isNotEmpty()) {
            SmallTitle(
                text = text.chat.extrasNotes,
                insideMargin = SHEET_HEADING_INSET,
            )
            extras.imageNotes.forEach { note ->
                Text(
                    text = note,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = SHEET_GUTTER, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * What a message carries under it: when it was sent, and a copy button.
 *
 * ## Where the row is drawn
 *
 * **Under the message's card, not inside it** — and mirrored between the two sides,
 * which is what [alignment] decides. It is the arrangement the page had before the
 * cards arrived: a prompt is `[time][copy]` at its end edge, an answer is
 * `[copy][time]` at its start edge, so the glyph is always the outer mark and the
 * time is always inboard. Drawn *inside* the fill it read as a second line of the
 * message — the report "时间和复制按钮应该显示在气泡外而非里面" — because the pair sat on the
 * same colour as the words above it and inherited none of the air a footnote needs.
 * Nothing about the row changed except its parent: the card is still the item, and
 * the row is still the card's own furniture.
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
 * The button is 28dp rather than Miuix's own 40dp icon-button minimum, and that is a
 * deliberate exception: it sits *under* a message, next to text that is not a
 * control, and a 40dp disc there would be a second bubble.
 *
 * ## Which edge, and why the row is pulled out by the glyph's inset
 *
 * The pull's *direction* is the mirror: the glyph sits at the row's outer edge on
 * both sides — right for a prompt, left for an answer — so the row moves out by
 * [CopyButtonInkInset] toward that edge and the same magnitude serves both. See the
 * inline note where `pull` is computed for the measurement behind it.
 *
 * ## Why the row is the accent colour
 *
 * The glyph and the timestamp are both the app's accent. The reader's report — "把工作几秒、
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
    /** Which edge of the transcript the message this row belongs to sits on. */
    alignment: Alignment.Horizontal,
) {
    if (item is ChatItem.Assistant && item.isStreaming) return
    if (item is ChatItem.Assistant && item.text.isBlank()) return

    val accent = ChatTone.Accent.ink()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    val stamp = remember(item.createdAt, now) {
        if (item.createdAt > 0L) text.chat.messageTime(item.createdAt, now) else null
    }

    // The row is aligned by its **ink**, and the button's ink is smaller than its
    // box: an 18dp glyph centred in a 28dp button leaves 5dp of the button on each
    // side the reader cannot see — the report "ai消息回复气泡下面复制按钮最左侧没对齐气泡",
    // measured with `uiautomator dump` at 32px against 46px. [CopyButtonInkInset] is
    // that 5dp, taken from the button's own geometry rather than written down twice.
    //
    // Which side is pulled depends on which side the *button* ends: a left-aligned
    // row starts with it, a right-aligned one ends with it.
    val pull = if (alignment == Alignment.End) CopyButtonInkInset else -CopyButtonInkInset
    // Mirrored order: an answer is [button][time] at the left, a prompt is
    // [time][button] at the right, so the button is always the outer glyph — which is
    // what "copy sits under the bubble's edge" means on either side.
    val timeThenButton = alignment == Alignment.End

    // No width request: the column this row is the last child of decides the side —
    // the prompt's card column is end-aligned, the answer's is the full measure and
    // starts at the start edge — so `fillMaxWidth` here would be the minimum-width trap
    // the card above documents, and the row would sit on the wrong edge if the parent
    // ever changed its alignment.
    Row(
        modifier = Modifier
            .offset(x = pull)
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (timeThenButton && stamp != null) {
            Text(
                text = stamp,
                modifier = Modifier.padding(end = 2.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = accent,
                maxLines = 1,
            )
        }
        CopyButton(
            onClick = { scope.launch { clipboard.setClipEntry(clipEntryFor(messageSource(item))) } },
            contentDescription = text.chat.copyMessage,
            tint = accent,
        )
        if (!timeThenButton && stamp != null) {
            Text(
                text = stamp,
                modifier = Modifier.padding(start = 2.dp),
                style = MiuixTheme.textStyles.footnote2,
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
 * The row is stacked, and `rowGap` treats it as **one** row however many of the
 * three parts are present — which is why the parts are not separated by the
 * transcript's own gap numbers. Everything *inside* here belongs to the same
 * message: the reasoning control opens a block that belongs to the answer under it,
 * so the distances here are one step smaller than the gaps between modules and are
 * written down as constants with the pair they separate in mind
 * ([REASONING_ANSWER_GAP], and the 6dp the block itself opens with). That is
 * unchanged by the card this row is drawn in: the card is the *turn's* frame and
 * this is one module of it, so the gap between two modules and the gap between the
 * parts of one module are still two different numbers on purpose.
 *
 * ## Why there is no live cursor any more
 *
 * This row used to draw a 2dp caret — an alpha tween, then a 2 Hz blink — for as
 * long as an answer was streaming and there was nothing to show yet. It is gone at
 * the reader's request ("对话页输出时不要那个闪烁光标"), and the reasoning row is
 * what replaces it as a progress signal: its label carries a character count that
 * moves as the model thinks (`思考 · 1.2k 字`), and it is on screen for the whole of
 * the wait. Nothing is lost with the cursor — the row that draws nothing is dropped
 * by `drawsNothing()` rather than holding a blank line open, and the two live
 * signals this page does have are the agent's mark in the header and the composer's
 * stop button.
 *
 * A *static* bar was the other candidate and is worse than nothing here: it is the
 * same caret with the only thing that made it read as a caret taken away, sitting
 * on a page whose whole point is that it does not decorate bare rows.
 */
@Composable
private fun AssistantBlock(
    item: ChatItem.Assistant,
    text: Strings,
    showReasoning: Boolean = true,
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

    // The expanded block is below the toggle, so a tap near the foot of the
    // viewport opens a panel that is entirely under the fold — the reader could
    // see that something opened (everything below moved) and then had to scroll
    // to find out what. `bringIntoView` on the block itself is the scroll, and
    // it is the block rather than the toggle that is asked for: the toggle was
    // already on screen, which is what made the tap possible.
    val reasoningBringIntoView = remember { BringIntoViewRequester() }

    // `isStreaming` is deliberately not part of this any more. It was, because the
    // row drew a live cursor for as long as the answer was on its way and was
    // therefore never empty; with the cursor removed (see below) a streaming row
    // with nothing in it would compose to a blank `Column` — and the list would
    // still measure it and pay its `rowGap`. Same test as `drawsNothing`, which is
    // what the row filter uses before this is ever called.
    val hasBody = item.thinking.isNotBlank() || item.text.isNotBlank() || item.error != null
    if (!hasBody) return

    // No `fillMaxWidth`: this is a module *of* a card, and the card's own width is
    // the transcript's measure. A `fillMaxWidth` here is harmless in this position
    // (the card already fills the column) but it is the same call that turns a
    // content-sized frame into a slab where the parent is a wrapped surface, so the
    // rule on this page is that only the card asks for a width.
    Column {
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
                Box(Modifier.bringIntoViewRequester(reasoningBringIntoView)) {
                    ReasoningBlock(item.thinking)
                }
                LaunchedEffect(showThinking) {
                    reasoningBringIntoView.bringIntoView()
                }
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
            // No `fillMaxWidth`: `MarkdownText` is a `BoxWithConstraints` over a
            // `Column` that fills its own width, so it takes the card's measure
            // without being asked — and asking would be the one `fillMaxWidth` that
            // could travel back out of the card, which passes its minimum
            // constraint through (`propagateMinConstraints`).
            MarkdownText(item.text)
        }

        item.error?.let {
            Text(
                text = it,
                color = PiTone.error(),
                style = MiuixTheme.textStyles.footnote1,
            )
        }
    }
}

/** How many lines the field grows to before it scrolls inside itself. */
private const val COMPOSER_MAX_LINES = 8

/**
 * The field's own inset: 14dp beside the text and 12dp above and below it.
 *
 * Miuix's default is 16dp on both axes, which is a settings field's height; a
 * composer's field is written in, not filled in, and the smaller vertical inset is
 * what keeps a one-line draft a field rather than a box.
 */
private val COMPOSER_FIELD_INSET = DpSize(14.dp, 12.dp)

/**
 * The composer: the page's bottom band, with the field inside it.
 *
 * ## A band, not a panel
 *
 * It was a panel for a release: a rounded surface floating over the transcript on an
 * ambient shadow, with a margin on each side. What that bought was a clear "this is in
 * front of the list" — and what it cost was the field's own shape. With the panel
 * explaining where the message goes, the field needed no edge, so it had none, and the
 * reader's report is that they wanted it back ("输入框形状改回 0.4.1 的样子，取代现在的悬浮
 * 样式"). So the composer is the strip the app had before the panel: a full-bleed band
 * in the page's column, under the transcript rather than over it, on the band's own
 * `surfaceContainer` so the foot of the page is one band of chrome; and the field
 * inside it is Miuix's own [TextField] — a filled box on `secondaryContainer`, its
 * placeholder carried by the library's label slot, its focused border the palette's
 * `primary`.
 *
 * Nothing about the *contents* changed. The four chips stay where they were, and they
 * still read as the settings the next message goes with rather than as part of the
 * message, because a chip between the field and the keyboard sits in the busiest strip
 * on a phone screen.
 *
 * ## The three rows, and one control
 *
 * ```
 *  (🧠 中) (🤖 packy…) (⤓ 上下文…) (⚡ 缓存…)   the settings this message goes with
 *  ╭────────────────────────────────────╮
 *  │ Ask Pi…                            │   the field, growing upward
 *  ╰────────────────────────────────────╯
 *   ( ⌘ )                              (+) (→)   the row a thumb reaches
 * ```
 *
 * The four controls are above the field rather than below it: a chip between the
 * field and the keyboard sits in the busiest strip on a phone screen and reads as
 * part of the message being written, where a chip above the field reads as the
 * setting the next message will be sent with. They are [ControlChip]s — a pill on the
 * palette's high surface step, drawn with a Miuix `Surface` so the press feedback and
 * the clip are the library's — and each one composes its own [WindowListPopup], which
 * is what anchors the menu to the chip that opened it. They scroll horizontally once
 * there are four of them, so a long profile name cannot squeeze the last one off
 * screen.
 *
 * The agent's **state** is not one of them: it is a fact rather than a control, and
 * it lives in the header. Nor is the *live* tone — live means a turn is running, and
 * nothing in a composer is.
 *
 * The action row is under the field: Miuix's field owns its whole box, so there is no
 * last line of it to sit on, and a row under it is where a thumb already is when the
 * keyboard is up — the field grows *upward* out of the row rather than pushing it
 * down the screen.
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
    /** Which of the four menus is open, or null. Each chip asks whether it is the one. */
    openSheet: ComposerSheet?,
    onOpenSheet: (ComposerSheet) -> Unit,
    onCloseSheet: () -> Unit,
    onRunCommand: (BuiltInCommand) -> Unit,
    onPickAttachment: (String) -> Unit,
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

    // Read here rather than taken as a parameter: it is only this card's own
    // dismissal that needs it, and the page's `focusManager` is the same object
    // either way — a `LocalFocusManager` is one per window.
    val fieldFocus = LocalFocusManager.current

    // Whether the field currently holds the focus, which is what tells a *first*
    // tap on it apart from a second one. See the field's pointer handler: the first
    // tap has to be allowed to focus the field and raise the keyboard, and the second
    // has to put away the selection toolbar the first one made possible.
    var fieldFocused by remember { mutableStateOf(false) }

    // The band: full bleed, in the page's column, on the same `surfaceContainer` the
    // tab strip and the root behind it are painted in, so the foot of the page is one
    // band of chrome from the transcript to the navigation bar rather than a box
    // floating over the list with a seam above the bar. There is no shadow and no
    // hairline: the fill is a step away from the page's own `surface`, which is the
    // boundary — a drawn line here would compete with the transcript's own dividers
    // (§7.1 has the measurement that settled that).
    Surface(
        color = MiuixTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
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
            // Horizontally scrollable so four chips whose values can each be long
            // stay on one line: the alternative is squeezing them, which turns the
            // last one into `缓存命…`. A `LazyRow` rather than a `Row` with
            // `horizontalScroll`, because a plain scrollable `Row` under a
            // `fillMaxWidth` measures its children against the *screen*'s width and
            // then draws the overflow past the edge — measured, the cache chip came
            // out at x=966..1048 and was sliced by the window rather than scrolled
            // to, which looks like a layout bug and is not scrollable either. The
            // content padding is the same 12dp as the band's other insets, so the
            // first chip stands where the field below it starts.
            //
            // The fades are what say "there is more". Content padding alone was not
            // enough: on a phone the fourth chip is cut at the screen edge with nothing
            // between it and the edge to say the row continues, and a half-visible pill
            // at a hard edge reads as a layout fault rather than as a scroll. Each fade
            // is drawn in the band's own colour, and the draw pass reads the row's own
            // scroll state, so one appears exactly while there is content behind it.
            // It covers no touch: a `draw` pass takes no pointer events, so the chip
            // under a fade is still tappable where it shows.
            val chipState = rememberLazyListState()
            // The band's own colour, read in composition: `MiuixTheme.colorScheme` is
            // a composable read and a `DrawScope` is not one, and the fade has to end
            // on exactly the colour behind the row or it is a visible rectangle.
            val bandColor = MiuixTheme.colorScheme.surfaceContainer
            LazyRow(
                state = chipState,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        drawContent()
                        val fade = CHIP_FADE_WIDTH.toPx()
                        val clear = bandColor.copy(alpha = 0f)
                        // Each edge fades only while there is content behind it, so a
                        // row that fits has no gradient at all and one scrolled to its
                        // end keeps only the leading fade.
                        if (chipState.canScrollBackward) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(bandColor, clear),
                                    startX = 0f,
                                    endX = fade,
                                ),
                                topLeft = Offset.Zero,
                                size = Size(fade, size.height),
                            )
                        }
                        if (chipState.canScrollForward) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(clear, bandColor),
                                    startX = size.width - fade,
                                    endX = size.width,
                                ),
                                topLeft = Offset(size.width - fade, 0f),
                                size = Size(fade, size.height),
                            )
                        }
                    },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    ControlChip(
                        glyph = painterResource(R.drawable.ic_thinking),
                        // The persisted level, not pi's report alone. pi reports it
                        // only once `get_state` has answered, so a label built from
                        // `state.thinkingLevel` alone was empty for the whole of the
                        // agent's start-up and the chip then grew by the width of its
                        // own text — measured from the code, 37dp of glyph and padding
                        // became ~89dp with "Medium". The same value the menu ticks
                        // and the settings row shows.
                        label = thinkingLevelOf(state, saved),
                        onClick = { onOpenSheet(ComposerSheet.Thinking) },
                    ) {
                        WindowListPopup(
                            show = openSheet == ComposerSheet.Thinking,
                            onDismissRequest = onCloseSheet,
                        ) {
                            ThinkingMenu(
                                show = openSheet == ComposerSheet.Thinking,
                                session = session,
                                onDismiss = onCloseSheet,
                            )
                        }
                    }
                }
                item {
                    ControlChip(
                        // The app's own model mark — the same stack of layers the
                        // settings row and the profile's model list use. It was
                        // `Hub`, and before that the `SmartToy` face: a hub's three
                        // spokes at 14dp read as a five-pointed star, and a robot
                        // head is a picture of an assistant rather than of *which*
                        // model is about to answer. See `ic_model.xml`.
                        glyph = painterResource(R.drawable.ic_model),
                        // The model that will answer, not the profile's name: this
                        // is the "which model" control, and once one provider can
                        // hold several models the profile name stops saying which
                        // one is about to reply.
                        //
                        // The profile's choice wins over pi's report, and the store
                        // is collected rather than read: it is what the menu's
                        // tick shows and what the next launch will use, so a change
                        // made on the settings page appears here at once instead of
                        // waiting for the agent to come back up. Before either has
                        // answered, the chip says so rather than showing a glyph on
                        // its own — a chip that grows its label later is a row that
                        // reflows under the reader's thumb.
                        //
                        // pi's own report for "nothing is configured" is the id
                        // `unknown` under the provider `unknown`; a reader who has
                        // just installed the app reads that identifier as a fault,
                        // so the chip answers with the translated loading sentence
                        // instead of pi's identifier.
                        label = config.activeProfile?.modelId?.takeIf { it.isNotBlank() }
                            ?: state.model?.takeIf { it.isUsable }?.id?.takeIf { it.isNotBlank() }
                            ?: text.chat.loadingModel,
                        onClick = { onOpenSheet(ComposerSheet.Model) },
                    ) {
                        WindowListPopup(
                            show = openSheet == ComposerSheet.Model,
                            // Two dozen models in one menu: bounded, so the panel
                            // stays a menu rather than covering the conversation it
                            // is asking about.
                            maxHeight = MENU_MAX_HEIGHT,
                            onDismissRequest = onCloseSheet,
                        ) {
                            ModelMenu(
                                show = openSheet == ComposerSheet.Model,
                                session = session,
                                text = text,
                                onDismiss = onCloseSheet,
                            )
                        }
                    }
                }
                item {
                    ControlChip(
                        glyph = MiuixIcons.Layers,
                        label = contextPercentLabel(state, text),
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
                        glyph = MiuixIcons.Stopwatch,
                        label = text.chat.cacheHit(cacheHitPercent(state)),
                        onClick = { onOpenSheet(ComposerSheet.Context) },
                    )
                }
            }

            // The field, Miuix's own. Its label slot is the placeholder: the band's
            // `Surface` still provides the content colour, and `useLabelAsPlaceholder`
            // makes the label sit inline until the reader types rather than float up
            // over the first line of a growing message.
            //
            // The pointer handler is the one thing the field cannot do for itself:
            // dismissing the selection toolbar its own long-press raises — the
            // 粘贴 / 全选 strip.
            //
            // The report was: long-press the input for the paste button, tap the input
            // again, and the button stays. The field consumes a tap on its own bounds —
            // that is how it moves its caret — so a handler on a parent never sees it.
            //
            // ## Why this is a tap detector and not a per-event `clearFocus`
            //
            // It was, and that is the report "现在打开app点击输入框无反应，无法输入文字".
            // Clearing on *every* pointer event includes the down event of the tap that
            // focuses the field, so the sequence for a first tap was: the field takes the
            // focus from the down event, this handler clears it at the end of the same
            // pass, and the field is left unfocused — no caret, no keyboard, and every
            // subsequent tap repeating it. Confirmed from the emulator's accessibility
            // dump rather than from the code: after `input tap` on the field, no node in
            // the window had `focused="true"` and there was no `InputMethod` window at
            // all.
            //
            // So the clear happens after a *completed tap*, and only when the field
            // already had the focus when the finger went down. The first tap therefore
            // focuses and stops; a second one dismisses. Asking `awaitFirstDown` for the
            // `Initial` pass is what makes "already had" true without a frame of lag —
            // the initial pass runs before the field's own gesture detector, so
            // `fieldFocused` still holds the state from before this gesture began. The
            // tap has to be told from a long press, which is the selection gesture: it
            // is dismissed by this handler only if it lasted less than the long-press
            // timeout.
            //
            // A hand-rolled tap rather than `detectTapGestures`, which owns its
            // `awaitPointerEventScope` for the life of the modifier and so offers no point
            // at which a caller can read the field's focus *before* the tap that is about
            // to change it. `awaitEachGesture` is the loop that does: one scope per
            // gesture, and `waitForUpOrCancellation` ends it on the lift. `clickable` is
            // the other candidate and is wrong for a different reason: it would give the
            // field a click semantics node, and this is a region, not a button.
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // Watched, never consumed — the event still travels on to the
                            // field, which is what places the caret and raises the
                            // keyboard.
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val hadFocus = fieldFocused
                            // One gesture, one scope: the down is read on the Initial
                            // pass (before the field's own detector), and the gesture
                            // ends here on its lift. `down.pressed` is the value it had
                            // at the down event and never changes, so a loop on it never
                            // ends — the handler this replaced did exactly that, which
                            // left the clear below unreachable.
                            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            // A *tap*, not a long press. A long press is the gesture that
                            // raises the selection, and dismissing the field on its lift
                            // closed the toolbar the same press had just opened — text
                            // selection in the composer would stop working the moment
                            // this handler started working. A gesture shorter than the
                            // long-press timeout is a tap; anything else is left alone.
                            val tapped = up != null &&
                                up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                            if (hadFocus && tapped) fieldFocus.clearFocus()
                        }
                    },
                enabled = inputEnabled,
                // One short line: the hint that explained the `!` convention wrapped
                // to two lines in the field and looked like a rendering fault.
                label = placeholder,
                useLabelAsPlaceholder = true,
                maxLines = COMPOSER_MAX_LINES,
                insideMargin = COMPOSER_FIELD_INSET,
                textStyle = MiuixTheme.textStyles.body1,
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The command list, at the field's *left* corner, mirrored by the
                // send button at its right, and sharing the `+`'s own style —
                // three plain icon buttons on one line, with the space between the
                // first and the other two held by a weighted spacer so the layout does
                // not depend on how wide the field's text is.
                //
                // It is the same control as the `+` beside it, deliberately. It *was*
                // a filled circle carrying the `/` character, and that was wrong twice
                // over: the character was a description of the list rather than of the
                // button — a reader who has not opened it does not know what a slash
                // means — and a filled 40dp disc opposite a 40dp plain button made two
                // controls that do the same *kind* of thing look like two different
                // kinds.
                //
                // `ic_commands.xml` and **not** a terminal glyph, which is the bottom
                // tab strip's terminal tab: a terminal glyph six centimetres above it
                // in the composer read as a second way into the terminal tab rather
                // than as the command list. It is also no longer `</>`, which is what
                // this was for two rounds and is the report "目前 </> 没有正常显示" — that
                // glyph is drawn as an open *stroked* polyline whose stroke width lands
                // on zero, so it measured, reported a node to a screen reader, and
                // painted nothing. See `ic_commands.xml` for the read of the artefact
                // and the replacement geometry.
                PopupAnchor {
                    IconButton(
                        onClick = { onOpenSheet(ComposerSheet.Commands) },
                        enabled = enabled,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_commands),
                            contentDescription = text.chat.commands,
                            // 17dp, against the 23dp of the `+` beside it, and that is a
                            // property of the glyph rather than of the button: Lucide's
                            // `command` draws its loops out to 21 of its 24 units, where
                            // a plus keeps its ink inside 14 — so the same size reads as
                            // a much bigger mark, which is the report "常用命令图标 太大了".
                            // Scaled here, at the call site: the vector itself is the
                            // library's and is not edited (see `ic_commands.xml`).
                            modifier = Modifier.size(17.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    WindowListPopup(
                        show = openSheet == ComposerSheet.Commands,
                        maxHeight = MENU_MAX_HEIGHT,
                        onDismissRequest = onCloseSheet,
                    ) {
                        CommandMenu(
                            show = openSheet == ComposerSheet.Commands,
                            text = text,
                            onRun = onRunCommand,
                            onShell = { onOpenSheet(ComposerSheet.Shell) },
                            onDismiss = onCloseSheet,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                PopupAnchor {
                    IconButton(
                        onClick = { onOpenSheet(ComposerSheet.Attach) },
                        enabled = enabled,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = text.chat.attachTitle,
                            modifier = Modifier.size(23.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    WindowListPopup(
                        show = openSheet == ComposerSheet.Attach,
                        onDismissRequest = onCloseSheet,
                    ) {
                        AttachMenu(
                            show = openSheet == ComposerSheet.Attach,
                            text = text,
                            onPick = onPickAttachment,
                            onDismiss = onCloseSheet,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                // The page's one action, and the only fully round control on it:
                // Miuix's icon button is a 40dp pill by default, which is exactly the
                // shape this control is saying. The stop state is the same control with
                // the same shape and the error pair — the reader is looking for one
                // button there, not two.
                if (isStreaming) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.size(40.dp),
                        backgroundColor = MiuixTheme.colorScheme.errorContainer,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Pause,
                            contentDescription = text.chat.stop,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onErrorContainer,
                        )
                    }
                } else {
                    val armed = enabled && (value.isNotBlank() || attachments.isNotEmpty())
                    IconButton(
                        onClick = onSend,
                        enabled = armed,
                        modifier = Modifier.size(40.dp),
                        backgroundColor = if (armed) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.surfaceContainerHigh
                        },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Send,
                            contentDescription = text.chat.send,
                            modifier = Modifier.size(20.dp),
                            tint = if (armed) {
                                MiuixTheme.colorScheme.onPrimary
                            } else {
                                MiuixTheme.colorScheme.disabledOnSurface
                            },
                        )
                    }
                }
            }
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
 * 600dp is the small/medium window boundary Android's own size classes use, and the
 * cap is set from the prose rather than from the screen: 40 Han characters — the
 * limit WCAG's visual-presentation criterion puts on a line — is 40 em, which is
 * 680sp at the reading style's 17sp. [MAX_TRANSCRIPT_WIDTH] stays at the 560dp it was
 * measured at, which is now *inside* that limit rather than exactly on it (about 33
 * Han characters a line), because a transcript is read at speed and the narrower
 * measure costs nothing on a phone. See the note at the transcript's own
 * `BoxWithConstraints`.
 *
 * The cap is the **prose's** measure, so the answer card's own 16dp inset on each
 * side is added to it rather than taken out of it: a column capped at 560dp draws an
 * answer that is 528dp of text. Capping the *column* so that the text lands on 560dp
 * keeps one number doing one job, and the prompt card (sized to its content) is
 * inside the same measure either way.
 */
private val WIDE_TRANSCRIPT_WINDOW = 600.dp
private val MAX_TRANSCRIPT_WIDTH = 560.dp + ANSWER_CARD_INSET * 2

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
 * True for a row whose composable would return without drawing anything.
 *
 * Only an assistant row can: [AssistantBlock] draws nothing when a message has no
 * text, no reasoning and no error — which is exactly the row pi opens for the
 * message that carried a tool call and nothing else.
 *
 * `isStreaming` used to be excluded, because a streaming row drew the live cursor
 * and was therefore never empty. The cursor is gone — see [AssistantBlock] — so a
 * row with nothing in it draws nothing whether or not it is streaming, and the
 * transcript no longer pays 8dp of `MODULE_GAP` for a blank row while the first
 * token is on its way.
 *
 * It is asked *before* the rows are grouped into cards, which is what keeps a card
 * from being measured for a row that draws nothing: a dropped row is not part of any
 * card, so it costs neither its own height nor the gap in front of it.
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
 * How much blank transcript sits under the last line of an answer.
 *
 * One line of the reading style's line box: Miuix's `paragraph` is 17sp on a 26sp
 * leading, so this is 26dp and no longer the 24dp the old 14sp body style was
 * measured at — the number means one line of whatever style the transcript is drawn
 * in, and the style changed. What it separates is unchanged: the last line of an
 * answer from the composer's edge, so the two do not touch.
 */
private val TRANSCRIPT_TAIL_SPACE = 26.dp

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
 * One of the composer's four small controls: a pill that opens a menu or a sheet.
 *
 * ## Why this is a Surface and not a component of its own
 *
 * The chip this used to be was hand-built, and almost every line of it was spent
 * working out its own *colour*: a 34dp pill on the high surface step looked washed
 * out on the composer's own bar — a 2% difference, measured — and the fix was to step
 * the light palette one further down while stepping the dark one nowhere, because the
 * same move would have put the chip at `#1A2028` on a `#171C23` bar and erased it.
 * That is a luminance test at the call site to answer a question the palette should
 * answer.
 *
 * Miuix's palette already answers it: the band is `surfaceContainer`, so one step up
 * is `surfaceContainerHigh` — visible in both schemes without asking the colour
 * anything. The pill is a Miuix [Surface] at a full round, so the clip, the fill and
 * the press feedback are the library's, and its height is 32dp — the composer's own
 * control height, which is a size larger than the transcript's 28dp pills on purpose:
 * a tool row is read at a glance, while these four are the settings the next message
 * goes with. It is a minimum, so the label grows with the reader's text size.
 *
 * ## Why the chip has no separate accessibility name
 *
 * The chip this replaced took a `contentDescription` and never used it, and the row
 * has no slot for one — which is the right answer rather than a gap: the pill is a
 * `clickable`, so it merges its own label, and the label is what the control *says*
 * (`high`, `packyapi-custom`, `缓存命中 0%`). A description on that node would replace
 * the value the reader needs with the noun they can already infer from where the chip
 * is.
 *
 * [menu] is the popup the chip opens, composed *here* rather than at the page: Miuix
 * anchors a [WindowListPopup] to its own parent's bounds, so the popup has to be a
 * sibling of the pill for the menu to open where the finger tapped — and that is also
 * what lets the `/model` command row open the model menu at the model chip.
 */
@Composable
private fun ControlChip(
    glyph: ImageVector,
    label: String,
    onClick: () -> Unit,
    menu: @Composable () -> Unit = {},
) {
    ControlChipBase(label = label, onClick = onClick, menu = menu) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            modifier = Modifier.size(CHIP_GLYPH),
        )
    }
}

/** The same chip, for a glyph that is a drawable resource rather than a vector. */
@Composable
private fun ControlChip(
    glyph: Painter,
    label: String,
    onClick: () -> Unit,
    menu: @Composable () -> Unit = {},
) {
    ControlChipBase(label = label, onClick = onClick, menu = menu) {
        Icon(
            painter = glyph,
            contentDescription = null,
            modifier = Modifier.size(CHIP_GLYPH),
        )
    }
}

@Composable
private fun ControlChipBase(
    label: String,
    onClick: () -> Unit,
    menu: @Composable () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Box {
        Surface(
            // Clip, then click: Miuix's press indication is drawn by the node that
            // owns the gesture, so a `clickable` outside the shape would tint a
            // rectangle behind a pill.
            modifier = Modifier
                .heightIn(min = CHIP_HEIGHT)
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(percent = 50),
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = CHIP_INSET),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                glyph()
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 4.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        menu()
    }
}

/**
 * How wide the fade at the end of the chip row is.
 *
 * One chip's own side inset plus its glyph — wide enough that a cut pill is visibly
 * going away rather than chopped, short enough not to hide the label of a chip the
 * reader has almost scrolled to.
 */
private val CHIP_FADE_WIDTH = 28.dp

/** The chip's height, its side inset, and the glyph inside it. */
private val CHIP_HEIGHT = 32.dp
private val CHIP_INSET = 10.dp
private val CHIP_GLYPH = 14.dp

/**
 * A box whose only job is to be the bounds a [WindowListPopup] opens against.
 *
 * Miuix's popups are positioned from their *parent's* bounds in the window, so a
 * popup that has to open at a 40dp icon button must be composed as that button's
 * sibling rather than at the page. One place that says so beats a bare `Box` at four
 * call sites.
 */
@Composable
private fun PopupAnchor(content: @Composable () -> Unit) {
    Box { content() }
}

/** The tallest a menu may be before it scrolls: an answer's viewport, not the page. */
private val MENU_MAX_HEIGHT = 320.dp

/**
 * The thinking levels the model in front of us can actually do.
 *
 * The level is read live rather than captured: the menu is over a conversation that
 * is still running, and the tick on it must be the level pi is on *now*.
 */
@Composable
private fun ThinkingMenu(
    show: Boolean,
    session: PiAgentSession,
    onDismiss: () -> Unit,
) {
    val live by session.conversation.collectAsState()
    val saved by session.settingsStore.settings.collectAsState()
    // Live levels once the agent has answered for the model that is running, the
    // remembered ones otherwise — see `thinkingLevelsFor`, and `thinkingLevelOf` for
    // why the saved model id is the fallback key. The menu cannot offer a level the
    // model does not have.
    val available = thinkingLevelsFor(
        live.availableThinkingLevels,
        saved.rememberedThinkingLevels(live.model?.id ?: saved.modelId),
    )
    val current = thinkingLevelOf(live, saved)
    val options = available.takeIf { it.isNotEmpty() } ?: PiLaunchOptions.THINKING_LEVELS
    ListPopupColumn {
        options.forEachIndexed { index, level ->
            DropdownImpl(
                // The id alone. The menu carried each level's sentence under it
                // once, and seven rows of "off — 直接回答，不做思考" made a menu of
                // two-line entries out of seven one-word choices; the ids are the
                // wire strings pi documents, and the picker that explains them is the
                // model page's own rows.
                text = level,
                optionSize = options.size,
                isSelected = level == current,
                index = index,
                onSelectedIndexChange = {
                    if (show) {
                        onDismiss()
                        if (level != current) {
                            // Applied to the running process immediately: pi
                            // accepts the change over RPC, so unlike the provider and
                            // model it needs no restart.
                            session.settingsStore.update { it.copy(thinkingLevel = level) }
                            session.setThinkingLevel(level)
                        }
                    }
                },
            )
        }
    }
}

/**
 * Every model of every profile, one line each, so the menu is the whole configuration
 * rather than one entry per provider.
 *
 * Read live, *inside* the menu: the profile store is state and the menu is drawn from
 * it on every open, so a model changed on the settings page moves the tick too.
 * Reading it from the click handler's closure was the bug — the chip was rebuilt from
 * the current store while the menu still ticked the model from the previous
 * composition, and the two disagreed.
 */
@Composable
private fun ModelMenu(
    show: Boolean,
    session: PiAgentSession,
    text: Strings,
    onDismiss: () -> Unit,
) {
    val config by session.settingsStore.profiles.snapshots.collectAsState()
    val profiles = config.profiles
    val active = config.activeProfile
    val selectedId = active
        ?.takeIf { it.modelId.isNotBlank() }
        ?.let { modelOptionId(it.id, it.modelId) }

    // A menu with no rows is not an empty menu: the popup still measures 200dp wide
    // and `WindowListPopup` still dims the page, so the chip becomes a tap that dims
    // the screen and shows nothing. One disabled row says why there is nothing to pick
    // — the settings page is where a profile is added, and this page cannot open it.
    val hasModels = profiles.any { it.selectableModels.isNotEmpty() }

    ListPopupColumn {
        if (!hasModels) {
            PiMenuRow(
                title = text.chat.noModel,
                onClick = {},
                enabled = false,
            )
        }
        profiles.forEach { profile ->
            val heading = providerLabel(profile, text)
            val hint = modelRowHint(profile, text)
            profile.selectableModels.forEach { model ->
                val selected = modelOptionId(profile.id, model) == selectedId
                PiMenuRow(
                    title = model,
                    // The provider is the line under the model, and the profile's own
                    // name when two profiles share one provider — the same pair the
                    // sheet picker grouped and captioned by.
                    summary = listOfNotNull(heading, hint).joinToString(" · "),
                    // A provider this build does not know cannot be sent to pi at
                    // all, so its rows are shown but not offered.
                    enabled = profile.providerEntry != null,
                    // The model in use is one of a set, so the row says "selected"
                    // to a screen reader instead of "button".
                    selected = selected,
                    trailing = {
                        if (selected) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        if (show && profile.providerEntry != null) {
                            onDismiss()
                            session.selectProfile(profile, model)
                        }
                    },
                )
            }
        }
    }
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
 * full width and a second line — which is why each row is a Miuix [BasicComponent]
 * with the label over the value instead of the library's title-beside-value shape.
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

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // Inside the scroll, for the reason [sheetBottomInset] records.
            .padding(bottom = sheetBottomInset()),
    ) {
        SheetFact(
            label = text.chat.detailModel,
            value = when {
                model == null -> text.chat.loadingModel
                !model.isUsable -> text.chat.noModel
                else -> model.id.ifBlank { model.name }
            },
        )
        SheetFact(
            label = text.chat.detailProvider,
            // `model == null` is the agent still starting; `provider == "unknown"` is
            // pi's own word for "no profile is configured". Both have a translated
            // sentence rather than pi's identifier, which a reader who has not
            // configured anything reads as a fault.
            value = when {
                model == null -> text.chat.loadingModel
                model.provider == "unknown" -> text.chat.noModel
                else -> model.provider.ifBlank { text.settings.none }
            },
        )
        SheetFact(label = text.chat.thinkingLabel, value = thinking)
        SheetFact(
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
        SheetFact(label = text.chat.detailUsed, value = text.chat.tokens(context.total))
        SheetFact(label = text.chat.detailInput, value = text.chat.tokens(context.input))
        SheetFact(label = text.chat.detailOutput, value = text.chat.tokens(context.output))
        SheetFact(label = text.chat.detailCacheRead, value = text.chat.tokens(context.cacheRead))
        SheetFact(label = text.chat.detailCacheWrite, value = text.chat.tokens(context.cacheWrite))
        if (context.reasoning > 0) {
            SheetFact(label = text.chat.detailReasoning, value = text.chat.tokens(context.reasoning))
        }
        SheetFact(label = text.chat.cacheHitLabel, value = text.chat.cacheHit(cacheHitPercent(state)))
        SheetFact(
            label = text.chat.detailCost,
            // `Locale.ROOT` for the digits: the default locale writes a comma decimal in
            // half of Europe, which turns "$0,0123" into a number whose separator is also
            // its digit-group separator. The currency is the provider's own, USD.
            value = if (usage.total.costUsd > 0) {
                String.format(Locale.ROOT, "$%.4f", usage.total.costUsd)
            } else {
                null
            },
        )
        SheetFact(label = text.chat.detailTotal, value = text.chat.tokens(usage.total.total))
        SheetFact(label = text.chat.detailTurns, value = text.chat.turnCount(state.turns.size))
        // The file's *name*: the sheet is 360dp wide and the container path is
        // 48 characters before the name. Which conversation this is, is the
        // question; where it lives on disk is not.
        SheetFact(
            label = text.chat.detailSession,
            value = state.sessionFile?.let { java.io.File(it).name },
        )
    }
}

/**
 * One read-only fact of the context report: Miuix's own row, its title over its summary.
 *
 * The label is the **title** and the value the **summary** — the library's hierarchy
 * rather than this file's. A settings row puts the setting's name in the title and its
 * value under it, and a fact in a report is the same shape; a hand-built column inside
 * `BasicComponent` was the same two lines drawn twice, with a KDoc that claimed the
 * component stacked them the other way (it does not).
 *
 * A null value is a row with a title and no summary, which is how a cost that is not
 * known yet stays a row rather than a blank. The session file name that used to be set
 * in monospace is a summary like any other: the component owns the type in a row, and a
 * second body style for one value is not a distinction this report needs.
 */
@Composable
private fun SheetFact(label: String, value: String?) {
    BasicComponent(title = label, summary = value)
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
 * Each row is a [PiMenuRow] — a two-line row with the id over its description —
 * rather than a `DropdownImpl`: the ids share no mutually exclusive choice, and the
 * description under each one is half of what the row says. It is `PiMenuRow` and not
 * a `BasicComponent` because the library cannot measure one inside a
 * `ListPopupColumn` once it has a start or end slot (see that file's header), and
 * the attach menu beside this one proved it on a phone.
 *
 * ## Why `agentCommands()` is still on the session
 *
 * It is not called from here any more and it is deliberately not deleted: the
 * filter is the *page's* decision, and a future surface that shows the registry —
 * the search page, or a settings list with a warning on the rows that need a
 * terminal — should read it from the session rather than re-implement the RPC.
 */
@Composable
private fun CommandMenu(
    show: Boolean,
    text: Strings,
    onRun: (BuiltInCommand) -> Unit,
    onShell: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Each row dismisses before it acts, the gate every menu row carries: the panel
    // is drawn for the length of its exit and a second tap aimed at the page behind
    // it must not land on a row of a menu that is leaving.
    fun pick(action: () -> Unit) {
        if (show) {
            onDismiss()
            action()
        }
    }

    ListPopupColumn {
        PiMenuRow(
            title = SHELL_SIGIL,
            summary = text.chat.shellCommandHint,
            onClick = { pick(onShell) },
        )
        BUILT_IN_COMMANDS.forEach { command ->
            PiMenuRow(
                title = command.id,
                summary = command.description(text),
                onClick = { pick { onRun(command) } },
            )
        }
    }
}

/**
 * The three things a pending message can carry, as a menu at the `+`.
 *
 * It was a sheet of three rows and it is a dropdown now, for the same reason the
 * command list is: three choices at a toolbar button do not cover the page to ask
 * their question (§7.1). The rows keep their leading marks — a capture frame, a
 * picture, a document — because the three are told apart by what they *are* before
 * they are read. The camera's mark is the capture frame rather than a camera body:
 * Miuix's icon set has no camera, and a frame is the same statement — this row takes
 * a picture — without borrowing a glyph set that draws at another weight.
 *
 * **Camera first**, because it is the one row this control actually adds: the
 * other two hand the job to the system picker, and a photo of a whiteboard is not
 * something the picker can offer at all. The report "拍照应该放在图片上面" is
 * that the row worth opening the control for should not be the third one.
 */
@Composable
private fun AttachMenu(
    show: Boolean,
    text: Strings,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    fun pick(kind: String) {
        if (show) {
            onDismiss()
            onPick(kind)
        }
    }

    ListPopupColumn {
        PiMenuRow(
            title = text.chat.attachCamera,
            summary = text.chat.attachCameraHint,
            startAction = {
                Icon(imageVector = MiuixIcons.Scan, contentDescription = null)
            },
            onClick = { pick("camera") },
        )
        PiMenuRow(
            title = text.chat.attachImage,
            summary = text.chat.attachImageHint,
            startAction = {
                Icon(imageVector = MiuixIcons.Photos, contentDescription = null)
            },
            onClick = { pick("image") },
        )
        PiMenuRow(
            title = text.chat.attachFile,
            summary = text.chat.attachFileHint,
            startAction = {
                Icon(imageVector = MiuixIcons.File, contentDescription = null)
            },
            onClick = { pick("file") },
        )
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

    // 8dp: the dense step for a small information tile — between the 8dp chip and
    // the 12dp panel, which is as far as the shape scale is worth taking for a
    // 64dp square — and the band's own bottom corners have to repeat the number
    // because a clip rounds *all* four, which on a band that crosses the tile's foot
    // would notch the picture in half.
    //
    // The tile's size is fixed on the *Box*, and that is load-bearing rather than
    // tidiness: a `LazyRow` measures its items with an unbounded main axis, so
    // `fillMaxWidth` inside one is a no-op and a weighted child is resolved against
    // a target width of zero. With the size only on the `Surface` the name band ran
    // the item as wide as the file's own name, and the ✕ anchored to the item's end
    // therefore sat past the picture it belongs to.
    Box(Modifier.size(ATTACHMENT_TILE)) {
        Surface(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(ATTACHMENT_CORNER),
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
                        imageVector = MiuixIcons.File,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                        bottomStart = ATTACHMENT_CORNER,
                        bottomEnd = ATTACHMENT_CORNER,
                    ),
                )
                .background(MiuixTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f))
                .height(ATTACHMENT_BAND_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = image.name,
                modifier = Modifier
                    .weight(1f)
                    // The remove target sits over the band's end, so the name stops
                    // where the old segment did and the glyph never covers a letter.
                    .padding(start = 5.dp, end = ATTACHMENT_REMOVE_WIDTH),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A real 40dp target, holding the glyph where the old 26×20dp segment drew it:
        // that segment was the smallest interactive area in the app because the visible
        // ✕ *was* the whole control. The button draws nothing but its press tint, so the
        // picture loses no pixel and the thumb gets a square.
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(ATTACHMENT_REMOVE_TARGET),
            minWidth = ATTACHMENT_REMOVE_TARGET,
            minHeight = ATTACHMENT_REMOVE_TARGET,
            // Nothing is painted behind the glyph, so the only visible thing is the
            // press tint: a disc of it, so the feedback reads as a control on the tile
            // rather than as a rectangular patch of the picture.
            cornerRadius = ATTACHMENT_REMOVE_TARGET / 2,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(end = 6.dp, bottom = 3.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    // The button has no label of its own: the glyph's description is what
                    // a screen reader announces for it.
                    contentDescription = removeLabel,
                    // 14dp, the app's one small-mark size (was 13).
                    modifier = Modifier.size(14.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
 * single-line Miuix [TextField] rather than the composer's growing field: this is a
 * form, not a composer — it exists for one line and then leaves.
 */
@Composable
private fun ShellCommandSheet(
    text: Strings,
    onRun: (String) -> Unit,
) {
    var line by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = sheetBottomInset()),
    ) {
        Text(
            text = text.chat.shellCommandHint,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = SHEET_GUTTER, end = SHEET_GUTTER, bottom = 8.dp),
        )
        TextField(
            value = line,
            onValueChange = { line = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_GUTTER),
            label = text.chat.shellCommandPlaceholder,
            useLabelAsPlaceholder = true,
            singleLine = true,
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
        )
        Button(
            onClick = { onRun(line.trim()) },
            // Refused while the line is blank: `bash` with an empty command is a
            // shell error the user would be reading for a reason they caused.
            enabled = line.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SHEET_GUTTER, end = SHEET_GUTTER, top = 12.dp),
        ) {
            Text(text = text.common.ok)
        }
    }
}

/** One attachment tile's edge — square, as the composer's other tiles are. */
private val ATTACHMENT_TILE = 64.dp

/** The tile's own corner, repeated for the name band's foot. */
private val ATTACHMENT_CORNER = 8.dp

/** The foot band: the file name, and the remove control at its end. */
private val ATTACHMENT_BAND_HEIGHT = 20.dp

/** How much of the band's end the name keeps clear of the remove glyph. */
private val ATTACHMENT_REMOVE_WIDTH = 26.dp

/** The remove control's target: Miuix's smallest icon-button minimum, and its square. */
private val ATTACHMENT_REMOVE_TARGET = 40.dp

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

/**
 * Copies a picked file into a temporary folder and returns a path attachment.
 *
 * The cache directory, not the agent's workspace: an attachment is staging for
 * one message, not project content, and a workspace that collected every
 * document ever mentioned would be a workspace the agent has to wade through.
 * The path is absolute and the agent runs as this app's uid, so the model can
 * open it from wherever it is working. A name that is already taken gets a
 * numeric suffix rather than overwriting: two documents called `report.pdf` in
 * one prompt are two documents.
 *
 * Size is capped the same way an image is, because the copy is a real file on
 * the app's private disk and the alternative is a pick of a 2 GB video filling
 * it. Past the cap the file is not copied and the pick reports as a failure.
 */
private fun copyToTemp(
    context: Context,
    uri: Uri,
    text: Strings,
): PendingImage? = runCatching {
    val declared = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    val size = if (declared >= 0) declared else measure(context, uri)
    if (size <= 0 || size > MAX_IMAGE_BYTES) return@runCatching null
    val name = queryName(context, uri) ?: text.chat.addressFallbackName
    val dir = java.io.File(context.cacheDir, TEMP_ATTACHMENT_DIRECTORY).apply { mkdirs() }
    val target = uniqueChild(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    PendingImage(
        id = uri.toString() + "#" + System.nanoTime(),
        uri = uri,
        name = target.name,
        workspacePath = target.absolutePath,
    )
}.getOrNull()

/** `report.pdf`, then `report-2.pdf`, then `report-3.pdf` — never an overwrite. */
private fun uniqueChild(dir: java.io.File, name: String): java.io.File {
    val direct = java.io.File(dir, name)
    if (!direct.exists()) return direct
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 2
    while (true) {
        val candidate = java.io.File(dir, "$stem-$n$ext")
        if (!candidate.exists()) return candidate
        n++
    }
}

/** Where file attachments land: under the cache dir, which the OS may prune. */
private const val TEMP_ATTACHMENT_DIRECTORY = "pikit-attachments"

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
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
    }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null

    var sample = 1
    while (longest / (sample * 2) >= THUMBNAIL_EDGE) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null
    bitmap.asImageBitmap()
}.getOrNull()
