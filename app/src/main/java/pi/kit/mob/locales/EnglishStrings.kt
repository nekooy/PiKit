package pi.kit.mob.locales

internal object EnglishStrings : Strings {
    override val tabs = object : Strings.Tabs {
        override val chat = "Chat"
        override val terminal = "Terminal"
        override val files = "Files"
        override val settings = "Settings"
    }

    override val header = object : Strings.Header {
        override val files = "Files"
        override val terminal = "Terminal"
        override val terminalSubtitleIdle = "A shell inside the bundled environment"
        override fun terminalSubtitle(count: Int) = "$count sessions running"
    }

    override val chat = object : Strings.Chat {
        override val newConversation = "New conversation"
        override val loadingModel = "Loading model…"
        override val noModel = "No model configured"
        override val agentReady = "Agent ready"
        override val agentStarting = "Starting…"
        override val agentFailed = "Agent failed"
        override val agentStopped = "Agent stopped"
        override val removeAttachment = "Remove"
        override fun attachmentCount(count: Int) = plural(count, "image", "images")
        override val placeholder = "Ask Pi…"
        override val placeholderAttachment = "Add a message for these images…"
        override val send = "Send"
        override val stop = "Stop"
        override val sessions = "Conversations"
        override val newSession = "New conversation"
        override val compact = "Compact context"
        override val compacting = "Compacting context…"
        override val showReasoning = "Show reasoning"
        override val hideReasoning = "Hide reasoning"
        override fun reasoningLabel(chars: Int) =
            "Thinking · " + if (chars < 1000) "$chars chars" else "%.1fk chars".format(chars / 1000.0)
        override val copyCode = "Copy code"
        override val copyOutput = "Copy output"
        override val copyMessage = "Copy message"
        override val expand = "Expand"
        override val collapse = "Collapse"
        override fun outputLines(count: Int) = "Output · $count lines"
        override val imageAttached = "Image attached"
        override val codeBlockFallback = "code"
        override val toolFallback = "tool"

        override fun stepsSummary(steps: Int) =
            if (steps == 1) "1 step" else "$steps steps"
        override val stepsShow = "Show"
        override val stepsHide = "Hide"
        override fun contextUsage(percent: Int) = "context $percent%"
        override val thinkingLabel = "Thinking"
        override fun duration(seconds: Long) = when {
            seconds < 60 -> "${seconds}s"
            seconds % 60 == 0L -> "${seconds / 60}m"
            else -> "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
        }

        override fun turnWorked(duration: String, steps: Int?) =
            if (steps == null) "Worked $duration" else "Worked $duration · $steps steps"

        override val imageTooLarge = "That image is too large (limit 4 MB)."
        override fun imageLimit(max: Int) = "A message can carry up to $max images."
        override val imageNotSupported =
            "pi reports this model as text-only, so the image will not be sent. Turn on " +
                "\u201cTakes image input\u201d for it in Settings \u2192 Model & provider."

        override val commands = "Commands"
        override val shellCommandHint =
            "Type a terminal command here to run it in this environment, without " +
                "going through the model; its output is left in the conversation. " +
                "Starting a message with ! is another way to write the same thing."
        override val shellCommandPlaceholder = "e.g. ls -la"
        override val commandNew = "Start a new conversation, interrupting this one"
        override val commandCompact = "Compact the context now, summarising what came before"
        override val commandStop = "Stop the answer in flight and clear the queued messages"
        override val commandClone = "Duplicate this conversation into a new one and keep this one"
        override val commandExport = "Export to an HTML file under home/export"
        override val commandClear = "Throw away the draft in the composer"
        override val commandModel = "Choose which model answers, by opening the model picker"
        override val attachTitle = "Add to the message"
        override val attachImage = "Image"
        override val attachFile = "File"
        override val attachCamera = "Take a photo"
        override val attachImageHint = "Pick an image you already have"
        override val attachFileHint = "Pick a document or an image from your files"
        override val attachCameraHint = "Take a photo now and add it to the message"
        override val cameraDenied =
            "PiKit does not have the camera permission, so it cannot take a photo. " +
                "You can turn it on for this app in the system settings."
        override val cameraUnavailable = "No camera app on this device can take a photo."
        override val thinkingLevelLabel = "Thinking level"

        // The chip is one line beside two other chips, and the level it names is
        // pi's own id (`off`, `medium`, `xhigh`, …) — the value that is passed to
        // `set_thinking_level` and printed by `pi --list-models`. The picker carries
        // the meaning, in the interface's language.
        override fun thinkingLevelDescription(id: String) = when (id) {
            "off" -> "Answer straight away, with no reasoning step"
            "minimal" -> "The shortest reasoning pi allows"
            "low" -> "Quick answers, light reasoning"
            "medium" -> "The default balance of speed and depth"
            "high" -> "Thinks longer before answering"
            "xhigh" -> "Thorough reasoning; slow and expensive"
            "max" -> "No limit on the reasoning step"
            else -> ""
        }

        override fun thinkingModelNote(levels: String) =
            "This model offers only these levels: $levels. A level it does not have is " +
                "moved up to the next one it does."

        override val thinkingDisabled = "This model does not reason; the level is off."

        override val switchModel = "Model"
        override val contextDetails = "Context"
        override val contextEmpty = "Nothing sent yet"
        override fun cacheHit(percent: Int) = "Cache hit $percent%"
        override val cacheHitLabel = "Prompt cache hit rate"

        override val detailModel = "Model"
        override val detailProvider = "Provider"
        override val detailContext = "Context window"
        override val detailUsed = "Used"
        override val detailInput = "Input"
        override val detailOutput = "Output"
        override val detailCacheRead = "Cache read"
        override val detailCacheWrite = "Cache written"
        override val detailReasoning = "Reasoning"
        override val detailCost = "Cost"
        override val detailTotal = "Session total"
        override val detailSession = "Session file"
        override val detailTurns = "Turns"

        override fun contextOfWindow(percent: Int, windowK: Int) = "$percent% of ${windowK}k"
        override fun tokens(count: Long) = when {
            count < 1_000 -> count.toString()
            count < 1_000_000 -> "%.1fk".format(count / 1_000.0)
            else -> "%.1fM".format(count / 1_000_000.0)
        }

        override fun turnCount(count: Int) = plural(count, "turn", "turns")

        override val jumpToLatest = "Jump to the latest"

        override fun messageTime(at: Long, now: Long): String {
            val when_ = java.util.Calendar.getInstance().apply { timeInMillis = at }
            val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val clock = "%02d:%02d".format(
                when_.get(java.util.Calendar.HOUR_OF_DAY),
                when_.get(java.util.Calendar.MINUTE),
            )
            val sameDay = when_.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                when_.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
            return if (sameDay) {
                clock
            } else {
                "${MONTHS[when_.get(java.util.Calendar.MONTH)]} " +
                    "${when_.get(java.util.Calendar.DAY_OF_MONTH)}, $clock"
            }
        }
    }

