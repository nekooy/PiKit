package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.WebSearchStore
import kotlinx.coroutines.launch

/** What the last button press did, which is the only thing the editor reports. */
private enum class EditorOutcome { Saved, Invalid, Failed }

/**
 * The configuration document, as editable text — the fallback path.
 *
 * ## When this is on screen
 *
 * Only when `web-search.json` exists and is not a JSON object. The page's normal
 * configuration section is a list plus an "Add an option" button
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
 * ## What is in the box, and what is in the file
 *
 * The bundled extension reads roughly eighty keys and the page above draws controls
 * for six. Everything else is in this box when it is shown: the keys in use are set,
 * and every other key is a comment carrying its path, what it does and a usable
 * example, so the document explains itself. Removing a `//` is how a key is turned on.
 *
 * The comments are the *editor's*, not the file's, and that is not a detail: the
 * extension parses `web-search.json` with a strict `JSON.parse`, so a comment in the
 * file makes every web tool fail (`Unexpected token '/' … is not valid JSON`). Save
 * therefore writes only the keys that are live here, as plain JSON — see
 * [WebSearchStore] for the whole of it — and the box is re-rendered from the file that
 * resulted, so what is on screen is always what pi has. A comment the user adds is not
 * stored, and the page's note says so.
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
internal fun WebSearchDocumentEditor(store: WebSearchStore) {
    val text = strings
    val scope = rememberCoroutineScope()
    // Its own draft, not a read per recomposition: the box is what the user is
    // editing, and re-reading the file under their cursor would delete keystrokes.
    var draft by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<EditorOutcome?>(null) }

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

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = { next ->
                draft = next
                outcome = null
            },
            enabled = value != null,
            // Monospace and small: this is a machine document, and the indentation
            // is how a nested key is read. `bodySmall` keeps about 46 columns on a
            // phone, which is the width the file is written at.
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                // A JSON document is machine text: an autocorrected key name is a
                // setting that silently does nothing.
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT),
        )

        // Only what the last press did. A file that arrived unreadable is the
        // page-level note's subject, and it says something better than "nothing was
        // saved": nothing has been attempted yet.
        when (outcome) {
            EditorOutcome.Invalid ->
                ConfigNote(text.settings.searchConfigInvalid, MaterialTheme.colorScheme.error)

            EditorOutcome.Saved ->
                ConfigNote(text.settings.searchConfigSaved, MaterialTheme.colorScheme.primary)

            EditorOutcome.Failed ->
                ConfigNote(text.settings.searchConfigFailed, MaterialTheme.colorScheme.error)

            null -> Unit
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        outcome = store.replaceDocument(draft.orEmpty()).fold(
                            onSuccess = {
                                // Re-read rather than keeping the box as it was typed:
                                // the comments in it are not in the file, so leaving
                                // them on screen would show a document pi does not
                                // have. What comes back is the annotated rendering of
                                // what was just written.
                                draft = store.documentText()
                                EditorOutcome.Saved
                            },
                            onFailure = { error ->
                                if (error is IllegalArgumentException) {
                                    EditorOutcome.Invalid
                                } else {
                                    EditorOutcome.Failed
                                }
                            },
                        )
                        saving = false
                    }
                },
                // Refused only while the write is in flight: whether the text is a
                // JSON object is answered by the write's own result, which is where
                // the reason can be told from a disk failure.
                enabled = value != null && !saving,
            ) {
                Text(text.settings.searchConfigSave)
            }
            TextButton(
                onClick = {
                    scope.launch {
                        draft = store.documentText()
                        outcome = null
                    }
                },
                enabled = value != null && !saving,
            ) {
                Text(text.settings.searchConfigRevert)
            }
        }
    }
}

/**
 * One line of this section's own state, in the colour of what it reports.
 *
 * Shared with the add-an-option list next door ([WebSearchConfigOptions]) and with the
 * reset section ([WebSearchResetSection]): all three are the same statement about the
 * same file — what the last press did — so they are one composable rather than three
 * that drift on the first change to any of them.
 *
 * Four dp of vertical padding rather than two, because in both of the sections that
 * are not the editor this note is the last thing in its section: at two the line box sat
 * a hair above the card's rounded bottom edge and read as falling out of the section. 4dp
 * is the inset the app's other trailing notes already use (every `SettingsNote` that ends
 * a card passes `vertical = 4.dp`), so this is that convention rather than a new number.
 * It sits **outside** the card (see [WebSearchConfigOptions] for why: a line under the
 * card's last row squares off that row's press ripple), so what the padding keeps it off
 * is the card's bottom edge rather than the note's own container.
 */
@Composable
internal fun ConfigNote(message: String, color: Color) {
    Text(
        text = message,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

/**
 * The editor's height bounds.
 *
 * A floor so the box reads as a document rather than as a one-line field, and a cap
 * so a large file does not push the buttons off the screen — past the cap the field
 * scrolls itself, which is what a text field with a fixed height does. The rendered
 * document is around two hundred lines, so the cap is what the reader will see most
 * of the time.
 */
private val EDITOR_MIN_HEIGHT = 240.dp
private val EDITOR_MAX_HEIGHT = 440.dp
