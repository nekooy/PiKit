package pi.kit.mob.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import pi.kit.mob.data.AvatarColor
import pi.kit.mob.data.AvatarSlot
import pi.kit.mob.data.AvatarStore
import pi.kit.mob.data.FontSize
import pi.kit.mob.data.LauncherIcon
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.kit.AvatarSpeaker
import pi.kit.mob.ui.kit.PiAvatar
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.piOverScroll
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Cold-start conversation behaviour, the light/dark choice, the text size, the
 * launcher icon and the transcript's two speakers.
 *
 * Five settings that are all "how the app feels when I open it" and none of which is
 * about the agent: which conversation a launch lands in, how large its text is,
 * whether the palette is light or dark, which of the two marks the home screen shows,
 * and whether the transcript draws who is talking. They share a page because a page
 * each would be five rows deep — the same mistake the old "Advanced" group made.
 *
 * The *palette* is not on this page at all any more. The interface is drawn in
 * Miuix's own light and dark schemes, so "follow the system / light / dark" is the
 * whole of the colour choice, and an accent would be the app re-deciding a scheme the
 * library has already decided. `PiKitMiuixTheme` carries the reasoning.
 *
 * The switch is **on** when a cold start opens a *new* conversation. That is the
 * default because a launch is usually a new question, and the previous
 * conversation is one tap away in the history; turning it off is the choice that
 * used to be the only behaviour. Agent restarts after the first launch still
 * restore — see `shouldRestoreRememberedSession`.
 *
 * ## The modes are radio rows, the size is a slider
 *
 * Both used to be a row that opened a picker sheet. Three palettes are a *set*, and a
 * set is better read at once than one at a time behind a scrim: the modes as radio
 * rows mean the choice is visible, the current one is marked, and picking one
 * re-themes the very rows that were just tapped. That is the live preview the old
 * sheet hid behind its own scrim.
 *
 * The text size is the one that is a *range*, and Miuix has the component for it:
 * `SliderPreference` keeps the slider on the row instead of behind a panel, which
 * removes the sheet and the "let go to apply" note with it. The one behaviour the
 * panel had is kept: the step is committed on **release**, not per tick — measured on
 * the emulator, applying each step under the finger re-measured the whole interface and
 * the drag stopped being delivered (nine `input motionevent MOVE`s advanced one step),
 * so the drag moves a draft and the store is written when the thumb stops.
 *
 * The icon's choice is the one on this page whose effect the app does not draw:
 * `applyLauncherIcon` turns a manifest alias on and the other off, and the system paints
 * what the launcher reads. Its footnote is about the home screen rather than about the
 * icon, because that is the part a user notices.
 */
