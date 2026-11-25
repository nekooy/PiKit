package pi.kit.mob.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CoroutineScope
import pi.kit.mob.ui.kit.PiToast
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pi.kit.mob.R
import pi.kit.mob.data.AgentMode
import pi.kit.mob.data.AttachmentStore
import pi.kit.mob.data.ChatPreset
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.PermissionMode
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.RefusedWorkspace
import pi.kit.mob.data.Workspace
import pi.kit.mob.data.WorkspaceRefusal
import pi.kit.mob.data.WorkspaceStore
import pi.kit.mob.data.rememberedThinkingLevels
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.pi.ChatTurn
import pi.kit.mob.pi.ConversationState
import pi.kit.mob.pi.ImageAttachment
import pi.kit.mob.pi.ModelInfo
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.PiLaunchOptions
import pi.kit.mob.pi.PromptExtras
import pi.kit.mob.pi.ReplyStatus
import pi.kit.mob.pi.clampThinkingLevel
import pi.kit.mob.pi.parsePromptExtras
import pi.kit.mob.pi.thinkingLevelsFor
import pi.kit.mob.ui.WorkspacePickerSheet
import pi.kit.mob.ui.chat.ChatPictureCache
import pi.kit.mob.ui.chat.ChatStatusPill
import pi.kit.mob.ui.chat.ChatTone
import pi.kit.mob.ui.chat.FollowTailController
import pi.kit.mob.ui.chat.NoticeCard
import pi.kit.mob.ui.chat.PictureSource
import pi.kit.mob.ui.chat.PictureTarget
import pi.kit.mob.ui.chat.PictureTileFrame
import pi.kit.mob.ui.chat.PictureViewer
import pi.kit.mob.ui.chat.PreparedImage
import pi.kit.mob.ui.chat.SEND_IMAGE_MAX_EDGE
import pi.kit.mob.ui.chat.dimensionNote
import pi.kit.mob.ui.chat.prepareImageForSend
import pi.kit.mob.ui.chat.PromptPictures
import pi.kit.mob.ui.chat.ReasoningBlock
import pi.kit.mob.ui.chat.ReasoningToggle
import pi.kit.mob.ui.chat.ToolCallRow
import pi.kit.mob.ui.chat.TurnSummaryData
import pi.kit.mob.ui.chat.TurnSummaryRow
import pi.kit.mob.ui.chat.rememberFollowTail
import pi.kit.mob.ui.chat.screenRect
import pi.kit.mob.ui.chat.scrollToEnd
import pi.kit.mob.ui.components.chatPresetName
import pi.kit.mob.ui.components.chatPresetSummary
import pi.kit.mob.ui.kit.AvatarSpeaker
import pi.kit.mob.ui.kit.LocalPiBackdrop
import pi.kit.mob.ui.kit.LocalPiHaptics
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiAvatar
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiCustomDialog
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiMenuRow
import pi.kit.mob.ui.kit.PiMenuAnchor
import pi.kit.mob.ui.kit.PiPopupHostProvider
import pi.kit.mob.ui.kit.rememberLastPresent
import pi.kit.mob.ui.kit.PiPopupMenu
import pi.kit.mob.ui.kit.PiPopupPlacement
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.PiGlass
import pi.kit.mob.ui.kit.PiTone
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import pi.kit.mob.ui.kit.piFieldHeightForPlaceholder
import pi.kit.mob.ui.kit.piGlass
import pi.kit.mob.ui.kit.piMenuAnchor
import pi.kit.mob.ui.kit.piOverScroll
import pi.kit.mob.ui.kit.piSheetBottomInset
import pi.kit.mob.ui.kit.rememberPiBackdrop
import pi.kit.mob.ui.kit.rememberPiMenuAnchor
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassFloatingActionButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
import top.yukonga.miuix.kmp.material.GlassColorBlendMode
import top.yukonga.miuix.kmp.material.GlassColorLayer
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 * Everything the chat page owns that has to survive a switch to another destination.
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
 * page: they are modal over content that is not on screen any more, so a page switch is a
 * reasonable way to answer them.
 *
 * `listState` is the transcript's scroll position and the anchor for the follow rule.
 * [ChatScreen] hands it to `rememberFollowTail`, which is remembered per composition — so
 * the scroll offset survives a page switch and the follow rule's own "did the reader scroll
 * away" flag does not. That is the safe half of the pair: a switched-back-to chat page that
 * starts out following the answer is the behaviour the user asked for by looking at the
 * chat tab again.
 */
internal class ChatComposerState {
    /** The draft prompt. */
    var input by mutableStateOf("")

    /** Images and documents picked but not sent. */
    var attachments by mutableStateOf<List<PendingImage>>(emptyList())

    /** The warning strip above the composer, when one is showing. */
    var notice by mutableStateOf<String?>(null)

    /** The transcript's scroll position. */
    val listState = LazyListState()

    /**
     * Whether this conversation's transcript has been placed at its end before its
     * first draw.
     *
     * Per conversation rather than per page, because the page is rebuilt when the
     * reader comes back from the terminal while the list state — and the position the
     * reader left it at — survives. A page-local flag would put a scrolled-up
     * conversation back at its end on every return.
     */
    val transcriptPlaced = mutableStateOf(false)
}

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
 * One name for all nine rather than nine booleans: they are mutually exclusive by
 * construction (each is modal, and each covers the others), and a name per one keeps
 * each surface one comparison away from the control that opens it.
 *
 * The *state* lives in [ChatScreen] and this enum is passed down to the composer,
 * because two of the nine are not the composer's: the context sheet is opened from
 * two chips, and the shell sheet is opened from the command menu. A sheet the
 * composer owned privately would be a second answer to "which one is open" — the
 * question the app's one sheet host used to answer for every screen.
 */
private enum class ComposerSheet {
    Workspace,
    Thinking,
    Model,
    Context,
    ContextRing,
    Commands,
    Attach,
    Shell,
    Permission,
    Preset,
}




@Composable
internal fun ChatScreen(
    session: PiAgentSession,
    agent: AgentStatus,
    state: ConversationState,
    composer: ChatComposerState,
    /**
     * Makes [summary]'s conversation the one on screen — the host opens it as a second
     * process when it is not already live, so this never stops the answer that is
     * running here.
     */
    onOpenConversation: (PiAgentSession.SessionSummary) -> Unit,
    /**
     * Opens an empty conversation beside this one, which keeps running, in this
     * conversation's mode — the mode whose history the drawer lists.
     */
    onNewConversation: () -> Boolean,
    /**
     * Opens an empty conversation in [mode], beside this one, which keeps running.
     *
     * The drawer's mode switch is this and nothing else: the switch used to flip the
     * *current* conversation's mode, which relaunched its process and made the
     * transcript of an idle conversation depend on a control that reads like a
     * filter. A conversation's mode is now changed from the drawer's row menu, and
     * the switch creates the conversation the reader asked for instead.
     */
    onNewConversationInMode: (AgentMode) -> Boolean,
    /**
     * The drawer's own motion — offset, measured width, composition flag.
     *
     * Owned by `RootContent`, above `key(session)`, for the reason [ConversationDrawerState]
     * gives: a conversation switch must not cancel the close that switch started.
     */
    drawer: ConversationDrawerState,
    /** The root's scope: the drawer's settle and a conversation's launch outlive the page. */
    rootScope: CoroutineScope,
    /**
     * The saved conversation whose row is drawn filled.
     *
     * [ConversationState.sessionFile] once the conversation has one; until then, the path
     * the drawer tap asked for ([ChatTab] keeps it). Null is a conversation that was never
     * saved, which has no row to mark.
     */
    currentPath: String?,
    /** The drawer's own scroll position, held by the root so a conversation change keeps it. */
    historyListState: LazyListState,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val text = strings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toasts = LocalPiToast.current
    // The launch mode belongs to the *conversation*, not to the app: opening a work
    // conversation while a chat conversation is on screen has to show work in the
    // drawer's switch, and switching it has to relaunch this conversation rather than
    // open another one. Collected here because both the drawer and the page below it
    // ask the same question.
    val mode by session.mode.collectAsStateWithLifecycle()
    // Owned by `RootContent`, above the page crossfade, so a page switch does not destroy
    // it — see [ChatComposerState] for what that cost and why `rememberSaveable` is not
    // the fix.
    val listState = composer.listState
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    // Composition only depends on the committed answer, not on the moving progress:
    // the panel's own drawing reads `progress` in its graphics layer. Using
    // `progress > 0` here flipped semantics and the drawer's BackHandler on the very
    // last frame of a close, which recomposed the whole drawer exactly as the spring
    // landed — the other half of the end-of-animation hitch.
    val drawerVisible = drawer.open
    val settlePage: (open: Boolean) -> Unit = { open ->
        drawer.settleTo(open, velocity = 0f, scope = rootScope)
        focus.clearFocus()
    }

    // A workspace picked while the agent is answering. Switching starts a new
    // conversation in that folder — the same interruption `newSession` makes, because
    // the workspace is the child process's working directory and pi reads it once at
    // startup — so the same question is asked before it happens.
    var pendingWorkspace by remember { mutableStateOf<String?>(null) }
    // The folder picker's own truth: opened from the workspace menu's last row.
    var showWorkspacePicker by remember { mutableStateOf(false) }
    // The workspace in force, collected rather than read once so the chip follows a
    // switch made from anywhere — this page, the history list, or the settings page's
    // default.
    val currentWorkspace by session.workspace.collectAsStateWithLifecycle()

    // Which of the composer's menus or sheets is open, and the prompt-extras sheet.
    //
    // One state per *kind* rather than a `SheetHost` at the root, because Miuix's
    // menus are anchored to the composable that asks for them: an `OverlayListPopup`
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
    // The picture a tap in the transcript opened, and the row it came from. A value
    // rather than a file, because the row that raised it can be recycled by the list
    // while the viewer is still on screen (see `PictureTarget`).
    var pictures by remember { mutableStateOf<PictureTarget?>(null) }
    // The viewer's own switch, kept beside the value it draws: the window is driven by
    // `show` so its exit can play over the picture, and the target has to outlive the
    // closing animation (see `PictureViewer`).
    var viewerUp by remember { mutableStateOf(false) }
    // The extras sheet keeps its value while it leaves: a Miuix sheet animates out
    // with the content it was showing, so clearing the value on dismissal would play
    // the exit over an empty panel.
    var showPromptExtras by remember { mutableStateOf(false) }

    fun closeSheet() {
        openSheet = null
    }

    // A conversation read from disk names its pictures by key, and the files behind those
    // keys are the app's own cache. A session written before that cache existed — or one
    // whose entries were pruned — would draw the count line for ever, so the session file
    // is read once, off the main thread, and the copies the transcript is asking for are
    // filled in; the tiles redraw when they land (`ChatPictureCache.generation`).
    //
    // Keyed on the file rather than on the items: the wanted set is read once per
    // conversation, and a key that arrives while the reader is sending is already on disk
    // from the send path.
    LaunchedEffect(state.sessionFile) {
        val path = state.sessionFile ?: return@LaunchedEffect
        val wanted = state.items
            .filterIsInstance<ChatItem.User>()
            .flatMap { it.imageKeys }
            .toSet()
        if (wanted.isNotEmpty()) {
            ChatPictureCache.hydrate(context.filesDir, java.io.File(path), wanted)
        }
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
     * Copies picked files into the app's attachment directory and attaches their *paths*.
     *
     * This is the `文件` row: any format at all. The image channel is images
     * only, and a document stuffed into it becomes an attachment pi drops or
     * misreads. The copy lands in `files/attachments` rather than the agent's
     * workspace — an attachment is staging, not project content — and never in
     * the cache, because the path it hands pi is also the path the transcript
     * keeps ([copyToTemp] has both refusals). The prompt names the path when the
     * message is sent, so the model opens it with its own tools.
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
     * Starts a new conversation beside this one.
     *
     * No question and no interruption, which is what having more than one conversation
     * means: the answer that is arriving here keeps arriving, because the new
     * conversation is a second pi process rather than a `new_session` on this one. The
     * old behaviour — a dialog asking whether to throw the answer away — is what the
     * reader asked to be rid of.
     */
    fun newSession() {
        onNewConversation()
    }

    /**
     * Switches this conversation's workspace, asking first when an answer is running.
     *
     * The composer's chip is the control; the switch itself is the session's, because
     * only it knows the agent has to be relaunched in the new directory — see
     * `PiAgentSession.selectWorkspace`. The draft and the attachments are kept: they are
     * the reader's next message, and the workspace is where it will be answered.
     */
    fun selectWorkspace(path: String) {
        if (path == currentWorkspace) return
        if (session.turnInFlight) {
            pendingWorkspace = path
        } else {
            session.selectWorkspace(path)
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

            // Both of these read what pi already knows, and both answer into a toast
            // rather than the transcript: a number and a "copied" are the kind of answer
            // that should not become part of the conversation.
            "/stats" -> session.sessionStats { result ->
                result.onSuccess { report ->
                    toasts?.show(
                        text.chat.statsLine(
                            turns = report.turns,
                            messages = report.messages,
                            inputTokens = report.inputTokens,
                            outputTokens = report.outputTokens,
                            cached = report.cacheReadTokens,
                            cost = report.cost,
                        ),
                    )
                }.onFailure { toasts?.show(it.message ?: text.chat.commandStats) }
            }

            "/copy" -> session.copyLastAnswer { answer ->
                if (answer == null) {
                    toasts?.show(text.chat.answerCopyEmpty)
                } else {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(text.chat.answerCopied, answer))
                    toasts?.show(text.chat.answerCopied)
                }
            }

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
    // `showMeta = false` and never gain its timestamp.
    val rows = remember(state.items, state.turns, state.isStreaming, expandedTurns) {
        transcriptRows(state, expandedTurns)
    }

    // The conversation drawer, and the page it covers.
    //
    // The list is reached three ways: the mark at the bar's leading edge, a rightward
    // drag from anywhere on the conversation, and — coming back from the settings page —
    // already being out.
    //
    // All three drive the *same* number, how far the drawer has been pulled out, so a
    // drag and a tap cannot animate differently, and a drag released short of the
    // threshold springs back from where the thumb left it rather than from a guess.
    // That is also why the swap is not an `AnimatedContent`: a transition has no notion
    // of "follow the finger".
    //
    // The page behind it moves right and shrinks a little, which is what makes the panel
    // read as a layer over the conversation rather than as a second screen: the page it
    // covers is still visibly there, at the edge. `graphicsLayer` for that motion rather
    // than `offset`, because the page is not interactive while the drawer is out — the
    // scrim below takes every touch — so moving what is drawn and not what is touched is
    // exactly right here, and it is the cheap version of the same thing.
    PiPopupHostProvider(base = MiuixTheme.colorScheme.background) {
    Box(
        Modifier
            .fillMaxSize()
            // The ground the drawer and the card are drawn on, and it is the panel's own
            // colour rather than the scaffold's `surface`. The card does not cover the
            // window while it is a card — it shrinks about its centre by [CHAT_SHRINK] —
            // so a strip of this box shows above, below and at its trailing edge, and
            // that strip used to be `surface`: black in the dark scheme beside a
            // `background` panel (#242424), which drew a seam down the panel's edge and a
            // black notch at each corner of the card. One colour behind everything except
            // the card is the arrangement the reference draws: the panel and the ground
            // are one field, and the conversation is the only card on it.
            .background(MiuixTheme.colorScheme.background)
            .onSizeChanged {
                drawer.width = minOf(
                    (it.width * DRAWER_SHARE).roundToInt(),
                    with(density) { DRAWER_MAX_WIDTH.roundToPx() },
                )
            }
            .pageDrag(
                direction = drawer::allowedDirection,
                onStart = {
                    drawer.beginDrag().also { focus.clearFocus() }
                },
                onDrag = drawer::dragBy,
                onRelease = { owner, distance, velocity, minFling ->
                    drawer.releaseDrag(owner, distance, velocity, minFling, rootScope)
                },
                onCancel = { drawer.cancelDrag(it, rootScope) },
            ),
    ) {
        // Retain the warm list offscreen, but don't paint or expose its controls while parked.
        if (drawer.reachable) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(LocalDensity.current) { drawer.width.toDp() })
                    // The panel slides in from the leading edge while the card slides
                    // right: at progress p the panel occupies `[-W + pW, pW]` and the
                    // card `[pW, pW + W]`, so their edges meet on every frame and both
                    // halves of the gesture are visible. A panel that merely sat still
                    // was a reveal, not a slide, and the reference is explicit about
                    // which it is.
                    // A *layer* translation, not a layout offset: the panel holds the
                    // whole conversation list, and `Modifier.offset` re-laid-out all of it
                    // on every frame of the drag. Measured on the emulator, the drawer's
                    // open/close animation was 90-100% janky frames; a graphics layer only
                    // moves what was already rasterised.
                    .graphicsLayer {
                        val progress = drawer.progress
                        translationX = (progress - 1f) * drawer.width
                        alpha = if (progress > 0f) 1f else 0f
                    }
                    .then(if (drawerVisible) Modifier else Modifier.clearAndSetSemantics {})
                    // `background`, not `surface`: the card over this panel is `surface`
                    // and the dark scheme's `surface` is black, so a surface panel made
                    // the two halves of the screen one colour. `background` is #242424
                    // there and #F7F7F7 in the light scheme, a step from the #F0F0F0
                    // card, so the panel is one field and the page is still the object
                    // on it.
                    // The box this panel is drawn in paints the same role, so the strips
                    // of ground around the card belong to the same field: the panel is
                    // not a second colour, it is where that field is widest.
                    .background(MiuixTheme.colorScheme.background),
            ) {
                SessionsScreen(
                    session = session,
                    listState = historyListState,
                    onBack = { settlePage(false) },
                    onOpened = { settlePage(false) },
                    onOpenConversation = onOpenConversation,
                    onOpenSettings = onOpenSettings,
                    mode = mode,
                    onNewConversationInMode = { wanted ->
                        onNewConversationInMode(wanted).also { created ->
                            // The new conversation is on screen behind the drawer, so the
                            // drawer closes exactly as a tapped row does. A refusal (the
                            // pool is full) keeps the panel out, where the toast that
                            // explains it can be read.
                            if (created) settlePage(false)
                        }
                    },
                    currentPath = currentPath,
                    open = drawer.open,
                    drawerVisible = drawerVisible,
                )
            }
        }

        // The card, over the panel.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // The card travels exactly the drawer's width, so its leading edge
                    // lands on the panel's: the reveal is the same distance the page
                    // moved, which is what makes the pair read as one gesture.
                    val progress = drawer.progress
                    translationX = drawer.offset
                    val scale = 1f - CHAT_SHRINK * progress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    // Rounded only while it is a card. At rest the page is the window,
                    // and a corner radius there would cut the screen. `GlassShape` rather
                    // than `RoundedCornerShape`: the card's corner during the drag is the
                    // same continuous curve as the cards it uncovers.
                    shape = GlassShape(PAGE_CARD_CORNER)
                    clip = progress > 0f
                }
                // After the layer, so the fill travels with the card. Before it, the
                // background is painted at the layer's *untranslated* position: measured
                // mid-drag, the page's own surface covered the panel the card had just
                // uncovered, and the drawer was in the tree and invisible.
                //
                // And opaque, which it has to be: the page's top and bottom are gradients
                // into `surface`, so with the panel behind them those gradients would
                // dissolve into the conversation list instead of into the page.
                .background(MiuixTheme.colorScheme.surface),
        ) {
            ChatPage(
                session = session,
                agent = agent,
                state = state,
                text = text,
                rows = rows,
                listState = listState,
                transcriptPlaced = composer.transcriptPlaced,
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
                onOpenPictures = { target ->
                    pictures = target
                    viewerUp = true
                },
                historyOpen = drawerVisible,
                onOpenSessions = { settlePage(true) },
                onNewSession = ::newSession,
                mode = mode,
                currentWorkspace = currentWorkspace,
                onSelectWorkspace = ::selectWorkspace,
                onOpenWorkspacePicker = { showWorkspacePicker = true },
                onSend = {
                    val prompt = composer.input
                    val images = composer.attachments
                    composer.input = ""
                    composer.attachments = emptyList()
                    // Sending is the end of the sentence, so the keyboard goes with it.
                    // The field kept the focus after a send — the text was cleared but
                    // the IME stayed up over a transcript the reader had just asked to
                    // see more of, and the page below it stayed shortened by the
                    // keyboard's inset. One `clearFocus` puts both away; the reader who
                    // wants to send a second line taps the field again, which is the
                    // same tap that puts the keyboard up in the first place.
                    focus.clearFocus()
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
                                    // The one moment the bytes are certain: the row pi
                                    // echoes back is drawn from this copy, and the copy
                                    // is named by the hash of the same base64 that is
                                    // about to be sent (`ChatPictures.kt`).
                                    .onEach { ChatPictureCache.store(context.filesDir, it.attachment) }
                            }
                            // A picture this app had to fit is a smaller raster than the
                            // file the reader picked, so the model is told the ratio in the
                            // wording pi uses — the note the machinery chip already knows
                            // how to read. An untouched picture needs none: what it sees is
                            // the original pixels.
                            val notes = payload.mapNotNull { dimensionNote(it.prepared) }
                            val promptWithNotes = buildString {
                                append(pathNote)
                                if (notes.isNotEmpty()) {
                                    append("\n\n")
                                    append(notes.joinToString("\n"))
                                }
                            }
                            session.sendPrompt(promptWithNotes, payload.map { it.attachment })
                        }
                    }
                },
                onOpenTerminal = onOpenTerminal,
                onOpenFiles = onOpenFiles,
                overlay = {
                    // The same question for a workspace switch: pi's working directory is fixed at
                    // launch, so the switch is a relaunch, and a relaunch ends the answer in flight.
                    PiConfirm(
                        show = pendingWorkspace != null,
                        title = text.sessions.switchInterruptTitle,
                        text = text.sessions.switchInterruptBody,
                        confirm = text.sessions.switchInterruptConfirm,
                        dismiss = text.common.cancel,
                        destructive = true,
                        onConfirm = {
                            val target = pendingWorkspace
                            pendingWorkspace = null
                            if (target != null) {
                                session.selectWorkspace(target, interrupt = true)
                                composer.notice = null
                            }
                        },
                        onDismiss = { pendingWorkspace = null },
                    )

                    // The two report sheets and the prompt's extras, in Miuix's own window sheets.
                    // They are drawn here rather than inside the band that opens them: a sheet is a
                    // window of its own, and one composed inside the composer would be measured
                    // against the band's bounds instead of the screen's.
                    //
                    // The context report is **not** one of them any more. It was a bottom sheet of
                    // fourteen two-line rows — a page-sized panel for a question the reader asks by
                    // glancing at a number — while every other menu on this page is a dropdown at its
                    // own chip. It is a dropdown now, for the reason §7.1 gives: a menu that answers
                    // one question belongs at the control that asks it.
                    //
                    // The extras sheet is closed by `showPromptExtras` rather than by clearing the
                    // value, so the panel still has its own content for the length of its exit.
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

                    // A prompt's pictures, full screen. The window is composed while it has a target and
                    // driven by `show`: a window that animates out is composed while it leaves, and the
                    // target is what it draws for the length of that exit — the same contract every Miuix
                    // window in this app has (§13).
                    pictures?.let { target ->
                        PictureViewer(
                            show = viewerUp,
                            target = target,
                            text = text,
                            onDismiss = { viewerUp = false },
                            onDismissFinished = { pictures = null },
                        )
                    }

                    // The folder picker. Its own sheet rather than a row of the workspace menu: a menu
                    // answers "which one", and this asks "where is the one that is not in the list yet".
                    PiSheet(
                        show = showWorkspacePicker,
                        title = text.chat.workspacePickerTitle,
                        onDismiss = { showWorkspacePicker = false },
                    ) {
                        WorkspacePickerSheet(
                            text = text,
                            home = session.env.homePath,
                            // The phone's storage: `/sdcard`, when the device has one. A workspace may
                            // live there — it is an ordinary folder once "all files access" is granted,
                            // and the tier's writable root is the workspace, not the page of switches
                            // that used to stand between them (§14).
                            shared = StorageAccess.sharedPath(session.env),
                            existing = rememberWorkspacePaths(context, session.env),
                            granted = StorageAccess.isGranted(),
                            // The intent may not resolve on every device, and a grant that
                            // cannot be asked for is not a crash: the sheet's own sentence
                            // already says the folder is unreadable without it.
                            onGrant = {
                                runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
                            },
                            onAdd = { folder ->
                                val added = WorkspaceStore.add(context, session.env, folder)
                                val reason = (added.exceptionOrNull() as? RefusedWorkspace)?.reason
                                val workspace = when {
                                    added.getOrNull() != null -> added.getOrNull()
                                    // Already in the list is not a failure: the folder is a workspace, and
                                    // what the reader asked for is to work in it.
                                    reason == WorkspaceRefusal.DUPLICATE ->
                                        WorkspaceStore.list(context, session.env).firstOrNull { it.path == folder }

                                    else -> null
                                }
                                if (workspace != null) {
                                    // Added *and* switched to: a folder the reader just picked is the one
                                    // they want to work in, and making them tap it again in the menu is the
                                    // second half of one decision.
                                    showWorkspacePicker = false
                                    selectWorkspace(workspace.path)
                                    null
                                } else {
                                    reason?.let { text.chat.workspaceRefusal(it) }
                                }
                            },
                        )
                    }
                },
            )
        }

        // The card's own surface takes every touch while it is out — a tap on it closes
        // the drawer, and a leftward drag is the same gesture that closes it. Composed
        // over the card and starting where the card does, so the panel beside it keeps its
        // own rows, its own taps and its own drag.
        if (drawerVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = drawer.offset }
                    .pointerInput(drawer, focus, rootScope) {
                        detectTapGestures { settlePage(false) }
                    },
            )
        }

    }
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
 * The page frame is not a `PiPage` bar: the leading drawer disc, the current mode's name
 * beside it and the trailing action pill float over the transcript, which fades out under
 * them. The app's name used to stand beside the disc and the **Work / Chat** switch
 * followed it; the switch now belongs to the drawer's foot, where it sits beside the
 * settings disc, and the space it left names the mode the conversation is in. The header
 * used to be the large *collapsing*
 * bar, on the argument that a moving title should give its height back to the transcript
 * as the reader scrolled — the price was that this page's chrome was 36dp taller than the
 * settings pages' and moved while the others did not, and §9.2 has the tokens that made
 * the two heights unequal. The floating capsules are pinned by construction, which is the
 * same decision the rest of the app already made.
 *
 * The **agent's mark** used to stand in the header's action row, drawn by a component
 * of the old design system that nothing else used; it is gone, because to the reader
 * it was a placeholder before the compress button with nothing to say that the
 * subtitle does not already say in words. The compress button followed it: what it
 * did is `/compact` on the command list now that the list is a page rather than a
 * menu, and a header whose second action is one row of another screen's list is a
 * header with one action. What remains is three — terminal, files, then new — left to
 * right as "look at the others, start another", which is also the order of how much
 * each disturbs what is on screen.
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
 * The *mode* is a property of the conversation, read once here as a small enum rather
 * than as the settings snapshot: `ConversationState` changes several times a second
 * while an answer streams, and the mode changes once per choice.
 */