    override val sessions = object : Strings.Sessions {
        override val title = "Conversations"
        override val loading = "Loading…"
        override fun selected(count: Int) = "$count selected"
        override fun saved(count: Int) = "$count saved"
        override val searchHint = "Search titles or content"
        override val searching = "Searching conversations…"
        override fun contentMatch(snippet: String) = "Matched in the conversation: $snippet"
        override val select = "Select conversations"
        override val deleteSelected = "Delete selected"
        override val cancelSelection = "Cancel selection"
        override val back = "Back"
        override val empty =
            "No saved conversations yet.\nA conversation is written to disk once pi replies."
        override fun nothingMatches(query: String) = "Nothing matches \u201c$query\u201d."
        override val pinned = "Pinned"
        override val actions = "Conversation actions"
        override val rename = "Rename"
        override val pin = "Pin to top"
        override val unpin = "Unpin"
        override val delete = "Delete"
        override val untitled = "untitled"
        override val emptyTitle = "Empty conversation"
        override fun messageCount(count: Int) = plural(count, "message", "messages")
        override val renameTitle = "Rename conversation"
        override val renameLabel = "Name"
        override val renameActiveNote = "This conversation is open, so pi renames it directly."
        override val renameClosedNote =
            "pi reads a conversation's name from the last naming record in its file; " +
                "one is appended for this conversation."
        override val cancel = "Cancel"
        override val deleteTitleOne = "Delete conversation?"
        override fun deleteTitleMany(count: Int) = "Delete $count conversations?"
        override fun deleteBodyOne(title: String) =
            "\u201c$title\u201d is deleted from disk. This cannot be undone."
        override val deleteBodyMany = "These are deleted from disk. This cannot be undone:"
        override fun renameFailed(reason: String) = "Could not rename: $reason"
        override fun deleteFailed(reason: String) = "Could not delete: $reason"
        override fun partiallyDeleted(removed: Int, total: Int) =
            "Deleted $removed of $total conversations."
        override val switchWhileWorking =
            "The agent is still answering in this session, so it cannot be deleted yet: " +
                "removing the open session starts a new one, and pi does not allow that " +
                "while it is answering. Wait for it to finish, or press Stop."
        override val switchInterruptTitle = "Interrupt the answer?"
        override val switchInterruptBody =
            "The agent is still answering in this session. pi aborts the running turn " +
                "when it changes session, so this answer stops immediately and cannot be " +
                "resumed afterwards."
        override val switchInterruptConfirm = "Interrupt and switch"
    }