@Composable
internal fun PersonalizationPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val settings by session.settingsStore.settings.collectAsState()

    // Which avatar is being edited, as the sheet's own state: a Miuix sheet is a window
    // of its own and is dismissed by the scrim, the back gesture and a downward drag, so
    // the page only has to hold *which* one is open. Null closes it.
    var editingAvatar by remember { mutableStateOf<AvatarSlot?>(null) }
    // The slot the panel is drawing: replaced by a newer one, never cleared by the tap.
    var shownSlot by remember { mutableStateOf<AvatarSlot?>(null) }

    PiPage(
        title = text.settings.personalization,
        subtitle = text.settings.personalizationSubtitle,
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The page's elastic end: two sections fit a phone outright, and a page
                // that fits is the one Compose's own effect skips. See `piOverScroll`.
                .piOverScroll()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            PiSection(title = text.settings.personalizationConversation) {
                SwitchPreference(
                    title = text.settings.openNewOnLaunch,
                    summary = text.settings.openNewOnLaunchSubtitle,
                    checked = settings.openNewOnLaunch,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(openNewOnLaunch = on) }
                    },
                )
            }

            PiSection(title = text.settings.personalizationAppearance) {
                FontSizeRow(
                    current = settings.fontSize,
                    onChange = { chosen ->
                        session.settingsStore.update { it.copy(fontSize = chosen) }
                    },
                    text = text,
                )
            }

            PiSection(title = text.settings.theme) {
                ThemeMode.entries.forEach { mode ->
                    RadioButtonPreference(
                        title = themeModeLabel(mode, text),
                        selected = settings.themeMode == mode,
                        onClick = {
                            session.settingsStore.update { it.copy(themeMode = mode) }
                        },
                    )
                }
            }

            PiSection(title = text.settings.launcherIcon) {
                LauncherIcon.entries.forEach { icon ->
                    RadioButtonPreference(
                        title = launcherIconLabel(icon, text),
                        selected = settings.launcherIcon == icon,
                        onClick = {
                            session.settingsStore.update { it.copy(launcherIcon = icon) }
                        },
                    )
                }
            }

            // The transcript's two speakers, as names, pictures or marks. A switch
            // over the pair rather than one per side: they are one feature — "draw
            // who is talking" — and a reader who wants them off wants both off.
            PiSection(title = text.settings.avatars) {
                SwitchPreference(
                    title = text.settings.showAvatars,
                    summary = text.settings.showAvatarsSubtitle,
                    checked = settings.showAvatars,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(showAvatars = on) }
                    },
                )
                ArrowPreference(
                    title = text.settings.aiAvatar,
                    endActions = {
                        PiAvatar(
                            speaker = AvatarSpeaker.AGENT,
                            color = settings.aiAvatarColor.seed,
                            label = settings.aiAvatarName.ifBlank { null },
                            imagePath = settings.aiAvatarImage.ifBlank { null },
                        )
                    },
                    onClick = {
                        shownSlot = AvatarSlot.Ai
                        editingAvatar = AvatarSlot.Ai
                    },
                )
                ArrowPreference(
                    title = text.settings.userAvatar,
                    endActions = {
                        PiAvatar(
                            speaker = AvatarSpeaker.READER,
                            color = settings.userAvatarColor.seed,
                            // Blank draws the silhouette — the reader's default mark.
                            label = settings.userAvatarName.ifBlank { null },
                            imagePath = settings.userAvatarImage.ifBlank { null },
                        )
                    },
                    onClick = {
                        shownSlot = AvatarSlot.User
                        editingAvatar = AvatarSlot.User
                    },
                )
            }
        }
    }

    // One sheet for both speakers: the body is told which slot it edits, so the two rows
    // cannot drift into two editors that save differently.
    //
    // The slot and the panel are two values, not one: `show` goes false on the tap while
    // Miuix's exit animation is still playing, and a body and a title read off the same
    // null slot play that last 200 ms empty — the panel shrinks away with no title and no
    // content in it. `shownSlot` is the slot the panel is *drawing*, and it is only ever
    // replaced by a newer one, which is the shape `WebSearchConfig` uses for its own
    // value sheet for the same reason.
    val slot = editingAvatar
    PiSheet(
        show = slot != null,
        title = when (shownSlot) {
            AvatarSlot.Ai -> text.settings.aiAvatar
            AvatarSlot.User -> text.settings.userAvatar
            null -> null
        },
        onDismiss = { editingAvatar = null },
    ) {
        when (shownSlot) {
            AvatarSlot.Ai -> AvatarSheet(
                slot = AvatarSlot.Ai,
                label = settings.aiAvatarName,
                imagePath = settings.aiAvatarImage,
                speaker = AvatarSpeaker.AGENT,
                color = settings.aiAvatarColor,
                onChangeLabel = { value ->
                    session.settingsStore.update { it.copy(aiAvatarName = value) }
                },
                onChangeImage = { path ->
                    session.settingsStore.update { it.copy(aiAvatarImage = path) }
                },
                onChangeColor = { chosen ->
                    session.settingsStore.update { it.copy(aiAvatarColor = chosen) }
                },
                text = text,
            )

            AvatarSlot.User -> AvatarSheet(
                slot = AvatarSlot.User,
                label = settings.userAvatarName,
                imagePath = settings.userAvatarImage,
                speaker = AvatarSpeaker.READER,
                color = settings.userAvatarColor,
                onChangeLabel = { value ->
                    session.settingsStore.update { it.copy(userAvatarName = value) }
                },
                onChangeImage = { path ->
                    session.settingsStore.update { it.copy(userAvatarImage = path) }
                },
                onChangeColor = { chosen ->
                    session.settingsStore.update { it.copy(userAvatarColor = chosen) }
                },
                text = text,
            )

            null -> Unit
        }
    }
}

