package pi.kit.mob.pi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import pi.kit.mob.env.BundledExtension
import pi.kit.mob.env.TermuxEnv
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.WEB_ACCESS_PARAMS
import pi.kit.mob.locales.WebAccessParam
import pi.kit.mob.locales.stringsFor
import java.io.File

/**
 * The settings page's view of `$HOME/.pi/agent/web-search.json`.
 *
 * ## Two documents, and why they are no longer one
 *
 * The extension reads its configuration with a **strict `JSON.parse`** — there is no
 * comment stripping anywhere on its side, contrary to what pi's own config reader
 * does: `loadConfiguredProxy` in its `utils.ts` parses the file directly, and so does
 * the `loadConfig` of every provider that takes a key. A file with a `//` comment in
 * it therefore fails the *whole* extension, with
 * `Failed to load proxy config from ~/.pi/agent/web-search.json: Unexpected token '/'`,
 * and every `web_search`, `fetch_content` and `source_check` call fails with it.
 * Writing the documentation *into* the file — which is what this class used to do —
 * was that bug.
 *
 * So there are two documents now:
 *
 *  - **The file** (`web-search.json`) is plain JSON, written by [strictDocument].
 *    Only keys that are live in the editor are in it, and every comment is gone.
 *  - **The document** is what [renderWebSearchDocument] produces and what the
 *    settings page's editor shows: PiKit's own six keys live with the values in
 *    effect, and every other option the extension reads as a comment carrying its
 *    path, what it does and a usable example. `WEB_ACCESS_PARAMS` is that list.
 *
 * The annotated text is *parsed with comments allowed* on the way in and stripped on
 * the way out, so the reader keeps the documentation and the extension gets JSON it
 * can parse. The cost, and it is stated on the page: comments are the app's, not the
 * file's, so a comment the user adds is not stored.
 *
 * ## Why the app edits pi's file instead of remembering its own answer
 *
 * These are options for the `pi-web-access` extension, which pi loads inside the
 * runtime. PiKit could keep a copy in its own `SharedPreferences` and apply it at
 * launch, and then the copy the user edits and the file pi reads could disagree —
 * two writers for one setting, with the agent acting on the one the page is not
 * showing. So the page edits the file pi actually reads, and this class is its
 * only writer.
 *
 * ## PiKit's default is not the extension's default
 *
 * The extension defaults `workflow` to `summary-review`, which opens a browser
 * curator so each summary can be hand-checked — a desktop workflow, useless on a
 * phone. PiKit's default is `none`, and it is in the rendered document, so the
 * default the page shows is also the default pi is running with.
 *
 * ## Reads are tolerant, the file on disk is not
 *
 * A file this app cannot parse as a JSON object is left exactly as it is: it may be
 * one the user wrote by hand, and guessing at it would replace their text with
 * PiKit's. Everything else is read through the comment stripper, so a hand-commented
 * file — one written by the build that had this bug, or by an older PiKit — is read
 * rather than reported as broken, and [ensureDocument] *rewrites* it in the strict
 * form, because a commented file is one the extension cannot use at all. That repair
 * is the only thing that touches a file nobody asked this app to change, and it is
 * what makes the fix land on an existing install without the user doing anything.
 *
 * A write from a *control* re-renders the whole document from what is on disk, so the
 * user's keys and values all survive it. A write from the editor is the user's own
 * text, parsed and re-encoded, because that is the one path where they are the
 * author — minus the comments, which are not the file's to keep.
 */
