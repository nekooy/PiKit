package pi.kit.mob.locales

/**
 * The languages the interface can be shown in.
 *
 * [code] is what is written to preferences, and it doubles as the JVM locale tag
 * so that formatted dates and byte sizes follow the same choice as the text.
 */
enum class Lang(val code: String, val label: String) {
    ENGLISH("en", "English"),
    CHINESE("zh", "简体中文"),
    JAPANESE("ja", "日本語"),
    ;

    companion object {
        /**
         * What a fresh install shows: **Simplified Chinese**.
         *
         * Deliberately not the system language. Falling back to the device locale
         * sounds more polite and is worse in practice: an English-locale phone
         * would then get an English interface whose user manual, in-app help and
         * error text were written for a Chinese-speaking user, and the agent's own
         * replies would keep arriving in whatever language the prompt used. The
         * app has one primary audience, so it starts in their language and the
         * language row in Settings changes it in one tap.
         */
        val DEFAULT = CHINESE

        /**
         * The language for a stored code.
         *
         * An absent or unrecognised code — a fresh install, or a value written by
         * a build that knew a language this one does not — resolves to [DEFAULT]
         * rather than to English.
         */
        fun fromCode(code: String?): Lang =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/** `1 message` / `2 messages`, and the same for every other counted noun. */
internal fun plural(count: Int, one: String, many: String): String =
    if (count == 1) "1 $one" else "$count $many"

/**
 * Every string the interface shows, grouped by the surface that shows it.
 *
 * Deliberately a plain Kotlin object tree rather than `strings.xml`: the app has
 * no resource-based text today, and the three catalogs sit next to each other
 * here so a missing translation is visible in the diff rather than buried in
 * three files with different key orderings.
 *
 * Nothing from the agent (its replies, tool output, error text from pi) is
 * translated — that is model output, not interface.
 */
interface Strings {
    val root: Root
    val tabs: Tabs
    val header: Header
    val chat: Chat
    val sessions: Sessions
    val terminal: Terminal
    val files: Files
    val settings: Settings
    val manual: Manual
    val notes: Notes
    val common: Common

    /** The text shown while the bundled runtime is being unpacked. */
    interface Root {
        val preparing: String
        val settingUp: String

        /** One short line under the mark, before the unpacking starts. */
        val setupSubtitle: String
        val setupNote: String
        val environmentIncomplete: String
        val couldNotPrepare: String
    }

    interface Tabs {
        val chat: String
        val terminal: String
        val files: String
        val settings: String
    }

    interface Header {
        val files: String
        val terminal: String
        val terminalSubtitleIdle: String
        fun terminalSubtitle(count: Int): String
    }

    interface Chat {
        val newConversation: String
        val loadingModel: String
        val noModel: String
        val agentReady: String
        val agentStarting: String
        val agentFailed: String
        val agentStopped: String
        val removeAttachment: String
        fun attachmentCount(count: Int): String
        val placeholder: String
        val placeholderAttachment: String
        val send: String
        val stop: String
        val sessions: String
        val newSession: String
        val compact: String
        val compacting: String
        val showReasoning: String
        val hideReasoning: String

        /**
         * The reasoning row's whole label: `Thinking · 1.2k chars`.
         *
         * A count rather than a duration, and this is a deliberate reversal. The row
         * used to read `Show reasoning` with the count pushed to the far end, which
         * is a *control* with a fact beside it; what the reader wants from a
         * collapsed reasoning block is how much of it there is, and the reference
         * this page was rebuilt against puts that in the label itself, in the same
         * shape as the turn's own `Worked 47s · 5 steps`. The words for "show" and
         * "hide" did not go away — they are the control's `onClickLabel`, which is
         * where a screen reader expects to hear what a tap will do.
         */
        fun reasoningLabel(chars: Int): String
        val copyCode: String
        val copyOutput: String

        /** The copy button under a message, which copies the message's source text. */
        val copyMessage: String
        val expand: String
        val collapse: String
        fun outputLines(count: Int): String
        val imageAttached: String
        val codeBlockFallback: String
        val toolFallback: String

        /** The row that hides a finished turn's intermediate steps. */
        fun stepsSummary(steps: Int): String

        val stepsShow: String
        val stepsHide: String

        /**
         * How much of the model's context window the conversation occupies.
         * A percentage only: the raw token counts are shown beside it.
         */
        fun contextUsage(percent: Int): String

        /**
         * The label in front of the thinking level, in the conversation's numbers.
         *
         * The other two rows of the header table this came from — the agent's state
         * and the answering model — are gone: the state is the header's subtitle and
         * the model is a value on the composer's chip, so their labels had nothing
         * left to label and were removed with them.
         */
        val thinkingLabel: String

        /**
         * A duration in the interface's own units: `47 秒`, `47s`, `1m 04s`.
         *
         * The units belong to the language and the arithmetic does not, so the
         * caller passes whole seconds and each catalog formats them — which is why
         * the row reads `工作 47 秒` in Chinese rather than `工作 47s`.
         */
        fun duration(seconds: Long): String

        /**
         * The fold row's label: how long a finished turn ran, and what it hides.
         *
         * [steps] is null when the turn hides nothing — one that ended without a
         * reply — and the label then stops after the time rather than reporting a
         * `0` nothing asked for. A conversation restored from a session file whose
         * messages carry no timestamps has no duration either, and shows the step
         * count alone through [stepsSummary].
         */
        fun turnWorked(duration: String, steps: Int?): String

        val imageTooLarge: String

        /**
         * Shown when a pick would carry more images than one prompt may hold.
         *
         * A count cap rather than a byte cap because the two are refused for
         * different reasons: [imageTooLarge] is about the JSONL line a single
         * picture produces, this is about how much the app can hold in memory at
         * once while it encodes them — each attachment is live three times over
         * (bytes, base64, the JSON document), so the limit is what keeps a prompt
         * from being refused by the out-of-memory killer instead.
         */
        fun imageLimit(max: Int): String

        /** Shown when the attached image cannot reach a model pi calls text-only. */
        val imageNotSupported: String

        // ---- the composer's control row ----------------------------------
        //
        // Three chips sit above the field, in this order: how hard the model
        // should think, which profile answers, and how full the context window
        // is. Each is a control, not a label, so each needs a name for itself
        // (what it says when a screen reader reaches it) as well as a value.
        // The values are short because a chip is one line next to two others.

        /** The composer's `</>` button: what it opens, and the sheet's title. */
        val commands: String

        /**
         * The `!` row's own description, and the sentence the shell sheet opens with.
         *
         * Longer than the built-in commands' one-line descriptions because this is a
         * *way of working* rather than a command: it is the one thing the list
         * teaches that nothing else on the screen does, and a user who does not
         * already know the convention cannot guess it.
         */
        val shellCommandHint: String

        /** The shell sheet's field, before anything is typed. */
        val shellCommandPlaceholder: String

        // ---- the commands PiKit runs itself ---------------------------------
        //
        // One description per built-in command, and the sentences are the app
        // explaining its own controls rather than pi's: pi has never heard of
        // `/export`, and the ones it *has* heard of mean something slightly
        // different here (`/new` asks before it throws an answer away; `/model`
        // opens the picker instead of switching to a model nobody chose).
        //
        // The list these describe used to be pi's own `get_commands` registry plus
        // these, and there was a `commandDescription(name, fallback)` here to
        // translate the registry's English. It is gone with the rows: everything the
        // bundled web-access extension registers — `/websearch`, `/curator`,
        // `/google-account`, `/search`, `/llama` — needs an interactive terminal to
        // do anything, so a row for one was a control that could not act. See
        // `CommandSheet`.

        val commandNew: String
        val commandCompact: String
        val commandStop: String
        val commandClone: String
        val commandExport: String
        val commandClear: String

        /**
         * The `/model` row's description, and it is **not** [switchModel].
         *
         * It was, and the sheet read `/model` over the single word "模型" — which is
         * the *control's* name, the thing a screen reader announces for the chip above
         * the field, and a row's description is a sentence about what the row does.
         * The two are different jobs that happened to want similar words; three
         * locales sharing one string is how a list ends up with a row that says
         * nothing.
         */
        val commandModel: String

        /** The `+` sheet title. */
        val attachTitle: String
        val attachImage: String
        val attachFile: String

        /**
         * Taking a picture from the `+` sheet.
         *
         * A third entry beside the picker's two, because "attach a photo" and "take
         * a photo" read the same on a phone and only one of them was reachable: the
         * picker offers what is already in the gallery, and the camera is where a
         * photo of a whiteboard, a receipt or a screen starts.
         */
        val attachCamera: String

        /**
         * What each entry of the `+` sheet does, under its label.
         *
         * The three rows are one line each and the labels alone do not say where the
         * picture comes from: "Image" and "Take a photo" are the same destination and
         * two different beginnings, and a reader who has never used an attach menu
         * has no way to tell them apart from the nouns.
         */
        val attachImageHint: String
        val attachFileHint: String
        val attachCameraHint: String

        /**
         * Shown when the camera permission is refused, or no camera app answers.
         *
         * Two sentences because they are two different failures with one answer: the
         * reader either said no to the permission or the device has nothing that can
         * take a picture, and both leave the composer exactly as it was. Said out
         * loud rather than left as a tap that did nothing, which is this page's
         * recurring complaint.
         */
        val cameraDenied: String
        val cameraUnavailable: String

        /** The thinking-level chip's own name, e.g. "Thinking level". */
        val thinkingLevelLabel: String

        /**
         * One thinking level's *meaning*, in the interface language.
         *
         * The level's **name** is not here, and that is deliberate: a level is
         * `off`, `minimal`, `low`, `medium`, `high`, `xhigh` or `max`, exactly the
         * string [pi.kit.mob.pi.PiCommand.setThinkingLevel] puts on the wire, and it
         * is shown as it is. Translating the names was the bug — the chip, the
         * picker, the footnote and `pi --list-models` in the terminal all name the
         * same level, and a user who reads `中` on the chip and `medium` in pi's own
         * output has no way to know they are the same thing. What *is* language is
         * the sentence explaining the level, and that is this.
         */
        fun thinkingLevelDescription(id: String): String

        /**
         * The thinking picker's footnote when the model offers fewer than pi's seven.
         *
         * [levels] is already comma-separated. pi maps a request the model cannot
         * honour onto the next level it can, so the picker offers only the model's own
         * levels — and this is the sentence that says why the menu is shorter than the
         * seven pi knows. The levels themselves are named as pi names them.
         */
        fun thinkingModelNote(levels: String): String

        /**
         * The footnote for a model that does not reason at all.
         *
         * Its own sentence rather than [thinkingModelNote] with a one-item list: pi
         * answers `["off"]` for a model without reasoning support
         * (`getSupportedThinkingLevels`), and "this model offers only these levels:
         * off" describes a choice where there is none — there is nothing to pick and
         * nothing pi would do differently.
         */
        val thinkingDisabled: String

        /** The model chip's own name. */
        val switchModel: String

        /** The percent chip's own name, and the title of what it opens. */
        val contextDetails: String

        /**
         * How much of the last request's input the provider served from its prompt
         * cache — the number that says whether the conversation is being re-billed
         * in full on every turn.
         *
         * A sibling of [contextUsage] rather than part of it: the window tells you
         * when the agent will have to forget something, the cache tells you what
         * the forgetting costs.
         */
        fun cacheHit(percent: Int): String

        /** The cache chip's own name, for a screen reader. */
        val cacheHitLabel: String

        /** Shown while the conversation has not been sent anything yet. */
        val contextEmpty: String

        // ---- the conversation-details sheet ------------------------------

        val detailModel: String
        val detailProvider: String
        val detailContext: String
        val detailUsed: String
        val detailInput: String
        val detailOutput: String
        val detailCacheRead: String
        val detailCacheWrite: String
        val detailReasoning: String
        val detailCost: String
        val detailTotal: String
        val detailSession: String
        val detailTurns: String

        /** A context-window figure, e.g. `41% of 200k`. */
        fun contextOfWindow(percent: Int, windowK: Int): String

        /** `1.2k`-style token counts; the sheet is too narrow for exact ones. */
        fun tokens(count: Long): String

        /** How many turns the conversation has had. */
        fun turnCount(count: Int): String

        // ---- the transcript --------------------------------------------------

        /**
         * The floating control that appears once the reader has scrolled away
         * from a running answer's tail.
         */
        val jumpToLatest: String

        /**
         * When a message was sent, under it.
         *
         * Two spellings from one member, because the question a reader is asking is
         * "is this from today", and only the catalog knows how this language writes
         * the two answers: the time alone for today's message, the date and the time
         * for anything older. The caller passes the wall-clock millisecond the row
         * was created — `ChatItem.createdAt`, which comes from pi's own timestamp
         * where it sends one — and never formats it itself, because a date's order
         * and its separators are language and not arithmetic.
         *
         * [now] is passed in for the same reason the reducer takes it: the day
         * boundary is the one clock read this needs, and a function that reads the
         * clock is a function a test cannot pin.
         */
        fun messageTime(at: Long, now: Long): String
    }

    interface Sessions {
        val title: String
        val loading: String
        fun selected(count: Int): String
        fun saved(count: Int): String
        val searchHint: String

        /** Shown in the page header while the content scan is reading transcripts. */
        val searching: String

        /** The snippet line's spoken name; the visible text is the match itself. */
        fun contentMatch(snippet: String): String
        val select: String
        val deleteSelected: String
        val cancelSelection: String
        val back: String
        val empty: String
        fun nothingMatches(query: String): String
        val pinned: String
        val actions: String
        val rename: String
        val pin: String
        val unpin: String
        val delete: String
        val untitled: String

        /** A conversation whose transcript holds no message at all. */
        val emptyTitle: String
        fun messageCount(count: Int): String
        val renameTitle: String
        val renameLabel: String
        val renameActiveNote: String
        val renameClosedNote: String
        val cancel: String
        val deleteTitleOne: String
        fun deleteTitleMany(count: Int): String
        fun deleteBodyOne(title: String): String
        val deleteBodyMany: String
        fun renameFailed(reason: String): String
        fun deleteFailed(reason: String): String
        fun partiallyDeleted(removed: Int, total: Int): String

        /**
         * Why a delete was refused while the agent is answering.
         *
         * pi's own `switch_session` and `new_session` abort the running turn
         * (`RuntimeHost.teardownCurrent` starts with `session.abort()`), and
         * deleting the session pi is writing to has to start a new one — so the
         * delete is refused rather than half-done. Only the *delete* still refuses
         * silently: a move the user asked for asks first instead, with
         * [switchInterruptTitle] and [switchInterruptBody].
         */
        val switchWhileWorking: String

        /**
         * The confirmation a session move asks for while a turn is running.
         *
         * There is no way to leave a session without ending its answer — pi's
         * `switch_session` and `new_session` both call `session.abort()` first — so
         * this is not a "cancel and retry later" question, it is a statement of
         * what the tap will cost. The app used to refuse outright and print
         * [switchWhileWorking] as a notice, which left the user with a tap that did
         * nothing they could act on; the two honest answers are "switch anyway" and
         * "not now", and a dialog is where those belong.
         */
        val switchInterruptTitle: String
        val switchInterruptBody: String

        /** The dialog's confirm button: it says what it does, not `OK`. */
        val switchInterruptConfirm: String
    }

    interface Terminal {
        val clear: String
        val newTerminal: String
        val closeTerminal: String
        val switchTerminal: String
        fun exited(code: Int?): String
        val sessionsRunOn: String

        /** The ✕ beside a row of the session picker; `label` names that session. */
        fun closeSession(label: String): String

        /** The action row under the session picker: closes every session. */
        val closeAllTerminals: String
        val ctrl: String
        val alt: String
        val escape: String
        val tab: String
        val home: String
        val end: String
        val pageUp: String
        val pageDown: String
        val interrupt: String
        val eof: String

        /**
         * The extra-keys bar's scroll-follow toggle.
         *
         * One label and one icon for both states: the icon and the words do not
         * change when it is tapped, so the button is the same size and shape
         * whichever way it is set and cannot be mistaken for a mode indicator the
         * user has to read. The tint is what carries the state.
         */
        val scrollToggleHint: String

        /**
         * The toggle's own label, as short as the concept allows.
         *
         * It has to fit one key: the key bar sizes every key to its widest label,
         * and "auto-scroll" spelled out in two Han glyphs is three times what `↑`
         * needs. The full phrase is [scrollToggleHint], so nothing is lost to a
         * screen reader.
         */
        val scrollShort: String

        /** The key that sends a bare newline, i.e. Return. */
        val newline: String
    }

    interface Files {
        val home: String
        val parent: String
        val close: String
        val openWith: String
        val notAFile: String
        fun tooLarge(size: String): String
        fun binary(size: String): String
        fun unreadable(reason: String): String

        /**
         * The file could not be handed to another app at all.
         *
         * Shown under the preview's buttons. It used to be nothing: `openExternally`
         * swallowed the failure of `FileProvider.getUriForFile` and returned, so
         * tapping **Open with** on a file under the `storage` links did *nothing* on
         * screen — which is the report. The cause was that the provider's roots
         * covered the app's private directory only and a `~/storage` link resolves
         * to `/storage/emulated/0/…`, outside every root; the roots are fixed, and
         * this is what says so if some other path is ever exposed the same way.
         */
        val openFailed: String
    }

    interface Settings {
        val title: String
        val none: String
        val manageProfiles: String
        val manageProfilesSubtitle: String
        val thinkingLevel: String
        val workingDirectory: String
        val updateAndRepair: String
        val updateAndRepairSubtitle: String
        val userManual: String
        val userManualSubtitle: String
        val about: String
        val aboutSubtitle: String
        val language: String
        val languageSubtitle: String
        val essentials: String
        val advancedSection: String
        val bundlesNote: String
        val running: String
        val starting: String
        val stopped: String
        fun failedWith(message: String): String
        val notInstalled: String
        val notFound: String

        // Shared storage
        val storageTitle: String
        val storageSubtitle: String
        val storageGrant: String
        val storageMissing: String
        val storageNote: String

        /**
         * The one-time prompt shown after the first launch. Android has no runtime
         * dialog for "all files access", so this explains the situation and takes
         * the user to the system page.
         */
        val storageAskTitle: String
        val storageAskBody: String
        val storageAskOpen: String
        val storageAskLater: String

        /** The folder-grant page. */
        val storagePageTitle: String
        val storagePageSubtitle: String
        val storageNoAccessBody: String
        val storageAccessLevel: String
        val storageLevelNone: String
        val storageLevelSelected: String
        val storageLevelAll: String
        val storageFolders: String

        /** One folder row: its name, and whether the agent may reach it. */
        val storageFolderShared: String
        val storageFolderDownloads: String
        val storageFolderDocuments: String
        val storageFolderPictures: String
        val storageFolderDcim: String
        val storageFolderMusic: String
        val storageFolderMovies: String

        /** The warning under the folder list while the whole tree is granted. */
        val storageBroadWarning: String

        /**
         * The question asked before *any* folder is switched on.
         *
         * One pair for every folder rather than a warning attached to the ones that
         * hold irreplaceable data: a user who is prompted for one switch and not for
         * the next stops reading the prompts, and the question is about the agent's
         * reach in either case.
         */
        val storageConfirmGrantTitle: String
        fun storageConfirmGrantBody(name: String): String

        /** Folders the user added themselves, by path. */
        val storageCustomSection: String
        val storageCustomAdd: String
        val storageCustomAddBody: String

        /** Shown instead of [storageCustomAddBody] while the whole tree is granted. */
        val storageCustomSubsumed: String
        val storageCustomRemove: String

        /**
         * The question asked before one folder the user added is taken back.
         *
         * The same shape the search page's option list uses, and for the same reason:
         * the ✕ is a control with no undo, and the request for it was that it not act
         * on a single tap. Removing a folder only withdraws the link — nothing on the
         * phone is deleted — so the body says so rather than warning about data loss.
         */
        val storageCustomRemoveTitle: String
        fun storageCustomRemoveBody(path: String): String
        val storageCustomPickTitle: String
        val storageCustomPickUse: String
        val storageCustomPickUp: String
        val storageRevokeAll: String
        val storageRevokeAllTitle: String
        val storageRevokeAllBody: String
        val storageGrantPromptTitle: String
        val storageGrantPromptBody: String
        val storageGrantPromptConfirm: String

        // Model profiles
        val profilesTitle: String
        val profilesSubtitle: String
        val savedProfiles: String
        val active: String
        val editProfile: String
        val deleteProfile: String
        val noProfileProvider: String
        val noModelChosen: String
        val noKey: String
        val keySaved: String
        val tapToActivate: String
        val addProfile: String
        val lastProfile: String
        val deleteProfileTitle: String
        fun deleteProfileBody(name: String): String
        val newProfile: String
        val name: String
        val nameSubtitle: String
        val namePlaceholder: String
        val provider: String
        val providerSubtitle: String

        /**
         * The filter field's placeholder in the provider picker.
         *
         * pi ships more than thirty providers that take a single key — the regional
         * variants of one service included — so the sheet is a list to search rather than a
         * handful of alternatives to choose between.
         */
        val providerPickSearch: String
        fun keyPassedAs(envVar: String): String
        val notChosen: String
        val apiKey: String
        val modelId: String
        val modelIdPlaceholder: String

        /**
         * The group of models one profile can answer with.
         *
         * A profile is a *provider* — an endpoint and a key — and it may hold
         * several models: the one the agent is launched with, and the ones the user
         * switches to from the composer. The section around the list is labelled
         * with this, the field under it with [modelId].
         */
        val models: String

        /** The line under the [models] section label: what tapping a row does. */
        val modelsSubtitle: String
        val addModel: String

        /** The spoken name of the control that drops a model from the list. */
        val removeModel: String

        /**
         * Taking one model out of a profile's list, which is its own question.
         *
         * The page's delete dialog used [deleteProfileTitle] and [deleteProfileBody] — the
         * *profile* delete — and the two controls do different things: the model list's ✕
         * removes one row and throws away everything the user declared about that model,
         * while the profile row's bin deletes the profile, its key and all of it. So the
         * dialog said "Delete profile?" and named the profile's display name while the button
         * under the user's cursor did neither of those things. The body here names the model
         * and says what survives.
         */
        val removeModelTitle: String
        fun removeModelBody(model: String): String

        /** The endpoint a custom provider is served from. */
        val baseUrl: String
        val baseUrlPlaceholder: String
        val baseUrlNote: String
        val needBaseUrl: String

        val fetchModels: String
        val fetching: String
        val chooseProviderFirst: String
        val fetchNote: String
        fun modelCount(count: Int): String
        val fromProvider: String
        val fromCatalog: String
        val save: String
        val cancel: String
        val needProvider: String

        /**
         * The model section's status row when a model has been added with no provider
         * chosen.
         *
         * [needProvider] is the *save* refusal and reads as one ("before saving"), which is
         * the wrong sentence halfway up a form the user has not tried to save yet. This is
         * the same fact said as a state: nothing can be read about a model id until there is
         * an endpoint to read it from. It is not tappable — there is nothing to retry.
         */
        val needProviderSubtitle: String
        val needModel: String

        /**
         * The question asked when a form with unsaved edits is left.
         *
         * The model form is the one page in the app that holds edits until Save, which
         * is also why it is the one page that can lose them: the back arrow and the
         * system back gesture both leave the page, and the request was that neither do
         * it silently. [discard] is the button that throws the edits away, and it is a
         * word of its own rather than a second "cancel" — the page behind the dialog
         * already has a Cancel that means "leave without saving", and dismissing the
         * dialog (back, or a tap outside it) is the answer that stays.
         *
         * [keepEditing] is the other button, and it is what the dialog offers *instead*
         * of Save when the form cannot be stored yet. Offering Save there was a dead
         * loop: the press was refused, the reason was written at the bottom of the page
         * the dialog was still covering, and the user was back where they started with
         * no way to answer the question. The refusal is the dialog's own body in that
         * case, so the reason and the way out of it are on screen together. See
         * `ModelEditPage.refusal`.
         */
        val unsavedTitle: String
        val unsavedBody: String
        val discard: String

        /** The dialog's answer when the form is not yet savable: stay and finish it. */
        val keepEditing: String
        val profile: String
        /** One entry of a fetched model's capability line, e.g. `128k context`. */
        fun contextWindow(k: Int): String
        val capabilityImages: String
        val capabilityReasoning: String

        /**
         * The three things the user may say about any model: whether it takes images, and
         * the two numbers. Nothing about the controls differs between a model pi knows and
         * one it does not — only where a change is written, which is the writer's problem
         * (`modelDefinitions`).
         *
         * Its own section, and its own inert rows per model: a switch in the trailing
         * slot of the model list's rows — which are themselves tapped to choose the
         * answering model — fires both the row's tap and the switch's, so the same tap
         * changed the selection and toggled the capability.
         *
         * Why the two numbers are here at all: pi resolves an id its catalogue does not
         * contain from a copy of the provider's default model (`buildFallbackModel`),
         * and the only mechanism that reaches such an id is a `models` entry, which
         * *replaces* that copy. Every field the entry does not name falls back to pi's
         * hard-coded default — a 128k window and a 16k max-out — so an entry that names
         * only `input` silently downgrades the model. The entry therefore names the
         * fallback's own window and max-out, which the app read from pi, and the user
         * may correct both.
         *
         * A model pi's catalogue contains has the same three controls, and this is the line
         * that says where its values come from: the catalogue's own entry for it, which pi
         * *merges* the change into (`modelOverrides`), rather than replacing wholesale as a
         * `models` entry would. See `modelDefinitions`.
         *
         * The heading is "model parameters" and not "custom model settings": it held only
         * the models pi's catalogue did not know when it was named, and it holds every model
         * now. A heading that names half its contents is how a reader concludes the other
         * half cannot be edited.
         */
        val imageInputSection: String

        /**
         * The section's own intro row, above the models: the subject of the card.
         *
         * It used to name a mechanism ("not in pi's catalog: these three go into this
         * model's own entry"), which was a sentence about the writer's job rather than about
         * the reader's, and wrong for half the models once a catalogued one became declarable
         * too. What the card is about is that these options are available *per model* — the
         * per-model line under each id says where its values come from ([imageInputCatalogRow]
         * and the note under the switch).
         */
        val imageInputRowSubtitle: String

        /**
         * The row's line for a model pi's own catalogue already contains.
         *
         * The three values under it start at what pi catalogued for this model — its own
         * window, its own max-out, its own capability list — and changing one writes a
         * `modelOverrides` entry, which pi merges field by field, so everything the user
         * does not touch stays exactly as the catalogue has it.
         */
        val imageInputCatalogRow: String

        /** The row's line while the catalogue check is running. */
        val imageInputChecking: String

        /**
         * The status row's title while there is no answer yet.
         *
         * A row of its own rather than [imageInputSection] or [imageInput], because what it
         * reports is a fact about pi rather than a control: the three options are what the
         * answer decides, so before it arrives the honest subject is the catalogue itself.
         */
        val catalogueTitle: String

        /**
         * The row's line when the check could not answer at all — and the row is tappable
         * then, which the line has to say: without an answer the three controls cannot be
         * drawn (see [imageInputRowSubtitle]), so this state has to offer a way out rather
         * than describing a dead end.
         */
        val imageInputUnavailable: String
        val imageInput: String

        /** The label over the two numbers one model's entry names. */
        val contextWindowLabel: String
        val maxTokensLabel: String

        /**
         * The line under the two numbers: what leaving one empty means.
         *
         * An empty field is not zero and not "clear it" — the key is left out of what is
         * written, so pi keeps whatever it would have used. That value is not one thing: for
         * a model pi's catalogue contains it is the catalogue's own, and for one it does not
         * it is the fallback model's. The line therefore points at the value the row is
         * already showing rather than naming either source, which is also what the muted
         * colour on that value means (`SettingsRow.valueEmphasised`).
         */
        val modelNumbersNote: String

        /**
         * The line under one model's image switch, and the only place the switch itself is
         * explained.
         *
         * ## Why it is under the switch rather than at the top of the section
         *
         * It used to be one paragraph under the section's heading, which was both too far
         * from the control it describes — "此开关" with two rows and a divider in between —
         * and written from a premise that is no longer true: it said a model pi's catalogue
         * contains cannot be declared at all ("该选项无作用"). It can, now, for every model;
         * what differs is only where the statement is written ([imageInputRowSubtitle] says
         * that, per model).
         *
         * What is left for this line is the one thing the switch *is*: a claim about what the
         * model accepts, which the provider may still refuse. A model that really cannot take
         * images will not recognise one however this is set, and that is worth saying where
         * the switch is rather than a screen above it.
         */
        val imageInputNote: String

        /**
         * Shown instead of the per-model line for a custom endpoint — above the rows, because
         * it is about the whole provider rather than one control.
         *
         * pi has no catalogue entry for a provider the user invented, so there is nothing to
         * check the ids against and no catalogue value to start from: the three values are a
         * starting *point* (pi's own defaults for a definition that names nothing), not a
         * description of the model, and everything the user types is written into the model's
         * own entry.
         */
        val imageInputCustomNote: String

        /** Why a pi started by hand in the terminal now answers with a model. */
        val terminalModelNote: String

        // Agent page
        val modelBehaviour: String
        val thinkingSubtitle: String
        val workspace: String

        /**
         * What the working directory is when it is left blank.
         *
         * `workspace` is the absolute path of `$HOME/workspace` — where the agent is
         * meant to work, and the only directory a recursive delete may target. It is
         * deliberately not `$HOME`: the agent's home also holds pi's own
         * configuration, the saved sessions and the shared-storage links.
         */
        fun workingDirSubtitle(workspace: String): String
        val workingDirNote: String
        val process: String
        val agentProcess: String
        val restartAgent: String
        val stopAgent: String
        val failedStartNote: String

        // About PiKit: the app's own facts and, since the environment's page was
        // folded into it, what the runtime is and where it lives.
        val environment: String
        val prefix: String
        val home: String
        val appFiles: String
        val installedImage: String
        val installedImageSubtitle: String
        val bundledTools: String
        val runtimePrefixNote: String
        // Maintenance
        val maintenanceSubtitle: String
        val piAgent: String
        val installedVersion: String
        val installedVersionSubtitle: String
        val unknown: String
        val updatePi: String
        val updatePiSubtitle: String
        val checkAndUpdate: String
        val installing: String
        val dismiss: String
        fun updatedTo(version: String): String
        val unknownVersion: String
        val updateFailedNote: String
        val updateIdleNote: String
        val installedPackages: String
        val relocate: String

        /** The row's own one-line caption: what the button does, in a phrase. */
        val relocateSubtitle: String
        val relocateNow: String

        /**
         * The paragraph under the relocation button.
         *
         * Short by design: the reasoning that used to be here — package ids,
         * `DT_RUNPATH`, shebangs — is in docs/ARCHITECTURE.md, and this page only
         * has to say what the button is for and why it is usually unnecessary.
         */
        val relocateNote: String

        /**
         * Shown while the repair walk is running.
         *
         * The walk reads every file under `$PREFIX` — more than twenty thousand of
         * them once the bundled extension is in the image — so a button that only
         * greys out looks like it did nothing. `files` is how many it has read so
         * far, which is the only progress there is to report.
         */
        fun relocateScanning(files: Int): String

        /**
         * Shown when the runtime's package relocator is itself damaged.
         *
         * Only reachable on an install that pressed "check and repair" with a build
         * that lacked this class's exclusion list, which rewrote the relocator's own
         * `OLD_ID`. The instruction is to reinstall, because the only thing that
         * restores it is a fresh runtime image.
         */
        val relocateBroken: String
        fun relocated(occurrences: Int, files: Int, symlinks: Int, modes: Int): String
        val nothingToRelocate: String
        val relocateProblems: String

        // Storage self-test
        val storageCheck: String
        val storageCheckSubtitle: String
        val storageCheckRun: String
        val storageCheckRunning: String
        val storageCheckPassed: String
        val storageCheckFailed: String
        val storageCheckNote: String

        /**
         * Under a finished run: where the full output went.
         *
         * The page prints the verdict and any failing line and nothing else — the
         * script's fourteen `ok` lines are not what the user came for — so this is
         * what says the rest is still available, by the name the image puts on
         * `PATH` (see `StorageSelfTest`).
         */
        val storageCheckTerminalHint: String

        // About
        val aboutTitle: String
        val application: String
        val appSubtitle: String
        val packageName: String
        val bundledPi: String
        val bundledToolsMissing: String

        /**
         * The Termux environment's own version, beside pi's.
         *
         * It is the same value `TERMUX_VERSION` carries inside the runtime
         * (`env/BundledImage.kt`), and it is on the page because it is otherwise a
         * terminal away — "which Termux is this?" is a question a bug report asks.
         * The subtitle is the bootstrap tag it was derived from, so the two can be
         * read against each other.
         */
        val termuxEnvironment: String

        /**
         * The update check's row, and the one thing on this page that reaches the
         * network. It looks only when it is tapped: see `data/UpdateCheck.kt` for why
         * that is not a background check, and [updateNote] for what the row says
         * about it.
         */
        val checkForUpdates: String
        val checkForUpdatesSubtitle: String
        val updateChecking: String

        /**
         * The two answers, with the version itself in the row's *value* column
         * rather than repeated in the sentence: this page's other rows put versions
         * there too (the app's, the installed image's revision), and a subtitle that
         * says it again is the same fact twice on one row.
         */
        val updateUpToDate: String
        val updateAvailable: String

        /** A repository that exists and has published nothing, which is its state before the first release. */
        val updateNoReleases: String

        /**
         * Under the row: where the check goes, that it happens only when asked, and
         * that the APK comes from a browser rather than from this app.
         *
         * All three in one paragraph because they are one answer — "what happens if I
         * tap this" — and a page that states the traffic it makes is the reason the
         * rest of the app can say it makes none.
         */
        val updateNote: String

        // Search settings: the `pi-web-access` extension PiKit bundles for the
        // agent. Its options live in pi's own `$HOME/.pi/agent/web-search.json`
        // rather than in this app's preferences, so that the file pi reads and the
        // page the user edits cannot disagree — see `pi/WebSearchStore.kt`.
        val searchTitle: String
        val searchSubtitle: String
        val searchPageSubtitle: String

        /**
         * The first thing on the page, above every control.
         *
         * The extension searches through Exa's keyless endpoint when nothing is
         * configured (its `auto` routing tries Exa — `searchWithExa` over
         * `mcp.exa.ai` — before any provider that needs a key), so a user can stop
         * reading here and still have working web search. Rate limits are the one
         * caveat, and saying so up front is what keeps "add a key" from reading as
         * a requirement.
         */
        val searchFreeNote: String
        val searchNotBundled: String
        val webAccess: String
        val webAccessSubtitle: String
        val searchWorkflow: String
        val searchWorkflowSubtitle: String
        val workflowNone: String
        val workflowAutoSummary: String
        val workflowSummaryReview: String

        /** What each workflow does, as a picker entry's second line. */
        fun workflowDescription(id: String): String
        val searchProvider: String
        val searchProviderSubtitle: String

        /** The label for "let the extension choose", on both pickers. */
        val providerAutomatic: String

        /** What each search provider is, as a picker entry's second line. */
        fun providerDescription(id: String): String

        /**
         * The line under the provider picker that says the list is the extension's
         * own, and how long it is.
         *
         * It used to be eleven rows, presented without comment, which read as "these
         * are the providers" — so a user of Kagi or Serper had no way to tell that
         * their service was supported and simply not listed.
         */
        fun searchProviderFootnote(count: Int): String

        val searchContent: String
        val inlineContentLimit: String
        val fetchTimeout: String

        /**
         * The configuration section: the file *and* the way to add to it.
         *
         * The extension reads around eighty keys and the page draws controls for
         * six. This section is where the rest live. It used to be a text editor of
         * the whole document — the documentation and the configuration in one box —
         * and it was replaced by a list plus one button, because the box demanded
         * that the user write JSON to set one API key: know the key's name, know
         * whether its value wants quotes, and keep the commas straight.
         *
         * The list is the file's own keys that the controls above do not own, and
         * the button adds one from the extension's documented options.
         */
        val searchConfig: String
        val searchConfigNote: String

        /** The button that opens the option picker. */
        val searchConfigAdd: String

        /**
         * The add button's one-line caption.
         *
         * The row used to be a bare title with a chevron, which read as a menu entry
         * rather than as an action and did not match the app's one other "add a
         * thing" row — the storage page's add-a-folder, which says what the picker
         * it opens is for. This is that sentence, in this page's terms.
         */
        val searchConfigAddBody: String

        /** Shown in place of the list when the file has nothing but the controls. */
        val searchConfigEmpty: String

        /** The picker's title and its filter field's placeholder. */
        val searchConfigPickTitle: String
        val searchConfigPickSearch: String

        /** The muted line under the option list. */
        val searchConfigPickFootnote: String

        /** The sheet's heading; `path` names the key being set. */
        fun searchConfigValueTitle(path: String): String

        /** The value field's own label, next to the key the sheet is about. */
        val searchConfigValueLabel: String

        /** What the value field is for, under the field. */
        val searchConfigValueNote: String

        /** Shown when the text typed is not a value the option's type accepts. */
        val searchConfigValueInvalid: String

        /** The confirm button on the value sheet. */
        val searchConfigValueAdd: String

        /** The confirm button on the removal dialog: it says what it does. */
        val searchConfigRemoveAction: String

        /** The ✕ on a row of the list; `path` names the key it removes. */
        fun searchConfigRemove(path: String): String

        /** Confirms a removal. */
        val searchConfigRemoved: String

        /** The removal's own confirmation, asked before the key is dropped. */
        val searchConfigRemoveTitle: String
        fun searchConfigRemoveBody(path: String): String

        /**
         * The comment block at the top of the document PiKit renders.
         *
         * Lines, not a paragraph: the renderer prefixes each with `//`, so a
         * newline in this string is a new comment line and a `\n` inside a sentence
         * would end the comment early.
         *
         * It is the document's legend, and the one place the marker below is
         * explained: what a line without `//` means, what the marker means, how to
         * turn an option on, and the two traps (the comma, and a whole block whose
         * braces have to be uncommented with it).
         */
        val searchConfigHeader: String

        /**
         * The marker in front of an option that is in the file, e.g. `[in effect]`.
         *
         * Inside the rendered document, in brackets, in front of the key's path —
         * `// [in effect] fetch.timeout — …`. It is what answers "which of these
         * lines are actually configured?" without the reader having to notice the
         * `//`, and it is written only for the keys that are in the file: a tag on
         * every line would be no clearer than the `//` and would push every
         * description further right in a box about 46 columns wide.
         *
         * Keep it short — it costs its columns on each live line — and expect
         * [searchConfigHeader] to name it.
         */
        val searchConfigInEffect: String

        /** Shown under the editor while its text is not a JSON object. */
        val searchConfigInvalid: String
        val searchConfigSaved: String
        val searchConfigFailed: String
        val searchConfigRestored: String
        val searchConfigSave: String
        val searchConfigRevert: String

        /**
         * Puts the document a fresh install has back into the file.
         *
         * Destructive, so the page asks first with [searchConfigRestoreTitle] and
         * [searchConfigRestoreBody]: everything the user configured — including
         * every API key — goes with it.
         *
         * Its own section, and its own action: it is not one more key in the list
         * above it, it is the one control that touches them all. [searchConfigRestore]
         * labels that section and the row inside it, [searchConfigRestoreSubtitle] is
         * the row's caption and [searchConfigRestoreBody] the dialog's.
         */
        val searchConfigRestore: String
        val searchConfigRestoreSubtitle: String
        val searchConfigRestoreTitle: String
        val searchConfigRestoreBody: String

        /**
         * The file exists and is not a JSON object.
         *
         * Every control above this note is hidden while it is true — they would all
         * be writing from a file they could not read — so the note has to say that
         * the editor is the way out rather than leaving the page looking broken.
         */
        val searchConfigUnreadable: String

        val searchAdvanced: String
        val searchProxy: String
        val searchProxySubtitle: String
        val searchRestartNote: String

    }

    /** The in-app manual. Prose rather than strings: see [manualFor]. */
    interface Manual {
        val title: String
        val subtitle: String
    }

    /** Long prose blocks that belong to the About page. */
    interface Notes {
        val licenceTitle: String
        val licence: String
        val creditsTitle: String
        val credits: String
    }

    interface Common {
        val ok: String
        val cancel: String
        val back: String
        val confirm: String
        val retry: String
    }
}