    override val terminal = object : Strings.Terminal {
        override val clear = "Clear the screen"
        override val newTerminal = "New terminal"
        override val closeTerminal = "Close this terminal"
        override val switchTerminal = "Switch terminal"
        override fun exited(code: Int?) = "exited ($code)"
        override val sessionsRunOn = "Sessions keep running when you leave this tab"
        override fun closeSession(label: String) = "Close $label"
        override val closeAllTerminals = "Close all terminals"
        override val ctrl = "CTRL"
        override val alt = "ALT"
        override val escape = "ESC"
        override val tab = "TAB"
        override val home = "HOME"
        override val end = "END"
        override val pageUp = "PGUP"
        override val pageDown = "PGDN"
        override val interrupt = "C-C"
        override val eof = "C-D"
        override val scrollToggleHint =
            "Auto-scroll: paused, new output will not pull the view back to the bottom"
        override val scrollShort = "Scroll"

        /**
         * The key that inserts a line break into the line being typed.
         *
         * One word, and deliberately not "Line break": the bar sizes every key to
         * its widest label and caps that width (`MAX_KEY_WIDTH` in
         * `TerminalScreen.kt`), so a two-word label was the one label in the bar
         * that the cap clipped — `Line brea`, in English, which is the report that
         * came back. "Newline" is the same fact in the width the bar has.
         */
        override val newline = "Newline"
    }

    override val files = object : Strings.Files {
        override val home = "Home directory"
        override val parent = "Parent directory"
        override val close = "Close"
        override val openWith = "Open with"
        override val notAFile = "Not a file"
        override fun tooLarge(size: String) =
            "File is $size; preview is limited to 64 KB.\nOpen it from the terminal instead."
        override fun binary(size: String) = "Binary file ($size)"
        override fun unreadable(reason: String) = "Could not read file: $reason"
        override val openFailed =
            "This file could not be handed to another app. Copy it into the agent's " +
                "workspace first, then open it from there."
    }