data class WebSearchSettings(
    /**
     * The "Web access" master switch: `webSearch.enabled` plus the four
     * `tools.<name>.enabled` keys — `webSearch`, `sourceCheck`, `fetchContent` and
     * `getSearchContent`.
     *
     * The extension has no single switch of its own. `webSearch.enabled` is a
     * legacy shorthand that unregisters only `web_search` and `source_check`, so a
     * row reading "Search the web, read pages, clone GitHub links" bound to it
     * alone would be a lie: fetching and cloning would still be registered. PiKit
     * therefore writes all five together — `true` for on, `false` for off — and
     * reads them the way the extension resolves them (its `isToolEnabled`): a
     * tool's own `tools.<name>.enabled` when that is a boolean, the shorthand for
     * the two tools it covers otherwise, and on when neither says anything, so the
     * switch is off only when the extension would have no tool left.
     */
    val enabled: Boolean = true,
    /**
     * `workflow`: `none`, `auto-summary` or `summary-review`. See the class note
     * for why PiKit defaults to `none` rather than to the extension's default.
     */
    val workflow: String = WORKFLOW_NONE,
    /**
     * `provider`; null means the extension routes the search itself.
     *
     * `searchProvider` is the extension's alias for this key and wins when both are
     * present, so a file that spells it that way is not one this app's picker can
     * show or change: [readWebSearchSettings] reads only `provider`, and the
     * renderer leaves `searchProvider` where it is. Documented in the file itself
     * rather than worked around, because honouring both would be two answers to one
     * question.
     */
    val provider: String? = null,
    /** `maxInlineContentChars`: how much fetched text is handed to the model. */
    val maxInlineContentChars: Int = DEFAULT_INLINE_CONTENT_CHARS,
    /** `fetch.timeout`, in seconds. */
    val fetchTimeoutSeconds: Int = DEFAULT_FETCH_TIMEOUT_SECONDS,
    /**
     * `proxy`; null means no proxy. Schemes: http, https, socks4, socks4a, socks5,
     * socks5h.
     */
    val proxy: String? = null,
) {

    internal companion object {

        /**
         * The name of pi's own config file for the extension, under
         * [TermuxEnv.piConfigDir].
         *
         * The directory is `$HOME/.pi/agent` because that is the one the vendored
         * extension actually resolves to: it takes `$PI_CODING_AGENT_DIR` when it
         * is set, else `$XDG_CONFIG_HOME/pi` (or `~/.pi` when that older directory
         * already holds the file), else `$HOME/.pi/agent` (`utils.ts`,
         * `getWebSearchConfigDir`). PiKit sets neither variable, so the agent
         * directory is what pi reads.
         *
         * The extension's published README has named `~/.pi/web-search.json` in
         * older versions. That is stale — do not "fix" this path from it.
         */
        const val FILE_NAME = "web-search.json"

        /** Workflow ids, in the order the extension documents them. */
        const val WORKFLOW_NONE = "none"
        const val WORKFLOW_AUTO_SUMMARY = "auto-summary"
        const val WORKFLOW_SUMMARY_REVIEW = "summary-review"

        /** An omitted provider and `"auto"` mean the same thing: the extension routes. */
        const val PROVIDER_AUTO = "auto"

        /**
         * Every search provider the bundled extension can route to, in the order the
         * page offers them — the ones that work with no key first, then the ones that
         * run the search through a model, then the rest alphabetically enough to be
         * findable.
         *
         * **This is the extension 0.30.0's own `RESOLVED_SEARCH_PROVIDERS`**, read
         * from its `gemini-search.ts`, and it is 31 entries rather than the eleven
         * the page used to offer. The eleven were not "the supported ones": they were
         * the ones somebody had heard of, so a user whose provider was `kagi` or
         * `serper` could not select it at all and the picker looked complete.
         *
         * `auto` and `all` are selectors rather than providers and are not listed:
         * `auto` is the picker's own "Automatic" row (it stores the omission), and
         * `all` fans out over eighteen providers at once, which is a deliberate
         * choice for the document rather than a value to tick by accident.
         *
         * The list is pinned by `WebSearchStoreTest`, and the bundled version it
         * belongs to is in the runtime image's `build-metadata.json`
         * (`web_access.version`). A version bump that changes this set has to change
         * it here in the same commit.
         */
        val SEARCH_PROVIDERS = listOf(
            // No key needed.
            "exa",
            "duckduckgo",
            "searxng",
            // Search runs through a model you already have a key for.
            "openai",
            "gemini",
            "perplexity",
            "kimi",
            "xai",
            "mistral",
            "ollama",
            // Keyed search APIs.
            "brave",
            "tavily",
            "jina",
            "firecrawl",
            "serper",
            "serpapi",
            "serpbase",
            // Added by 0.30.0, and explicitly opt-in there: it is never chosen by
            // `auto`, so it appears in this list without becoming the default for
            // anyone who has a key from somewhere else.
            "serply",
            "kagi",
            "valyu",
            "bocha",
            "querit",
            "search1api",
            "searchinfinity",
            "tinyfish",
            "parallel",
            "parallel-mcp",
            "anysearch",
            "xcrawl",
            "brightdata",
            "serpdive",
        )

        /** `maxInlineContentChars` as the extension documents it. */
        const val DEFAULT_INLINE_CONTENT_CHARS = 30_000

        /** The cap the extension applies, so a larger number is not what is in effect. */
        const val MAX_INLINE_CONTENT_CHARS = 200_000

        /** `fetch.timeout` as the extension documents it. */
        const val DEFAULT_FETCH_TIMEOUT_SECONDS = 30
    }
}

// ------------------------------------------------------------------ reading

/**
 * One key in the file that no control on the settings page owns.
 *
 * [path] is dotted (`fetch.timeout`, `xaiApiKey`) and [value] is what the file
 * holds, verbatim. A key whose own path is one of the extension's documented
 * options is reported **once, whole** — an object-valued option like
 * `curatorRemote` is one entry, not one per member — so the page's list and the
 * page's "remove" both act on the thing the user added.
 *
 * A key the schema has never heard of is reported by its leaf path, because that
 * is the only name it has; `WebSearchStore.renderWebSearchDocument` keeps such a
 * key exactly where it was found.
 */
data class WebSearchEntry(val path: String, val value: JsonElement)

/**
 * The file's live keys that the page's controls do not own, in file order.
 *
 * A node whose path is one of the extension's documented options is reported whole
 * and not descended into — an object-valued option like `curatorRemote` is one
 * entry, so the page's list and its "remove" both act on the thing the user added —
 * and anything else that holds an object is descended into, so a hand-written
 * `fetch: {timeout: 5}` is listed as the key it actually is. The order is the
 * file's own, because that is the order the page's list shows.
 *
 * Top-level and internal rather than a private method so `WebSearchStoreTest` can
 * assert it against the rendered document: the rule that a key the controls own is
 * never listed, and a key they do not own always is, is worth a failing test.
 */
internal fun webSearchEntries(document: JsonObject?): List<WebSearchEntry> {
    if (document == null) return emptyList()
    val out = mutableListOf<WebSearchEntry>()
    fun walk(node: JsonObject, prefix: String) {
        node.forEach { (key, value) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (isOwned(path)) return@forEach
            if (value is JsonObject && path !in SCHEMA_PATHS) {
                walk(value, path)
            } else {
                out += WebSearchEntry(path, value)
            }
        }
    }
    walk(document, "")
    return out
}

/**
 * The comment and trailing-comma rule this app reads with.
 *
 * Two regular expressions, deliberately: pi's own core reader strips `//` comments
 * and a comma before a closing brace this way (`utils/json.js`,
 * `stripJsonComments`), so a file written for pi by hand — or by an older PiKit,
 * which wrote the documentation into the file — is read here rather than reported as
 * broken.
 *
 * It is **not** the extension's rule, and that distinction is the whole of the bug
 * this file's documentation records: the extension parses the file with a bare
 * `JSON.parse`, so what this app *writes* has to be strict JSON. This function is
 * therefore only ever the reader, and the writer's guard is [strictDocument].
 *
 * Block comments are deliberately *not* handled: neither pi nor the extension
 * accepts them, so a file using one is a file nothing can read, and silently
 * accepting it here would let the page show a document the agent fails on.
 *
 * The string-literal alternative in each pattern is what keeps `//` inside a value
 * — a URL, a path — from being taken for a comment.
 */