/**
 * The text size, as Miuix's own slider row.
 *
 * ## Why the slider is on the page rather than behind a panel
 *
 * It used to be a sheet with two "A" samples and a percentage. A sheet is a window of
 * its own, so the one control whose whole point is that the interface changes size was
 * the one control that could not show the change until it was closed — and the note
 * under the track had to *say* "let go to apply" because the drag did not. On the row,
 * the drag re-measures the page it is on, which is the preview.
 *
 * ## The drag commits on release, and that is a measurement
 *
 * The first version applied each step as the finger passed it. Measured on the
 * emulator, dragging across the whole track with nine `input motionevent MOVE`s
 * advanced **one** step (80% → 90%): the first change re-laid the page out under the
 * pointer and the gesture stopped being delivered. A single MOVE of the same total
 * distance moved four steps, which is what shows the mapping is fine and the
 * interruption is the live update. So the thumb moves a draft — the value text, the
 * thumb and the ticks, all of which are this row's own — and the store is written in
 * `onValueChangeFinished`, which is one re-measure per gesture instead of one per pixel.
 *
 * The draft is keyed on the stored step, so the row follows a change made anywhere else
 * (a restore, a build whose default moved) without a hand-written effect, and it does
 * not fight the finger while the finger is down.
 */
@Composable
private fun FontSizeRow(
    current: FontSize,
    onChange: (FontSize) -> Unit,
    text: Strings,
) {
    val steps = FontSize.entries
    var draft by remember(current) { mutableStateOf(current.ordinal.toFloat()) }
    val shown = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]

    SliderPreference(
        value = draft,
        onValueChange = { draft = it },
        title = text.settings.fontSize,
        summary = text.settings.fontSizeSubtitle,
        // The step as a percentage of the ladder's own unit, which is the one number a
        // reader can check against nothing at all: `100%` is the size the app ships as
        // its default, and that size is what the ladder used to call 95%
        // (`TEXT_SCALE_BASELINE`).
        valueText = "${shown.percent}%",
        valueRange = 0f..steps.lastIndex.toFloat(),
        // Miuix (like the slider it wraps) counts the ticks *between* the ends, so
        // seven positions is five of them.
        steps = steps.size - 2,
        onValueChangeFinished = {
            val chosen = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]
            if (chosen != current) onChange(chosen)
        },
    )
}

/**
 * The avatar editor: a large preview, the picture, the label field, and the eight
 * colours.
 *
 * Saves as it is typed and on a swatch tap, like the working directory and the text size
 * — none of them restarts anything, and the sheet is dismissed by the scrim, the drag and
 * back, so a second "done" control would be the third of three.
 *
 * Everything is inside one card, which is what keeps the sheet on a single column: the
 * sheet's own 24dp gutter plus a card's 16dp is where the rows' text starts, and the
 * field put inside the same card starts there too. A field outside the card sat 16dp to
 * the left of the rows above it.
 *
 * ## The picture
 *
 * A row above the label rather than a second control beside it, because the two are
 * not peers: a picture is drawn *instead of* the label, which is what the hint under
 * the row says. Picking one opens the system's image picker and copies the result
 * into the app's own storage ([AvatarStore]); a picture that could not be read is
 * reported in the sheet rather than leaving the circle unchanged and unexplained.
 *
 * The name is capped at [AVATAR_NAME_MAX] characters because it is drawn beside a 34dp
 * mark in one line: past that it is an ellipsis in the transcript, and a field that accepts
 * more than it can show is a field the reader keeps retyping into.
 */