    override val settings = object : Strings.Settings {
        override val title = "Settings"
        override val none = "None"
        override val manageProfiles = "Model & provider"
        override val manageProfilesSubtitle = "The key and model Pi answers with"
        override val thinkingLevel = "Thinking level"
        override val workingDirectory = "Working directory"
        override val updateAndRepair = "Maintenance & repair"
        override val updateAndRepairSubtitle = "Model list, repair packages"
        override val userManual = "User manual"
        override val userManualSubtitle = "How this app works, start to finish"
        override val about = "About PiKit"
        override val aboutSubtitle = "Version, licence and app updates"
        override val language = "Language"
        override val languageSubtitle = "The language of the interface"
        override val essentials = "Essentials"
        override val advancedSection = "Advanced"
        override val bundlesNote =
            "PiKit bundles a complete Termux runtime, Node.js and the Pi agent inside " +
                "the APK: nothing has to be installed separately, and nothing is downloaded " +
                "at all until you ask a model something."
        override val running = "Running"
        override val starting = "Starting…"
        override val stopped = "Stopped"
        override fun failedWith(message: String) = "Failed — $message"
        override val notInstalled = "not installed"
        override val notFound = "Not found in the bundled runtime"
        override val storageTitle = "Shared storage"
        override val storageSubtitle = "Choose which of your folders the agent may reach"
        override val storageGrant = "Grant"
        override val storageMissing = "Not granted"
        override val storageNote =
            "Android grants access to your files only on its own \"All files access\" page, " +
                "so the button opens system settings. That permission is all-or-nothing for the " +
                "app, and the agent runs as this app — so the folders you switch on here are not " +
                "a wall Android builds for you, they are a rule PiKit holds the agent to: a call " +
                "that names any other folder is refused."
        override val storageAskTitle = "Let the agent reach your files?"
        override val storageAskBody =
            "The agent can only see its own private directory right now, so it cannot read " +
                "your photos, documents or downloads. To let it work on those, Android has to " +
                "grant \"All files access\" — it has no dialog for that permission, so it is " +
                "given on a system page.\n\n" +
                "After that, open Settings → Phone storage and switch on the folders it may " +
                "use. A folder you leave off is one PiKit refuses to let the agent name, and you " +
                "can revoke all of it at any time."
        override val storageAskOpen = "Grant access"
        override val storageAskLater = "Not now"

        override val storagePageTitle = "Shared storage"
        override val storagePageSubtitle =
            "The agent may reach the folders you switch on; PiKit refuses the rest."
        override val storageNoAccessBody =
            "The agent can read and write only inside its own home directory. Your files are " +
                "untouched, and it cannot see them at all."
        override val storageAccessLevel = "Access"
        override val storageLevelNone = "None"
        override val storageLevelSelected = "Selected folders"
        override val storageLevelAll = "All files"
        override val storageFolders = "Folders"
        override val storageFolderShared = "All of shared storage"
        override val storageFolderDownloads = "Downloads"
        override val storageFolderDocuments = "Documents"
        override val storageFolderPictures = "Pictures"
        override val storageFolderDcim = "Camera"
        override val storageFolderMusic = "Music"
        override val storageFolderMovies = "Movies"
        override val storageBroadWarning =
            "These files are usually irreplaceable. The agent will be able to delete them."
        override val storageConfirmGrantTitle = "Give the agent access?"
        override fun storageConfirmGrantBody(name: String) =
            "\u201c$name\u201d contains files that are probably not replaceable. Once the agent " +
                "can see this folder it can also delete from it. Turn it on only if you need it."
        override val storageCustomSection = "Added folders"
        override val storageCustomAdd = "Add a folder"
        override val storageCustomAddBody = "Pick any folder in shared storage."
        override val storageCustomSubsumed = "All of shared storage is already granted"
        override val storageCustomRemove = "Remove"
        override val storageCustomRemoveTitle = "Remove this folder?"
        override fun storageCustomRemoveBody(path: String) =
            "The agent will no longer be able to reach `$path`. The folder and its " +
                "files are not deleted."
        override val storageCustomPickTitle = "Choose a folder"
        override val storageCustomPickUse = "Use this folder"
        override val storageCustomPickUp = "Parent folder"
        override val storageRevokeAll = "Remove all access"
        override val storageRevokeAllTitle = "Remove all access?"
        override val storageRevokeAllBody =
            "The agent will keep working inside its home directory. Nothing on your phone is " +
                "deleted \u2014 the folders are only unlinked from the environment."
        override val storageGrantPromptTitle = "Android permission needed"
        override val storageGrantPromptBody =
            "Shared storage is not reachable yet, so no folder can be switched on. PiKit has to " +
                "ask Android for \"All files access\"; the folders you choose here still decide " +
                "what the agent can actually reach.\n\n" +
                "Be careful on this page: granting it to a file manager or a terminal app gives " +
                "that app the same reach over your files."
        override val storageGrantPromptConfirm = "Open settings"

        override val storageGuardSection = "Safety extension"
        override val storageGuardTitle = "Safety extension"
        override val storageGuardSubtitle = "Checks every tool call before it runs"
        override val storageGuardMissing = "This runtime image carries no safety extension"
        override val storageGuardNote =
            "It refuses a recursive delete outside the workspace, a filesystem command, a write " +
                "to a raw device, a delete or rewrite of pi's own credentials and settings, and " +
                "a command naming shared storage you did not grant. Switched off, none of that " +
                "is checked — the extension stays installed and loaded, it just does nothing, " +
                "and it can be switched back on at any time."
        override val storageGuardOffTitle = "Switch the safety extension off?"
        override val storageGuardOffBody =
            "With it off, the agent's tool calls are no longer checked: a recursive delete " +
                "outside the workspace, a filesystem command, a write to a raw device, and a " +
                "delete of pi's own credentials or settings all run. A folder you switched off " +
                "still does not appear in ~/storage, but a command naming its real path is no " +
                "longer refused.\n\n" +
                "Nothing is uninstalled and the switch can be turned back on at any time. The " +
                "agent restarts now so the change takes effect."
        override val storageGuardOffConfirm = "Switch off"

        override val profilesTitle = "Model & provider"
        override val profilesSubtitle = "The active profile is what the agent is launched with"
        override val savedProfiles = "Saved profiles"
        override val active = "Active"
        override val editProfile = "Edit profile"
        override val deleteProfile = "Delete profile"
        override val noProfileProvider = "No provider"
        override val noModelChosen = "no model chosen"
        override val noKey = "no key"
        override val keySaved = "key saved"
        override val tapToActivate =
            "Tapping a profile makes it active. The agent restarts so the new provider, " +
                "model and key take effect — they are passed on the command line and " +
                "through the environment when the process starts."
        override val addProfile = "Add a profile"
        override val lastProfile = "At least one profile has to remain."
        override val deleteProfileTitle = "Delete profile?"
        override fun deleteProfileBody(name: String) =
            "\u201c$name\u201d and the key saved for it are removed from the config file."
        override val newProfile = "New profile"
        override val name = "Name"
        override val nameSubtitle = "Only used to tell profiles apart"
        override val namePlaceholder = "e.g. Work DeepSeek"
        override val provider = "Provider"
        override val providerSubtitle = "Choose the provider this profile is for"
        override val providerPickSearch = "Search providers"
        override fun keyPassedAs(envVar: String) = "The key is passed to pi as $envVar"
        override val notChosen = "Not chosen"
        override val apiKey = "API key"
        override val modelId = "Model id"
        override val modelIdPlaceholder = "e.g. deepseek-chat"
        override val models = "Models"
        override val modelsSubtitle =
            "Tap a model to make it the one the agent answers with"
        override val addModel = "Add model"
        override val removeModel = "Remove model"
        override val removeModelTitle = "Remove this model?"
        override fun removeModelBody(model: String) =
            "\u201c$model\u201d leaves the list, and what you set for it \u2014 images, the " +
                "window and the max output \u2014 goes with it. The profile, its key and its " +
                "other models are untouched."

        override val baseUrl = "Endpoint"
        override val baseUrlPlaceholder = "https://relay.example.com/v1"
        override val baseUrlNote =
            "The base URL of the API, including any version segment the provider " +
                "documents — for example https://relay.example.com/v1. The model id " +
                "below is sent to this endpoint."
        override val needBaseUrl = "A custom endpoint needs its base URL."
        override val fetchModels = "Fetch models"
        override val fetching = "Fetching…"
        override val chooseProviderFirst = "Choose a provider first."
        override val fetchNote =
            "Fetching asks the provider which models this key can use, and falls back to " +
                "pi's own catalog when the provider has no model list. You can also type " +
                "an id by hand."
        override fun modelCount(count: Int) = "$count models"
        override val fromProvider = "Reported by the provider, so this key can use them."
        override val fromCatalog =
            "From pi's catalog. pi does not check them against your key, so one of these " +
                "may be rejected on the first prompt."
        override val save = "Save"
        override val cancel = "Cancel"
        override val needProvider = "Choose a provider before saving."
        override val needProviderSubtitle =
            "Choose a provider first — a model id means nothing without one."
        override val needModel = "A model id is required before saving."
        override val unsavedTitle = "Save your changes?"
        override val unsavedBody = "This profile has changes that have not been saved."
        override val discard = "Discard"
        override val keepEditing = "Keep editing"
        override val profile = "Profile"
        override fun contextWindow(k: Int) = "${k}k context"
        override val capabilityImages = "images"
        override val capabilityReasoning = "reasoning"
        override val imageInputSection = "Model parameters"
        override val imageInputRowSubtitle = "Options this model can take"
        override val imageInputCatalogRow = "In pi's catalog — the values below are its own"
        override val catalogueTitle = "pi's catalog"
        override val imageInputChecking = "Reading…"
        override val imageInputUnavailable = "Could not read it — tap to try again"
        override val imageInput = "Takes image input"
        override val contextWindowLabel = "Context window"
        override val maxTokensLabel = "Max output"
        override val modelNumbersNote =
            "Leave one empty to change nothing: the value shown above is what pi uses."
        override val imageInputNote =
            "On declares that this model accepts images; a model that does not will still " +
                "not recognise one. Off takes image input away."
        override val imageInputCustomNote =
            "pi does not know a custom endpoint and has no catalog for it, so these three " +
                "are only a starting point: no images, a 128k window and a 16k max-out. " +
                "Whatever you change is used as you typed it."
        override val terminalModelNote =
            "The same provider, model and key are written into pi's own settings.json, so a " +
                "pi you start in the Terminal tab answers with the same model."

        override val modelBehaviour = "Thinking"
        override val thinkingSubtitle =
            "How hard the model is asked to reason before it answers. Applied at once, " +
                "with no restart. The picker offers the levels the chosen model has: pi " +
                "maps a level it does not have onto the next one it does."
        override val workspace = "Workspace"
        override fun workingDirSubtitle(workspace: String) = "Left blank, the agent works in $workspace"
        override val workingDirNote =
            "The directory the agent treats as its root, and where its bash tool starts. " +
                "It is also the only directory a recursive delete is allowed in — " +
                "everything else outside it is refused by the safety guard. It is read " +
                "when the process is spawned, so restart the agent after changing it."
        override val process = "Process"
        override val agentProcess = "Agent process"
        override val restartAgent = "Restart agent"
        override val stopAgent = "Stop"
        override val failedStartNote =
            "A failed start is almost always configuration: check that the active profile " +
                "has a key and that the model id exists for that provider. The manual has " +
                "a longer checklist."

        override val environment = "Runtime"
        override val prefix = "Prefix"
        override val home = "Home"
        override val appFiles = "App files"
        override val installedImage = "Installed image"
        override val installedImageSubtitle = "Revision of the runtime unpacked from the APK"
        override val bundledTools = "Bundled tools"
        override val runtimePrefixNote =
            "Packages installed here are relocated to this prefix automatically."
        override val maintenanceSubtitle = "Model list and installed packages"
        override val piAgent = "Pi agent"
        override val installedVersion = "Installed version"
        override val installedVersionSubtitle = "Read from the package inside the runtime"
        override val unknown = "unknown"
        override val modelList = "Model list"
        override val modelListRefresh = "Refresh now"
        override val modelListRefreshSubtitle = "Updates the model list on demand"
        override val modelListRefreshing = "Contacting the providers…"
        override val modelListChanged =
            "The model list changed. The agent is restarting to read it."
        override val modelListUnchanged =
            "The model list is already up to date."
        override val modelListNote =
            "Usually nothing to do here: the list refreshes by itself every four hours, " +
                "and a model released since then is the only reason to press the button. " +
                "A refresh asks each configured provider for its catalogue, so it needs a " +
                "network connection; the rest of the app keeps working offline. Pi itself " +
                "is not updated here — it is part of the app, and arrives with a " +
                "PiKit update."
        override val dismiss = "Dismiss"
        override val installedPackages = "Installed packages"
        override val relocate = "Package relocation"
        override val relocateSubtitle = "Rewrite a package that still points at Termux"
        override val relocateNow = "Check and repair"
        override val relocateNote =
            "Only needed if something you installed already fails to run — normally " +
                "nothing has to be pressed: anything installed with pkg or apt is " +
                "rewritten automatically as it installs. For a package that arrived some " +
                "other way and refuses to start, this scan reads every file in the " +
                "runtime and rewrites the paths inside them; nothing installed outside " +
                "it is touched, and nothing is deleted."
        override fun relocateScanning(files: Int) =
            "Scanning… ${plural(files, "file", "files")} so far"
        override val relocateBroken =
            "The package relocator in this runtime has been damaged, so a repair cannot " +
                "help. Reinstall PiKit: the runtime is unpacked fresh from the app."
        override fun relocated(occurrences: Int, files: Int, symlinks: Int, modes: Int) =
            buildString {
                append("Relocated $occurrences references in $files files and $symlinks symlinks.")
                // Worth naming: an executable bit that the zip could not carry is
                // why `npm` would refuse to run.
                if (modes > 0) append(" Restored the executable bit on $modes files.")
            }
        override val nothingToRelocate =
            "Nothing to relocate: every file already matches this app's prefix."
        override val relocateProblems = "Finished with problems:"

        override val storageCheck = "Check storage"
        override val storageCheckSubtitle = "Read, write and delete safety, measured"
        override val storageCheckRun = "Run the check"
        override val storageCheckRunning = "Running…"
        override val storageCheckPassed = "All checks passed"
        override val storageCheckFailed = "Some checks failed"
        override val storageCheckNote =
            "Runs a script inside the runtime, as a child process of this app — the " +
                "same position the agent and the terminal are in — and prints one line " +
                "per check, so the answer is what the agent can really do rather than " +
                "what Android recorded. It grants nothing: it lists the folders the " +
                "agent can reach, writes and deletes one probe file in each, builds " +
                "the link-and-sentinel shape a delete guard has to survive, and asks " +
                "the package relocator to refuse a path outside the runtime. Its " +
                "scratch files are removed as it goes."
        override val storageCheckTerminalHint =
            "Only the verdict and any failing line are shown here. Run " +
                "pikit-storage-check in the Terminal tab for the whole list."

        override val aboutTitle = "About PiKit"
        override val application = "Application"
        override val appSubtitle = "A self-contained coding agent for Android"
        override val packageName = "Package"
        override val bundledPi = "Bundled pi"
        override val bundledToolsMissing = "missing"

        override val termuxEnvironment = "Termux environment"
        override val checkForUpdates = "Check for updates"
        override val checkForUpdatesSubtitle = "Looks for a newer release on GitHub"
        override val updateChecking = "Asking GitHub…"
        override val updateUpToDate = "This is the newest release"
        override val updateAvailable = "A newer release is available"
        override val updateNoReleases = "No release has been published yet"
        override val updateNote =
            "Asks github.com for this app's newest release only when you tap the row, then " +
                "opens its release page in a browser. PiKit downloads nothing by itself."

        override val searchTitle = "Search settings"
        override val searchSubtitle = "Web search for the agent"
        override val searchPageSubtitle = "The bundled web-access extension"
        override val searchFreeNote =
            "You can leave this page exactly as it is. With no key configured the " +
                "extension searches through Exa's free endpoint, which needs no account " +
                "— it may be rate-limited when it is busy, and adding a key below is " +
                "what lifts that. Nothing here has to be set up for the agent to search " +
                "the web."
        override val searchNotBundled =
            "This runtime has no web-access extension. Update the runtime under Maintenance & " +
                "repair, then open this page again."
        override val webAccess = "Web access"
        override val webAccessSubtitle = "Search the web, read pages, clone GitHub links"
        override val searchWorkflow = "Search workflow"
        override val searchWorkflowSubtitle = "What happens after a search"
        override val workflowNone = "Raw results"
        override val workflowAutoSummary = "Summarise"
        override val workflowSummaryReview = "Review in a browser"
        override fun workflowDescription(id: String) = when (id) {
            "none" -> "The model gets the results as they come"
            "auto-summary" -> "Each search is summarised before the model sees it"
            "summary-review" -> "A curator page opens so each summary can be checked"
            else -> ""
        }

        override val searchProvider = "Search provider"
        override val searchProviderSubtitle = "Which search service is asked first"
        override val providerAutomatic = "Automatic"
        override fun providerDescription(id: String) = when (id) {
            "auto" ->
                "Try Exa's keyless endpoint first, then any provider with a key configured"

            // No key.
            "exa" -> "Works without a key, with a rate limit"
            "duckduckgo" -> "No key needed"
            "searxng" -> "Your own instance; set its address in the file below"
            // The search runs through a model.
            "openai" -> "Runs through OpenAI's hosted web search"
            "gemini" -> "Runs through a Gemini model"
            "perplexity" -> "Perplexity's own search"
            "kimi" -> "Runs through a Kimi model"
            "xai" -> "Grok's web and X search"
            "mistral" -> "Mistral's web search tool"
            "ollama" -> "Ollama Cloud's search"
            // Keyed search APIs.
            "brave" -> "Needs a Brave Search key"
            "tavily" -> "Needs a Tavily key"
            "jina" -> "Needs a Jina key"
            "firecrawl" -> "Needs a Firecrawl key"
            "serper" -> "Needs a Serper key"
            "serpapi" -> "Needs a SerpApi key"
            "serpbase" -> "Needs a SerpBase key"
            // 0.30.0's provider, and the one that is never chosen automatically:
            // listing it without saying so would make a Google result look like the
            // keyless default.
            "serply" -> "Google results; needs a Serply key, and is never chosen automatically"
            "kagi" -> "Needs a Kagi key"
            "valyu" -> "Needs a Valyu key"
            "bocha" -> "Needs a Bocha key"
            "querit" -> "Needs a Querit key"
            "search1api" -> "Needs a Search1API key"
            "searchinfinity" -> "Needs a Searchinfinity key"
            "tinyfish" -> "Needs a TinyFish key"
            "parallel" -> "Needs a Parallel key"
            "parallel-mcp" -> "Parallel, over its MCP endpoint"
            "anysearch" -> "Needs an AnySearch key"
            "xcrawl" -> "Needs an XCrawl key"
            "brightdata" -> "Needs a Bright Data key and a zone name"
            "serpdive" -> "Needs a SERPdive key; a retrieval depth can be chosen"
            else -> ""
        }

        override val searchContent = "Content and limits"
        override val inlineContentLimit = "Inline content limit (characters)"
        override val fetchTimeout = "Fetch timeout (seconds)"
        override fun searchProviderFootnote(count: Int) =
            "All $count providers the extension can route to, in the order it prefers them. " +
                "A provider still needs its key — see the file below."

        override val searchConfig = "Configuration"
        override val searchConfigNote =
            "Everything here is written to the extension's own web-search.json, the file " +
                "the agent reads. The controls above write their own keys; the list below " +
                "is every other key the file holds. Adding one does not replace anything — " +
                "it layers a new key on top of what is already in effect."
        override val searchConfigAdd = "Add an option"
        override val searchConfigAddBody =
            "Pick one of the keys the extension reads and give it a value."
        override val searchConfigEmpty =
            "Nothing added yet. The controls above are the whole configuration."
        override val searchConfigPickTitle = "Options the extension reads"
        override val searchConfigPickSearch = "Filter options"
        override val searchConfigPickFootnote =
            "Every key the bundled extension reads, with what each one does. Values are " +
                "stored in the shape the option expects: text as a string, a switch as " +
                "true or false, a number as a number."
        override fun searchConfigValueTitle(path: String) = path
        override val searchConfigValueLabel = "Value"
        override val searchConfigValueNote =
            "Type the text itself, without quotes — it is stored as a JSON string. A " +
                "switch is `true` or `false`, a number is `50`, and a structured value is " +
                "JSON such as `{\"host\": \"my-box.ts.net\"}`."
        override val searchConfigValueInvalid =
            "That is not a value this option accepts. Text needs no quotes; a switch is " +
                "`true` or `false`; a number is `50`; anything else is JSON."
        override val searchConfigValueAdd = "Add"
        override val searchConfigRemoveAction = "Remove"
        override fun searchConfigRemove(path: String) = "Remove $path"
        override val searchConfigRemoved = "Removed. It applies the next time the agent starts."
        override val searchConfigRemoveTitle = "Remove this option?"
        override fun searchConfigRemoveBody(path: String) =
            "`$path` is removed from web-search.json. Every other key in the file is left " +
                "exactly as it is."
        override val searchConfigInEffect = "in effect"
        override val searchConfigHeader =
            "Web access configuration (web-search.json).\n" +
                "[in effect] marks the lines that are written to\n" +
                "the file. Lines starting with // are the\n" +
                "documentation and an example only — they\n" +
                "configure nothing; delete the // to enable one,\n" +
                "then press Save (and add a comma above it if\n" +
                "it needs one).\n" +
                "Comments are never written to the file: the\n" +
                "extension reads strict JSON. A whole\n" +
                "commented-out block needs its braces too."
        override val searchConfigInvalid =
            "This is not a JSON object — nothing was saved. Check for a missing comma or " +
                "quote; comments and trailing commas are fine."
        override val searchConfigSaved = "Saved. It applies the next time the agent starts."
        override val searchConfigFailed =
            "The file could not be written. Check the terminal tab for the reason."
        override val searchConfigRestored =
            "Restored. The file is back to what a fresh install has, so anything you had " +
                "configured is gone; it applies the next time the agent starts."
        override val searchConfigSave = "Save"
        override val searchConfigRevert = "Discard changes"
        override val searchConfigRestore = "Restore defaults"
        override val searchConfigRestoreSubtitle =
            "Puts web-search.json back to what a fresh install has; every option above is reset."
        override val searchConfigRestoreTitle = "Restore the default file?"
        override val searchConfigRestoreBody =
            "This replaces web-search.json with the document a fresh install has. Every " +
                "API key and every option you have set is removed from the file, and that " +
                "cannot be undone from here."
        override val searchConfigUnreadable =
            "web-search.json is not a JSON object, so the controls above are hidden: " +
                "they would be writing from a file this app cannot read, and the file may " +
                "well be one you wrote by hand. Comments and trailing commas are fine; a " +
                "missing brace is not. Nothing here has been changed. Fix or replace the " +
                "text below and save to make the page editable again."

        override val searchAdvanced = "Network"
        override val searchProxy = "Proxy"
        override val searchProxySubtitle = "Sent as the web-access proxy, if set"
        override val searchRestartNote = "Changes take effect the next time the agent starts."

    }