internal fun stripJsonComments(input: String): String =
    JSON_STRING_OR_LINE_COMMENT.replace(input) { match ->
        if (match.value.startsWith("\"")) match.value else ""
    }.let { withoutComments ->
        JSON_STRING_OR_TRAILING_COMMA.replace(withoutComments) { match ->
            val tail = match.groupValues[1]
            when {
                tail.isNotEmpty() -> tail
                match.value.startsWith("\"") -> match.value
                else -> ""
            }
        }
    }

/** `"(?:\\.|[^"\\])*"` — a JSON string literal, escapes and all. */
private const val JSON_STRING = """"(?:\\.|[^"\\])*""""

private val JSON_STRING_OR_LINE_COMMENT = Regex("$JSON_STRING|//[^\\n]*")

private val JSON_STRING_OR_TRAILING_COMMA = Regex("$JSON_STRING|,(\\s*[}\\]])")

/**
 * The document as a JSON object, or null when the text is not one.
 *
 * Comments and trailing commas are stripped first, because the annotated document
 * carries them: this is the reader that decides whether the page can show a file,
 * and whether what the user typed into the editor is a document at all.
 */
internal fun readWebSearchDocument(text: String): JsonObject? =
    runCatching {
        Json.parseToJsonElement(stripJsonComments(text)) as? JsonObject
    }.getOrNull()

/**
 * The bytes the extension reads: the annotated document with every comment resolved
 * away, as strict JSON.
 *
 * This is the writer's guard rail, and it is one line of work for a large bug: the
 * extension parses the file with `JSON.parse`, so anything commented in the editor
 * must not reach the disk. Parsing the annotated text also decides *which* keys are
 * live — a key inside a `//` line is not in the object, so it is not in the file —
 * which is why the live/commented split is expressed once, in the rendering, and
 * derived here rather than duplicated as a second writer.
 *
 * Pretty-printed at two spaces with no trailing comma, so the file is the same shape
 * as the document the editor shows. A malformed annotated document writes `{}`
 * rather than an unparseable file; callers validate before they get here.
 */
internal fun strictDocument(annotated: String): String {
    val document = readWebSearchDocument(annotated) ?: return "{}\n"
    return STRICT_JSON.encodeToString(JsonObject.serializer(), document) + "\n"
}

/**
 * Two spaces, like the annotated document, so a key sits at the same column in both
 * and the editor's indentation is the file's.
 */
@OptIn(ExperimentalSerializationApi::class)
private val STRICT_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * The settings [text] describes, or null when it is not a JSON object.
 *
 * Every field is optional to the extension, so an absent key reads as PiKit's
 * default for it rather than as an error. A value whose type is wrong reads as
 * absent for the same reason — the extension ignores a `"timeout": "soon"` too.
 *
 * Omitted and `"auto"` are the same statement about the provider, so both read as
 * null: the page has one "Automatic" option and PiKit writes the omission.
 */
internal fun readWebSearchSettings(text: String): WebSearchSettings? {
    val document = readWebSearchDocument(text) ?: return null
    val defaults = WebSearchSettings()
    val fetch = document[KEY_FETCH] as? JsonObject
    val search = document[KEY_WEB_SEARCH] as? JsonObject
    val tools = document[KEY_TOOLS] as? JsonObject

    // The extension's own resolution, tool by tool (`isToolEnabled` in its
    // `index.ts`): a tool's own `tools.<name>.enabled` when that is a boolean, the
    // legacy `webSearch.enabled` shorthand for the two tools it covers, otherwise
    // on. A tool is an object — `tools.webSearch.enabled` — and not a bare
    // boolean, which is what a file that put `"webSearch": false` under `tools`
    // would have to mean if it meant anything.
    val legacy = search.boolean(KEY_ENABLED)

    return WebSearchSettings(
        enabled = listOf(
            tools.nestedObject(KEY_WEB_SEARCH).boolean(KEY_ENABLED) ?: (legacy != false),
            tools.nestedObject(KEY_SOURCE_CHECK).boolean(KEY_ENABLED) ?: (legacy != false),
            tools.nestedObject(KEY_FETCH_CONTENT).boolean(KEY_ENABLED) ?: true,
            tools.nestedObject(KEY_GET_SEARCH_CONTENT).boolean(KEY_ENABLED) ?: true,
        ).any { it },
        workflow = document.string(KEY_WORKFLOW) ?: defaults.workflow,
        provider = document.string(KEY_PROVIDER)?.takeIf { it != WebSearchSettings.PROVIDER_AUTO },
        maxInlineContentChars = document.int(KEY_MAX_INLINE) ?: defaults.maxInlineContentChars,
        fetchTimeoutSeconds = fetch.int(KEY_TIMEOUT) ?: defaults.fetchTimeoutSeconds,
        proxy = document.string(KEY_PROXY),
    )
}

// ------------------------------------------------------------------ writing

/**
 * The annotated document, with every key the extension reads in it: PiKit's own keys
 * live, everything else a comment.
 *
 * This is what the settings page's editor shows, and — through [strictDocument],
 * which resolves the comments away — where the file's contents come from. Nothing
 * here is written to disk as it stands; see the class note for why.
 *
 * ## The shape
 *
 * The schema is [WEB_ACCESS_PARAMS] — the same list the page used to draw a
 * reference table from — split on `.` into a tree. Walking it produces the document
 * in that list's order, so the file and the documentation cannot drift: a key added
 * there appears here, and a key removed there stops being written.
 *
 * Every option's note line carries its full path, what it does and — when it is
 * actually in the file — a marker in front of the path
 * ([Strings.Settings.searchConfigInEffect]). Only the live ones are marked: the
 * question a reader has in front of three hundred lines is *which of these are on*,
 * and a tag on every line would answer it no better than the `//` already did while
 * pushing every description further right in a box about 46 columns wide. The
 * unmarked lines are documentation, and the header says so.
 *
 * ## The three rules that make it lossless
 *
 * 1. **A key the document already has is written uncommented with its value**,
 *    whether or not PiKit owns it. Uncommenting a line in the editor and saving is
 *    therefore permanent: the next tap of a control re-renders the document with
 *    that key still live.
 * 2. **A key the schema does not contain is written uncommented**, inside whatever
 *    object it was found in, with a comment saying it is not PiKit's. A newer
 *    extension's option, or something the user invented, is never dropped.
 * 3. **A key the schema has and the document does not is written as a comment** —
 *    its path, its description, and an example — which is the documentation.
 *
 * The one thing it does not preserve is the user's own *formatting*: the document
 * comes back in PiKit's shape. That is deliberate. The document is the documentation,
 * and one whose comments survive only until the first switch is tapped would be worse
 * than one that is always the same shape.
 *
 * @param header the comment block at the top, already split into lines.
 */