/**
 * The chat page's bar: a drawer disc at the leading edge, the current mode's name beside
 * it, and one action pill at the trailing edge.
 *
 * The mode's name stands where the app's name used to, so the page answers "which mode
 * is this conversation in" without opening the drawer. The switch itself belongs to the
 * drawer's foot now, beside the settings disc, because both are choices about the
 * conversation rather than about the page. Neither capsule has a bar behind it — what is
 * behind them is the transcript, faded out by [FADE_TAIL]'s gradient — which is the whole
 * of the difference between this and the opaque strip a `PiPage` would draw.
 *
 * The capsules are `PiGlass.panel(...)`, the same material the composer's chips and the
 * jump-to-latest button use: a control that floats over the page is the same object
 * whether it holds a label or a glyph, and its fallback is the opaque `PiGlass.fill()`
 * for a device without the backdrop renderer.
 */
@Composable
private fun ChatHeader(
    title: String,
    onOpenSessions: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onNewSession: () -> Unit,
    text: Strings,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalPiHaptics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The capsules sit about a third of a status bar lower than a bar's row would:
            // the reference's handle is clear of the clock's own line rather than tucked
            // under it, and 22dp is what puts its top edge where the drawing has it.
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            onClick = {
                haptics.tap()
                onOpenSessions()
            },
            backdrop = LocalPiBackdrop.current,
            material = PiGlass.material(),
            size = HEADER_MARK_SIZE,
            // The header is a row of objects on a page, not a bar's buttons over a
            // scrolling surface; the bar's shadow would halo a 42dp disc. The panel's
            // own small shadow is the one the trailing capsule carries, so both ends
            // of the row are the same object.
            shadow = PiGlass.floatingShadow,
        ) {
            Icon(
                imageVector = DrawerGlyph,
                contentDescription = text.chat.sessions,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        val trailingShape = RoundedCornerShape(percent = 50)
        val trailingGlass = PiGlass.panel(trailingShape)
        Row(
            modifier = Modifier
                .then(trailingGlass)
                // **No vertical padding**, and the height is the reason: a capsule's
                // height is [HEADER_MARK_SIZE], because that is what the leading circle
                // beside this one is. It had 2dp above and below them, so this pill was
                // 46dp against the circle's 42dp and the two ends of the bar did not line
                // up — a 4dp difference that reads as a mistake rather than as a design.
                // The horizontal padding is the pill's own: it is what keeps the three
                // marks off the rounded ends, and it does not change the height.
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Three marks, in the order the reference drawing has them: the shell the
            // agent drives, the files it changed, then a new conversation — the last one
            // is the circled plus, because that is the one control here that makes
            // something rather than opening something.
            HeaderMark(TerminalGlyph, text.header.terminal, onOpenTerminal)
            HeaderMark(MiuixIcons.Folder, text.header.files, onOpenFiles)
            HeaderMark(MiuixIcons.AddCircle, text.chat.newSession, onNewSession)
        }
    }
}

/** One mark inside the trailing pill. */
@Composable
private fun HeaderMark(
    glyph: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val haptics = LocalPiHaptics.current
    IconButton(
        onClick = {
            haptics.tap()
            onClick()
        },
        modifier = Modifier.size(HEADER_MARK_SIZE),
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = description,
            tint = MiuixTheme.colorScheme.onSurface,
            // 20dp in a 42dp capsule: between the original 22/48 and the 18/38 that was a
            // step too small, and still the same proportion.
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Back from the conversation.
 *
 * The page is the app's root, so a back press has nothing to go back *to* — and the
 * platform's left-edge gesture is exactly where a thumb lands when it means to open a
 * drawer from the left. Measured on the emulator with gesture navigation: the activity
 * was finished by a left-edge swipe and the launcher appeared, which reads as the app
 * crashing to black. The first press therefore arms and says so, and a second within
 * [EXIT_ARM_MILLIS] leaves the app — the convention every Chinese phone app uses, and one
 * that makes an accidental edge swipe a sentence instead of an exit.
 */
@Composable
private fun ExitBackHandler(enabled: Boolean) {
    val context = LocalContext.current
    val toasts = LocalPiToast.current
    val text = strings
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(EXIT_ARM_MILLIS)
            armed = false
        }
    }
    // Disabled while the drawer is out: the drawer's handler closes the panel first, and
    // two enabled handlers whose precedence depends on composition order made "back"
    // either swallow the press or leave the app from behind an open drawer.
    BackHandler(enabled = enabled) {
        if (armed) {
            armed = false
            (context as? Activity)?.finish()
        } else {
            armed = true
            toasts?.show(text.chat.exitHint)
        }
    }
}

/**
 * The drawer's mark: a long line over a short one, left-aligned.
 *
 * The reference draws the drawer's handle this way — two strokes of unequal length, which
 * is what makes it read as a list rather than as a hamburger. Miuix has no such glyph
 * (its `ListView` is three bullets and three rules, its `Sidebar` a framed panel), so this
 * is the second *navigation* mark drawn for this app beside the terminal's: two strokes,
 * the library's line weight, no fill.
 */
private val DrawerGlyph: ImageVector by lazy {
    val stroke = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "Drawer",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = stroke,
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4.5f, 9.4f)
            lineTo(19.5f, 9.4f)
        }
        path(
            stroke = stroke,
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4.5f, 14.6f)
            lineTo(12.5f, 14.6f)
        }
    }.build()
}

/** The chat page's bar height, without the status bar: 30dp above, 48dp of capsule, 8dp below, and the air the first row keeps. */
private val HEADER_CLEARANCE = 86.dp

/** How far the fade reaches below the bar before it is fully transparent. */
private val FADE_TAIL = 96.dp

/** One mark's box in the header, capsule and glyph alike. */
private val HEADER_MARK_SIZE = 42.dp

/** How long a first back press stays armed. */
private const val EXIT_ARM_MILLIS = 2000L