    override val manual = object : Strings.Manual {
        override val title = "User manual"
        override val subtitle = "How this app works, start to finish"
    }

    override val notes = object : Strings.Notes {
        override val licenceTitle = "Licence"
        override val licence =
            "PiKit is free software, distributed under the GNU General Public License " +
                "version 3. It bundles the Termux runtime and terminal emulator, which are " +
                "GPLv3, so the whole app is GPLv3 as well. The Pi coding agent is MIT " +
                "licensed, and so is the web-access extension.\n" +
                "There is no warranty, and no telemetry: nothing leaves the device except " +
                "the requests you make to your model provider and the searches the agent runs."
        override val creditsTitle = "Credits"
        override val credits = listOf(
            "Runtime, package manager and terminal emulator: Termux (GPLv3), whose " +
                "terminal-emulator and terminal-view packages are this app's terminal",
            "JavaScript runtime: Node.js (MIT)",
            "Agent: @earendil-works/pi-coding-agent (MIT)",
            "Web search, page reading and PDF extraction: pi-web-access by Nico Bailon " +
                "(MIT), with turndown, @mozilla/readability, defuddle, linkedom, unpdf, " +
                "undici, p-limit and typebox",
            "Command-line tools bundled for the agent: ripgrep (MIT/Unlicense) and " +
                "fd (MIT/Apache-2.0)",
            "Interface: Jetpack Compose and Material 3 (Apache-2.0), Kotlin and " +
                "kotlinx.serialization (Apache-2.0), Okio (Apache-2.0)",
            "Formula typesetting: JLaTeXMath, as the Android port jlatexmath-android " +
                "(GPL-2.0, and GPL-2.0 with a linking exception upstream)",
            "Model mark: Lucide (ISC)",
        ).joinToString("\n")
    }

    override val root = object : Strings.Root {
        override val preparing = "Preparing"
        override val settingUp = "Setting up PiKit"
        override val setupSubtitle = "Unpacking the runtime for the first launch"
        override val setupNote =
            "This happens once. Termux, Node.js and the Pi agent are unpacked from the " +
                "app itself — no downloads needed."
        override val environmentIncomplete = "The environment is incomplete"
        override val couldNotPrepare = "Could not prepare the environment"
    }

    override val common = object : Strings.Common {
        override val ok = "OK"
        override val cancel = "Cancel"
        override val back = "Back"
        override val confirm = "Confirm"
        override val retry = "Retry"
    }
}

/**
 * The month names a message's date is written with.
 *
 * English's own order — month, then day — and its own abbreviations, which is why
 * the date is formatted in the catalog and not by the caller: `9月18日` and
 * `Sep 18` are the same instant and not the same string.
 */
private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