internal fun renderWebSearchDocument(
    document: JsonObject,
    settings: WebSearchSettings,
    lang: Lang,
    header: List<String>,
): String = buildString {
    header.forEach { line ->
        append("// ")
        append(oneLineComment(line))
        append('\n')
    }
    append("{\n")
    SchemaBody.render(SCHEMA_ROOT, document, INDENT, lang, settings).lines.forEach { line ->
        append(line)
        append('\n')
    }
    append("}\n")
}

/** The indent every level adds. Two spaces, like every other file PiKit writes. */
private const val INDENT = "  "

/**
 * A comment never spans two lines: everything after `//` to the end of the line is
 * dropped when the file is read, so a description with a newline in it would lose
 * its second half *and* every line after it.
 */
private fun oneLineComment(text: String): String =
    text.replace('\n', ' ').replace('\r', ' ').trim()

/** One node of the schema tree: a key, its children, and the row describing it. */
private class SchemaNode(val path: String, val key: String) {
    val children = LinkedHashMap<String, SchemaNode>()
    var param: WebAccessParam? = null
}

private val SCHEMA_ROOT: SchemaNode = SchemaNode("", "").also { root ->
    WEB_ACCESS_PARAMS.forEach { param ->
        var node = root
        var path = ""
        param.path.split('.').forEach { segment ->
            path = if (path.isEmpty()) segment else "$path.$segment"
            node = node.children.getOrPut(segment) { SchemaNode(path, segment) }
        }
        node.param = param
    }
}

/**
 * The keys PiKit owns, and the value each one takes from [WebSearchSettings].
 *
 * A key that is *owned* is always written by the settings — including when the
 * settings say it is unset, in which case it goes back to being a comment. A key
 * that is not owned is never written from the settings at all; that is what keeps
 * `githubClone.enabled` and its defaults out of this app's hands.
 */
private fun isOwned(path: String): Boolean =
    path in OWNED_PATHS || path in TOOL_ENABLED_PATHS

private val OWNED_PATHS = setOf(
    "workflow",
    "provider",
    "maxInlineContentChars",
    "fetch.timeout",
    "proxy",
    "webSearch.enabled",
)

private val TOOL_ENABLED_PATHS = setOf(
    "tools.webSearch.enabled",
    "tools.sourceCheck.enabled",
    "tools.fetchContent.enabled",
    "tools.getSearchContent.enabled",
)

/**
 * Every key the settings page's own controls write.
 *
 * The "add an option" list excludes these, because a value added there would be
 * overwritten by the next tap on any control — the renderer writes them from
 * [WebSearchSettings] every time. Exported so the page has one list rather than a
 * second copy of it that drifts the first time either side gains a key.
 */
internal val WEB_SEARCH_OWNED_PATHS: Set<String> = OWNED_PATHS + TOOL_ENABLED_PATHS

/**
 * Every path the reference list documents, as a set.
 *
 * The page's "add an option" list is drawn from the same list, and this is what
 * tells a *documented* key from a key nobody has heard of: the former is reported
 * whole by [WebSearchStore.extra] — an object-valued option is one entry — and the
 * latter is walked down to its leaves, because those are the only names it has.
 */
private val SCHEMA_PATHS: Set<String> = WEB_ACCESS_PARAMS.map { it.path }.toSet()

/**
 * The document with `path` set to [value], creating the objects on the way.
 *
 * A non-object found where the path needs to descend is replaced. `githubClone`
 * holding `true` cannot also hold `.enabled`, and merging something into a
 * boolean is not a thing; replacing it is the write the user asked for, and the
 * document is re-rendered afterwards so they see exactly what they got.
 */
internal fun JsonObject.withValueAt(segments: List<String>, value: JsonElement): JsonObject {
    val key = segments.first()
    val next = if (segments.size == 1) {
        value
    } else {
        (this[key] as? JsonObject ?: JsonObject(emptyMap())).withValueAt(segments.drop(1), value)
    }
    return JsonObject(LinkedHashMap(this).apply { put(key, next) })
}

/**
 * The document without `path`, and without any object the removal emptied.
 *
 * Pruning matters because an empty `{}` is not the same as an absent key: the
 * extension reads a present-but-empty branch as configured, and the page would
 * list `fetch` as an added option for ever after its last member was removed.
 */
internal fun JsonObject.withoutValueAt(segments: List<String>): JsonObject {
    val key = segments.first()
    if (!containsKey(key)) return this
    val next: JsonElement? = if (segments.size == 1) {
        null
    } else {
        (this[key] as? JsonObject)?.withoutValueAt(segments.drop(1))
    }
    val updated = LinkedHashMap(this)
    when {
        next == null -> updated.remove(key)
        next is JsonObject && next.isEmpty() -> updated.remove(key)
        else -> updated[key] = next
    }
    return JsonObject(updated)
}

private fun ownedValue(path: String, settings: WebSearchSettings): JsonElement? = when {
    path == "workflow" -> JsonPrimitive(settings.workflow)
    path == "provider" -> settings.provider?.let(::JsonPrimitive)
    path == "maxInlineContentChars" -> JsonPrimitive(settings.maxInlineContentChars)
    path == "fetch.timeout" -> JsonPrimitive(settings.fetchTimeoutSeconds)
    path == "proxy" -> settings.proxy?.let(::JsonPrimitive)
    path == "webSearch.enabled" -> JsonPrimitive(settings.enabled)
    path in TOOL_ENABLED_PATHS -> JsonPrimitive(settings.enabled)
    else -> null
}