@Composable
private fun ChatPage(
    session: PiAgentSession,
    agent: AgentStatus,
    state: ConversationState,
    text: Strings,
    rows: List<TranscriptRow>,
    listState: LazyListState,
    /** Whether the transcript has been put at its end for this conversation. */
    transcriptPlaced: MutableState<Boolean>,
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
    /** Opens the viewer on a prompt's pictures, at the one that was tapped. */
    onOpenPictures: (PictureTarget) -> Unit,
    /** Whether the conversation drawer is out; the exit handler stands down while it is. */
    historyOpen: Boolean,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    /** This conversation's launch mode: what the empty-state tips and the composer follow. */
    mode: AgentMode,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    /** The workspace this conversation runs in, as an absolute path. */
    currentWorkspace: String,
    onSelectWorkspace: (String) -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onSend: () -> Unit,
    /**
     * The page's own windows — the workspace-switch question, the shell and extras
     * sheets, the picture viewer and the folder picker.
     *
     * Chat draws its own frame instead of using `PiPage`, so it owns the same
     * backdrop contract: these windows are composed inside the page's
     * [LocalPiBackdrop] provider but outside the recorded transcript, which is what
     * keeps `PiConfirm`/`PiSheet` on the OS4 glass path instead of the opaque window
     * fallback. They are still declared by `ChatScreen` because that is where their
     * state lives; this slot is only the composition point.
     */
    overlay: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current
    val focusManager = LocalFocusManager.current
    val toasts = LocalPiToast.current
    // The toast capsule is anchored to this page's composer band while it is on
    // screen; once the page leaves, the provider's own window-inset fallback applies.
    DisposableEffect(toasts) {
        onDispose { toasts?.setAnchor(0f) }
    }

    // The two avatars the transcript draws, rebuilt only when one of the settings
    // behind them moves. Collected here rather than passed in so a change on the
    // personalization page reaches this one without a restart.
    val saved by session.settingsStore.settings.collectAsStateWithLifecycle()
    // A chat conversation's agent identity is its preset's, with every unset part
    // answered by the personalization page — that is the "未设置时则沿用" half of the
    // preset feature. Work mode has no preset, so the same identity the app has always
    // drawn is used there and this whole read is inert.
    val chatPresetId by session.chatPresetId.collectAsStateWithLifecycle()
    val presets by session.chatPresets.collectAsStateWithLifecycle()
    val activePreset = if (mode == AgentMode.CHAT) {
        presets.firstOrNull { it.id == chatPresetId } ?: presets.firstOrNull()
    } else {
        null
    }
    val presetIdentity = activePreset?.identity(saved)
    val avatars = remember(
        saved.showAvatars,
        saved.accentColor,
        saved.aiAvatarName,
        saved.aiAvatarImage,
        saved.aiAvatarColor,
        saved.userAvatarName,
        saved.userAvatarImage,
        saved.userAvatarColor,
        presetIdentity?.name,
        presetIdentity?.image,
        presetIdentity?.color,
    ) {
        if (!saved.showAvatars) {
            null
        } else {
            ChatAvatars(
                // Blank is the mark, not a word: the agent's fallback is the app's own
                // PiKit mark, drawn by `PiAvatar` for `AvatarSpeaker.AGENT`. A chat
                // preset's own name and picture win when it has them, which is what
                // makes a translator preset sign its answers as itself.
                aiName = (presetIdentity?.name ?: saved.aiAvatarName).ifBlank { null },
                aiImage = (presetIdentity?.image ?: saved.aiAvatarImage).ifBlank { null },
                aiColor = (presetIdentity?.color ?: saved.aiAvatarDisc).seed,
                // The reader's blank is the person figure for the same reason: the two
                // speakers must not fall back to the same figure, and the figure is the
                // personalization page's — the page that lets the reader replace it is
                // the one that decides what it is when they have not.
                userName = saved.userAvatarName.ifBlank { null },
                userImage = saved.userAvatarImage.ifBlank { null },
                userColor = saved.userAvatarDisc.seed,
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

    val messageItems = rememberUpdatedState(state.items)

    // What the composer shows, projected out of the state. The projection is keyed on
    // the facts that can change it, so a streamed token that only grows `items` hands
    // the composer an equal value and Compose skips its chip row, field and menus.
    // `state.usage.context` is a key because the two percentage readings are derived
    // from it; they round to whole numbers, so most tokens leave them alone.
    val composerFacts = remember(
        state.model,
        state.requestedModel,
        state.availableThinkingLevels,
        state.availableThinkingLevelsFor,
        state.usage.context,
    ) {
        state.composerFacts()
    }

    FollowTranscriptEnd(listState, follow, programmatic, hasMessages = cards.isNotEmpty())

    // One scroll position belongs to one conversation.
    //
    // The state is hoisted above the page crossfade (`ChatComposerState`), so the offset
    // survives a page switch — which is what it is for. It also survived the *conversation*
    // changing, and that is not the same thing: a new conversation inherited the previous
    // one's first visible index, and every question about the list answered from a
    // position that no longer existed. `canScrollForward` was the visible half — the
    // jump-to-latest control stayed on screen over an empty conversation and did nothing
    // when tapped, because it is a *measurement* of a laid-out list and the list was not
    // there to be measured. Resetting here means the state a conversation is entered with
    // is the state it has, and the control's own gate (below) covers the one frame before
    // the new list has been measured.
    LaunchedEffect(state.sessionFile) {
        // **Only for a conversation with nothing in it.** A transcript read from disk
        // arrives with its messages and the follow lands the list at its end; jumping to
        // item 0 as well put the reader at the top of the conversation a frame later —
        // the "点开历史消息，闪一下又回到顶部" report.
        if (state.items.isEmpty()) listState.scrollToItem(0)
    }

    // A transcript that arrives with messages opens at its **end**, before it is ever
    // measured.
    //
    // The follow effect above is a coroutine: it runs after composition, and the list is
    // laid out for the first time between the two — so a conversation opened from the
    // history list drew its first question for one frame and replaced it with the last
    // answer on the next. How long that frame lasts is the first layout of the whole
    // transcript (every row measured, every formula's drawable attached), which is why
    // the flash was long on the conversations worth opening and why opening one while
    // the drawer was already sliding over it made it obvious. `requestScrollToItem`
    // records the position and is applied by the next measure pass, so the frame that
    // first draws the list already draws its end. It is a pre-measure request rather
    // than a scroll, which is also why there is no animation to stand aside from.
    //
    // Once per conversation: the list state outlives the page (a look at the terminal
    // disposes the page and not the state), and a reader who had scrolled up comes back
    // to where they were rather than being put back at the tail.
    if (!transcriptPlaced.value && cards.isNotEmpty()) {
        SideEffect {
            transcriptPlaced.value = true
            listState.requestScrollToItem(
                index = cards.lastIndex,
                // Past the last card on purpose: the list clamps the request to its own
                // furthest scroll position, and that is the one thing this code cannot
                // compute — the end of the content is the last card's height plus its
                // bottom padding, and neither is known before the first measure. A
                // request for the last card's *top* would draw that card at the top and
                // leave the rest below the fold, which is the jump this exists to avoid.
                scrollOffset = TRANSCRIPT_END_REQUEST,
            )
        }
    }

    // The composer band's insets: the objects here, their heights inside the layout
    // modifier, so a keyboard animation is a layout and not a recomposition.
    val imeInsets = WindowInsets.ime
    val barInsets = WindowInsets.navigationBars
    // The colour the band's own surface, its rank and the strip under the field are
    // painted with. Read inside the draws and nowhere else.
    val bandSurface = MiuixTheme.colorScheme.surface
    // The band's own coordinates and the field's top edge inside them: the rank's anchor.
    // Positions rather than a sum of heights — every earlier version tried to *compute*
    // where the field began and got it wrong by exactly the padding it forgot.
    var bandCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var fieldTopPx by remember { mutableFloatStateOf(0f) }
    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val headerSpace = topInset + HEADER_CLEARANCE
    var menuFor by remember { mutableStateOf<MessageActionPoint?>(null) }
    var editingPrompt by remember { mutableStateOf<EditPrompt?>(null) }
    val menuAnchor = rememberPiMenuAnchor()
    // The message whose Markdown source is open in the reader sheet.
    var sourceFor by remember { mutableStateOf<ChatItem?>(null) }
    ExitBackHandler(enabled = !historyOpen)
    val messageClipboard = LocalClipboard.current
    val messageToasts = LocalPiToast.current
    val messageScope = rememberCoroutineScope()
    // The long press answers with the kit's hold *before* the panel appears. Every
    // other control on this page acknowledges the finger, and 500 ms of holding
    // followed by a silent menu reads as a delayed tap rather than as a recognised
    // gesture — the same moment a held conversation row in the drawer answers.
    val haptics = LocalPiHaptics.current


    // The page, and the layer its bar floats on.
    //
    // The reference draws the top of the screen as a *surface* the conversation runs
    // under: two capsules holding the marks, and the top of
    // the transcript fading into the background behind them. A `PiPage` bar is the
    // opposite of that — an opaque strip that pushes the content down — so this page
    // draws its own frame: the transcript fills the window, a gradient covers its first
    // stretch, and the marks sit on the gradient.


    // The transcript is the page's glass layer: the header's capsules sample it, so a
    // row scrolling under the header is what the material blurs. The header sits
    // outside the layer — a control inside it would sample its own output.
    val chatBackdrop = rememberPiBackdrop()
    CompositionLocalProvider(LocalPiBackdrop provides chatBackdrop) {
        Box(Modifier.fillMaxSize()) {
            // The transcript fills the page and the composer floats over its bottom, so the
            // conversation runs *behind* the band rather than being cut by it. That is what
            // lets the bottom fade start where the reference draws it — at the field's top —
            // instead of at the top of the chip row, which is where the band's own edge used
            // to fall.
            var bottomStackPx by remember { mutableIntStateOf(0) }
            val bottomStack: Dp = with(LocalDensity.current) { bottomStackPx.toDp() }
            Box(
                Modifier
                    .fillMaxSize()
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
                    detectTapGestures(
                        onTap = {
                            focusManager.clearFocus()
                        },
                    )
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                // Centre the prose measure on wide windows without moving the chrome.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        // The transcript's own layer, and it covers the **empty state
                        // too**. It used to sit on the `LazyColumn` alone, so a
                        // conversation with no messages had no layer recording at all:
                        // the header's capsules and the composer's chips sampled an
                        // empty backdrop and drew as the page itself. The empty state
                        // is content the glass should blur like any other; the jump
                        // control is outside this box so it cannot sample its own
                        // output.
                        .then(
                            if (chatBackdrop != null) {
                                Modifier.layerBackdrop(chatBackdrop)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // **No selection container around the whole transcript.**
                    //
                    // It was one, and Compose's text selection is what consumed the
                    // long press: a press on a bubble raised the platform's copy
                    // toolbar, which is the behaviour this page no longer wants. The
                    // gesture belongs to the message now — a long press opens the page's
                    // own menu. Selection opens the message's source in its own sheet.
                    Box(
                        modifier = if (maxWidth >= WIDE_TRANSCRIPT_WINDOW) {
                            Modifier.widthIn(max = MAX_TRANSCRIPT_WIDTH)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    ) {
                        // A conversation nothing has been sent to is not a blank
                        // area: it is the one place the page can say what it is for.
                        // The title does that and the line under it is a tip from the
                        // catalog's set rather than the same sentence every time —
                        // see `Strings.Chat.tips`.
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
                                    body = remember(cards, mode) {
                                        val tips = if (mode == AgentMode.CHAT) {
                                            text.chat.chatTips
                                        } else {
                                            text.chat.tips
                                        }
                                        tips.random()
                                    },
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    // The transcript is the only thing in the page's
                                    // backdrop. The layer lives on the `BoxWithConstraints`
                                    // above, so the empty state is recorded too; the
                                    // composer and its chips stay outside it, or they
                                    // would sample their own blur output.
                                    // A conversation shorter than the viewport has no
                                    // edge of its own to give a drag, and Compose's
                                    // `ScrollingLogic` gates the overscroll effect on
                                    // whether the list can scroll — so a one-message
                                    // conversation swallowed the pull a long one answers.
                                    // The kit's modifier is a nested-scroll connection
                                    // above the list, with the reasoning in
                                    // `PiOverScroll.kt` and §9.1.
                                    .piOverScroll()
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
                                    // The bar floats over the list, so the list starts
                                    // *under* it: this is the clearance that keeps a
                                    // restored conversation's first row off the capsules,
                                    // and it is content padding rather than a margin for
                                    // the reason a scroll must still be able to put that
                                    // row under the fade.
                                    top = 10.dp + headerSpace,
                                    // One line of the body style's air, and nothing
                                    // else: the composer is a band in this page's
                                    // column now rather than a panel over the list, so
                                    // the list's own box already ends above it. The
                                    // measured height the floating panel needed is gone.
                                    bottom = TRANSCRIPT_TAIL_SPACE + bottomStack,
                                ),
                                // No uniform gap. See `rowGap`: the space between two rows is
                                // a statement about what the two rows *are*, and one number
                                // for every pair is what made a prompt, its band, its steps
                                // and its answer read as one undifferentiated stream.
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                itemsIndexed(
                                    items = cards,
                                    key = { _, card -> card.key },
                                    contentType = { _, card -> card is TranscriptCard.Prompt },
                                ) { index, card ->
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
                                            top = rowGap(cards.getOrNull(index - 1)?.rows?.lastOrNull(), card.rows.first()),
                                        ),
                                    ) {
                                        val targetKey = card.rows.asReversed().firstNotNullOfOrNull { row ->
                                            (row as? TranscriptRow.Message)?.item?.let { item ->
                                                item.key.takeIf { item is ChatItem.User || item is ChatItem.Assistant }
                                            }
                                        }
                                        val latestTargetKey = rememberUpdatedState(targetKey)
                                        var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                                        val openMenu: (Offset?) -> Boolean = open@{ press ->
                                            val coords = cardCoords?.takeIf { it.isAttached } ?: return@open false
                                            val action = messageActionPoint(messageItems.value, latestTargetKey.value)
                                                ?: return@open false
                                            val origin = press ?: Offset(coords.size.width / 2f, coords.size.height / 2f)
                                            val at = coords.localToWindow(origin)
                                            if (!at.x.isFinite() || !at.y.isFinite()) return@open false
                                            menuAnchor.bounds = Rect(at, at)
                                            haptics.hold()
                                            menuFor = action
                                            true
                                        }
                                        val latestOpenMenu = rememberUpdatedState(openMenu)
                                        Box(
                                            Modifier
                                                .onGloballyPositioned { cardCoords = it }
                                                .then(
                                                    if (targetKey != null) Modifier.semantics { onLongClick { openMenu(null) } }
                                                    else Modifier,
                                                )
                                                .pointerInput(card.key) {
                                                    detectTapGestures(onLongPress = { latestOpenMenu.value(it) })
                                                },
                                        ) {
                                            TranscriptCardView(
                                                card = card,
                                                text = text,
                                                expandedTurns = expandedTurns,
                                                onToggleTurn = onToggleTurn,
                                                avatars = avatars,
                                                showMessageTime = saved.showMessageTime,
                                                thinkingAutoExpand = saved.expandThinkingWhileStreaming,
                                                onShowExtras = onShowPromptExtras,
                                                onOpenPictures = onOpenPictures,
                                            )
                                        }
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
                //
                // **Gated on the list existing**, and that is not a formality.
                // `canScrollForward` is a reading of the last *measurement* of a
                // `LazyColumn`, and the transcript does not draw one over an empty
                // conversation — so the value freezes at whatever the previous
                // conversation left behind. That is the report "点了新建对话，回到底部的
                // 按钮不会消失，点击也没反应": an empty conversation inherited a
                // scrollable position from the long one before it, and the control was
                // both visible and pointed at a list that was not composed. Nothing is
                // scrollable about nothing, so there is nothing for the control to be.
                if (cards.isNotEmpty() && listState.canScrollForward) {
                    GlassIconButton(
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
                        backdrop = LocalPiBackdrop.current,
                        material = PiGlass.material(),
                        size = 40.dp,
                        // The 40dp disc is the header capsule's own object, not a
                        // bar button: the bar's 64-unit shadow would halo it.
                        shadow = PiGlass.floatingShadow,
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
                            .padding(end = 10.dp, bottom = 10.dp + bottomStack),
                    ) {
                        Icon(
                            // An arrow, not the chevron the disclosure controls use:
                            // this button scrolls the list, it does not open anything,
                            // and the two figures sat within a screen of each other on
                            // the same page. See `ic_arrow_down.xml`.
                            painter = painterResource(R.drawable.ic_arrow_down),
                            contentDescription = text.chat.jumpToLatest,
                            // 20dp, matching the send/stop glyphs in the composer.
                            modifier = Modifier.size(20.dp),
                            // Normal ink rather than `onSecondaryContainer`: the disc's
                            // own content colour is #A9A9A9 on #F0F0F0 in the light
                            // scheme and #7C7C7C on #434343 in the dark one — a glyph
                            // drawn below the 3:1 floor on both, which is what made the
                            // control look disabled while it was the only way back to
                            // the end of the conversation.
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // The notices and the composer, floating over the transcript's last stretch.
            // Their height is published to the list as its bottom content padding, so
            // the end of the conversation still scrolls clear of the band; what changed
            // is only what happens *above* that end, where a row now passes behind the
            // band and fades at the field's top instead of meeting the chips' edge.
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
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // The keyboard lifts **this column and nothing else**: the page's
                    // height, and with it the transcript's measurement, stays put while
                    // the keyboard animates. `onSizeChanged` sits *after* the inset, so
                    // what the list pads its end by is the band's own content height and
                    // not the keyboard's — which is what keeps a keyboard close from
                    // re-measuring every row of the conversation on each step of it.
                    // The larger of the two insets plus [BAND_BOTTOM_AIR], resolved in
                    // layout: the union alone put the field's bottom edge *on* the
                    // keyboard — measured, 5px of it — which is the "输入框和键盘之间没有
                    // 间距" report, and on a phone whose gesture strip reports zero it put
                    // the field on the screen's edge.
                    // **Before** the inset: a draw declared after `bandBottom` is a node
                    // the size of the *content*, so the strip the inset adds was outside
                    // it and the transcript showed through under the field. Declared
                    // first, this node spans content and inset together.
                    .onGloballyPositioned { bandCoords = it }
                    .drawBehind {
                        val surface = bandSurface
                        val top = fieldTopPx
                        if (top > 0f) {
                            val rampTop = (top - BAND_FADE.toPx()).coerceAtLeast(0f)
                            if (top > rampTop) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to surface.copy(alpha = 0f),
                                        1f to surface,
                                        startY = rampTop,
                                        endY = top,
                                    ),
                                    topLeft = Offset(0f, rampTop),
                                    size = Size(size.width, top - rampTop),
                                )
                            }
                            // Solid from the field's top edge to the bottom of the band,
                            // inset included.
                            drawRect(
                                color = surface,
                                topLeft = Offset(0f, top),
                                size = Size(size.width, size.height - top),
                            )
                        }
                    }
                    // **Before the inset, like the draw.** A modifier declared after
                    // `bandBottom` sees the *content* node, so the height it published
                    // was the band's body without the gesture strip — measured, 95 px
                    // short on the 1080 px emulator. The jump-to-latest button then sat
                    // 95 px lower than the band's top, its disc half hidden behind the
                    // chip row, and the transcript's tail padded by 95 px too little.
                    // Declared here, it spans content and inset together.
                    .onSizeChanged {
                        bottomStackPx = it.height
                        toasts?.setAnchor(it.height.toFloat())
                    }
                    // The drawer is a page of its own while it is out: its search
                    // field and the rename dialog raise the keyboard over the
                    // drawer, and the conversation behind it is not being typed
                    // into. Lifting the composer for that keyboard moves the input
                    // box under a panel that covers it — the chat page's bottom
                    // edge would follow a field it does not own. The band keeps the
                    // navigation strip and ignores the IME until the drawer closes.
                    .bandBottom(imeInsets, barInsets, liftByIme = !historyOpen),
            ) {
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
                            // field under it. The corner is the kit's, so the 28dp
                            // control still belongs to the same shape family as the
                            // 40dp buttons around it.
                            cornerRadius = PiCorner.radius,
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
                onFieldTop = { coords ->
                    // Keep the last value when the band has not been laid out yet: writing
                    // 0 would drop the whole background for a frame, which reads as a
                    // flash under the field.
                    bandCoords?.let { band ->
                        fieldTopPx = band.localPositionOf(coords, Offset.Zero).y
                    }
                },
                text = text,
                value = input,
                onValueChange = onInputChange,
                attachments = attachments,
                onRemoveImage = onRemoveImage,
                onOpenPictures = onOpenPictures,
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
                facts = composerFacts,
                mode = mode,
                openSheet = openSheet,
                onOpenSheet = onOpenSheet,
                onCloseSheet = onCloseSheet,
                onRunCommand = onRunCommand,
                onPickAttachment = onPickAttachment,
                currentWorkspace = currentWorkspace,
                onSelectWorkspace = onSelectWorkspace,
                onOpenWorkspacePicker = onOpenWorkspacePicker,
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

        // The conversation fades out under the bar rather than being cut by it: a hard
        // edge there is the seam the reference drawing spends the whole top of the screen
        // hiding. Drawn over the transcript and under the marks, so a row scrolling up
        // dissolves into the page instead of colliding with a capsule.
        // The fade belongs to the **status bar's bottom edge**, not to the button row's.
        //
        // Its solid part is exactly the status bar's height and the ramp starts there, so
        // a row begins to dissolve the moment it leaves the clock's line rather than
        // staying at full contrast all the way down to the capsules. That is where the
        // reference drawing's own fade begins.
        val fadeHeight = topInset + FADE_TAIL
        val fadeSolid = if (fadeHeight > 0.dp) topInset / fadeHeight else 0f
        val ramp = 1f - fadeSolid
        Box(
            Modifier
                .fillMaxWidth()
                .height(fadeHeight)
                .background(
                    Brush.verticalGradient(
                        0f to MiuixTheme.colorScheme.surface,
                        fadeSolid to MiuixTheme.colorScheme.surface,
                        fadeSolid + ramp * 0.45f to MiuixTheme.colorScheme.surface.copy(alpha = 0.62f),
                        fadeSolid + ramp * 0.8f to MiuixTheme.colorScheme.surface.copy(alpha = 0.26f),
                        1f to Color.Transparent,
                    ),
                ),
        )

        ChatHeader(
            title = if (mode == AgentMode.WORK) text.settings.modeWork else text.settings.modeChat,
            onOpenSessions = onOpenSessions,
            onOpenTerminal = onOpenTerminal,
            onOpenFiles = onOpenFiles,
            onNewSession = onNewSession,
            text = text,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // The message menu: the rows the transcript's long press offers, at the point the
        // finger went down. It is registered here rather than inside the card because the
        // page's popup host draws the panel — outside the transcript's recorded layer and
        // measured against the window — so a card the list recycles while the menu is open
        // no longer takes its menu with it.
        val menuSubject = rememberLastPresent(menuFor)
        if (menuSubject != null) {
            PiPopupMenu(
                show = menuFor != null,
                onDismissRequest = { menuFor = null },
                anchor = menuAnchor,
            ) {
                messageMenuItems(
                    target = menuSubject.target,
                    counted = menuSubject.throughTarget,
                    prompt = menuSubject.prompt,
                    forkCount = menuSubject.beforePrompt,
                    streaming = state.isStreaming,
                    text = text,
                    session = session,
                    workspace = currentWorkspace,
                    clipboard = messageClipboard,
                    scope = messageScope,
                    toasts = messageToasts,
                    onDismiss = { menuFor = null },
                    onSelectSource = { sourceFor = it },
                    onEdit = { prompt, count ->
                        menuFor = null
                        editingPrompt = EditPrompt(prompt, count)
                    },
                    onRegenerate = { prompt, count ->
                        menuFor = null
                        forkAndSend(
                            appContext,
                            messageScope,
                            session,
                            currentWorkspace,
                            count,
                            prompt.text,
                            prompt.imageKeys,
                            messageToasts,
                            text.chat.forkFailed,
                        )
                    },
                ).forEach { action ->
                    PiMenuRow(
                        title = action.title,
                        startAction = action.leading,
                        onClick = action.onClick,
                    )
                }
            }
        }
        }

        // The edit dialog: the prompt's own words, and what a branch costs. The
        // subject is held through the exit for the same reason every window keeps its
        // last value (docs/UI.md) — the field must not blank while the panel leaves.
        val editSubject = rememberLastPresent(editingPrompt)
        if (editSubject != null) {
            EditPromptDialog(
                show = editingPrompt != null,
                text = text,
                initial = parsePromptExtras(editSubject.prompt.text).displayText,
                hasImages = editSubject.prompt.imageKeys.isNotEmpty(),
                onDismiss = { editingPrompt = null },
                onConfirm = { edited ->
                    val request = editSubject
                    editingPrompt = null
                    val extras = parsePromptExtras(request.prompt.text)
                    val body = edited.trim()
                    // The file paths are machinery the prompt already carried. They
                    // are kept — the copies still exist in `files/attachments` — and
                    // re-stated in the app's own header so the model still receives
                    // what the edited sentence is about.
                    val withPaths = if (extras.filePaths.isEmpty()) {
                        body
                    } else {
                        buildString {
                            append(text.chat.addressHeader)
                            extras.filePaths.forEach { append('\n').append(it) }
                            if (body.isNotBlank()) append("\n\n").append(body)
                        }
                    }
                    forkAndSend(
                        appContext,
                        messageScope,
                        session,
                        currentWorkspace,
                        request.forkCount,
                        withPaths,
                        request.prompt.imageKeys,
                        messageToasts,
                        text.chat.forkFailed,
                    )
                },
            )
        }

        // The Markdown source of the message, in a sheet of its own: what the reader asked
        // for when they chose "select text" is the *text*, not a selection mode they then have
        // to discover.
        //
        // **The sheet carries no copy row.** The menu that opened it already copies this
        // same source (`itemSource`), so a second copy button in the sheet was the same
        // action twice, one tap apart, with the same toast. What is left is what the sheet
        // is for: text a finger can select, and the platform's own toolbar acting on the
        // selection the reader actually made.
        //
        // The subject is held through the exit: `show` follows `sourceFor`, but the content
        // reads the last non-null value, or dismissing clears the one thing the leaving sheet
        // draws and its 200 ms exit plays over an empty panel (docs/UI.md, *Dialogs, sheets*).
        var sourceShown by remember { mutableStateOf<ChatItem?>(null) }
        if (sourceFor != null) sourceShown = sourceFor
        val sourceItem = sourceShown
        PiSheet(
            show = sourceFor != null,
            title = text.chat.selectText,
            onDismiss = { sourceFor = null },
        ) {
            if (sourceItem != null) {
                val source = itemSource(sourceItem)
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The sheet's own edge is the window's, so the text clears the
                        // gesture strip itself (docs/UI.md, *Dialogs, sheets*). There is
                        // no row under it any more, so the 12dp that used to separate the
                        // copy row from the panel's edge went with the row: the text's own
                        // padding is the last air above the inset.
                        .padding(horizontal = 12.dp)
                        .padding(bottom = piSheetBottomInset()),
                ) {
                    Text(
                        text = source,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
        }

        overlay()
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
    // Built here rather than in a file-level val: `GlassShape` lives in `miuix-material`,
    // whose classes are Java 21 bytecode, and a top-level initializer would run at
    // `ChatScreenKt`'s class init — which the JVM-17 unit tests load for the pure row
    // functions. A local reference is resolved only when this composable runs.
    val noticeShape = remember { GlassShape(12.dp) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            // The tap area is put on *after* the margin, so what answers a tap is
            // exactly the strip the reader can see: a dismissable notice whose
            // gesture started in the gap beside it would be a control a few
            // millimetres larger than it looks. The clip comes first for the same
            // reason the chip's does — the press tint must not square off the corners —
            // and it is the glass renderer's continuous shape, so the strip's corner is
            // the same curve as the cards around it rather than a circular one.
            .clip(noticeShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = noticeShape,
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
 * The page with nothing in it yet: what this page is, and one tip out of the
 * catalog's set.
 *
 * A conversation nothing has been sent to is not a blank area — it is the one place
 * the page can say what it is for. It says it in the title and the tip, without a
 * mark above them: the new-conversation page is a page the reader is about to fill,
 * and the app's mark has already done its job on the launcher and the setup screen.
 * The old 64dp mark made the page read as a splash screen rather than as an empty
 * conversation, and it repeated what the title and the app's own icon already said.
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
        Text(
            text = title,
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

/**
 * The handful of conversation facts the composer displays.
 *
 * `ConversationState` is a data class whose `items` list is rebuilt for every streamed
 * token, so passing the whole state into the composer made its chip row, its field and
 * four menus re-run several times a second for values that had not changed. This type is
 * the *answer* the composer needs rather than the state it was derived from: the model
 * and its pending switch, that model's thinking levels, and the two whole-number
 * readings the chips show. Compose sees an equal instance and skips the composer while
 * the answer is the same; [contextPercent] and [cacheHitPercent] round to integers, so
 * token growth moves them only when the displayed percentage does.
 */
@Immutable
private data class ComposerFacts(
    val model: ModelInfo? = null,
    val requestedModel: String? = null,
    val availableThinkingLevels: List<String> = emptyList(),
    val availableThinkingLevelsFor: String? = null,
    val contextPercent: Int = 0,
    val cachePercent: Int = 0,
)

/** Projects a conversation's display state onto [ComposerFacts]. */
private fun ConversationState.composerFacts(): ComposerFacts = ComposerFacts(
    model = model,
    requestedModel = requestedModel,
    availableThinkingLevels = availableThinkingLevels,
    availableThinkingLevelsFor = availableThinkingLevelsFor,
    contextPercent = contextPercent(this),
    cachePercent = cacheHitPercent(this),
)

/**
 * The thinking level to show: the reader's own choice, mapped onto the model in use.
 *
 * pi reports the level it is running (`get_state.thinkingLevel`), and that value is
 * deliberately **not** an input here. Measured against the pinned 1.4.0, `set_model`
 * carries the running level across a switch and clamps it to the new model's list — so
 * what pi reports is one model's answer to a question another model asked, and reading
 * it let a level that belonged to a different row win over the one the reader had just
 * chosen. The case that names it: `off` on one profile, `max` chosen on the next, and a
 * switch to a third came back `off` — the level of the row that had been left, clamped
 * by whichever model pi happened to be on, rather than the level of the model the
 * reader had come from.
 *
 * The saved value is the only thing that is actually the reader's, so it is the input
 * and [clampThinkingLevel] is what maps it: `max` on a model whose map is `{off, high}`
 * shows `high`, which is what "matching" means for that model. A model that can only do
 * `off` shows `off` while it is in front of the reader and nothing is written back —
 * making the clamp permanent is the bug the *remembered levels* below replaced, because
 * using such a model once would then leave `off` on the command line for every model
 * afterwards (§7.2).
 *
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
 *
 * ## Why nothing reads pi's live level
 *
 * That reading was removed, and the reason is the rule above: pi's answer is about one
 * model, so it can carry a level that belongs to a model the reader is no longer on —
 * `off` chosen on another row, arriving on this one as if it were this model's. What the
 * model actually does is kept in step the other way round: the session sends this same
 * clamped level to pi after every switch (`PiAgentSession.selectProfile`), so the chip
 * and the process agree without either of them being asked to trust the other. §7.2
 * records the sequence that settled it.
 */
private fun thinkingLevelOf(
    facts: ComposerFacts,
    saved: PiSettings,
    active: ModelProfile?,
    catalogued: Map<String, List<String>>,
): String = clampThinkingLevel(
    saved.thinkingLevel,
    levelsFor(facts, saved, active, catalogued),
)

/**
 * The thinking levels to offer for the model the next message will be sent with.
 *
 * The model is [modelInUse]'s answer, and the list is [thinkingLevelsFor]'s: pi's live
 * answer when it is an answer for *that* model, the list remembered for it, and pi's
 * catalogue on disk — which is what makes a model switch show the new model's levels on
 * the frame the switch lands rather than two round trips later.
 */
private fun levelsFor(
    facts: ComposerFacts,
    saved: PiSettings,
    active: ModelProfile?,
    catalogued: Map<String, List<String>>,
): List<String> {
    val modelId = modelInUse(facts, saved, active)
    return thinkingLevelsFor(
        live = facts.availableThinkingLevels,
        liveFor = facts.availableThinkingLevelsFor,
        modelId = modelId,
        remembered = saved.rememberedThinkingLevels(modelId),
        catalogued = modelId?.let { catalogued[it] }.orEmpty(),
    )
}

/**
 * The model id a thinking question is about: the model the next message goes with.
 *
 * The app's own pending request wins first: `requestedModel` is written on the frame
 * the user taps a model in the picker, and pi's confirming `get_state` — 320 ms on the
 * emulator, over a second on a busy phone — is still in flight. Without this the chip
 * kept the previous model's name and clamped against its levels for that whole window.
 *
 * pi's own report then wins **when the levels agree with it**, because pi is the
 * authority on what is running — a `/model` typed into the composer moves the model
 * without moving the profile, and it also clears the pending request. When they do not
 * agree, the profile's choice is the answer: that is the model the app has just told
 * pi to switch to, and it is also the only id there is on the frame the app opens,
 * before `get_state` has said anything.
 */
private fun modelInUse(
    facts: ComposerFacts,
    saved: PiSettings,
    active: ModelProfile?,
): String? {
    facts.requestedModel?.takeIf { it.isNotBlank() }?.let { return it }
    val live = facts.model?.id?.takeIf { it.isNotBlank() }
    if (live != null && live == facts.availableThinkingLevelsFor) return live
    return active?.modelId?.takeIf { it.isNotBlank() }
        ?: live
        ?: saved.modelId.takeIf { it.isNotBlank() }
}

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
 * The line under a model in the picker: `name/provider`, and the key's state under it.
 *
 * ## Why a slash, and why the key moved to its own line
 *
 * The line used to join three facts with a middot — `provider · name · key` — which
 * reads as a *run-on*: nothing on it says which part is the endpoint and which is the
 * configuration's own name, and at 13sp a middot is indistinguishable from the space
 * around it. `name/provider` is the shape the same pair is written in on the model
 * settings page, so the two surfaces agree about how a configuration is named, and
 * reading one teaches the other.
 *
 * The key's state is the one fact on this line that is a *warning* rather than a
 * name, and a warning at the end of a run-on sentence is the part a reader skips.
 * `DropdownImpl`'s summary is a plain `Text`, so a newline is all it takes to give it
 * a line of its own.
 *
 * The wrapping is why the state is stated as "no key" rather than "key saved": a row
 * whose summary is one line for most profiles and two for one of them is a row that
 * changes height exactly when the reader is looking for the reason it fails.
 */
private fun modelRowSummary(profile: ModelProfile, text: Strings): String {
    val provider = providerLabel(profile, text)
    val name = profile.name.takeIf { it.isNotBlank() }
    val heading = if (name != null) "$name/$provider" else provider
    return if (profile.apiKey.isBlank()) {
        "$heading\n${text.settings.noKey}"
    } else {
        heading
    }
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
         * Whether this assistant row provides its turn's timestamp. A time under every
         * work fragment made one answer read as several. Only the settled turn's own
         * [pi.kit.mob.pi.ChatTurn.finalReplyKey] carries the time, so the fold cannot
         * change where it belongs.
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
        // Count only rows that are *drawn* as steps. The live reducer appends an
        // assistant row on `message_start` before it knows the message carries only a
        // tool call; that row has no body and is dropped by `drawsNothing`, so counting
        // it made a turn with one tool call say "2 个步骤" and then show one row when
        // expanded. Notices are never folded, so they are not steps either.
        val step = when (item) {
            is ChatItem.Tool -> true
            is ChatItem.Assistant ->
                item.thinking.isNotBlank() || item.text.isNotBlank() ||
                    item.error != null || item.status == ReplyStatus.Stopped
            else -> false
        }
        if (step && item.key in hidden) hiddenPerTurn[turn]++
        if (item is ChatItem.Tool && item.state == pi.kit.mob.pi.ToolState.Failed) failedPerTurn[turn]++
        if (item is ChatItem.Notice && item.kind == pi.kit.mob.pi.NoticeKind.Error) failedPerTurn[turn]++
    }

    val now = System.currentTimeMillis()
    val rows = ArrayList<TranscriptRow>(items.size + state.turns.size)

    // The rows that may draw a timestamp: every turn's own
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

/**
 * The two avatars the transcript draws over its cards, or null when they are off.
 *
 * A null name draws no word beside the mark; it never changes what the disc holds. The
 * agent's is the app's own PiKit mark and the reader's is the person figure — [PiAvatar]
 * holds both and is the one place that decides which is drawn, so this transcript and the
 * personalization page cannot disagree about the reader's own avatar. The colours are
 * the two `AvatarColor` seeds that page offers, and an image is a file path the
 * settings store holds.
 */
private data class ChatAvatars(
    val aiName: String?,
    val aiImage: String?,
    val aiColor: Color,
    val userName: String?,
    val userImage: String?,
    val userColor: Color,
)

/**
 * A speaker's name, beside their mark.
 *
 * One style for both sides: it is a caption on the conversation rather than part of it,
 * which is why it is the footnote face and the muted ink. Blank never reaches here — a
 * reader who chose no name passes null, and the row is then just the mark.
 *
 * **`footnote1` (13sp), not `footnote2` (11sp).** The smaller footnote is the smallest
 * text the interface has, and it sat beside a 34dp disc where the mark's own drawing is
 * 21dp: the name read as a speck attached to the mark rather than as the speaker's name,
 * and a caption nobody can read at a glance is a caption that has stopped being one. One
 * step up the same face keeps it below the message's 17sp, so it is still a caption.
 */
@Composable
private fun SpeakerName(name: String) {
    Text(
        text = name,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 140.dp),
    )
}

/**
 * The avatar's drawn size and the air between it and the card under it.
 *
 * 34dp for both speakers. The prompt mark was 24dp while it sat in the bubble's
 * footer; once it returned above the bubble it had to carry the same "who is
 * speaking" weight as the answer's mark, and two sizes on two sides of one exchange
 * read as two different components.
 */
private val AVATAR_SIZE = 34.dp
private val AVATAR_GAP = 8.dp

/**
 * One card of the transcript, drawn: the prompt, or the answer it produced.
 *
 * Both sides are framed. The prompt is content-sized at the end edge; the answer is
 * the transcript's full measure. Each speaker's mark and optional name sit **above**
 * their card, never below it — the mark opens the message, and the card is what they
 * said.
 */
@Composable
private fun TranscriptCardView(
    card: TranscriptCard,
    text: Strings,
    expandedTurns: Set<Int>,
    onToggleTurn: (Int) -> Unit,
    avatars: ChatAvatars?,
    /** Whether a finished message draws its date and time underneath. */
    showMessageTime: Boolean,
    /** Whether the current step's reasoning opens while it streams and folds when it ends. */
    thinkingAutoExpand: Boolean,
    onShowExtras: (PromptExtras) -> Unit,
    onOpenPictures: (PictureTarget) -> Unit,
) {
    when (card) {
        is TranscriptCard.Prompt -> BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.widthIn(max = maxWidth * PROMPT_MAX_SHARE)) {
                Column(horizontalAlignment = Alignment.End) {
                    avatars?.let { marks ->
                        // The reader's name sits to the left of their mark because the
                        // mark is on the trailing edge.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = AVATAR_GAP),
                        ) {
                            marks.userName?.let { SpeakerName(it) }
                            PiAvatar(
                                speaker = AvatarSpeaker.READER,
                                color = marks.userColor,
                                imagePath = marks.userImage,
                                size = AVATAR_SIZE,
                            )
                        }
                    }
                    Card(
                        cornerRadius = PiCorner.radius,
                        insideMargin = PROMPT_CARD_PADDING,
                        colors = CardDefaults.defaultColors(
                            color = PiTone.prompt(),
                            contentColor = MiuixTheme.colorScheme.onSurface,
                        ),
                    ) {
                        PromptBody(card.row.item as ChatItem.User, text, onShowExtras, onOpenPictures)
                    }
                    MessageMeta(
                        item = card.row.item,
                        text = text,
                        alignment = Alignment.End,
                        show = showMessageTime,
                    )
                }
            }
        }

        is TranscriptCard.Answer -> Column(Modifier.fillMaxWidth()) {
            avatars?.let { marks ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = AVATAR_GAP),
                ) {
                    PiAvatar(
                        speaker = AvatarSpeaker.AGENT,
                        color = marks.aiColor,
                        imagePath = marks.aiImage,
                        size = AVATAR_SIZE,
                    )
                    marks.aiName?.let { SpeakerName(it) }
                }
            }
            Card(
                cornerRadius = PiCorner.radius,
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(ANSWER_CARD_INSET),
                colors = CardDefaults.defaultColors(),
            ) {
                AnswerCardRows(
                    rows = card.rows,
                    text = text,
                    expandedTurns = expandedTurns,
                    onToggleTurn = onToggleTurn,
                    thinkingAutoExpand = thinkingAutoExpand,
                    onShowExtras = onShowExtras,
                    onOpenPictures = onOpenPictures,
                )
            }
            metaItemOf(card.rows)?.let { answered ->
                MessageMeta(
                    item = answered,
                    text = text,
                    alignment = Alignment.Start,
                    show = showMessageTime,
                )
            }
        }
    }
}

/**
 * One answer card's rows: the turn's work as a step spine, then the answer.
 *
 * ## What the spine is
 *
 * ZCode's transcript draws a run of agent work as one group: a summary header, then
 * the individual steps under it, offset eight pixels from a one-pixel vertical rule,
 * with the text starting at twenty-four. PiKit's turn band already *is* the summary
 * header — `8 个步骤` above the rows it folds — so the missing half was the list under
 * it, which used to be a stack of filled pills. This is that half: every step row a
 * plain line in a 24dp text column, one hairline down the left, and the final answer
 * left on the card's own margin so the reader can always tell where "what the agent
 * did" stops and "what it says" begins.
 *
 * The answer is the **last work row when that row is an assistant row carrying text**,
 * not by `showMeta`: while a reply streams, `showMeta` is false on every row — the row
 * that is becoming the answer included — and using it would make the answer jump out
 * of the spine at the end of the turn. The end-of-list test also keeps a narration
 * sentence (`我先看一下项目结构。`) in the spine while the tool calls it announces are
 * still arriving; only the text that follows them is the answer.
 *
 * ## Why this is not the old step rail
 *
 * A previous design inset every tool row by 12dp and joined them with a 2dp bar. It
 * was removed because it cost width on every row of a turn and put every tool's mark
 * 12dp right of every other row's text. This one is different in every part that made
 * that one wrong: it is 1dp rather than 2dp, it exists only inside one answer card's
 * work section rather than on every tool row everywhere, and its 24dp column is the
 * same column the turn band's own 16dp mark and 8dp gap already use — so the group
 * header and its children share one text margin instead of acquiring a second one.
 * The final answer and every notice stay outside it.
 */
@Composable
private fun AnswerCardRows(
    rows: List<TranscriptRow>,
    text: Strings,
    expandedTurns: Set<Int>,
    onToggleTurn: (Int) -> Unit,
    thinkingAutoExpand: Boolean,
    onShowExtras: (PromptExtras) -> Unit,
    onOpenPictures: (PictureTarget) -> Unit,
) {
    val answerIndex = answerRowIndex(rows)

    var previous: TranscriptRow? = null
    var index = 0
    while (index < rows.size) {
        val row = rows[index]
        if (index != answerIndex && row.isStepRow()) {
            val start = index
            while (index < rows.size && index != answerIndex && rows[index].isStepRow()) {
                index++
            }
            val group = rows.subList(start, index)
            Column(Modifier.padding(top = rowGap(previous, group.first()))) {
                StepGroup(
                    rows = group,
                    text = text,
                    expandedTurns = expandedTurns,
                    onToggleTurn = onToggleTurn,
                    thinkingAutoExpand = thinkingAutoExpand,
                    onShowExtras = onShowExtras,
                    onOpenPictures = onOpenPictures,
                )
            }
            previous = group.last()
        } else {
            Column(Modifier.padding(top = rowGap(previous, row))) {
                key(row.key) {
                    TranscriptCardRow(
                        row = row,
                        text = text,
                        expandedTurns = expandedTurns,
                        onToggleTurn = onToggleTurn,
                        thinkingAutoExpand = thinkingAutoExpand,
                        onShowExtras = onShowExtras,
                        onOpenPictures = onOpenPictures,
                    )
                }
            }
            previous = row
            index++
        }
    }
}

/** A tool call or an assistant fragment: one step of the turn's work. */
private fun TranscriptRow.isStepRow(): Boolean = when (this) {
    is TranscriptRow.Message -> item is ChatItem.Tool || item is ChatItem.Assistant
    is TranscriptRow.TurnSummary -> false
}

/**
 * The index of the row that is the turn's answer, or -1 while there is none.
 *
 * The answer is the **last work row** when that row is an assistant row carrying text.
 * Two failures are avoided by looking at the end rather than at every assistant row.
 * A tool-using turn narrates while it works (`我先看一下项目结构。`), and that sentence is
 * followed by the tool calls it announces, so it is not the answer — an `indexOfLast`
 * over every text-bearing assistant row would lift it out of the spine and drop it back
 * when the real answer arrived. And a turn that has not produced its answer yet ends in
 * a tool row, so there is no answer row to keep outside the spine until the last row is
 * actually text. `showMeta` is deliberately not consulted: while an answer streams it
 * is false on every row, and a layout that keyed on it would move the answer out of the
 * spine on the turn's last token.
 */
internal fun answerRowIndex(rows: List<TranscriptRow>): Int {
    val lastWork = rows.indexOfLast { it.isStepRow() }
    if (lastWork < 0) return -1
    val item = (rows[lastWork] as? TranscriptRow.Message)?.item
    return if (item is ChatItem.Assistant && item.text.isNotBlank()) lastWork else -1
}

/**
 * A run of steps under one vertical rule.
 *
 * The rule is drawn in the outer column so the 24dp content inset does not move it;
 * the last row's bottom and the first row's top are the group's own bounds, which is
 * where ZCode's group rule stops too. A group of one row draws no rule — a line beside
 * a single line of text is a bracket around nothing.
 */
@Composable
private fun StepGroup(
    rows: List<TranscriptRow>,
    text: Strings,
    expandedTurns: Set<Int>,
    onToggleTurn: (Int) -> Unit,
    thinkingAutoExpand: Boolean,
    onShowExtras: (PromptExtras) -> Unit,
    onOpenPictures: (PictureTarget) -> Unit,
) {
    val rail = MiuixTheme.colorScheme.onSurface.copy(alpha = STEP_RAIL_ALPHA)
    // A group of one draws no rail (see the KDoc below), so it has no mark to stand in
    // for the step's own: a lone thinking step keeps its brain. The moment a second
    // step arrives the rail appears and the marks drop, which is the existing rule for
    // every row in a group.
    val nested = rows.size > 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (nested) {
                    drawLine(
                        color = rail,
                        start = Offset(STEP_RAIL_X.toPx(), 0f),
                        end = Offset(STEP_RAIL_X.toPx(), size.height),
                        strokeWidth = STEP_RAIL_WIDTH.toPx(),
                    )
                }
            }
            .padding(start = STEP_GROUP_INSET),
    ) {
        rows.forEachIndexed { groupIndex, step ->
            Column(
                Modifier.padding(
                    top = if (groupIndex == 0) 0.dp else rowGap(rows[groupIndex - 1], step),
                ),
            ) {
                key(step.key) {
                    TranscriptCardRow(
                        row = step,
                        text = text,
                        expandedTurns = expandedTurns,
                        onToggleTurn = onToggleTurn,
                        thinkingAutoExpand = thinkingAutoExpand,
                        onShowExtras = onShowExtras,
                        onOpenPictures = onOpenPictures,
                        nested = nested,
                    )
                }
            }
        }
    }
}

/** The step spine's geometry: the rule, its column, and the text it opens onto. */
private val STEP_GROUP_INSET = 24.dp
private val STEP_RAIL_X = 8.dp
private val STEP_RAIL_WIDTH = 1.dp

/** How much ink the rule carries: a hairline, not a divider. */
private const val STEP_RAIL_ALPHA = 0.14f

/**
 * The message in an answer document that carries the time, or null.
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
 * The drawer's width, as a share of the screen.
 *
 * The reference drawing leaves a strip of the conversation visible down the trailing
 * edge — about a fifth of the screen at this share — and that strip is the whole reason
 * the panel reads as a layer rather than as a page: a drawer that covered the screen would
 * be a second screen. It was 0.86 when the drawer was first drawn; the wider strip is
 * deliberate, because the card's rounded edge needs to fit in the part of it
 * the reader can see. The prompt bubble's width cap is independent of the drawer.
 */
private const val DRAWER_SHARE = 0.79f

/**
 * The widest the drawer gets, whatever the window is.
 *
 * A phone at 1080px lands at 853px, well under this. A tablet would not: 79% of a
 * 1600dp window is a page, and a drawer that covers the conversation stops being a drawer.
 * 420dp is the width of a comfortable reading column, which is what a list of titles is.
 */
private val DRAWER_MAX_WIDTH = 420.dp

/**
 * How much the card shrinks as the drawer comes out.
 *
 * Five percent: enough to put its edges inside the window's, which is what reads as "this
 * is a card now"; not enough to be noticed as a zoom.
 */
private const val CHAT_SHRINK = 0.05f

/**
 * The radius the conversation is cut with while it is a card over the drawer.
 *
 * 32dp, up from the 24dp the card was drawn at first. 24dp is the *menu* panel's figure
 * (`PiCorner.popup`), and a surface the size of the window cut with a panel's corner reads
 * as a small object's radius carried over: the card's trailing pair of corners is the whole
 * of the "this is a card" signal while the drawer is out, so it is cut to the page rather
 * than to a card in a list.
 *
 * One step above [PiCorner.popup] rather than beside it, because this is the only surface
 * in the app as large as the window and a corner has to scale with what it cuts. 32dp is
 * also the figure at which the card's curve stays concentric with the chrome it cuts past:
 * the composer's field frame keeps the band's 12dp gutter and [BAND_AIR] above the card's
 * bottom, so its own [PiCorner.radius] corner arc is centred 12 + 20 = 32dp from the card's
 * corner — the same centre a 32dp card corner has — and the two arcs run a constant 12dp
 * apart. A larger figure pulls them together: at 40dp the centres separate and the closest
 * points are about 9dp apart, which is the card's corner starting to reach the control. The
 * layer's [CHAT_SHRINK] scale draws the curve at 30.4dp on screen.
 */
private val PAGE_CARD_CORNER = 32.dp

/**
 * The composer band's bottom inset. The surface that fills it is the band's own
 * background (see `ChatPage`), which spans this padding as well as the content.
 *
 * **One air, not two.** The band adds [BAND_AIR] above whichever edge is lowest — the
 * keyboard, the navigation strip, or the inset the root consumed before handing the page
 * its rectangle — and the same number on both edges. The first design had a smaller air
 * above a keyboard (10dp) than above the gesture strip (15dp), and the difference was a
 * hop at the end of every close: the band followed the keyboard's inset to zero and then
 * moved another 5dp as the air changed. The height is derived from the window's *safe*
 * edge rather than from any layout above it, so the number the band is drawn at and the
 * number the transcript's tail reserves are one measurement.
 *
 * Both inset heights are read **in the measure pass**: a window in `adjustResize` mode
 * reports no bottom inset at all, so in that mode the air *is* the whole gap.
 *
 * The declaration order at the call site is load-bearing: `onSizeChanged` must sit
 * *outside* this modifier to see the padding it adds. Inside, it publishes the content's
 * height, and the page pads its transcript's tail and lifts the jump-to-latest control by
 * that number — measured, 95 px of under-count at this density, which put the disc behind
 * the chip row. See §7.1.
 */
private fun Modifier.bandBottom(
    ime: WindowInsets,
    bars: WindowInsets,
    /**
     * Whether the IME's inset moves the band.
     *
     * False while the conversation drawer is out: its search field and its rename
     * dialog raise the keyboard over the drawer, which covers the conversation, so
     * the composer is not the field being typed into and must stay at the panel's
     * bottom edge instead of following an inset it did not cause.
     */
    liftByIme: Boolean = true,
): Modifier = layout { measurable, constraints ->
    val applied = bandInset(ime, bars, liftByIme)
        .let { if (constraints.hasBoundedHeight) it.coerceAtMost(constraints.maxHeight) else 0 }
    val inner = if (applied > 0) {
        constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight - applied)
    } else {
        constraints
    }
    val placeable = measurable.measure(inner)
    layout(placeable.width, placeable.height + applied) {
        placeable.placeRelative(0, 0)
    }
}

/**
 * The band's bottom inset in px, for a measure or a draw.
 *
 * **One air, not two.** The band used to add 10dp above a keyboard and 15dp above the
 * gesture strip, so a close ended with a 5dp hop after the last inset step — on a phone
 * whose keyboard animation is short that reads as the field jumping on its own. The air is
 * the same on both edges now, and the only thing that changes during a close is the inset
 * itself, which falls in one direction and never turns back.
 */
private fun Density.bandInset(
    ime: WindowInsets,
    bars: WindowInsets,
    liftByIme: Boolean = true,
): Int = maxOf(if (liftByIme) ime.getBottom(this) else 0, bars.getBottom(this)) +
    BAND_AIR.roundToPx()

/**
 * The air between the composer's field and whatever is below it.
 *
 * **One value for both edges.** Ten above a keyboard and fifteen above the gesture strip
 * were two answers to one question, and the difference between them was a hop at the end of
 * every close: the band tracked the keyboard's inset down to nothing and then moved another
 * 5dp as the air changed, which reads as the field jumping after the keyboard has gone.
 * Twelve on top of the 10dp foot measures 22dp of gap — near enough to the ten the keys
 * asked for and the fifteen the strip asked for that one number is the honest answer.
 */
private val BAND_AIR = 12.dp

/**
 * How far the transcript fades above the composer's field.
 *
 * The fade's solid edge is the field's top: everything below it is the page, and the
 * conversation dissolves over this distance above it — the same shape the top of the page
 * uses at the status bar.
 */
private val BAND_FADE = 88.dp

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
 * Tighter than the answer card's 16dp inset: a prompt is a sentence or two, and a
 * frame wider than its content is a box with a word in it.
 */
private val PROMPT_CARD_PADDING = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

/**
 * The answer card's own inset, on every side.
 *
 * [Card]'s default inside margin is zero, so the card states its own, and
 * [MAX_TRANSCRIPT_WIDTH] adds it back because the transcript's cap is a statement
 * about the prose measure rather than about the frame around it.
 */
private val ANSWER_CARD_INSET = 16.dp

/** One row of a card, whichever kind it is. */
@Composable
private fun TranscriptCardRow(
    row: TranscriptRow,
    text: Strings,
    expandedTurns: Set<Int>,
    onToggleTurn: (Int) -> Unit,
    thinkingAutoExpand: Boolean,
    onShowExtras: (PromptExtras) -> Unit,
    onOpenPictures: (PictureTarget) -> Unit,
    /**
     * Whether the row is drawn inside the turn's step spine.
     *
     * The spine owns the left rule and the 24dp text column; a nested step does not
     * repeat its family mark, because the group already says that these rows are one
     * run of work. See [StepGroup].
     */
    nested: Boolean = false,
) {
    when (row) {
        is TranscriptRow.Message -> when (val item = row.item) {
            is ChatItem.User -> PromptBody(item, text, onShowExtras, onOpenPictures)

            is ChatItem.Assistant ->
                AssistantBlock(item, text, row.showReasoning, nested, thinkingAutoExpand)

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
 * content, it opens the turn, and it carries the reader's own furniture — the pictures
 * they attached and the extras chip, which nothing the model says has.
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
    onOpenPictures: (PictureTarget) -> Unit,
) {
    // The prompt is the last thing the reader wrote; what follows it is the other
    // speaker. The space *between* the two cards is `rowGap`'s job — it is the only
    // place that knows whether an answer, a fold band or a tool card comes next.
    val extras = remember(item.key, item.text) { parsePromptExtras(item.text) }
    // The chip is the file-path block's control. An image note is machinery about a
    // picture, not a paragraph the prompt should wear: it is shown by the picture
    // viewer's own info button, next to the picture it describes.
    val showExtrasChip = extras.filePaths.isNotEmpty()
    Column {
        // The machinery first: file paths are one chip above the words, the same
        // position the image-count chip has always held. Tapping it opens the full
        // paths in a sheet — they do not belong in the sentence the reader typed.
        if (showExtrasChip) {
            PromptExtrasChip(
                extras = extras,
                text = text,
                onClick = { onShowExtras(extras) },
            )
        }
        // The pictures the reader sent, drawn: one at its own aspect, several as a
        // grid, and the old count line only when none of them can be read
        // ([PromptPictures] has the three cases and why the count survives).
        if (item.imageCount > 0) {
            PromptPictures(
                keys = item.imageKeys,
                count = item.imageCount,
                text = text,
                onOpen = onOpenPictures,
                notes = extras.imageNotes,
                modifier = Modifier.padding(
                    top = if (showExtrasChip) 6.dp else 0.dp,
                    bottom = if (extras.displayText.isBlank()) 0.dp else 6.dp,
                ),
            )
        }
        if (extras.displayText.isNotBlank()) {
            Text(
                text = extras.displayText,
                modifier = Modifier.padding(
                    top = if (showExtrasChip || item.imageCount > 0) 6.dp else 0.dp,
                ),
                style = transcriptBodyStyle(),
            )
        }
    }
}

/**
 * The chip that replaces a prompt's file-path list.
 *
 * A 14dp glyph and a small label, like the image count beside it, and the chevron is
 * what says this one opens something. The label is a count rather than a title
 * (`2 files`) because that is the fact the reader wants before deciding whether to
 * look inside. Image notes are deliberately absent: a note is machinery about one
 * picture, it made the prompt bubble carry an English metadata line's count, and the
 * viewer's info button is where a note belongs.
 *
 * ## Why it is a pill on a step of its own
 *
 * This is the one control inside a prompt: tapping it opens the paths in a sheet, so it
 * is an action and it takes the fully round shape — the page's shape for an action, the
 * same one the composer's send button wears. Its fill is `PiTone.inset()`, the wash
 * every quotation on this page is drawn in: the prompt card is the accent's own wash
 * now, so the chip has to be a *step* from whatever that card turns out to be rather
 * than a palette role chosen against the grey it used to be. The height is 28dp — the
 * transcript's own pill, the size the tool card's status uses — so the row of machinery
 * above a prompt cannot change the card's height; and it is a minimum, because the
 * app's text-size setting draws the label larger and a fixed height would clip it.
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
        color = PiTone.inset(),
        // Normal ink, not the variant's own. `onSecondaryContainerVariant` is #A8A8A8
        // — a *disabled* grey in this palette (it is what `disabledOnSecondaryVariant`
        // is built from) — so a chip carrying the number of things this prompt will be
        // sent with read as a control that was turned off. It is a value, so it is drawn
        // in the ink every other value in the composer is drawn in.
        contentColor = MiuixTheme.colorScheme.onSurface,
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
                text = text.chat.extrasCount(extras.filePaths.size, 0),
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
 * The sheet a [PromptExtrasChip] opens: every file path, in full.
 *
 * The heading is Miuix's [SmallTitle] — the library's own name for a group inside a
 * page: small, secondary, and deliberately quiet, so a heading is never mistaken for
 * something tappable. The rows under it are the sheet's body: a plain scrolling column
 * rather than a lazy list, because a prompt's machinery is a handful of paths and the
 * sheet's own window already bounds the height. Image notes are not here; they belong
 * to the picture they describe and live behind the viewer's info button.
 */
@Composable
private fun PromptExtrasSheet(extras: PromptExtras, text: Strings) {
    Column(
        Modifier
            .fillMaxWidth()
            // A sheet body standing in for a page, so it gets the page's elastic end —
            // and *before* `verticalScroll`, for the reason `PiOverScroll.kt` gives:
            // a connection placed after the scroller is downstream of it and is
            // never dispatched to.
            .piOverScroll()
            .verticalScroll(rememberScrollState())
            // Inside the scroll, not outside it: the inset is scrolled past rather than shown
            // as a band of the panel's own fill under the text.
            .padding(bottom = piSheetBottomInset()),
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
    }
}

/**
 * The time under a message.
 *
 * The stamp starts one corner radius in from the card's side on the message's own
 * edge, not flush with it: the card is a rounded rectangle and the stamp is drawn
 * *under* it, so a line starting at the card's left edge reads as though it begins
 * inside the bottom-left arc. The flat part of the bottom edge begins one
 * [PiCorner.radius] in, and that is where the first glyph stands; on the reader's
 * own card the same inset is taken off its trailing edge so the last glyph ends on
 * the flat part there.
 *
 * Grey, deliberately not the accent: the accent belongs to what is live and to the
 * turn's own work line; a finished message's time is furniture.
 */
@Composable
private fun MessageMeta(
    item: ChatItem,
    text: Strings,
    /** Which edge of the transcript the message this row belongs to sits on. */
    alignment: Alignment.Horizontal,
    /** Whether the personalization switch lets this row draw its stamp at all. */
    show: Boolean,
) {
    if (!show) return
    if (item is ChatItem.Assistant && (item.isStreaming || item.text.isBlank())) return

    // The stamp is the whole row, so an item with no time draws nothing rather than
    // an empty line: the space belongs to the message, not to a missing label.
    val now = remember { System.currentTimeMillis() }
    val stamp = remember(item.createdAt, now) {
        if (item.createdAt > 0L) text.chat.messageTime(item.createdAt, now) else null
    } ?: return

    val flatEdge = PiCorner.radius
    val onEnd = alignment == Alignment.End
    Row(
        modifier = Modifier.padding(
            start = if (onEnd) 0.dp else flatEdge,
            end = if (onEnd) flatEdge else 0.dp,
            top = 2.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stamp,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

/**
 * The text a message's menu copies: the source, not its rendering.
 */
private fun itemSource(item: ChatItem): String = when (item) {
    is ChatItem.User -> item.text
    is ChatItem.Assistant -> item.text
    is ChatItem.Tool -> item.output.ifBlank { item.argumentsJson }
    is ChatItem.Notice -> item.text
}

internal data class MessageActionPoint(
    val target: ChatItem,
    val throughTarget: Int,
    val prompt: ChatItem.User?,
    val beforePrompt: Int,
)

/** Resolve against the unfolded message order, not the mixed list of cards, bands and steps. */
internal fun messageActionPoint(items: List<ChatItem>, key: String?): MessageActionPoint? {
    if (key == null) return null
    var count = 0
    var prompt: ChatItem.User? = null
    var beforePrompt = 0
    for (item in items) {
        when (item) {
            is ChatItem.User -> {
                prompt = item
                beforePrompt = count
            }
            is ChatItem.Assistant -> Unit
            else -> continue
        }
        count++
        if (item.key == key) return MessageActionPoint(item, count, prompt, beforePrompt)
    }
    return null
}

/** The subject of the edit dialog: which prompt, and where its branch starts. */
private data class EditPrompt(val prompt: ChatItem.User, val forkCount: Int)

/**
 * Copies the conversation up to [count] and sends [text] into the copy.
 *
 * pi's sessions are append-only, so "regenerate" and "edit" cannot replace a
 * message in place: the branch is a new file whose history ends before the prompt,
 * and the prompt is sent again. The original conversation is untouched, which is
 * what makes the action safe to try — the old answer is still in the drawer.
 */
private fun forkAndSend(
    appContext: Context,
    scope: CoroutineScope,
    session: pi.kit.mob.pi.PiAgentSession,
    workspace: String,
    count: Int,
    text: String,
    imageKeys: List<String>,
    toasts: PiToast?,
    failed: String,
) {
    scope.launch {
        val path = withContext(Dispatchers.IO) { session.forkAt(count) }
        if (path == null) {
            toasts?.show(failed)
            return@launch
        }
        val opened = session.host.openConversation(path, workspace)
        if (opened == null) {
            toasts?.show(failed)
            return@launch
        }
        val images = withContext(Dispatchers.IO) { cachedImages(appContext, imageKeys) }
        opened.sendPrompt(text, images)
    }
}

/**
 * The prompt's pictures, as the wire takes them, from the app's own copies.
 *
 * The session file holds the base64 pi echoed, but the cache is the same bytes
 * named by the digest of that text, so a branch can send the picture that was
 * sent without reading a multi-megabyte record. A picture whose copy is gone is
 * omitted: a branch with one fewer attachment is better than a send that fails.
 */
private fun cachedImages(context: Context, keys: List<String>): List<ImageAttachment> =
    keys.mapNotNull { key ->
        val file = ChatPictureCache.file(context.filesDir, key)
        if (!file.isFile) return@mapNotNull null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
        val mime = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() ->
                "image/png"

            bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() ->
                "image/webp"

            else -> "image/jpeg"
        }
        ImageAttachment(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)
    }

/**
 * The prompt, in a field, with the branch it will start stated underneath.
 *
 * The field is the message's own words — the machinery chip's paths and image
 * notes are not prose — and the pictures travel with it separately. Confirm sends
 * into a fork; the original conversation is never modified.
 */
@Composable
private fun EditPromptDialog(
    show: Boolean,
    text: Strings,
    initial: String,
    hasImages: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val value = remember { TextFieldState(initial) }
    val typed = value.text.toString()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(show) {
        if (show) {
            value.setTextAndPlaceCursorAtEnd(initial)
            withFrameNanos { }
            withFrameNanos { }
            focus.requestFocus()
            keyboard?.show()
        }
    }
    PiCustomDialog(
        show = show,
        title = text.chat.editPromptTitle,
        summary = text.chat.editPromptSummary,
        onDismissRequest = onDismiss,
    ) {
        GlassTextField(
            state = value,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .piFieldHeightForPlaceholder(),
            label = text.chat.editPromptField,
            useLabelAsPlaceholder = true,
            lineLimits = TextFieldLineLimits.MultiLine(2, 6),
            cornerRadius = PiCorner.radius,
            colors = GlassTextFieldDefaults.textFieldColors(
                borderColor = Color.Transparent,
            ),
            cursorBrush = piFieldCursor(),
        )
        PiDialogAnswers(
            dismiss = text.common.cancel,
            onDismiss = onDismiss,
            confirm = text.chat.send,
            onConfirm = { onConfirm(typed) },
            confirmEnabled = typed.isNotBlank() || hasImages,
            modifier = Modifier.padding(top = 12.dp),
            glass = LocalPiBackdrop.current != null,
        )
    }
}

/**
 * The rows a message's menu offers.
 *
 * One list of [MenuAction]s rather than three rows drawn by hand: a menu is a component —
 * its panel, row height, press fill and dismissal are the popup's — and the same
 * [PiPopupMenu] the drawer's row actions and the files page's row menu open. The three
 * actions read as one list: copy the source, open it in a sheet to select text from, or
 * branch the conversation from this message. The branch needs the *count* of message
 * records up to the card, which is what the item scope knows and the item itself does
 * not.
 *
 * The copy ends with a toast. The panel closes on the tap, so there is no glyph left to
 * turn into a check the way a code block's copy button does — a copy that says nothing
 * is indistinguishable from a tap that missed.
 */
private fun messageMenuItems(
    target: ChatItem,
    counted: Int,
    prompt: ChatItem.User?,
    forkCount: Int,
    streaming: Boolean,
    text: Strings,
    session: pi.kit.mob.pi.PiAgentSession,
    workspace: String,
    clipboard: Clipboard,
    scope: CoroutineScope,
    toasts: PiToast?,
    onDismiss: () -> Unit,
    onSelectSource: (ChatItem) -> Unit,
    onEdit: (ChatItem.User, Int) -> Unit,
    onRegenerate: (ChatItem.User, Int) -> Unit,
): List<MenuAction> = buildList {
    add(
        MenuAction(
            title = text.chat.copyMessage,
            leading = { Icon(MiuixIcons.Copy, contentDescription = null) },
            onClick = {
                onDismiss()
                scope.launch { clipboard.setClipEntry(clipEntryFor(itemSource(target))) }
                toasts?.show(text.chat.copied)
            },
        ),
    )
    add(
        MenuAction(
            title = text.chat.selectText,
            // A borrowed glyph's size is a call-site decision (§12.3): this menu's ink sits
            // at about 19.5dp, and Material Symbols' figure fills three quarters of its
            // own box, so it is drawn in a [SELECT_TEXT_GLYPH] box — larger than the 24dp
            // cell, whose transparent spare dp are what keep this row's label on the same
            // column as the other two.
            leading = {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_select_text),
                        contentDescription = null,
                        modifier = Modifier.size(SELECT_TEXT_GLYPH),
                    )
                }
            },
            onClick = {
                onDismiss()
                onSelectSource(target)
            },
        ),
    )
    // The answer's own action: the prompt that produced it is re-sent to a fresh
    // branch, which is the closest pi's append-only session can get to chat-demo's
    // "another answer under the same question". A running turn has no finished file
    // to copy, so neither action is offered while it streams.
    if (!streaming && prompt != null && target is ChatItem.Assistant) {
        add(
            MenuAction(
                title = text.chat.regenerate,
                leading = { Icon(MiuixIcons.Refresh, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onRegenerate(prompt, forkCount)
                },
            ),
        )
    }
    if (!streaming && prompt != null) {
        add(
            MenuAction(
                title = text.chat.editAndResend,
                leading = { Icon(MiuixIcons.Edit, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onEdit(prompt, forkCount)
                },
            ),
        )
    }
    if (!streaming && counted > 0) {
        add(
            MenuAction(
                title = text.chat.fork,
                leading = { Icon(MiuixIcons.Share, contentDescription = null) },
                onClick = {
                    onDismiss()
                    scope.launch {
                        val path = withContext(Dispatchers.IO) { session.forkAt(counted) }
                        val opened = path?.let { session.host.openConversation(it, workspace) }
                        if (opened == null) toasts?.show(text.chat.forkFailed)
                    }
                },
            ),
        )
    }
}

/**
 * One row of a menu the page builds as data: a title, its leading mark, and what a tap
 * does.
 *
 * The mark is a composable rather than an `ImageVector` because one of these menus draws a
 * vector at its own size and another draws a painter — a borrowed glyph's size is a
 * call-site decision, and that decision is the call site's to make.
 */
private class MenuAction(
    val title: String,
    val leading: @Composable () -> Unit,
    val onClick: () -> Unit,
)

/**
 * The box the select-text figure is drawn in, inside the menu's 24dp icon cell.
 *
 * A borrowed glyph's size is a call-site decision (ARCHITECTURE §12.3), and the number is
 * chosen from what the two marks beside it *draw*, not from the box they are given:
 * measured on the medium phone at density 2.625, the copy mark's ink is 51×51 px (19.4dp
 * square) and the share mark's is 41×53 (15.6 by 20.2dp) — this menu's set sits at about
 * 19.5dp of ink, whatever the vectors' own viewports say. Material Symbols'
 * `text_select_start` keeps its ink inside 720 of its 960 grid units, so 26dp is what
 * lands it there: at 24 the figure measured 38×38 px, a quarter smaller than the copy
 * mark above it, and at 26 it measures 49×47 — two pixels off in both axes, which is the
 * closest a 960-unit grid gets to a 1024-unit one at this size. The box is wider than the
 * 24dp cell it sits in on purpose: the spare dp are transparent, so all three labels still
 * line up on one column.
 */
private val SELECT_TEXT_GLYPH = 26.dp

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
    nested: Boolean = false,
    /** Whether the current step's reasoning opens while it streams and folds when it ends. */
    thinkingAutoExpand: Boolean = false,
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

    // The personalization switch's two moments. The block opens when this step starts
    // producing reasoning and folds when the step is over; `isNotBlank` is a key
    // because the first token of thinking arrives after the row itself, and a key on
    // the text would re-open a block the reader had just closed on every token.
    LaunchedEffect(item.status, thinkingAutoExpand, item.thinking.isNotBlank()) {
        if (!thinkingAutoExpand) return@LaunchedEffect
        showThinking = shouldExpandThinking(
            enabled = thinkingAutoExpand,
            status = item.status,
            hasThinking = item.thinking.isNotBlank(),
        )
    }

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
    val hasBody = item.thinking.isNotBlank() || item.text.isNotBlank() ||
        item.error != null || item.status == ReplyStatus.Stopped
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
                nested = nested,
            )
            if (showThinking) {
                // The gap is the point: previously the control and the block it
                // opened were flush against each other, so the first line of the
                // reasoning read as part of the button.
                Spacer(Modifier.height(6.dp))
                Box(Modifier.bringIntoViewRequester(reasoningBringIntoView)) {
                    // A streaming block follows its own newest token. The transcript's
                    // follow rule is about the list; this is the block's own scroll, and
                    // the report is that opening it while the model thinks left the
                    // reader at the top of a growing panel.
                    ReasoningBlock(
                        thinking = item.thinking,
                        follow = item.status == ReplyStatus.Streaming,
                    )
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
            MarkdownText(item.text, streaming = item.isStreaming)
        }

        if (item.status == ReplyStatus.Error) {
            item.error?.let {
                Text(
                    text = it,
                    color = PiTone.error(),
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
        }
        if (item.status == ReplyStatus.Stopped) {
            // A stop is a state the reader chose, not a failure: the partial answer
            // above is theirs, and the menu offers to regenerate it. The line is the
            // quiet ink a fact uses, not the error role.
            Text(
                text = text.chat.answerStopped,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
    }
}

/** How many lines the field grows to before it scrolls inside itself. */
private const val COMPOSER_MAX_LINES = 8

/**
 * Whether the personalization switch should have this reasoning block open right now.
 *
 * Only the current step's block, and only while it is streaming and has something to show;
 * the moment the step ends the same rule closes it. A block the reader opened by hand is
 * not governed here — this is the automatic state, written on the two edges of a step, not
 * a value the toggle recomputes continuously.
 */
internal fun shouldExpandThinking(
    enabled: Boolean,
    status: ReplyStatus,
    hasThinking: Boolean,
): Boolean = enabled && status == ReplyStatus.Streaming && hasThinking

/**
 * The room under the composer's frame: the agent's state line and its margins.
 *
 * The line is drawn by the root at the window's bottom edge, so this is the space it needs
 * between the frame and the gesture strip — the composer's own 10dp of air plus the ~12dp
 * the caption occupies.
 */
private val COMPOSER_FOOT = 10.dp



/**
 * The field's own inset: 14dp beside the text and 14dp above and below it.
 *
 * Miuix's default is 16dp on both axes, which is a settings field's height; a
 * composer's field is written in, not filled in, and a smaller vertical inset keeps a
 * one-line draft a field rather than a box. The vertical figure is 14dp rather than
 * 12dp because the placeholder is drawn beside the field (see the composer's
 * `TextField`): the library's label used to carry 2dp of platform font padding above
 * and below its text, and moving that air into the inset keeps the resting frame the
 * height it had before the placeholder stopped being measured.
 */
private val COMPOSER_FIELD_INSET = DpSize(14.dp, 14.dp)


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
 * 样式"). So the composer is the strip the app had before the panel: a full-bleed,
 * bottom-aligned overlay band on the page's `surface`, so the foot of the page is one
 * band of chrome while a row passes behind it and dissolves; and the field inside it is
 * Miuix's own [TextField] living in the kit's glass frame — the frame is the only fill
 * and outline, while the field itself paints nothing and keeps the focus ring off
 * through `piFieldColors` + `piFieldCursor` (§7.1).
 *
 * Nothing about the *contents* changed. The chips stay where they were, and they
 * still read as the settings the next message goes with rather than as part of the
 * message, because a chip between the field and the keyboard sits in the busiest strip
 * on a phone screen.
 *
 * ## The three rows, and one control
 *
 * ```
 *  (🧠 中) (🤖 deepseek-v4) (⚡ 缓存…)        the settings this message goes with
 *  ╭────────────────────────────────────╮
 *  │ Ask Pi…                            │   the field, growing upward
 *  ╰────────────────────────────────────╯
 *   ( ⌘ ) (+)                    (◔) (→)      the row a thumb reaches
 * ```
 *
 * The chips are above the field rather than below it: a chip between the
 * field and the keyboard sits in the busiest strip on a phone screen and reads as
 * part of the message being written, where a chip above the field reads as the
 * setting the next message will be sent with. They are [ControlChip]s — a pill on the
 * palette's high surface step, drawn with a Miuix `Surface` so the press feedback and
 * the clip are the library's — and each one composes its own [OverlayListPopup], which
 * is what anchors the menu to the chip that opened it. They scroll horizontally once
 * they do not fit, so a long profile name cannot squeeze the last one off screen.
 *
 * The `◔` in the action row is the context meter, and it is there because it is a
 * *reading* rather than a choice: see [ContextRing]. The agent's **state** is not one
 * of the chips either: it is a fact rather than a control, and it lives in the header.
 * Nor is the *live* tone — live means a turn is running, and nothing in a composer is.
 *
 * The action row is the field's last line: the frame holds the field and the row's
 * four controls, so the row a thumb reaches is inside the box the text is typed in.
 * They are grouped by what they do rather than by which side had room: the two that put
 * something into the message at the start edge, the reading and the send at the end.
 * The field grows *upward* out of the row rather than pushing it down the screen. The keyboard
 * gap under the frame is the composer's own (the `Spacer` at the end of the band, read in
 * the layout phase): the band continues under
 * it, so the composer floats clear of the keyboard instead of sitting on it.
 */
@Composable
private fun Composer(
    session: PiAgentSession,
    /** Where the field's top edge sits, so the band can anchor its rank there. */
    onFieldTop: (LayoutCoordinates) -> Unit,
    text: Strings,
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<PendingImage>,
    onRemoveImage: (String) -> Unit,
    /** Opens the pictures a prompt is about to carry, from the tile that was tapped. */
    onOpenPictures: (PictureTarget) -> Unit,
    isStreaming: Boolean,
    /** Whether the actions are available: send, the command list and `+`. */
    enabled: Boolean,
    /** Whether the text field takes input. True from the first frame. */
    inputEnabled: Boolean,
    agent: AgentStatus,
    facts: ComposerFacts,
    /** This conversation's launch mode; chat mode hides the controls that need tools. */
    mode: AgentMode,
    /** Which of the four menus is open, or null. Each chip asks whether it is the one. */
    openSheet: ComposerSheet?,
    onOpenSheet: (ComposerSheet) -> Unit,
    onCloseSheet: () -> Unit,
    onRunCommand: (BuiltInCommand) -> Unit,
    onPickAttachment: (String) -> Unit,
    /** The workspace of this conversation: the chip's label, and the menu's tick. */
    currentWorkspace: String,
    onSelectWorkspace: (String) -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // The saved configuration, collected so the model chip follows a change made
    // on the settings page. A plain read of the store here was the report that
    // "changing the model on the model page does not change the chat page": the
    // store was a snapshot with no notification, so this row was only rebuilt when
    // something else happened to recompose it — the agent's handshake, a tab
    // switch, or the picker being opened again.
    val config by session.settingsStore.profiles.snapshots.collectAsStateWithLifecycle()

    // The saved settings as well, for the thinking chip: the level is persisted in
    // SharedPreferences, so it is readable synchronously on the first frame and the
    // chip never has to wait for pi to answer `get_state`.
    val saved by session.settingsStore.settings.collectAsStateWithLifecycle()

    // The chat persona this conversation speaks with, and its identity after the
    // personalization page answers whatever the preset left unset. Read here because the
    // chip is the *first* control above the field in chat mode: the voice a message is
    // sent in is chosen before the model and the thinking level are.
    val presetId by session.chatPresetId.collectAsStateWithLifecycle()
    val presets by session.chatPresets.collectAsStateWithLifecycle()
    val chatPreset = presets.firstOrNull { it.id == presetId } ?: presets.firstOrNull()
    val chatIdentity = chatPreset?.identity(saved)
    // The network tools' switch, which stands where the work page's permission control
    // does — the row's leading group is where "what this message may do" is answered.
    val chatWeb by session.chatWebAccess.collectAsStateWithLifecycle()

    // What pi's catalogue on disk says each model's levels are, so a model switch shows
    // the new model's own list before pi has answered for it — see [levelsFor].
    val catalogueLevels by session.catalogueThinkingLevels.collectAsStateWithLifecycle()

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
        else -> text.chat.placeholder
    }
    // Chat mode has no tools, so the two controls that only mean something to an
    // agent with hands are not drawn: a workspace is where tools act, and the
    // permission tier is the rule they are judged by. Leaving them on screen would
    // offer settings that the process they configure cannot honour.
    val chatMode = mode == AgentMode.CHAT
    val haptics = LocalPiHaptics.current

    // Read here rather than taken as a parameter: it is only this card's own
    // dismissal that needs it, and the page's `focusManager` is the same object
    // either way — a `LocalFocusManager` is one per window.
    val fieldFocus = LocalFocusManager.current

    // Whether the field currently holds the focus, which is what tells a *first*
    // tap on it apart from a second one. See the field's pointer handler: the first
    // tap has to be allowed to focus the field and raise the keyboard, and the second
    // has to put away the selection toolbar the first one made possible.
    var fieldFocused by remember { mutableStateOf(false) }

    // The air the composer's frame leaves above the keyboard, and the two corrections in
    // how it is measured.
    //
    // The root's `imePadding` ends the page exactly at the keyboard's top edge, so
    // without a gap the frame — the page's last object — would be drawn against the
    // keyboard with both of its bottom corners on that edge, and a field that is part of
    // a keyboard looks like one.
    //
    // **Read at the composer, in the layout phase, and not in composition.** The value
    // is the same either way; what differs is *which* frame of the keyboard's animation
    // pays for it. Read in composition — which is what this was, and before that a
    // composition local above the scaffold — every step of the platform's inset report
    // recomposed this whole card: the field, the chip row, four icon buttons and the
    // send disc, ten-odd times per open or close. Recorded on the emulator with that
    // read still in composition, the band was a full 10dp above its resting place on the
    // last frame the keyboard was visible and stayed there for the recorder's next
    // frame — the beat at the end of a close that reads as the input box sliding down
    // slowly. A layout-phase read invalidates the layout and nothing else, so the band is
    // placed on every frame the platform reports instead of on every frame a
    // recomposition happens to complete; the same close then has it within 3dp of its
    // rest on the frame the keyboard goes and at rest one frame later. `imePadding` is a
    // layout-phase read at the root for the same reason.
    //
    // The emulator is draw-bound (`gfxinfo` reports a 44–69 ms median frame for the whole
    // animation, before and after), so its frame *times* do not settle a question about
    // one card's work; the position of the band on each frame does.
    //
    // The band's own foot is resolved in the layout pass by `bandBottom`
    // (`WindowInsets.ime` ∪ `WindowInsets.navigationBars`), and the root's `imePadding`
    // keeps the page above it in step. The inset is read there rather than here, so a
    // keyboard close moves the band without recomposing this subtree.


    // Measured here, read in the band's own draw: the field block's height is what puts
    // the solid part of the background exactly at the frame's top edge, without a second
    // layout pass and without the ramp being painted over the chip row.
    // The band: full bleed over the transcript, and **not a surface of its own**.
    //
    // The chips float on the page with nothing behind them — the conversation stays at
    // full contrast until it reaches the field — and the fade that hides it starts at the
    // field's own top edge, which is where the reference draws it. That is why the
    // gradient is on the field's wrapper below rather than on this box.
    //
    // The gesture strip's inset is the band's own, and it is resolved in *layout*: the
    // strip minus whatever the keyboard has already taken. Two inset reads in composition
    // — `isImeVisible` and a conditional padding — were what made a keyboard close
    // recompose this subtree on every step of its animation.
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            if (attachments.isNotEmpty()) {
                // Every chip reports the frame it is drawn in and the bitmap it already
                // holds, so the target a tap builds knows where *every* picked picture
                // lives — the viewer swipes between them and has to land on the one on
                // screen, not on the one it was opened from.
                val tiles = remember(attachments) {
                    mutableMapOf<String, PictureTileFrame>()
                }
                val view = LocalView.current
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments, key = { it.id }) { image ->
                        AttachmentChip(
                            image = image,
                            openLabel = text.chat.imageOpen,
                            removeLabel = text.chat.removeAttachment,
                            onRemove = { onRemoveImage(image.id) },
                            onLocated = { tiles[image.id] = it },
                            onOpen = {
                                // The pictures the viewer may swipe through: the picked
                                // images, not the file attachments — a document is a path
                                // this page sends, and has no picture behind it.
                                val previewable = attachments.filter { it.workspacePath == null }
                                onOpenPictures(
                                    PictureTarget(
                                        pictures = previewable.map { pending ->
                                            val tile = tiles[pending.id]
                                            PictureSource(
                                                uri = pending.uri,
                                                preview = tile?.preview,
                                                origin = tile?.coordinates
                                                    ?.takeIf { it.isAttached }
                                                    ?.screenRect(view),
                                            )
                                        },
                                        start = previewable.indexOfFirst { it.id == image.id }
                                            .coerceAtLeast(0),
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            // The controls, on a line of their own above the field.
            //
            // Above, not below, and that ordering is the whole point of the
            // layout: a chip below the field sits between the thing you are
            // typing and the keyboard, which is the busiest strip on a phone
            // screen, and it reads as part of the message you are writing. Above
            // it they read as what they are — the settings the next message will
            // be sent with.
            //
            // Four of them, and the first is the odd one out: the workspace is where
            // the agent works, so it is what the three values to its right apply *to*.
            // The context share used to be one of the four and is now the ring at the
            // other end of the action row, beside the send, because it is a reading
            // rather than a choice (see `ContextRing`); the permission tier is the
            // button in the action row rather than a chip (§14).
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
            // enough: on a phone a long chip is cut at the screen edge with nothing
            // between it and the edge to say the row continues, and a half-visible pill
            // at a hard edge reads as a layout fault rather than as a scroll. Each fade
            // is drawn in the band's own colour, and the draw pass reads the row's own
            // scroll state, so one appears exactly while there is content behind it.
            // It covers no touch: a `draw` pass takes no pointer events, so the chip
            // under a fade is still tappable where it shows.
            // What the workspace chip says: the folder's own name — the built-in one is
            // `workspace` — and whether it is the default. A workspace whose folder the
            // reader deleted is not in the list, and the chip falls back to the path's
            // last segment rather than to an empty label.
            val workspaces = rememberWorkspaceList(
                context = LocalContext.current,
                env = session.env,
                savedDefault = saved.defaultWorkspace,
            )
            val defaultWorkspacePath = saved.defaultWorkspace.ifBlank { session.env.workspacePath }
            val currentWorkspaceEntry = workspaces.firstOrNull { it.path == currentWorkspace }
            // The default's name while the agent is still resolving where it was launched:
            // the state flow that carries the workspace is empty for the first frames, and a
            // chip with a folder glyph and no word is a control that looks broken.
            val workspaceLabel = currentWorkspaceEntry?.label
                ?: java.io.File(currentWorkspace).name.ifBlank {
                    workspaces.firstOrNull { it.path == defaultWorkspacePath }?.label
                        ?: java.io.File(defaultWorkspacePath).name
                }
            val chipState = rememberLazyListState()
            // The band's own colour, read in composition: `MiuixTheme.colorScheme` is
            // a composable read and a `DrawScope` is not one, and the fade has to end
            // on exactly the colour behind the row or it is a visible rectangle. It is
            // the *band's* colour, so it moved with the band from `surfaceContainer` to
            // `surface` — a fade left on the old value would have been a white wash over
            // a grey strip.
            val bandColor = MiuixTheme.colorScheme.surface
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
                if (chatMode) {
                    // The first chip in chat mode: whose voice the next message is in.
                    // It leads the row because the preset is what the model and the
                    // thinking level are *about* — a persona with a translation prompt
                    // and a persona with a reasoning prompt do not want the same model —
                    // and because choosing it is the one thing chat mode's reader does
                    // before typing. The chip draws the persona's own avatar, which is
                    // also the mark the transcript shows beside its answers, so the chip
                    // and the reply are visibly the same speaker.
                    item {
                        val presetLabel = chatIdentity?.name?.ifBlank { null }
                            ?: text.settings.modeChat
                        ControlChipBase(
                            label = presetLabel,
                            onClick = { onOpenSheet(ComposerSheet.Preset) },
                            menu = { anchor ->
                                ControlChipMenu(
                                    show = openSheet == ComposerSheet.Preset,
                                    anchor = anchor,
                                    onDismiss = onCloseSheet,
                                ) {
                                    ChatPresetMenu(
                                        show = openSheet == ComposerSheet.Preset,
                                        text = text,
                                        presets = presets,
                                        selectedId = presetId,
                                        settings = saved,
                                        onPick = { id ->
                                            onCloseSheet()
                                            session.selectChatPreset(id)
                                        },
                                        onDismiss = onCloseSheet,
                                    )
                                }
                            },
                        ) {
                            if (chatIdentity == null) {
                                Icon(
                                    imageVector = MiuixIcons.Messages,
                                    contentDescription = null,
                                    modifier = Modifier.size(CHIP_GLYPH),
                                )
                            } else {
                                PiAvatar(
                                    speaker = AvatarSpeaker.AGENT,
                                    color = chatIdentity.color.seed,
                                    imagePath = chatIdentity.image.ifBlank { null },
                                    size = CHIP_AVATAR,
                                )
                            }
                        }
                    }
                }
                if (!chatMode) {
                    item {
                        // The workspace, first in the row and the only chip on this page
                        // that is not about the next message's request: it decides which
                        // folder the agent is standing in, so it is what every other value on
                        // the row applies *to*. Drawn as the folder's own name and nothing
                        // else: `（默认）` is a fact about the *list* — which one a new
                        // conversation starts in — so it belongs where the list is, in the
                        // menu and in the settings dialog, and a chip that carried it would be
                        // saying the same thing on every conversation forever.
                        //
                        // Chat mode omits it: with no tools there is no folder for the
                        // agent to act in, and the chip would be the first control on the
                        // page promising something the process cannot do.
                        ControlChip(
                            glyph = MiuixIcons.Folder,
                            label = workspaceLabel,
                            onClick = { onOpenSheet(ComposerSheet.Workspace) },
                        ) { anchor ->
                            // Keyed on the list's size: the popup's content is its own
                            // composition, and a workspace that arrived while the popup was
                            // closed did not reach it — measured on the history page adopting
                            // a `pi` that had been run by hand, where the settings dialog
                            // listed the folder and this menu did not. A new key is a new
                            // composition, which is the cheapest honest fix for a slot the
                            // library does not re-read.
                            key(workspaces.size) {
                                ControlChipMenu(
                                    show = openSheet == ComposerSheet.Workspace,
                                    anchor = anchor,
                                    onDismiss = onCloseSheet,
                                ) {
                                    WorkspaceMenu(
                                        show = openSheet == ComposerSheet.Workspace,
                                        text = text,
                                        workspaces = workspaces,
                                        current = currentWorkspace,
                                        defaultPath = defaultWorkspacePath,
                                        home = session.env.homePath,
                                        onPick = onSelectWorkspace,
                                        onAdd = onOpenWorkspacePicker,
                                        onDismiss = onCloseSheet,
                                    )
                                }
                            }
                        }
                    }
                }
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
                        label = thinkingLevelOf(facts, saved, config.activeProfile, catalogueLevels),
                        onClick = { onOpenSheet(ComposerSheet.Thinking) },
                    ) { anchor ->
                        ControlChipMenu(
                            show = openSheet == ComposerSheet.Thinking,
                            anchor = anchor,
                            onDismiss = onCloseSheet,
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
                            ?: facts.model?.takeIf { it.isUsable }?.id?.takeIf { it.isNotBlank() }
                            // Three answers, not two: nothing configured is a *state*
                            // the reader can act on, and calling it "loading" left a
                            // fresh install saying 正在加载模型 for ever — the report
                            // was a first launch with no model that never stopped
                            // loading.
                            ?: if (config.profiles.isEmpty()) text.chat.noModel else text.chat.loadingModel,
                        onClick = { onOpenSheet(ComposerSheet.Model) },
                    ) { anchor ->
                        ControlChipMenu(
                            show = openSheet == ComposerSheet.Model,
                            anchor = anchor,
                            onDismiss = onCloseSheet,
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
                        glyph = MiuixIcons.Stopwatch,
                        label = text.chat.cacheHit(facts.cachePercent),
                        // The same menu the context ring beside the send opens, and the
                        // same one name in [ComposerSheet]: the two are two readings of
                        // one report, so opening either asks the same question.
                        onClick = { onOpenSheet(ComposerSheet.Context) },
                    ) { anchor ->
                        ControlChipMenu(
                            show = openSheet == ComposerSheet.Context,
                            anchor = anchor,
                            onDismiss = onCloseSheet,
                        ) {
                            ContextMenu(session = session, text = text)
                        }
                    }
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
            // The field's **frame**: one container holding the text and the three
            // controls, at the kit's corner radius and in the library's raised role.
            //
            // The controls were moved out of this frame and into a row *under* it, on
            // the argument that a control below the field sits between the text and the
            // keyboard. That argument was right about the *keyboard* and wrong about the
            // frame: the field lost its box, the row read as page furniture rather than
            // as part of the composer, and the send control ended up as far from the
            // text it sends as the bar is wide. They are back inside, which is also
            // where the original composer had them — and the frame is what makes them
            // read as belonging to the text rather than floating under it.
            //
            // `PiGlass.panel(...)` (the opaque `surfaceContainerHighest` fallback is
            // white in the light scheme, #2D2D2D in the dark one)
            // against the band's `surface` (#F0F0F0) is the same step the chips above
            // take, so the composer and the controls are one floating-object vocabulary.
            // The band itself is now the tab strip's own colour rather than a step above
            // it (§7.1), so the foot of the page is one surface from the transcript to the
            // navigation bar and the two no longer meet at a seam.
            // The band's own background is drawn behind *everything* it holds (see the
            // `drawBehind` on the band's box): solid at the frame's top edge and below it,
            // and a ramp upward from there. Drawn here instead, the ramp would have to be
            // painted over the chip row — this box is a sibling *after* it, so its ink
            // lands on top of the chips.
            Box(
                Modifier
                    .fillMaxWidth()
                    // **The fade's anchor is the field's top edge**, and this box's top edge
                    // *is* that edge. Its position is reported up to the band, which paints
                    // the rank and the solid *behind* the chips — painted here instead, the
                    // rank would land over the chip row and wash the chips themselves out.
                    .onGloballyPositioned { onFieldTop(it) },
            ) {
            // The frame is cut with the same continuous corner the glass around it
            // draws. A `RoundedCornerShape` here clipped the content on a circular
            // 20dp curve inside a squircle glass edge; `GlassShape` is the renderer's
            // own continuous shape.
            val fieldShape = remember { GlassShape(PiCorner.radius) }
            Surface(
                color = Color.Transparent,
                shape = fieldShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .piGlass(fieldShape),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // The placeholder is drawn here rather than handed to the library's
                    // `label` slot. The label is a plain `Text` at 17sp with the platform's
                    // font padding, while the field's own line has no font padding and is
                    // 17sp `main`: the empty frame measured 182px against 171px after a
                    // single character, because the label's line box was 23dp against the
                    // text's 19dp. The library has no `placeholder` parameter, so the
                    // placeholder is a sibling that does not take part in the field's
                    // measurement — same `main` style, font padding off, at the field's own
                    // inside margin. A one-line hint, because the `!` hint that wrapped to
                    // two lines looked like a rendering fault.
                    Box(Modifier.fillMaxWidth()) {
                        TextField(
                            cornerRadius = PiCorner.radius,
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                // The frame supplies the page gutter; this is the text's own
                                // inset inside it, so the caret starts where the glyphs of a
                                // chip above do rather than on the frame's wall.
                                .padding(horizontal = 2.dp)
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
                            label = "",
                            maxLines = COMPOSER_MAX_LINES,
                            insideMargin = COMPOSER_FIELD_INSET,
                            // `main` is the field's own default and the style every other text
                            // field in the app already uses; the placeholder below is copied
                            // from the same line box so typing cannot move the frame.
                            textStyle = MiuixTheme.textStyles.main,
                            // The frame *is* the box: the field paints nothing and its focus
                            // ring animates to nothing, so the only outline on screen is the
                            // frame's own fill. `piFieldColors(background = Transparent)`
                            // states both halves at once — the ring's colour *is* the
                            // background's in this library.
                            colors = piFieldColors(background = Color.Transparent),
                            cursorBrush = piFieldCursor(),
                        )
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MiuixTheme.textStyles.main.copy(
                                    fontWeight = FontWeight.Medium,
                                    // The field's own line has no platform font padding; the
                                    // label's did, which is the height this placeholder would
                                    // otherwise bring back when the text is empty.
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                ),
                                color = MiuixTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .fillMaxWidth()
                                    .padding(
                                        start = COMPOSER_FIELD_INSET.width + 2.dp,
                                        end = COMPOSER_FIELD_INSET.width,
                                        top = COMPOSER_FIELD_INSET.height,
                                    ),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // The controls' row is the frame's own last line, so its
                            // inset is the frame's: 6dp at the sides keeps the two
                            // outer buttons off the rounded corner, and 4 below keeps
                            // them off the bottom wall. The field above carries no
                            // bottom padding of its own, which is why this row reads as
                            // attached to the text rather than stacked under it.
                            .padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                // ## The row's order: what goes in, then what happens to it
                //
                // ```
                //  ( ⌘ ) (🔒) (+)                          (◔) (→)
                //  └───────┬───────┘                      └──┬──┘
                //   put something into the message  what it becomes
                //     └ the tier in between decides how far the agent may go
                // ```
                //
                // The command list and the `+` are the row's left pair, and the
                // permission control sits between them in the same plain 40dp dress:
                // the two "add" buttons go into the message, and the tier is the rule
                // the agent is held to while it acts on what was added (§14). A reader
                // reaches for any of the three while deciding what to say rather than
                // after saying it. The ring and the send are the other half — one
                // reading and one action, both *about* the message rather than part of
                // it — and the weighted spacer between the halves is what keeps the
                // layout from depending on how wide the field's text is.
                //
                // The order before this was ` ⌘ … ◔ + → `: the `+` sat at the far end
                // beside the send, and the ring stood between it and the command
                // list. Nothing in that row was wrong on its own, and it could not
                // say the one thing this row has to say — which two of the four are
                // the same kind of control — so the two "add" buttons were as far
                // apart as the row is wide, with the two controls that are not adds
                // between them.
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
                PopupAnchor { anchor ->
                    IconButton(
                        onClick = { onOpenSheet(ComposerSheet.Commands) },
                        enabled = enabled,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_commands),
                            contentDescription = text.chat.commands,
                            // 17dp, the same box as the `+` beside it, and the size is
                            // a property of the glyph rather than of the button:
                            // Lucide's `command` draws its loops out to 21 of its 24
                            // units where Miuix's `Add` keeps its ink inside 14, so two
                            // glyphs need two measurements to end up at one drawn size.
                            // The `+`'s own comment has the arithmetic; this figure is
                            // stated here, at the call site, because the vector is the
                            // library's and is not edited (see `ic_commands.xml`).
                            modifier = Modifier.size(17.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    ControlChipMenu(
                        show = openSheet == ComposerSheet.Commands,
                        anchor = anchor,
                        onDismiss = onCloseSheet,
                    ) {
                        CommandMenu(
                            show = openSheet == ComposerSheet.Commands,
                            text = text,
                            onRun = onRunCommand,
                            onShell = { onOpenSheet(ComposerSheet.Shell) },
                            onDismiss = onCloseSheet,
                            chatMode = chatMode,
                        )
                    }
                }
                // The permission tier, between the two controls that put something into
                // the message and the two that decide what happens to it — between them
                // literally as well: the list, then this, then the `+`. It is neither of
                // it is the rule the agent is held to while it acts on what it was given,
                // and the composer is where the reader is standing when that rule
                // matters. One 40dp button with the tier's own mark and no label — a
                // fourth pill here would be a second row of chips inside the field's
                // frame — and the menu under it names all three tiers and what each
                // allows. The mark carries the tier in colour as well as in shape:
                // `unlock` in the accent is the one setting on this row with no refusals
                // behind it.
                //
                // Chat mode does not draw it. The tier judges tool calls, and chat mode
                // has no tools; a lock beside a chat field is a rule for nothing.
                if (!chatMode) {
                    PopupAnchor { anchor ->
                        val permission = saved.permissionMode
                        IconButton(
                            onClick = { onOpenSheet(ComposerSheet.Permission) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = permissionGlyph(permission),
                                contentDescription = text.chat.permissionChip(permission),
                                modifier = Modifier.size(17.dp),
                                tint = if (permission == PermissionMode.FULL_ACCESS) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                        ControlChipMenu(
                            show = openSheet == ComposerSheet.Permission,
                            anchor = anchor,
                            onDismiss = onCloseSheet,
                        ) {
                            PermissionMenu(
                                show = openSheet == ComposerSheet.Permission,
                                text = text,
                                current = permission,
                                onPick = { session.setPermissionMode(it) },
                                onDismiss = onCloseSheet,
                            )
                        }
                    }
                } else {
                    // Chat mode's own answer to the same question, in the same slot: the
                    // tier's control judges tool calls, and a chat conversation's tools
                    // are the network ones, so this button is their switch. It is a plain
                    // 40dp button like the tier's — the row's leading group is four
                    // controls in one dress — and its tint carries the state, because a
                    // second shape for "off" would be a second glyph to keep in step.
                    //
                    // The switch is per conversation and a launch flag: flipping it
                    // restarts this conversation when the turn settles, so the tools it
                    // promises are the ones the next request actually declares.
                    IconButton(
                        onClick = {
                            haptics.toggle(on = !chatWeb)
                            session.setChatWebAccess(!chatWeb)
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Search,
                            contentDescription = if (chatWeb) {
                                text.chat.webAccessOn
                            } else {
                                text.chat.webAccessOff
                            },
                            modifier = Modifier.size(17.dp),
                            tint = if (chatWeb) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                }
                // The `+`, immediately after the command list and in the same plain
                // dress: the two controls that put something into the message are one
                // group at the field's left edge, which is the whole of this row's
                // order (see the diagram over the ⌘). The attach menu is the one of
                // the two that offers the reader a choice — a capture, a picture, a
                // document — and the command list is the one that acts on the tap.
                PopupAnchor { anchor ->
                    IconButton(
                        onClick = { onOpenSheet(ComposerSheet.Attach) },
                        enabled = enabled,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = text.chat.attachTitle,
                            // 17dp, the same as the command mark beside it — and the
                            // figure is *not* the one this used to state. Miuix's `Add`
                            // draws its ink out to 84% of its box (measured: the paths
                            // span 95→1062 of the 1152-unit viewport), so at the 23dp
                            // this button used the plus was 19dp of ink against the
                            // `⌘`'s 14dp — the two marks beside each other on one row
                            // were a third apart in size. At 17dp the plus is 14.3dp of
                            // ink and the command is 14.1: equal, which is what "co-
                            // ordinated" means for two glyphs of different weights.
                            modifier = Modifier.size(17.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    ControlChipMenu(
                        show = openSheet == ComposerSheet.Attach,
                        anchor = anchor,
                        onDismiss = onCloseSheet,
                    ) {
                        AttachMenu(
                            show = openSheet == ComposerSheet.Attach,
                            text = text,
                            onPick = onPickAttachment,
                            onDismiss = onCloseSheet,
                            chatMode = chatMode,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // The context meter, where the chip in the row above used to be: a
                // ring at the send's left, at the height of the buttons beside it.
                //
                // A ring rather than a chip because the number is a *share*, and a
                // share has a shape: `上下文 12%` in a pill was a label competing with
                // `deepseek-chat` and the level for a row that only fits three of them,
                // while a ring of the same number says the same thing in 20dp and
                // leaves the row to the two settings that are genuinely choices. The
                // value is still one tap away — the ring opens the same dropdown the
                // cache chip does — and it is still readable at a glance, which is the
                // whole of what a context meter is for.
                //
                // It is the last control before the send rather than a neighbour of
                // the `+`, because it is the one control in the row that is *about*
                // the message rather than part of it: a reading sits beside the
                // action that consumes it, and the two that add something keep the
                // edge where the writing starts.
                PopupAnchor { anchor ->
                    val percent = facts.contextPercent
                    ContextRing(
                        percent = percent,
                        description = text.chat.contextUsage(percent),
                        onClick = { onOpenSheet(ComposerSheet.ContextRing) },
                    )
                    ControlChipMenu(
                        show = openSheet == ComposerSheet.ContextRing,
                        anchor = anchor,
                        onDismiss = onCloseSheet,
                    ) {
                        ContextMenu(session = session, text = text)
                    }
                }
                Spacer(Modifier.width(4.dp))
                // The page's one action, and the only fully round control on it: a
                // glass disc carrying an arrow up, or — while an answer streams — the
                // same disc carrying the error role's square. The reader is looking for
                // one button in that corner, not two, and the pair is the one every chat
                // app draws: an arrow sends, a square stops.
                //
                // **Glass, like every other floating control.** The disc was a Miuix
                // `IconButton` filled with `primary` or `error`, which made it the one
                // opaque object in the composer's glass vocabulary — beside a glass
                // frame, glass chips and glass header. It is the library's
                // `GlassFloatingActionButton` now, cut from the same `PiGlass.material()`
                // as the frame it sits in: the bloom stroke and the floating shadow are
                // the objects every other control is made of.
                //
                // **The action colour is the disc, not a wash on it.** A 22% primary
                // layer under a primary glyph measured rgb(199,217,244): a pale blue disc
                // with a blue arrow, which is neither the filled `primary` disc nor the
                // white `onPrimary` arrow this button had before. The layer is 92% now —
                // the figure the library's own `GlassFloatingActionButtonDefaults` blends
                // its primary with — so the disc reads as the action's colour again:
                // primary while it can send, error while an answer streams. The last 8%
                // is what keeps the glass body visible, and the glyph takes the role's
                // on-colour (`onPrimary`/`onError`) exactly as it did on the opaque disc.
                // The idle state keeps the old `PiTone.inset()` wash and disabled ink;
                // below API 33 the fallback is the state's solid fill.
                //
                // The disc is round by construction: `GlassFloatingActionButton` derives
                // its shape from the size, so a 40dp button *is* a disc and the shape
                // cannot drift from the two constants that used to have to stay equal.
                val armed = enabled && (value.isNotBlank() || attachments.isNotEmpty())
                val actionLive = isStreaming || armed
                val actionFill = when {
                    isStreaming -> PiTone.error()
                    armed -> MiuixTheme.colorScheme.primary
                    else -> PiTone.inset()
                }
                val actionInk = when {
                    isStreaming -> MiuixTheme.colorScheme.onError
                    armed -> MiuixTheme.colorScheme.onPrimary
                    else -> MiuixTheme.colorScheme.disabledOnSurface
                }
                // The two live states are the action's fill with the library's 92% glass
                // blend; the idle wash already carries its own 10% in `PiTone.inset()`.
                val actionLayer = if (actionLive) actionFill.copy(alpha = 0.92f) else actionFill
                val baseActionMaterial = PiGlass.material()
                val actionMaterial = remember(baseActionMaterial, actionLayer) {
                    baseActionMaterial.copy(
                        third = GlassColorLayer(
                            color = actionLayer,
                            mode = GlassColorBlendMode.SrcOver,
                        ),
                    )
                }
                GlassFloatingActionButton(
                    onClick = {
                        // The library's FAB has no `enabled` parameter: a tap on a dead
                        // disc is refused here and said through semantics below, the way
                        // the drawer's glass actions state an unavailable control.
                        if (!actionLive) return@GlassFloatingActionButton
                        haptics.action()
                        if (isStreaming) onStop() else onSend()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        // `mergeDescendants` folds the button's own semantics, the
                        // glyph's description and this `disabled` into one node; a plain
                        // `semantics` node beside the button's would leave TalkBack
                        // reading an enabled control.
                        .semantics(mergeDescendants = true) {
                            if (!actionLive) disabled()
                        },
                    backdrop = LocalPiBackdrop.current,
                    // The composer's frame is the same near-white glass as this disc, and
                    // Miuix's chip-sized shadow disappears on it. `Float` is the drawer's
                    // answer to the same ground (the settings disc on #F7F7F7), so the
                    // disc keeps an edge without inventing a second fill.
                    shadow = GlassShadows.Float,
                    material = actionMaterial,
                    size = 40.dp,
                    fill = actionFill,
                ) {
                    Icon(
                        painter = painterResource(
                            if (isStreaming) R.drawable.ic_stop else R.drawable.ic_arrow_up,
                        ),
                        contentDescription = if (isStreaming) text.chat.stop else text.chat.send,
                        // 24dp of box for the 12 units of ink the stop square is: about
                        // a third of the disc, the proportion Material's own stop button
                        // uses. The send arrow is 22dp for 14 units, so the drawn mark is
                        // about 13dp on the 40dp disc — the same proportion the old pair
                        // drew.
                        modifier = Modifier.size(if (isStreaming) 24.dp else 22.dp),
                        tint = actionInk,
                    )
                }
                    }
                }
            }

                // The air below the frame, **inside** the gradient: below the field the
                // band has to be opaque, or the transcript shows through under the
                // input box — the report "you said it fades upward from the field, so
                // nothing below it should be readable", and it was right.
                Spacer(Modifier.fillMaxWidth().height(COMPOSER_FOOT))
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
 * The cap leaves 560dp for prose after both the list padding and document gutters.
 */
private val WIDE_TRANSCRIPT_WINDOW = 600.dp
private val MAX_TRANSCRIPT_WIDTH = 560.dp + ANSWER_CARD_INSET * 2

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
    return item.thinking.isBlank() && item.text.isBlank() && item.error == null &&
        item.status != ReplyStatus.Stopped
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
 * How far past the last card a first frame asks to be scrolled, in pixels.
 *
 * `requestScrollToItem` takes a pixel offset into the item it names, and the truthful
 * offset — the one that puts the content's end on the viewport's end — needs the last
 * card's height and the list's bottom padding, neither of which exists before the first
 * measure. A request past the end is clamped to the list's own furthest position, so the
 * number only has to be larger than any transcript: 100,000 px is ~38,000dp, three
 * hundred screens of a 26sp line. It is not an offset the list can honour, which is the
 * point.
 */
private const val TRANSCRIPT_END_REQUEST = 100_000

/** Follow the measured end, including layout changes that arrive after the last token. */
@Composable
private fun FollowTranscriptEnd(
    listState: LazyListState,
    follow: FollowTailController,
    programmatic: State<Boolean>,
    hasMessages: Boolean,
) {
    LaunchedEffect(listState, follow, programmatic, hasMessages) {
        if (!hasMessages) return@LaunchedEffect
        snapshotFlow {
            if (follow.follows && !programmatic.value && !listState.isScrollInProgress && listState.canScrollForward) {
                listState.layoutInfo.totalItemsCount
            } else {
                0
            }
        }.collect { count ->
            if (count > 0 && follow.follows && !programmatic.value && !listState.isScrollInProgress) {
                listState.requestScrollToItem(count - 1, TRANSCRIPT_END_REQUEST)
            }
        }
    }
}

/**
 * How full the context window is, as the number the ring draws and its tooltip states.
 *
 * A percentage only. Integer division, so 99.6% does not read as "full" a message
 * early, and an unknown window reports as 0% rather than hiding: the ring is always
 * there, so it may as well be the same shape before and after the model reports its
 * size. The raw counts are one tap away, in the menu.
 */
private fun contextPercent(state: ConversationState): Int {
    val window = state.model?.contextWindow ?: 0
    val used = state.usage.context.total
    return if (window > 0) (used * 100 / window).toInt().coerceIn(0, 100) else 0
}

/**
 * The context meter: a 20dp ring beside the send, filled to the share of the window
 * in use.
 *
 * ## Why a ring rather than the chip that was here
 *
 * The chip row above the field is `LazyRow` of the settings a message is sent with,
 * and it had four of them: the thinking level, the model, the context share and the
 * cache-hit share. The two percentages were the only ones the reader does not *choose*
 * — they are readings — and two of four chips on a row that scrolls is half the row
 * spent on meters. The context one moved here, beside the send, where a shape can say
 * a share without a label: a ring filled a third of the way round is the same number
 * as `上下文 33%` and it costs 20dp.
 *
 * ## What the drawing says
 *
 * The track is `outline` — the palette's own hairline role — and the fill is `primary`,
 * so the ring is drawn from the scheme rather than from a colour of its own. The fill
 * turns to the error role at [RING_TENSE_PERCENT]: a window about to overflow is the
 * one state of this meter that a reader has to act on, and the accent turning red is
 * how every meter in the platform says it. The track is drawn either way, so the ring
 * is never absent, only empty.
 *
 * The touch target is 36dp around the 20dp ring — the row's other two controls are
 * 40dp buttons, and a 20dp target beside them would be a control a thumb misses. The
 * label the ring has no room for is its accessibility name instead, and the menu it
 * opens is where the same number is written out in words.
 */
@Composable
private fun ContextRing(
    percent: Int,
    description: String,
    onClick: () -> Unit,
) {
    val track = MiuixTheme.colorScheme.outline
    val fill = if (percent >= RING_TENSE_PERCENT) {
        PiTone.error()
    } else {
        MiuixTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(RING_TOUCH)
            .clip(CircleShape)
            .clickable(onClickLabel = description, onClick = onClick)
            // The ring draws no text, so the sentence the chip in the row above used to
            // carry is its accessibility name instead — the share in words, which is
            // also the tooltip the menu spells out in rows.
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(RING_SIZE)) {
            val stroke = RING_STROKE.toPx()
            val side = size.minDimension - stroke
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(side, side)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (percent > 0) {
                drawArc(
                    color = fill,
                    startAngle = RING_START_ANGLE,
                    sweepAngle = 360f * percent / 100f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** The ring's box, the ring inside it, the line it is drawn with and where it starts. */
private val RING_TOUCH = 36.dp
private val RING_SIZE = 20.dp
private val RING_STROKE = 2.5.dp

/** Twelve o'clock, which is where a meter starts and where a reader looks for its end. */
private const val RING_START_ANGLE = -90f

/** The share at which the meter stops being a reading and starts being a warning. */
private const val RING_TENSE_PERCENT = 90

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
 * One of the composer's small controls: a pill that opens a menu or a sheet.
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
 * The answer that survived is `PiGlass.panel(...)` — the same glass the header's
 * capsules and the jump-to-latest button use, with the opaque fill as its fallback —
 * rather than a chip-local step
 * of the surface ramp: a chip is a floating control, so it wears the control fill
 * wherever it sits rather than a role chosen by the surface beneath it. The answer card
 * stays `surfaceContainer` (white), so the chip still reads as a separate object. The
 * pill is a Miuix [Surface] at a full round, so the clip, the fill and the press feedback
 * are the library's, and its height is 32dp — the composer's own control height, which is
 * a size larger than the transcript's 28dp pills on purpose: a tool row is read at a
 * glance, while these three are the settings the next message goes with. It is a
 * minimum, so the label grows with the reader's text size.
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
 * anchors an [OverlayListPopup] to its own parent's bounds, so the popup has to be a
 * sibling of the pill for the menu to open where the finger tapped — and that is also
 * what lets the `/model` command row open the model menu at the model chip.
 *
 * ## Why the overlay popup and not Miuix's window one
 *
 * `WindowListPopup` is a `Dialog`: a focusable window over a focused text field takes
 * the IME's connection with it, so tapping any chip on this page used to close the
 * keyboard — and it stayed closed after the menu was dismissed, which put the reader a
 * tap away from the sentence they were in the middle of. `OverlayListPopup` is the same
 * panel drawn in the root `Scaffold`'s popup host (`MiuixPopupUtils`), so there is no
 * second window, nothing to take the focus, and the keyboard stays up. The anchor
 * arithmetic is identical, because the library positions both from the parent's bounds
 * in the window. §7.1 has the measurement that settled the sheet half of the same
 * question.
 */
@Composable
private fun ControlChip(
    glyph: ImageVector,
    label: String,
    onClick: () -> Unit,
    menu: @Composable (PiMenuAnchor) -> Unit = {},
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
    menu: @Composable (PiMenuAnchor) -> Unit = {},
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
    menu: @Composable (PiMenuAnchor) -> Unit,
    glyph: @Composable () -> Unit,
) {
    // The panel morph starts at the pill's own bounds, not its wrapper's.
    val anchor = rememberPiMenuAnchor()
    Box {
        Surface(
            // Clip, then click: Miuix's press indication is drawn by the node that
            // owns the gesture, so a `clickable` outside the shape would tint a
            // rectangle behind a pill.
            modifier = Modifier
                .heightIn(min = CHIP_HEIGHT)
                .piGlass(RoundedCornerShape(percent = 50))
                .clip(RoundedCornerShape(percent = 50))
                .piMenuAnchor(anchor)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(percent = 50),
            // The same glass the header's capsules are drawn with: a chip floats over the
            // fading transcript exactly as a capsule does.
            color = Color.Transparent,
            // The chip's label is *normal* ink, not the muted one the library gives a
            // container-high surface. `onSurfaceContainerHigh` is #A2A2A2 — a summary's
            // grey, chosen for text that explains a row — and these are not
            // explanations: each one is the current value of a setting the next message
            // will be sent with (`Medium`, `deepseek-v4`, `上下文 12%`). Read as the
            // value rather than as a caption, it has to be the same ink as the sentence
            // in the field below it, which is `onSurface`.
            //
            // Against the white fill it is drawn on in the light scheme, #A2A2A2 is a
            // 2.5:1 contrast — below the 3:1 floor for anything a reader has to read —
            // while `onSurface` is 12.6:1. That is the whole of the change: the chips
            // were not "a strange colour", they were grey on grey.
            contentColor = MiuixTheme.colorScheme.onSurface,
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
        menu(anchor)
    }
}

/**
 * A chip's menu, at OS4's glass.
 *
 * The panel expands from the trigger and settles above it, aligned at logical start.
 * The field and keyboard remain below it regardless of how short the menu is.
 */
@Composable
private fun ControlChipMenu(
    show: Boolean,
    anchor: PiMenuAnchor,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    PiPopupMenu(
        show = show,
        onDismissRequest = onDismiss,
        anchor = anchor,
        placement = PiPopupPlacement.Above,
        content = content,
    )
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
 * The persona avatar inside a chip: larger than a glyph because it is a disc, not a mark.
 *
 * 20dp against the 14dp glyph beside it, and 16dp would read as a dot: the disc's own
 * edge is its ink, while a glyph's box is mostly air, so equal boxes would make the
 * avatar look half the size of the marks it sits beside.
 */
private val CHIP_AVATAR = 20.dp

/**
 * A 40dp control and the menu it opens, with the control as the menu's anchor.
 *
 * A `Box` around one button is the button: the glass panel is drawn by the page's popup
 * host rather than emitted here (see [PiPopupMenu]), so nothing inside this box has a size
 * of its own and the box's bounds *are* the button's. One place that says so beats a
 * `rememberPiMenuAnchor` and a `piMenuAnchor` at four call sites.
 */
@Composable
private fun PopupAnchor(content: @Composable (PiMenuAnchor) -> Unit) {
    val anchor = rememberPiMenuAnchor()
    Box(Modifier.piMenuAnchor(anchor)) { content(anchor) }
}

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
    val live by session.conversation.collectAsStateWithLifecycle()
    val saved by session.settingsStore.settings.collectAsStateWithLifecycle()
    // The profile is what says which model the *next* message goes with, and the
    // catalogue on disk is what says which levels that model has — see [levelsFor].
    // Together they are why the menu is right on the frame a model switch lands rather
    // than two round trips later.
    val config by session.settingsStore.profiles.snapshots.collectAsStateWithLifecycle()
    val catalogueLevels by session.catalogueThinkingLevels.collectAsStateWithLifecycle()
    // pi's live answer when it is an answer for this model, then the list remembered for
    // it, then its own catalogue entry. The menu cannot offer a level the model does not
    // have.
    val facts = live.composerFacts()
    val available = levelsFor(facts, saved, config.activeProfile, catalogueLevels)
    val current = thinkingLevelOf(facts, saved, config.activeProfile, catalogueLevels)
    val options = available.takeIf { it.isNotEmpty() } ?: PiLaunchOptions.THINKING_LEVELS
    options.forEach { level ->
        PiMenuRow(
            // The id alone. The menu carried each level's sentence under it
            // once, and seven rows of "off — 直接回答，不做思考" made a menu of
            // two-line entries out of seven one-word choices; the ids are the
            // wire strings pi documents, and the picker that explains them is the
            // model page's own rows.
            title = level,
            selected = level == current,
            // No buzz here: `PiMenuRow` performs the choice haptic itself, and a second
            // one in the handler is the double buzz `PiHaptics`' *Never twice* forbids.
            onClick = {
                if (show && level != current) {
                    // Applied to the running process immediately: pi
                    // accepts the change over RPC, so unlike the provider and
                    // model it needs no restart.
                    onDismiss()
                    session.settingsStore.update { it.copy(thinkingLevel = level) }
                    session.setThinkingLevel(level)
                }
            },
        )
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
 *
 * ## The rows are the library's dropdown rows
 *
 * This menu was the last one in the app still drawing its own row: a [PiMenuRow] with a
 * hand-placed `MiuixIcons.Ok` in its trailing slot, which is a *different glyph at a
 * different size* from the tick the thinking-level menu draws. Two menus over the same
 * conversation therefore marked their current answer two different ways, and the
 * thinking menu's mark is the library's own — `MiuixIcons.Basic.Check` at
 * `DropdownDefaults.CheckIconSize`, tinted `primary`, on a row whose title is also
 * `primary` when it is the selected one.
 *
 * `DropdownImpl` is that row, and it is usable here: it is not a `BasicComponent`, so
 * the `Constraints.Infinity` intrinsic-measure overflow that [PiMenuRow]'s KDoc
 * records does not apply to it, and it takes a leading [DropdownItem.icon] and a
 * [DropdownItem.summary] for the two things this menu wants to say beyond the id.
 *
 * The model list is a *choice*, so every row is a radio: the selected one is `primary`
 * with the tick, the rest are plain. The summary under the id is `name/provider`, with
 * a profile's key state on a line of its own — see [modelRowSummary], which is also
 * where the slash and the second line are argued for.
 */
@Composable
private fun ModelMenu(
    show: Boolean,
    session: PiAgentSession,
    text: Strings,
    onDismiss: () -> Unit,
) {
    val config by session.settingsStore.profiles.snapshots.collectAsStateWithLifecycle()
    val profiles = config.profiles
    val active = config.activeProfile
    val selectedId = active
        ?.takeIf { it.modelId.isNotBlank() }
        ?.let { modelOptionId(it.id, it.modelId) }

    // A menu with no rows is not an empty menu: the popup still measures 200dp wide
    // and `OverlayListPopup` still dims the page, so the chip becomes a tap that dims
    // the screen and shows nothing. One disabled row says why there is nothing to pick
    // — the settings page is where a profile is added, and this page cannot open it.
    val hasModels = profiles.any { it.selectableModels.isNotEmpty() }
    // The flat list the rows index into, so `optionSize` and `index` are the menu's
    // own coordinates rather than one profile's. Built once per composition because
    // the library's first/last padding is derived from them.
    val rows = profiles.flatMap { profile ->
        val summary = modelRowSummary(profile, text)
        profile.selectableModels.map { model ->
            ModelRow(
                model = model,
                summary = summary,
                enabled = profile.providerEntry != null,
                selected = modelOptionId(profile.id, model) == selectedId,
                onPick = { session.selectProfile(profile, model) },
            )
        }
    }

    val haptics = LocalPiHaptics.current
    if (!hasModels) {
        PiMenuRow(
            title = text.chat.noModel,
            enabled = false,
        )
        return
    }
    rows.forEach { row ->
        PiMenuRow(
            title = row.model,
            summary = row.summary,
            enabled = row.enabled,
            selected = row.selected,
            // `PiMenuRow` buzzes a changed choice itself (see `ThinkingMenu`).
            onClick = {
                // The gate every menu row in this page carries: the panel is drawn
                // for the length of its exit, so a second tap aimed at the page
                // behind a menu that is already leaving must not pick a model.
                if (show && row.enabled && !row.selected) {
                    onDismiss()
                    row.onPick()
                }
            },
        )
    }
}

/** One row of [ModelMenu], before it is a `DropdownItem`. */
private class ModelRow(
    val model: String,
    val summary: String,
    val enabled: Boolean,
    val selected: Boolean,
    val onPick: () -> Unit,
)

/**
 * The conversation's numbers, in full, as a **dropdown** at the chip that shows one of
 * them.
 *
 * It was a bottom sheet: fourteen two-line rows in a panel that rose from the bottom
 * edge, for a question the reader asks by glancing at a number. Every other menu on
 * this page is a dropdown at its own control (§7.1), and this one is a menu of values
 * rather than a page — so it is the same `ListPopupColumn` the model and thinking
 * menus use, at the same row metrics, drawn as the facts it holds rather than as
 * choices (the section below is the whole of that decision).
 *
 * ## Read-only rows are facts, not disabled choices
 *
 * A `DropdownImpl` is a *choice* row: it reports a radio-button role and draws a tick
 * when `isSelected`, and its way of saying "nothing here can be picked" is
 * `enabled = false` — the scheme's *disabled* ink, plus "unavailable" to a screen
 * reader. A page of numbers this app has just measured, drawn in that ink, reads as a
 * page of choices that have been taken away, which is the opposite of what this menu
 * is: every row states something true about the conversation and none of them is
 * waiting for permission. The row is [PiMenuRow] with no `onClick` — the kit's
 * read-only form of the same menu row, at the library's title-over-summary metrics
 * and in the scheme's normal ink — and a row with no gesture consumes no tap and
 * reports no role, so it is a reading rather than a control that looks switched off.
 *
 * ## One label per row rather than a two-column table
 *
 * The table this started as needed a fixed label column (112dp was measured), which
 * leaves 210dp for a value, and a session file name needs 480px. Stacked — the
 * label as the title and the value as the summary — the value gets the menu's full
 * width and a second line, which is the same shape a settings row has and therefore
 * needs no explanation.
 *
 * The numbers are read from the session's own flow *here*, inside the menu, rather
 * than passed in from the page: a turn keeps running while this is open, and a menu
 * that showed the totals as they were on the frame the chip was tapped would be a
 * snapshot pretending to be a meter.
 */
@Composable
private fun ContextMenu(session: PiAgentSession, text: Strings) {
    val state by session.conversation.collectAsStateWithLifecycle()
    val saved by session.settingsStore.settings.collectAsStateWithLifecycle()
    val config by session.settingsStore.profiles.snapshots.collectAsStateWithLifecycle()
    val catalogueLevels by session.catalogueThinkingLevels.collectAsStateWithLifecycle()
    val thinking = thinkingLevelOf(state.composerFacts(), saved, config.activeProfile, catalogueLevels)
    val usage = state.usage
    val context = usage.context
    val window = state.model?.contextWindow ?: 0
    val model = state.model

    val rows = buildList {
        add(text.chat.detailModel to when {
            // The same three answers as the chip: with nothing configured there is
            // nothing to wait for.
            model == null -> if (config.profiles.isEmpty()) text.chat.noModel else text.chat.loadingModel
            !model.isUsable -> text.chat.noModel
            else -> model.id.ifBlank { model.name }
        })
        add(text.chat.detailProvider to when {
            // `model == null` is the agent still starting; `provider == "unknown"` is
            // pi's own word for "no profile is configured". Both have a translated
            // sentence rather than pi's identifier, which a reader who has not
            // configured anything reads as a fault.
            model == null ->
                if (config.profiles.isEmpty()) text.chat.noModel else text.chat.loadingModel
            model.provider == "unknown" -> text.chat.noModel
            else -> model.provider.ifBlank { text.settings.none }
        })
        add(text.chat.thinkingLabel to thinking)
        add(
            text.chat.detailContext to if (window > 0 && context.total > 0) {
                text.chat.contextOfWindow(
                    percent = (context.total * 100 / window).toInt(),
                    windowK = window / 1000,
                )
            } else {
                text.chat.contextEmpty
            },
        )
        add(text.chat.detailUsed to text.chat.tokens(context.total))
        add(text.chat.detailInput to text.chat.tokens(context.input))
        add(text.chat.detailOutput to text.chat.tokens(context.output))
        add(text.chat.detailCacheRead to text.chat.tokens(context.cacheRead))
        add(text.chat.detailCacheWrite to text.chat.tokens(context.cacheWrite))
        if (context.reasoning > 0) {
            add(text.chat.detailReasoning to text.chat.tokens(context.reasoning))
        }
        add(text.chat.cacheHitLabel to text.chat.cacheHit(cacheHitPercent(state)))
        // `Locale.ROOT` for the digits: the default locale writes a comma decimal in
        // half of Europe, which turns "$0,0123" into a number whose separator is also
        // its digit-group separator. The currency is the provider's own, USD. Omitted
        // while nothing has been spent, rather than drawn as "$0.0000" — a cost row
        // that always says zero is a row that never says anything.
        if (usage.total.costUsd > 0) {
            add(text.chat.detailCost to String.format(Locale.ROOT, "$%.4f", usage.total.costUsd))
        }
        add(text.chat.detailTotal to text.chat.tokens(usage.total.total))
        add(text.chat.detailTurns to text.chat.turnCount(state.turns.size))
        // The file's *name*: the menu is 288dp wide at its widest and the container
        // path is 48 characters before the name. Which conversation this is, is the
        // question; where it lives on disk is not.
        state.sessionFile?.let { add(text.chat.detailSession to java.io.File(it).name) }
    }

    // Every row, in the order it was added, as a fact: see this function's KDoc.
    rows.forEach { (label, value) ->
        PiMenuRow(title = label, summary = value)
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
 * the web-access extension PiKit used to bundle registered. They are not commands this app can
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
 * Each row is the library's dropdown row — [DropdownImpl] — with the id as its title
 * and its description as its summary. It was a [PiMenuRow], and the argument for that
 * was that the ids share no mutually exclusive choice; what that produced was a menu
 * whose rows were a *different shape* from the thinking-level menu's one chip to the
 * left. `DropdownImpl` draws an unselected row identically (title, summary, the
 * library's 20dp sides), and it does not require the row to be a choice: `isSelected`
 * is simply false and no tick is drawn. `PiMenuRow` remains for the rows that carry a
 * leading mark or a trailing control — the terminal's roster is the live example —
 * because those are the ones the library's dropdown row cannot hold.
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
    /** Chat mode has no shell-as-a-message escape hatch; see `CommandMenu`'s shell row. */
    chatMode: Boolean = false,
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

    val rows = BUILT_IN_COMMANDS
    // The shell row is the one command that puts process output into the transcript.
    // It is not a tool the model can call, but it is still the escape hatch of a
    // coding conversation rather than a chat one; chat mode keeps only the commands
    // that operate on the conversation itself.
    if (!chatMode) {
        PiMenuRow(
            // The one row whose label is a sigil rather than a command id, and it
            // carries a summary like every row under it: `!` states the *shape* of
            // the line it wants, not what happens when it is tapped, and a menu of
            // rows that each explain themselves with one sentence leaves the one
            // punctuation mark in it as the only entry a reader has to already know.
            // The sentence is the catalog's own — the shell field it opens says the
            // same thing under its title.
            title = SHELL_SIGIL,
            summary = text.chat.shellCommandHint,
            onClick = { pick(onShell) },
        )
    }
    rows.forEach { command ->
        PiMenuRow(
            title = command.id,
            summary = command.description(text),
            onClick = { pick { onRun(command) } },
        )
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
 *
 * The mark is passed as the [DropdownItem]'s own `icon`, so these rows are the same
 * row as the model menu's with a leading cell filled in — `DropdownImpl` draws the
 * cell at `IconMinSize` (26dp) and pads it 12dp from the title. This is the one menu
 * in the composer that still has a leading mark at all, which is why it is the one
 * that kept a `DropdownItem` rather than the `text`-only overload.
 */
@Composable
private fun AttachMenu(
    show: Boolean,
    text: Strings,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Chat mode can carry pictures, but a file path needs a `read` tool it does not have. */
    chatMode: Boolean = false,
) {
    fun pick(kind: String) {
        if (show) {
            onDismiss()
            onPick(kind)
        }
    }

    val rows = buildList {
        add(AttachRow(text.chat.attachCamera, text.chat.attachCameraHint, MiuixIcons.Scan, "camera"))
        add(AttachRow(text.chat.attachImage, text.chat.attachImageHint, MiuixIcons.Photos, "image"))
        if (!chatMode) {
            add(AttachRow(text.chat.attachFile, text.chat.attachFileHint, MiuixIcons.File, "file"))
        }
    }
    rows.forEach { row ->
        PiMenuRow(
            title = row.title,
            summary = row.summary,
            icon = row.glyph,
            onClick = { pick(row.kind) },
        )
    }
}

/** One row of [AttachMenu]. */
private class AttachRow(
    val title: String,
    val summary: String,
    val glyph: ImageVector,
    val kind: String,
)


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
 *   it earns its row: the field keeps its draft across a page switch and there was
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
    BuiltInCommand("/stats") { it.chat.commandStats },
    BuiltInCommand("/copy") { it.chat.commandCopy },
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
    openLabel: String,
    removeLabel: String,
    onRemove: () -> Unit,
    onLocated: (PictureTileFrame) -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    // Decoded off the main thread: a 12-megapixel photo takes long enough to
    // decode that doing it during composition is a visible stall.
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, image.id) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, image.uri) }
    }
    // Where the tile is on screen, for the viewer's opening flight: tapping a picture in
    // the composer opens the same window a prompt's picture opens, and it grows out of
    // this square rather than fading in over the page. The coordinates are kept rather
    // than a rectangle so the position is the one at the tap.
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val bitmap = thumbnail
    // The bitmap can arrive after the first layout; the node reports its own position
    // changes, so a scrolled chip's origin is the one at the tap rather than at
    // composition time.
    LaunchedEffect(bitmap) {
        coordinates?.let { onLocated(PictureTileFrame(it, bitmap)) }
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
    Box(
        Modifier
            .size(ATTACHMENT_TILE)
            .onGloballyPositioned { node ->
                coordinates = node
                onLocated(PictureTileFrame(node, bitmap))
            },
    ) {
        Surface(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(ATTACHMENT_CORNER),
            modifier = Modifier.size(ATTACHMENT_TILE),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = image.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(ATTACHMENT_TILE)
                        // The tile is the picture's entry point: the same window a sent
                        // picture opens, on the bytes the send will carry. It used to be
                        // inert — the only thing on it was ✕ — so a picked photo could
                        // not be looked at before it left.
                        .clickable(
                            onClickLabel = openLabel,
                            onClick = onOpen,
                        ),
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
    val line = remember { TextFieldState() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = piSheetBottomInset()),
    ) {
        Text(
            text = text.chat.shellCommandHint,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = SHEET_GUTTER, end = SHEET_GUTTER, bottom = 8.dp),
        )
        GlassTextField(
            state = line,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_GUTTER)
                .piFieldHeightForPlaceholder(),
            label = text.chat.shellCommandPlaceholder,
            useLabelAsPlaceholder = true,
            lineLimits = TextFieldLineLimits.SingleLine,
            cornerRadius = PiCorner.radius,
            colors = GlassTextFieldDefaults.textFieldColors(
                borderColor = Color.Transparent,
            ),
            cursorBrush = piFieldCursor(),
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
        )
        Button(
            cornerRadius = PiCorner.radius,
            onClick = { onRun(line.text.toString().trim()) },
            // Refused while the line is blank: `bash` with an empty command is a
            // shell error the user would be reading for a reason they caused.
            enabled = line.text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SHEET_GUTTER, end = SHEET_GUTTER, top = 12.dp),
            // The sheet's glass body is darker than a page card; the library's
            // default secondary fill is almost the panel's own colour there.
            colors = ButtonDefaults.buttonColors(
                color = PiTone.control(),
                disabledColor = PiTone.control(),
                contentColor = MiuixTheme.colorScheme.onSurface,
                disabledContentColor = PiTone.disabledControlInk(),
            ),
        ) {
            Text(text = text.common.ok)
        }
    }
}

/**
 * The tier's own mark.
 *
 * Three shapes rather than one shape in three colours: an eye for the tier that can only
 * look, a lock for the one that can change only inside its workspace, and an open lock for
 * the one with no refusals — the progression a reader already knows from file permissions,
 * and only the last of the three changes colour (§7.1 has why the other two stay in the
 * row's own ink).
 */
private fun permissionGlyph(mode: PermissionMode): ImageVector = when (mode) {
    PermissionMode.READ_ONLY -> MiuixIcons.Show
    PermissionMode.WORKSPACE_WRITE -> MiuixIcons.Lock
    PermissionMode.FULL_ACCESS -> MiuixIcons.Unlock
}

/**
 * The workspace menu: one row per workspace, then the folder picker.
 *
 * The tick marks the conversation's own workspace, which is a *fact* about the row rather
 * than a saved choice — this menu is what made the current one current. The default is a
 * suffix on its title instead, because "which one am I in" and "which one a new
 * conversation starts in" are two answers and only one of them is a tick.
 */
@Composable
private fun WorkspaceMenu(
    show: Boolean,
    text: Strings,
    workspaces: List<Workspace>,
    current: String,
    defaultPath: String,
    home: String,
    onPick: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    // `chosen` used to be what decided the buzz as well as the action; the buzz is
    // `PiMenuRow`'s now — it performs it on a tap that changes a choice, and only then —
    // so the parameter is gone and each row states the one thing it does.
    fun pick(action: () -> Unit) {
        if (show) {
            onDismiss()
            action()
        }
    }

    workspaces.forEach { workspace ->
        PiMenuRow(
            title = text.chat.workspaceChip(workspace.label, workspace.path == defaultPath),
            summary = workspace.displayPath(home),
            selected = workspace.path == current,
            onClick = { pick { onPick(workspace.path) } },
        )
    }
    PiMenuRow(
        title = text.chat.addWorkspace,
        onClick = { pick(onAdd) },
    )
}

/**
 * The three tiers, with what each one allows under its name.
 *
 * A summary per row rather than three words: `read-only`, `workspace-write` and
 * `full-access` are the names of three *policies*, and a reader who has to guess what the
 * middle one changes is a reader who leaves the tier on whatever it was.
 */
/**
 * The chat presets, as one menu at the chip that opened it.
 *
 * A choice, so the library's own `DropdownImpl` rows are the shape — the tick on the one
 * in force is the "which of these" answer, and this is the same control as the model and
 * permission menus beside it. The leading mark is the persona's own avatar rather than a
 * glyph: it is the same disc the transcript draws beside an answer, which is what makes
 * the choice read as "this conversation now speaks as that".
 */
@Composable
private fun ChatPresetMenu(
    show: Boolean,
    text: Strings,
    presets: List<ChatPreset>,
    selectedId: String,
    settings: PiSettings,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Dismiss-then-act, the same order every menu in this app uses: a second tap aimed at
    // a panel that is already leaving must not land on one of its rows. The buzz for a
    // changed choice is `PiMenuRow`'s (see its KDoc), not this function's.
    fun pick(action: () -> Unit) {
        if (show) {
            onDismiss()
            action()
        }
    }

    presets.forEach { preset ->
        val identity = preset.identity(settings)
        PiMenuRow(
            title = chatPresetName(preset, settings, text),
            summary = chatPresetSummary(preset, text),
            selected = preset.id == selectedId,
            startAction = {
                PiAvatar(
                    speaker = AvatarSpeaker.AGENT,
                    color = identity.color.seed,
                    imagePath = identity.image.ifBlank { null },
                    size = PRESET_MENU_AVATAR,
                )
            },
            onClick = { pick { onPick(preset.id) } },
        )
    }
}

/** The persona disc in a menu row: one step larger than the chip's, as menu marks are. */
private val PRESET_MENU_AVATAR = 24.dp

@Composable
private fun PermissionMenu(
    show: Boolean,
    text: Strings,
    current: PermissionMode,
    onPick: (PermissionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    // The tier already in force is already the answer, so only a different one is a
    // choice — the menu's row for it is ticked and a tap on it changes nothing, which is
    // also why that tap is silent: `PiMenuRow` buzzes a changed choice itself.
    fun pick(action: () -> Unit) {
        if (show) {
            onDismiss()
            action()
        }
    }

    val modes = PermissionMode.entries
    modes.forEach { mode ->
        PiMenuRow(
            title = text.chat.permissionChip(mode),
            summary = text.chat.permissionSummary(mode),
            selected = mode == current,
            onClick = { pick { onPick(mode) } },
        )
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
 * Copies a picked file into the app's own attachment directory and returns a path
 * attachment.
 *
 * ## Not the cache, and not the workspace
 *
 * The path is written *into the prompt*: pi reads it there, the model opens it from
 * wherever it is working, and it is drawn again in the transcript for as long as the
 * conversation exists (the extras chip lists it). `cacheDir` is therefore the wrong
 * home — the platform may empty it at any moment, and a copy that a cleanup can take is a
 * path that silently stops being true in a history nobody edited. `filesDir` is the
 * app's own: still private, still not the agent's workspace, and the one place Android
 * does not prune behind the app's back.
 *
 * The workspace is the other refusal, and it was the original one: an attachment is
 * staging for one message rather than project content, and a workspace that collected
 * every document ever mentioned would be a workspace the agent has to wade through.
 *
 * A name that is already taken gets a numeric suffix rather than overwriting: two
 * documents called `report.pdf` in one prompt are two documents. The directory, and the
 * budget that bounds it, are [AttachmentStore]'s rather than this function's — the same
 * directory is what a backup carries and what a restore puts back.
 *
 * Size is capped the same way an image is, because the copy is a real file on the app's
 * private disk and the alternative is a pick of a 2 GB video filling it. Past the cap the
 * file is not copied and the pick reports as a failure.
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
    val dir = AttachmentStore.directory(context.filesDir).apply { mkdirs() }
    val target = uniqueChild(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    AttachmentStore.prune(context.filesDir)
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

/** One encoded attachment, plus the facts its prompt's coordinate note is built from. */
private data class EncodedImage(
    val attachment: ImageAttachment,
    val prepared: PreparedImage,
)

/**
 * The attachment as pi expects it: raw base64 plus the mime type.
 *
 * Read through a bounded buffer rather than `readBytes()`: a provider that lies
 * about its size, or one whose file grew between the pick and the send, would
 * otherwise put an unbounded array on the heap on the way to a base64 string twice
 * its size. Past the cap the attachment is dropped, which is the same answer the
 * pick would have given.
 *
 * The bytes are then fitted and encoded by [prepareImageForSend], which is what makes
 * the picture pi forwards the same picture the transcript draws: above
 * [SEND_IMAGE_MAX_EDGE] or [SEND_IMAGE_MAX_BYTES] it is scaled and re-encoded here,
 * and below both it is sent exactly as picked.
 */
private fun encodeImage(context: Context, image: PendingImage): EncodedImage? = runCatching {
    val mime = context.contentResolver.getType(image.uri) ?: "image/jpeg"
    val bytes = context.contentResolver.openInputStream(image.uri)?.use { readAtMost(it) }
        ?: return@runCatching null
    val prepared = prepareImageForSend(bytes, mime) ?: return@runCatching null
    EncodedImage(
        attachment = ImageAttachment(
            base64 = Base64.encodeToString(prepared.bytes, Base64.NO_WRAP),
            mimeType = prepared.mimeType,
        ),
        prepared = prepared,
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