@Composable
private fun AvatarSheet(
    slot: AvatarSlot,
    label: String,
    imagePath: String,
    speaker: AvatarSpeaker,
    color: AvatarColor,
    onChangeLabel: (String) -> Unit,
    onChangeImage: (String) -> Unit,
    onChangeColor: (AvatarColor) -> Unit,
    text: Strings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember(slot) { mutableStateOf(false) }
    val filesDir = context.filesDir

    // The picture goes no further until it has been cropped: a photo straight from the
    // camera is not square, and a circle that silently kept the middle of it was the
    // "上传图片似乎不能裁剪" this page used to answer by cropping on the reader's behalf.
    var cropping by remember(slot) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = AvatarStore.decodeForCrop(context, uri)
            failed = decoded == null
            if (decoded != null) cropping = decoded
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            PiAvatar(
                speaker = speaker,
                color = color.seed,
                imagePath = imagePath.ifBlank { null },
                size = AVATAR_PREVIEW_SIZE,
            )
        }

        Card {
            ArrowPreference(
                title = text.settings.avatarPicture,
                summary = text.settings.avatarPictureHint,
                endActions = {
                    SettingsValue(
                        if (imagePath.isBlank()) {
                            text.settings.avatarPicturePick
                        } else {
                            text.settings.avatarPictureChange
                        },
                    )
                },
                onClick = { pickImage.launch(IMAGE_MIME) },
            )
            if (imagePath.isNotBlank()) {
                ArrowPreference(
                    title = text.settings.avatarPictureClear,
                    // The clear is the one control here that removes something the user
                    // chose, so its title carries the error role.
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.error,
                    ),
                    onClick = {
                        // The file goes with the setting: a picture nothing points at is
                        // a file the app would keep for ever, and the backup would carry
                        // it. `AvatarStore.clear` is a single-file delete rather than the
                        // recursive one `SafeDelete` exists for.
                        AvatarStore.clear(filesDir, slot)
                        failed = false
                        onChangeImage("")
                    },
                )
            }
            // The name beside the avatar, not the letter inside it: the disc draws the
            // speaker's own figure now, and a name is a word the transcript shows at the
            // start edge (the agent) or the end edge (the reader).
            SettingsTextField(
                value = label,
                onValueChange = { value -> onChangeLabel(value.take(AVATAR_NAME_MAX)) },
                label = text.settings.avatarName,
                supporting = text.settings.avatarNameHint,
                singleLine = true,
            )
            if (failed) {
                SettingsNote(text.settings.avatarPictureFailed, danger = true)
            }
            Text(
                text = text.settings.avatarColor,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
            )
            // Eight swatches on one line, and the line is *measured* rather than scrolled:
            // 8 x 38dp + 7 x 4dp of gap + 32dp of gutter is 364dp, against 312dp inside a
            // sheet on a 360dp phone (the sheet supplies 24dp a side and the card 16dp
            // more), so the last two colours sat past the edge behind a scroll gesture
            // nothing announced. A palette that has to be dragged to be seen is not read
            // as a palette. 34dp instead: 8 x 34dp + 7 x 4dp = 300dp, inside the 312dp
            // the narrowest phone leaves, and still a 34dp touch target.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarColor.entries.forEach { entry ->
                    SettingsSwatch(
                        color = entry.seed,
                        selected = entry == color,
                        onClick = { onChangeColor(entry) },
                        size = SWATCH_SIZE,
                    )
                }
            }
        }
    }

    // The crop step. Its own window over the sheet, keyed on the decoded picture so a
    // second pick starts from the new one rather than from the last drag.
    cropping?.let { picture ->
        AvatarCropDialog(
            picture = picture,
            text = text,
            onDismiss = {
                picture.recycle()
                cropping = null
            },
            onConfirm = { square ->
                val stored = AvatarStore.storeSquare(filesDir, slot, square)
                failed = stored == null
                picture.recycle()
                cropping = null
                // The old picture is replaced only once the new one is on disk: a crop
                // that could not be written leaves the avatar as it was rather than
                // blanking it.
                if (stored != null) onChangeImage(stored)
            },
        )
    }
}

/**
 * The square-crop step between picking a picture and keeping it.
 *
 * ## Why it exists
 *
 * The stored picture is drawn in a circle at 28dp and in an 88dp preview, and every
 * picture the picker hands back is whatever shape the camera or the download was. Until
 * this step existed the app cropped to the centre itself (`ContentScale.Crop`), which
 * quietly threw away everything outside the middle of a portrait photo — the reader could
 * not say *what* part of the picture the circle should show. Now they drag it into place,
 * and what the square shows is what the circle will show.
 *
 * ## The arithmetic
 *
 * The picture is scaled to *cover* the square ([coverScale]), then pushed around by
 * [offset] and grown by [zoom]. The offsets are clamped so a corner can never come inside
 * the square — an empty wedge in a crop preview is a crop that has nothing to keep — and
 * the crop itself maps the square back into the source's pixels by the same two numbers,
 * with no rounding shared between the preview and the result: the preview is a guide, the
 * bitmap is the answer.
 */