/**
 * One entry of an object: its lines, and whether it is something the user can turn
 * into a live key by deleting `// `.
 *
 * The distinction is what the comma rule turns on. A live key and a commented-out
 * block both *become* part of the JSON when their `// ` goes, so both need their
 * comma already in place; a block of notes and an example line do not, and a comma
 * on a comment would only look like a typo.
 */
private class Entry(val lines: MutableList<String>, val kind: Kind) {
    enum class Kind { Key, Block, Comments }
}

/**
 * An object's rendered lines, and whether any of them is a live key.
 *
 * The flag is what tells a branch whether it may be written as `{}`: a branch whose
 * leaves are all comments is a branch the extension reads as "nothing configured",
 * which is right for most of the document and wrong for the two in
 * [NEVER_EMPTY_BRANCHES].
 */
private class Body(val lines: List<String>, val live: Boolean)

/**
 * Objects an empty `{}` is *not* a neutral value for.
 *
 * `searchRouting.providers` and `fetchRouting.providers` are required once their
 * parent exists — the extension validates the route and rejects a candidate list
 * that is missing or empty — so the two are written as a commented block until the
 * user gives them a value. An `{}` there would be a file the agent refuses to start
 * on, which is the class of failure this app exists to avoid.
 */
private val NEVER_EMPTY_BRANCHES = setOf("searchRouting", "fetchRouting")

private object SchemaBody {

    fun render(
        node: SchemaNode,
        source: JsonObject?,
        indent: String,
        lang: Lang,
        settings: WebSearchSettings,
    ): Body {
        val entries = mutableListOf<Entry>()
        val inEffect = stringsFor(lang).settings.searchConfigInEffect

        node.children.values.forEach { child ->
            val entry = if (child.children.isEmpty()) {
                leaf(child, source, indent, settings)
            } else {
                branch(child, source, indent, lang, settings)
            }

            // The state marker is a property of the finished entry — is this key in
            // the file? — so it is written after it rather than passed in. It is the
            // answer to the only question a reader has in front of three hundred
            // lines of which eight are configuration: *which of these are on?* The
            // `//` alone did not answer it, because a comment line and a live line
            // look alike until the eye reaches the value, and on the reader's own
            // report ("一头雾水") it never got that far.
            //
            // Only the live ones are marked. Tagging the documentation too would put
            // a tag on nearly every line and push every description further right in
            // a box that is about 46 columns wide — the marker is worth its columns
            // exactly as often as it is rare.
            child.param?.let { param ->
                entry.lines.add(
                    0,
                    buildString {
                        append(indent).append("// ")
                        if (entry.kind == Entry.Kind.Key) append("[").append(inEffect).append("] ")
                        append(param.path).append(" — ").append(oneLineComment(param.note(lang)))
                    },
                )
            }
            entries += entry
        }

        // Anything at this level the schema has never heard of. Kept uncommented,
        // where it was found, because it is the user's or a newer extension's and
        // this writer has no business deciding what it means.
        source?.forEach { (key, value) ->
            if (node.children.containsKey(key)) return@forEach
            entries += Entry(
                mutableListOf(
                    "$indent// $key — not a key PiKit knows; kept exactly as it was",
                    "$indent${quoted(key)}: ${encode(value)}",
                ),
                Entry.Kind.Key,
            )
        }

        return Body(join(entries), live = entries.any { it.kind == Entry.Kind.Key })
    }

    private fun leaf(
        child: SchemaNode,
        source: JsonObject?,
        indent: String,
        settings: WebSearchSettings,
    ): Entry {
        val value = if (isOwned(child.path)) {
            ownedValue(child.path, settings)
        } else {
            source?.get(child.key)
        }
        return if (value != null) {
            Entry(
                mutableListOf("$indent${quoted(child.key)}: ${encode(value)}"),
                Entry.Kind.Key,
            )
        } else {
            // The example comes from the reference row, so a key that has no value
            // still shows a usable one — and the line is a comment, so nothing is
            // configured by it.
            val example = child.param?.example ?: "null"
            Entry(
                mutableListOf("$indent// ${quoted(child.key)}: $example"),
                Entry.Kind.Comments,
            )
        }
    }

    private fun branch(
        child: SchemaNode,
        source: JsonObject?,
        indent: String,
        lang: Lang,
        settings: WebSearchSettings,
    ): Entry {
        val raw = source?.get(child.key)
        // A non-object where the schema expects one is the user's text; it is kept
        // verbatim rather than replaced by the rendered object.
        if (raw != null && raw !is JsonObject) {
            return Entry(
                mutableListOf("$indent${quoted(child.key)}: ${encode(raw)}"),
                Entry.Kind.Key,
            )
        }
        val body = render(child, raw as? JsonObject, "$indent$INDENT", lang, settings)
        if (body.live || child.key !in NEVER_EMPTY_BRANCHES) {
            // Spelled as a list rather than as `"…{" + body.lines + "…}"`, which is
            // the same shape only while the left operand is a List: with a String on
            // the left, Kotlin concatenates the *toString* of the list — `[a, b]` on
            // one line — and the document loses every branch it has.
            return Entry(
                mutableListOf<String>().apply {
                    add("$indent${quoted(child.key)}: {")
                    addAll(body.lines)
                    add("$indent}")
                },
                Entry.Kind.Key,
            )
        }
        // Nothing inside is live and an empty object would be a value the extension
        // rejects, so the whole block is a comment — braces included, which is what
        // makes uncommenting it a matter of removing three `// `.
        val block = buildList {
            add("$indent${quoted(child.key)}: {")
            addAll(body.lines)
            add("$indent}")
        }
        return Entry(block.map(::commentedOut).toMutableList(), Entry.Kind.Block)
    }

