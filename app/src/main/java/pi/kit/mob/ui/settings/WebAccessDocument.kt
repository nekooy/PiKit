package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.WebSearchStore
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The configuration document, as editable text — the fallback path.
 *
 * ## When this is on screen
 *
 * Only when `web-search.json` exists and is not a JSON object. The page's normal
 * configuration section is a list plus an "Add an option" row
 * ([WebSearchConfigOptions]), which cannot start from a file it cannot parse: the
 * store refuses to write over one, so every control would be a control that appears
 * to work and does not. This is the way out — the text as it is, editable, with a
 * Save that validates before it writes — and it is deliberately the same editor the
 * page used to show for everything.
 *
 * It is **not** redundant with the list next door, and that is worth stating because
 * it looks it: a file that is not an object has no keys for the list to show, and the
 * store will not write one it could not read, so without this box a hand-broken
 * `web-search.json` would be a page with no working control on it at all. The other
 * way out is the page's own reset section ([WebSearchResetSection]), which writes the
 * default document without reading the broken one; this one exists for the user who
 * wants to *keep* what they typed and fix the syntax.
 *
 * ## The document is not the file
 *
 * The bundled extension reads roughly 110 keys and the page above draws controls for
 * six. Everything else is in this box when it is shown: the keys in use are set, and
 * every other key is a comment carrying its path, what it does and a usable example,
 * so the document explains itself. Removing a `//` is how a key is turned on.
 *
 * The comments are the *editor's*, not the file's, and that is not a detail: the
 * extension parses `web-search.json` with a strict `JSON.parse`, so a comment in the
 * file makes every web tool fail (`Unexpected token '/' … is not valid JSON`). Save
 * therefore writes only the keys that are live here, as plain JSON — `strictDocument`
 * in `pi/WebSearchStore.kt` is the whole of it — and the box is re-rendered from the
 * file that resulted, so what is on screen is always what pi has. A comment the user
 * adds is not stored, and the page's note says so.
 *
 * ## What it is careful about
 *
 * It reads the file rather than a reconstruction of it, so a key this app has never
 * heard of is visible and editable. The text is validated before it is written, with
 * the comment rule this app reads by, so a mistake is refused here rather than
 * discovered by the agent at its next start.
 *
 * The box loads once, when it appears, and after that it is the user's: nothing else
 * on the page can write the file while this is on screen, because the controls that
 * could are hidden in the same state. That is why the draft no longer carries a
 * `dirty` flag and a "the controls above changed the file" effect. Restoring defaults
 * is no longer one of this box's buttons either: it is the page's own section now, and
 * one destructive action with two different-looking controls on one page was the thing
 * the two of them got wrong.
 */
@Composable
internal fun WebSearchDocumentEditor(store: WebSearchStore, session: PiAgentSession) {
    val text = strings
    val scope = rememberCoroutineScope()
    val toasts = LocalPiToast.current
    // Its own draft, not a read per recomposition: the box is what the user is
    // editing, and re-reading the file under their cursor would delete keystrokes.
    var draft by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Loaded once. There used to be a `LaunchedEffect(settings)` here with a `dirty`
    // flag in front of it, so that a change made by the *controls above* — which
    // rewrite the whole document — did not throw away edits in progress. That cannot
    // happen: those controls are hidden in exactly the state this editor is shown in
    // (`unreadable` in [SearchPage]), so the guard could never fire, and the effect
    // was doing nothing but the initial load. The reasoning stays because the flag
    // was doing real work when the editor was the page's *main* UI rather than its
    // fallback; if the two ever share a screen again, the guard has to come back.
    LaunchedEffect(Unit) {
        draft = store.documentText()
    }

    val value = draft

    // A card and not a group: the document is one subject with a body of its own, which
    // is the whole distinction between the two containers. A group is a set of peer
    // rows, and there is exactly one thing here — Miuix's `Card`, drawn where the page's
    // other sections draw theirs.
    Card(
        cornerRadius = PiCorner.radius,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        // `SettingsTextField` cannot be used here, and the two reasons are both
        // load-bearing: it takes a single-line label and this box has no label, and its
        // keyboard options carry the keyboard type only, while a JSON key name does not
        // survive autocorrect. Everything else about it is what that helper draws —
        // Miuix's own field, the monospace face, and no container of its own.
        TextField(
            cornerRadius = PiCorner.radius,
            value = value.orEmpty(),
            onValueChange = { next -> draft = next },
            enabled = value != null,
            // Monospace and small: Miuix's `body2` keeps about 46 columns on a phone,
            // which is the width the file is written at.
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                // A JSON document is machine text: an autocorrected key name is a
                // setting that silently does nothing.
                autoCorrectEnabled = false,
            ),
            maxLines = Int.MAX_VALUE,
            // No focus ring, and the caret stated instead: see `piFieldColors`.
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT),
        )

        // What the last press did is a toast, so nothing is drawn between the box and
        // its buttons. The three outcomes are the same three the list's own writes
        // report, and none of them is a *condition*: the file on disk is either what
        // was just written or what it was, and the box in front of the reader is the
        // evidence either way.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 8 above, and the card's own 12 at the sides. The pair splits the row —
                // Revert, then Save — which is the footer shape every form in the app
                // now takes; it used to be sized to its words on the trailing edge, and a
                // pair of halves is both easier to hit and the same gesture wherever a
                // form ends.
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsAction(
                text = text.settings.searchConfigRevert,
                enabled = value != null && !saving,
                onClick = {
                    scope.launch {
                        draft = store.documentText()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            SettingsAction(
                text = text.settings.searchConfigSave,
                onClick = {
                    scope.launch {
                        saving = true
                        toasts?.show(
                            store.replaceDocument(draft.orEmpty()).fold(
                                onSuccess = {
                                    // Re-read rather than keeping the box as it was
                                    // typed: the comments in it are not in the file, so
                                    // leaving them on screen would show a document pi
                                    // does not have. What comes back is the annotated
                                    // rendering of what was just written.
                                    draft = store.documentText()
                                    // The document carries the `tools.*` keys, so what
                                    // was just written is not in effect until pi has
                                    // started again — which this does rather than
                                    // telling the reader to.
                                    session.scheduleRestart()
                                    text.settings.searchConfigSaved
                                },
                                onFailure = { error ->
                                    // A syntax error and a disk failure are different
                                    // sentences and the reader can act on the first
                                    // one; both are toasts, and the box below the pill
                                    // is where the fix goes.
                                    if (error is IllegalArgumentException) {
                                        text.settings.searchConfigInvalid
                                    } else {
                                        text.settings.searchConfigFailed
                                    }
                                },
                            ),
                        )
                        saving = false
                    }
                },
                // Refused only while the write is in flight: whether the text is a
                // JSON object is answered by the write's own result, which is where
                // the reason can be told from a disk failure.
                enabled = value != null && !saving,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The editor's height bounds.
 *
 * A floor so the box reads as a document rather than as a one-line field, and a cap
 * so a large file does not push the buttons off the screen — past the cap the field
 * scrolls itself, which is what a text field with a fixed height does. The rendered
 * document is around two hundred lines, so the cap is what the reader will see most
 * of the time.
 *
 * The field is the one scroller inside the page's own: the page body is a plain
 * `Column(verticalScroll)`, and a field that scrolls itself inside it is fine — both
 * are bounded, and neither is a lazy list measured against an unbounded height.
 */
private val EDITOR_MIN_HEIGHT = 240.dp
private val EDITOR_MAX_HEIGHT = 440.dp