@Composable
private fun AvatarCropDialog(
    picture: android.graphics.Bitmap,
    text: Strings,
    onDismiss: () -> Unit,
    onConfirm: (android.graphics.Bitmap) -> Unit,
) {
    var zoom by remember(picture) { mutableFloatStateOf(1f) }
    var offset by remember(picture) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableIntStateOf(0) }
    val cover = if (box == 0) {
        1f
    } else {
        maxOf(box.toFloat() / picture.width, box.toFloat() / picture.height)
    }

    // The drawn size of the picture at the current zoom, and the bound the offset may
    // reach: the picture has to keep covering the square.
    fun clamp(candidate: Offset, scale: Float): Offset {
        if (box == 0) return Offset.Zero
        val drawnW = picture.width * cover * scale
        val drawnH = picture.height * cover * scale
        val maxX = ((drawnW - box) / 2f).coerceAtLeast(0f)
        val maxY = ((drawnH - box) / 2f).coerceAtLeast(0f)
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY),
        )
    }

    WindowDialog(
        show = true,
        title = text.settings.avatarCropTitle,
        summary = text.settings.avatarCropHint,
        onDismissRequest = onDismiss,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(PiCorner.radius))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .onSizeChanged { box = it.width }
                .pointerInput(picture, box) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val next = (zoom * gestureZoom).coerceIn(1f, 4f)
                        zoom = next
                        offset = clamp(offset + pan, next)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (box > 0) {
                val density = LocalDensity.current
                // The drawn size in pixels is the source size times `cover × zoom`, so at
                // zoom 1 the picture exactly covers the square in its longer axis.
                Image(
                    bitmap = picture.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(
                            width = with(density) { (picture.width * cover * zoom).toDp() },
                            height = with(density) { (picture.height * cover * zoom).toDp() },
                        )
                        .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) },
                )
            }
        }
        PiDialogAnswers(
            dismiss = text.common.cancel,
            onDismiss = onDismiss,
            confirm = text.settings.avatarCropUse,
            onConfirm = { onConfirm(crop(picture, box, cover * zoom, offset)) },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * The square the preview shows, as a bitmap.
 *
 * [scale] is the source-to-preview factor (`cover × zoom`), so dividing the preview's own
 * distances by it lands in source pixels. Clamped to the picture, because a crop that ran
 * off the edge would throw rather than produce a slightly smaller square.
 */
private fun crop(
    picture: android.graphics.Bitmap,
    box: Int,
    scale: Float,
    offset: Offset,
): android.graphics.Bitmap {
    val side = if (scale <= 0f) box else (box / scale).toInt().coerceAtLeast(1)
    val left = ((picture.width - side) / 2f - offset.x / scale).toInt()
    val top = ((picture.height - side) / 2f - offset.y / scale).toInt()
    val x = left.coerceIn(0, (picture.width - side).coerceAtLeast(0))
    val y = top.coerceIn(0, (picture.height - side).coerceAtLeast(0))
    val safeSide = side.coerceAtMost(minOf(picture.width - x, picture.height - y))
    return android.graphics.Bitmap.createBitmap(picture, x, y, safeSide, safeSide)
}

/** The swatch's diameter: eight of them plus four 6dp gaps is 298dp, inside 312dp. */
private val SWATCH_SIZE = 34.dp

/** Anything the system picker can hand back as an image. */
private const val IMAGE_MIME = "image/*"

/** The most characters an avatar *name* holds before it is cut. */
private const val AVATAR_NAME_MAX = 16

/** The large avatar at the top of its editor. */
private val AVATAR_PREVIEW_SIZE = 96.dp

/**
 * A theme's name in the interface language.
 *
 * Three radio rows rather than a segmented control of three short labels: the
 * Japanese `システムに合わせる` is nine characters in a third of a row, which is an
 * ellipsis in a segmented control and a whole label on a row of its own. The enum's
 * own order is the order they are offered in.
 */
private fun themeModeLabel(mode: ThemeMode, text: Strings): String = when (mode) {
    ThemeMode.SYSTEM -> text.settings.themeSystem
    ThemeMode.LIGHT -> text.settings.themeLight
    ThemeMode.DARK -> text.settings.themeDark
}

/**
 * A launcher mark's name in the interface language.
 *
 * Named by their colours rather than by "light" and "dark", because the two words the
 * theme rows above use already mean the interface palette, and the icon does not
 * follow it — a user on the light theme can have the black icon.
 */
private fun launcherIconLabel(icon: LauncherIcon, text: Strings): String = when (icon) {
    LauncherIcon.DARK -> text.settings.launcherIconBlack
    LauncherIcon.LIGHT -> text.settings.launcherIconWhite
}