    /**
     * Commas between the entries that are, or can become, part of the JSON, and one
     * blank line between every two siblings.
     *
     * A comma after the last of them is *not* written, and that was settled by
     * experiment rather than by taste. pi strips trailing commas with a regular
     * expression whose string-literal alternative can swallow a comma that is
     * followed by comment-stripped blank lines, and on this document it left two of
     * them behind — `JSON.parse` then refused the file outright. So the annotated
     * document carries no comma that needs stripping, and the price is that
     * uncommenting a line may need a comma added to the line above it. The editor's
     * Save is the guard rail: it validates before it writes, and says so rather than
     * producing a document nothing can parse.
     *
     * A commented-out block is one of those entries: its closing brace carries the
     * comma when a live setting follows it, so uncommenting the block's three `// `
     * is all it takes. While it is commented the comma is inside a comment and costs
     * nothing.
     *
     * The blank line is the page's readability fix and nothing else's: the document
     * is three hundred lines of one-line notes, and a sibling boundary that is only
     * visible as a change of key is what the reader reported as "messy". It is a
     * *line*, not a `//` comment, so it costs nothing in the file — [strictDocument]
     * drops it with the comments — and the schema's own order still decides
     * everything else.
     */
    private fun join(entries: List<Entry>): List<String> {
        val lastLive = entries.indexOfLast { it.kind != Entry.Kind.Comments }
        val out = mutableListOf<String>()
        entries.forEachIndexed { index, entry ->
            if (index > 0 && out.isNotEmpty()) out += ""
            out += entry.lines
            if (entry.kind != Entry.Kind.Comments && index != lastLive) {
                out[out.lastIndex] = out.last() + ","
            }
        }
        return out
    }

    private fun quoted(key: String): String = JsonPrimitive(key).toString()

    private fun encode(value: JsonElement): String =
        Json.encodeToString(JsonElement.serializer(), value)
}

/**
 * Turns a line into a comment, keeping its indentation — and leaves a line that is
 * already a comment alone, so a commented block does not come out as `// // …`.
 *
 * An empty line stays empty, which is what keeps the blank lines *between* entries
 * from turning into `// ` inside a commented block: a line with nothing on it but a
 * comment marker is the one thing in this document that reads as a mistake.
 */
private fun commentedOut(line: String): String {
    val trimmed = line.trimStart()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("//")) return line
    return line.take(line.length - trimmed.length) + "// " + trimmed
}

/** A string value, or null when the key is absent, blank or not a string. */
private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** An integer value, or null when the key is absent or not a number. */
private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull

/** A boolean value, or null when the key is absent or not a boolean. */
private fun JsonObject?.boolean(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.booleanOrNull

/** An object value, or null when the key is absent or holds anything else. */
private fun JsonObject?.nestedObject(key: String): JsonObject? =
    this?.get(key) as? JsonObject

/** The document keys the reader looks at, spelled once. */
private const val KEY_WORKFLOW = "workflow"
private const val KEY_PROVIDER = "provider"
private const val KEY_MAX_INLINE = "maxInlineContentChars"
private const val KEY_FETCH = "fetch"
private const val KEY_TIMEOUT = "timeout"
private const val KEY_WEB_SEARCH = "webSearch"
private const val KEY_ENABLED = "enabled"
private const val KEY_PROXY = "proxy"
private const val KEY_TOOLS = "tools"
private const val KEY_SOURCE_CHECK = "sourceCheck"
private const val KEY_FETCH_CONTENT = "fetchContent"
private const val KEY_GET_SEARCH_CONTENT = "getSearchContent"

/**
 * The only writer of [WebSearchSettings.FILE_NAME].
 *
 * One writer, because the file is pi's: a second one — a preferences copy, or a
 * page that writes the document it *believes* is there — is how the two come to
 * disagree, and the one pi acts on is the file.
 *
 * Reads and writes are both on [Dispatchers.IO] and serialised through one mutex.
 * The mutex is not only for the file: [update] runs its transform under the same
 * lock, so a change made while the first read is still in flight is applied to
 * what the read published rather than to the placeholder defaults — which would
 * otherwise be written back over the user's keys.
 *
 * @param language the interface language at the moment of a write. Only the comment
 *   text depends on it, so a file keeps the language it was written in until
 *   something rewrites it — which, on a fresh install, the next launch does.
 */
class WebSearchStore(
    private val env: TermuxEnv,
    private val scope: CoroutineScope,
    private val language: () -> Lang,
) {

    private val file: File get() = File(env.piConfigDir, WebSearchSettings.FILE_NAME)

    private val _settings = MutableStateFlow(WebSearchSettings())

    val settings: StateFlow<WebSearchSettings> = _settings.asStateFlow()

    private val _unreadable = MutableStateFlow(false)

    /**
     * True when the file exists and is not a JSON object.
     *
     * Much rarer than it used to be: comments and trailing commas are read, because
     * pi reads them, so this is a file that is genuinely malformed. The page hides
     * its controls while it is true — [update] refuses to write, so each would be a
     * control that appears to work and does not — and the editor is the way out: it
     * shows the text as it is and writes back whatever the user makes of it.
     */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    /** The extension this runtime carries, or null when it carries none. */
    val extension: BundledExtension? get() = env.webAccessExtension

    private val _extra = MutableStateFlow<List<WebSearchEntry>>(emptyList())

    /**
     * Every key in the file that the page's own controls do not own.
     *
     * This is what the "add an option" section lists: the six controls above it
     * write their own keys, and everything else in the file is the user's. Read
     * from the file rather than remembered, for the same reason the whole class
     * edits pi's file: a list reconstructed from what this app *believes* it wrote
     * would hide a key the file actually has.
     */
    val extra: StateFlow<List<WebSearchEntry>> = _extra.asStateFlow()

    /** Serialises the read and every write, so two writers cannot interleave. */
    private val lock = Mutex()

    init {
        // Read from here rather than from the page, so a caller that only watches
        // [settings] still gets the file's contents and the defaults below.
        reload()
    }

    /** Re-reads the file, and seeds it when it is not there. */
    fun reload() {
        scope.launch {
            lock.withLock {
                val loaded = withContext(Dispatchers.IO) { load() }
                _settings.value = loaded.settings
                _unreadable.value = loaded.unreadable
                _extra.value = loaded.extra
                if (!loaded.unreadable) withContext(Dispatchers.IO) { ensureDocument() }
            }
        }
    }

    /**
     * Applies [transform] to the current settings and writes the result.
     *
     * The transform is not applied by the caller and handed in ready-made: it runs
     * inside the lock, in order, against the settings the latest read published.
     * A keystroke that arrived before the first read finished would otherwise
     * transform the placeholder defaults, and the write that followed would be
     * those defaults with one field changed — the user's API keys gone.
     */
    fun update(transform: (WebSearchSettings) -> WebSearchSettings) {
        scope.launch {
            lock.withLock {
                val next = transform(_settings.value)
                _settings.value = next
                withContext(Dispatchers.IO) { persist(next) }
                _extra.value = withContext(Dispatchers.IO) { extras(documentOnDisk()) }
            }
        }
    }

    /**
     * Sets one key the page's controls do not own — the "add an option" action.
     *
     * The whole document is re-rendered from what is on disk with this one path
     * written, so every other key the user has survives it. Nested paths are
     * created as needed (`fetch.timeout` makes `fetch` if there is none), and an
     * object the path walks *through* that holds something else is replaced rather
     * than merged into: `githubClone` holding `true` cannot also hold `.enabled`,
     * and quietly turning a boolean into an object would be a worse answer than
     * the write the user asked for.
     *
     * [value] is the JSON value; the page builds it, because whether the text the
     * user typed is a string or a number is a property of the option, not of the
     * file. A path one of the controls owns is refused: those six are written from
     * [WebSearchSettings] on every render, so a value set here would be overwritten
     * by the next tap anywhere on the page.
     */
    suspend fun setValue(path: String, value: JsonElement): Result<Unit> = lock.withLock {
        val segments = path.split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty() || isOwned(path)) {
            return@withLock Result.failure(
                IllegalArgumentException("$path is not a key this page can add"),
            )
        }
        val document = documentOnDisk() ?: return@withLock Result.failure(refused())
        val written = write(render(_settings.value, document.withValueAt(segments, value)))
        if (!written) {
            return@withLock Result.failure(
                IllegalStateException("${WebSearchSettings.FILE_NAME} could not be written"),
            )
        }
        _extra.value = extras(documentOnDisk())
        Result.success(Unit)
    }

    /**
     * Removes one key the page's controls do not own.
     *
     * An object left empty by the removal goes with it, all the way up: removing
     * `fetch.timeout` from a file whose only `fetch` key was that one would
     * otherwise leave `"fetch": {}` behind, which is a value the extension reads as
     * "configured" and a line the page then lists for ever.
     */
    suspend fun removeValue(path: String): Result<Unit> = lock.withLock {
        val segments = path.split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty() || isOwned(path)) {
            return@withLock Result.failure(
                IllegalArgumentException("$path is not a key this page can remove"),
            )
        }
        val document = documentOnDisk() ?: return@withLock Result.failure(refused())
        val written = write(render(_settings.value, document.withoutValueAt(segments)))
        if (!written) {
            return@withLock Result.failure(
                IllegalStateException("${WebSearchSettings.FILE_NAME} could not be written"),
            )
        }
        _extra.value = extras(documentOnDisk())
        Result.success(Unit)
    }

    /**
     * The document PiKit renders: what a fresh install's editor shows, and what the
     * page's "restore defaults" puts back.
     *
     * Not a suspend function and not locked, because it touches no file: it is a
     * pure rendering of the default settings against the schema, and the page calls
     * it from a composition-free callback.
     */
    fun defaultDocument(): String = annotated(WebSearchSettings(), JsonObject(emptyMap()))

    /**
     * The annotated document, for the document editor.
     *
     * Read from disk rather than re-encoded from [settings]: the editor exists for
     * the keys PiKit does not own, and a document reconstructed from what this app
     * understands would drop exactly those. A file that is not a JSON object is
     * shown as it is — the editor is the way out of a file nothing can read, so it
     * must not replace it with something else on the way in. An absent or blank file
     * starts the editor from the rendered default, which is the document a fresh
     * install has.
     */
    suspend fun documentText(): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val text = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
            if (text.isBlank()) return@withContext defaultDocument()
            val document = readWebSearchDocument(text) ?: return@withContext text
            annotated(readWebSearchSettings(text) ?: WebSearchSettings(), document)
        }
    }

    /**
     * Writes [text] as the whole file, when it parses to a JSON object.
     *
     * The text is the annotated document the editor shows, and what lands on disk is
     * [strictDocument] of it: the live keys as strict JSON, with no comment in it.
     * That is not a detail — the extension's `JSON.parse` is what rejected the
     * commented version of this file, and every web tool failed with it.
     *
     * Validated first, so a mistake is refused here rather than discovered by the
     * agent at its next start.
     */
    suspend fun replaceDocument(text: String): Result<Unit> = lock.withLock {
        if (readWebSearchDocument(text) == null) {
            return@withLock Result.failure(
                IllegalArgumentException("the text is not a JSON object"),
            )
        }
        val written = withContext(Dispatchers.IO) { writeFile(strictDocument(text)) }
        if (!written) {
            return@withLock Result.failure(
                IllegalStateException("${WebSearchSettings.FILE_NAME} could not be written"),
            )
        }
        _settings.value = readWebSearchSettings(text) ?: WebSearchSettings()
        _unreadable.value = false
        _extra.value = extras(readWebSearchDocument(text))
        Result.success(Unit)
    }

    /**
     * Puts the file back to what a fresh install has.
     *
     * The destructive path, and the only one: anything the user had configured —
     * every key they typed — is gone. The page asks before calling it.
     */
    suspend fun restoreDocument(): Result<Unit> = replaceDocument(defaultDocument())

    /**
     * Creates the file if it is not there, and repairs it if the extension cannot
     * read it.
     *
     * The repair is for exactly one shape of file: one that is a JSON object *once
     * comments are stripped* but not as it stands — which is what an older PiKit
     * wrote, and what the extension answers with
     * `Unexpected token '/' … is not valid JSON` for every tool. Nothing else is
     * touched: a file that is already strict JSON keeps its bytes and its
     * formatting, and one that does not parse at all is left alone (it is the
     * user's, and the editor is the way out of it).
     *
     * Public and synchronous because the agent's start calls it — the file has to be
     * on disk *before* pi starts the extension, and the extension registers its
     * tools then. Idempotent; it returns immediately unless the file is absent or
     * commented.
     */
    fun ensureDocument() {
        if (!file.isFile) {
            writeFile(strictDocument(defaultDocument()))
            return
        }
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text.isBlank()) return
        if (isStrictJsonObject(text)) return
        val document = readWebSearchDocument(text) ?: return
        writeFile(STRICT_JSON.encodeToString(JsonObject.serializer(), document) + "\n")
    }

    private class Loaded(
        val settings: WebSearchSettings,
        val unreadable: Boolean,
        val extra: List<WebSearchEntry> = emptyList(),
    )

    private fun load(): Loaded {
        // No file is not an unreadable file: the extension is running on its own
        // defaults, and `seedIfAbsent` writes the document a fresh install has.
        if (!file.isFile) return Loaded(WebSearchSettings(), unreadable = false)

        val text = runCatching { file.readText() }.getOrNull()
            ?: return Loaded(WebSearchSettings(), unreadable = true)

        // An empty file is an empty document rather than an unreadable one: there
        // is nothing in it to preserve, and refusing would make a file someone
        // created with `touch` a dead end the page cannot fix.
        if (text.isBlank()) return Loaded(WebSearchSettings(), unreadable = false)

        val parsed = readWebSearchSettings(text)
            ?: return Loaded(WebSearchSettings(), unreadable = true)
        return Loaded(parsed, unreadable = false, extra = extras(readWebSearchDocument(text)))
    }

    /** The document on disk, or null when the file is not a JSON object. */
    private fun documentOnDisk(): JsonObject? {
        if (!file.isFile) return JsonObject(emptyMap())
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return JsonObject(emptyMap())
        return readWebSearchDocument(text)
    }

    /**
     * The file's live keys that the page's controls do not own; see
     * [webSearchEntries] for the rule.
     */
    private fun extras(document: JsonObject?): List<WebSearchEntry> = webSearchEntries(document)

    /** Renders [document] with [settings]' own keys over them, and writes the file. */
    private fun render(settings: WebSearchSettings, document: JsonObject): String =
        strictDocument(annotated(settings, document))

    /** Writes a rendered document and clears the unreadable flag. Returns success. */
    private fun write(encoded: String): Boolean {
        _unreadable.value = false
        return writeFile(encoded)
    }

    /** The failure of a write over a file this app could not read; see [refuse]. */
    private fun refused(): Throwable {
        refuse()
        return IllegalStateException("${WebSearchSettings.FILE_NAME} is not a JSON object")
    }

    private fun persist(next: WebSearchSettings) {
        val existing = if (file.isFile) runCatching { file.readText() }.getOrNull() else ""
        val document = when {
            existing == null -> return refuse()
            existing.isBlank() -> JsonObject(emptyMap())
            else -> readWebSearchDocument(existing) ?: return refuse()
        }
        _unreadable.value = false

        val encoded = strictDocument(annotated(next, document))
        // Written only when the encoded document differs, so an unchanged file
        // keeps its timestamp and a watcher sees no spurious edit. A file this app
        // did not write — hand-typed, or with other formatting — is rewritten to
        // PiKit's shape by the first control tap, which is what `ensureDocument`
        // already did for a commented one at launch.
        if (existing == encoded) return
        writeFile(encoded)
    }

    /**
     * Refuses a write over a file that is not a JSON object.
     *
     * This file is the user's own copy of their keys and it may be one they wrote
     * by hand, so a file that does not parse here may still be one they meant. It is
     * left alone and the page is told: guessing at it would replace their text with
     * PiKit's.
     */
    private fun refuse() {
        _unreadable.value = true
        Log.w(TAG, "not writing ${WebSearchSettings.FILE_NAME}: it is not a JSON object")
    }

    private fun annotated(defaults: WebSearchSettings, document: JsonObject): String {
        val lang = language()
        val header = stringsFor(lang).settings.searchConfigHeader.lines()
        return renderWebSearchDocument(document, defaults, lang, header)
    }

    /**
     * Writes through a sibling temp file and a rename.
     *
     * The file holds the user's API keys, and it is read by the extension on every
     * tool call and by pi at startup: a half-written one — a process killed
     * mid-write, a full disk — would be a file nothing can read at all, which is not
     * a risk worth taking to save a rename.
     *
     * Returns whether the bytes are on disk. Only the editor has a use for the
     * answer — it is a button the user pressed and is waiting on — but the rename
     * can fail on a full disk, and a "saved" the app cannot back up would be worse
     * than no button.
     */
    private fun writeFile(encoded: String): Boolean = runCatching {
        val parent = file.parentFile
        parent?.mkdirs()
        val temporary = File(parent, "${WebSearchSettings.FILE_NAME}.tmp")
        temporary.writeText(encoded)
        // Some Android filesystems refuse to rename onto an existing file, so
        // the first attempt is followed by a delete-and-retry.
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
        true
    }.onFailure { Log.w(TAG, "could not write ${WebSearchSettings.FILE_NAME}", it) }
        .getOrDefault(false)

    private companion object {
        const val TAG = "PiKit"
    }
}

/**
 * Whether [text] is a JSON object with nothing to strip.
 *
 * The test [WebSearchStore.ensureDocument] uses to decide whether a file needs
 * repairing: `JSON.parse`'s own rule, which is the one the extension applies, not
 * the comment-tolerant one this app reads with.
 */
internal fun isStrictJsonObject(text: String): Boolean =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() != null
