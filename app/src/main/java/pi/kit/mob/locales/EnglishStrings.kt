package pi.kit.mob.locales

import pi.kit.mob.data.AvatarColor
import pi.kit.mob.data.PermissionMode
import pi.kit.mob.data.WorkspaceRefusal

internal object EnglishStrings : Strings {
    override val header = object : Strings.Header {
        override val files = "Files"
        override val terminal = "Terminal"
        override val terminalSubtitleIdle = "A shell inside the bundled environment"
        override fun terminalSubtitle(count: Int) = "$count sessions running"
    }

    override val chat = object : Strings.Chat {
        override val exitHint = "Press back again to leave PiKit"
        override val newConversation = "New conversation"
        override val loadingModel = "Loading model…"
        override val noModel = "No model configured"
        override val agentFailed = "Agent failed"
        override val agentStopped = "Agent stopped"
        override val removeAttachment = "Remove"
        override fun attachmentCount(count: Int) = plural(count, "image", "images")
        override val imageOpen = "Show picture"
        override val imageUnavailable = "Picture is no longer available"
        override val imageClose = "Close"
        override val imageShare = "Share"
        override val imageSave = "Save"
        override val imageSaved = "Saved to your photos"
        override val imageSaveFailed = "Couldn't save the picture"
        override val imageShareFailed = "No app to share it with"
        override fun picturePosition(index: Int, total: Int) = "$index / $total"
        override fun extrasCount(fileCount: Int, noteCount: Int): String {
            val parts = buildList {
                if (fileCount > 0) add(plural(fileCount, "file", "files"))
                if (noteCount > 0) add(plural(noteCount, "image note", "image notes"))
            }
            return parts.joinToString(" · ")
        }
        override val extrasTitle = "Attachment details"
        override val extrasFiles = "File paths"
        override val extrasNotes = "Image notes"
        override val extrasOpen = "Show attachment details"
        override val placeholder = "Ask Pi…"
        override val tips = listOf(
            "Tip: start a line with ! to run it as a shell command.",
            "Tip: tap + to attach an image or a file.",
            "Tip: tap ⌘ for the commands PiKit runs itself.",
            "Tip: long-press a message for copy, select text and branch.",
            "Tip: the mark at the top-left opens your conversations.",
            "Tip: swiping right does the same, and swiping left closes it.",
            "Tip: the ring at the field's corner shows window use, cost and the session file.",
        )
        override val chatTips = listOf(
            "Chat mode loads no tools or workspace files — just talk.",
            "Chat mode still carries this conversation's history.",
            "To change the persona or where the time goes, open Settings → Agent settings → Chat mode.",
        )
        override val loadingConversation = "Opening the conversation…"
        override val placeholderAttachment = "Add a message for these images…"
        override val send = "Send"
        override val stop = "Stop"
        override val sessions = "Conversations"
        override val newSession = "New conversation"
        override val compacting = "Compacting context…"
        override val showReasoning = "Show reasoning"
        override val hideReasoning = "Hide reasoning"
        override fun reasoningLabel(chars: Int) =
            "Thinking · " + if (chars < 1000) "$chars chars" else "%.1fk chars".format(chars / 1000.0)
        override val copyCode = "Copy code"
        override val copyOutput = "Copy output"
        override val copyMessage = "Copy message"
        override val selectText = "Select text"
        override val copied = "Copied"
        override val fork = "Branch from here"
        override val forkFailed = "Could not branch: this conversation is not on disk yet"
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
        override val shellCommandHint = "Run a terminal command directly, not through the model"
        override val shellCommandPlaceholder = "e.g. ls -la"
        override val commandNew = "Start a new one, interrupting this answer"
        override val commandCompact = "Summarise the context into one paragraph"
        override val commandStop = "Stop the answer and clear the queue"
        override val commandClone = "Duplicate this conversation"
        override val commandExport = "Export to HTML under home/export"
        override val commandClear = "Clear the composer draft"
        override val commandStats = "Running totals and cost for this session"
        override val commandCopy = "Copy the last answer, whole"
        override fun statsLine(
            turns: Long,
            messages: Long,
            inputTokens: Long,
            outputTokens: Long,
            cached: Long,
            cost: String,
        ) = "$turns answers · $messages messages · $inputTokens in / $outputTokens out tokens, $cached cached · $cost"
        override val answerCopied = "Copied the last answer"
        override val answerCopyEmpty = "There is no answer to copy yet"
        override val commandModel = "Open the model picker"
        override val attachTitle = "Add to the message"
        override val attachImage = "Image"
        override val attachFile = "File"
        override val attachCamera = "Take a photo"
        override val attachImageHint = "Pick an existing image"
        override val attachFileHint = "Pick any file"
        override val attachCameraHint = "Snap a photo now"
        override val addressHeader = "These files are ready; read them by path:"
        override val addressCopyFailed = "Could not copy the file; nothing was added."
        override val addressFallbackName = "unnamed file"
        override val cameraDenied =
            "PiKit does not have the camera permission, so it cannot take a photo. " +
                "You can turn it on for this app in the system settings."
        override val cameraUnavailable = "No camera app on this device can take a photo."

        override val contextEmpty = "Nothing sent yet"
        override fun cacheHit(percent: Int) = "$percent% cache hit"
        override val cacheHitLabel = "Prompt cache hit rate"

        override fun workspaceChip(name: String, isDefault: Boolean) =
            if (isDefault) "$name (default)" else name
        override val addWorkspace = "Add a workspace"
        override val workspacePickerTitle = "Choose a folder"
        override val workspacePickerBody =
            "A workspace is a folder; the one you pick here joins the list."
        override val workspacePickerChoose = "Use this folder"
        override val workspacePickerParent = "Up"
        override val workspacePickerEmpty = "No folders in this one"
        override val workspacePickerPath = "Path"
        override val workspacePickerHome = "Home"
        override val workspacePickerShared = "Phone storage"
        override val workspacePickerGrant = "Allow file access"
        override val workspacePickerUnreadable = "This folder cannot be read. Phone storage needs \"all files access\"."
        override val workspacePickerAdded = "Added"
        override val workspacePickerNewFolder = "New"
        override val workspacePickerNewFolderTitle = "New folder"
        override val workspacePickerNewFolderName = "Folder name"
        override val workspacePickerCreate = "Create"
        override val workspacePickerCreateFailed = "Could not create that folder: the name is unusable or this folder is not writable"
        override val workspaceRemove = "Remove workspace"
        override fun workspaceRefusal(reason: WorkspaceRefusal): String = when (reason) {
            WorkspaceRefusal.BLANK -> "Enter a folder path"
            WorkspaceRefusal.NOT_ABSOLUTE -> "The path has to be absolute"
            WorkspaceRefusal.MISSING -> "That folder does not exist"
            WorkspaceRefusal.NOT_A_DIRECTORY -> "That is not a folder"
            WorkspaceRefusal.DUPLICATE -> "That workspace is already in the list"
        }
        override fun permissionChip(mode: PermissionMode): String = when (mode) {
            PermissionMode.READ_ONLY -> "Read only"
            PermissionMode.WORKSPACE_WRITE -> "Workspace write"
            PermissionMode.FULL_ACCESS -> "Full access"
        }
        override fun permissionSummary(mode: PermissionMode): String = when (mode) {
            PermissionMode.READ_ONLY ->
                "Read only: files can be looked at but not changed, and a command that " +
                    "would change them does not run"
            PermissionMode.WORKSPACE_WRITE ->
                "Reads anywhere; only the workspace this conversation runs in can be changed"
            PermissionMode.FULL_ACCESS ->
                "Nothing is refused; the agent can change any file it has the right to"
        }
        override val webAccessOn = "Web search and page fetching are on — tap to turn off"
        override val webAccessOff = "Web search and page fetching are off — tap to turn on"

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
        override val running = "Answering now"
        override val unread = "Answered, not read"
        override fun poolFull(max: Int) = "$max conversations are answering right now — wait for one to finish before opening another."
        override val loading = "Loading…"
        override fun selected(count: Int) = "$count selected"
        override fun saved(count: Int) = "$count saved"
        override val searchHint = "Search titles or content"
        override val searching = "Searching conversations…"
        override fun contentMatch(snippet: String) = "Matched in the conversation: $snippet"
        override val select = "Select conversations"
        override val selectAll = "Select all"
        override val deselectAll = "Clear selection"
        override val deleteSelected = "Delete selected"
        override val cancelSelection = "Cancel selection"

        override fun groupTitle(name: String, count: Int) = "$name · $count"
        override val groupUnknown = "No workspace recorded"
        override val emptyHeading = "No saved conversations yet"
        override val empty = "A conversation is written to disk once pi replies."
        override fun nothingMatches(query: String) = "Nothing matches \u201c$query\u201d."
        override val searchEmptyNote = "Search reads every message of every saved conversation."
        override val emptyModeHeading = "No conversations in this mode yet"
        override val emptyModeBody =
            "Work and chat keep separate histories: this list shows only the current " +
                "mode's, and the other side has its own."
        override val pinned = "Pinned"
        override val actions = "Conversation actions"
        override val rename = "Rename"
        override val pin = "Pin to top"
        override val unpin = "Unpin"
        override val delete = "Delete"
        override val emptyTitle = "Empty conversation"
        override fun imageTitle(count: Int) = plural(count, "image", "images")
        override fun fileTitle(count: Int) = plural(count, "file", "files")
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
        override val modeSwitchInterruptTitle = "Switch mode and restart?"
        override val modeSwitchInterruptBody =
            "The agent is answering in this conversation. Switching mode restarts this " +
                "conversation's process, so the answer stops here and cannot continue; " +
                "the messages and history stay."
        override val modeSwitchInterruptConfirm = "Switch and restart"
        override val switchToChatMode = "Switch to chat mode"
        override val switchToWorkMode = "Switch to work mode"
        override fun modeSwitched(mode: String) = "Switched to $mode mode"
    }

    override val terminal = object : Strings.Terminal {
        override val newTerminal = "New terminal"
        override val switchTerminal = "Switch terminal"
        override fun exited(code: Int?) = "exited ($code)"
        override fun closeSession(label: String) = "Close $label"
        override val closeAllTerminals = "Close all terminals"
        override val escape = "ESC"
        override val scrollToggleHint =
            "Auto-scroll: paused, new output will not pull the view back to the bottom"
        override val scrollShort = "Scroll"

        override val keysMessage = "Send text"
        override val keysMessageLabel = "Text to type into the terminal"
        override val keysMessageSend = "Send to terminal"
        override val keysKeyboardShow = "Show the keyboard"
        override val keysKeyboardHide = "Hide the keyboard"
        override val keysPanelShow = "Show the app's own keyboard instead of the IME"
        override val keysPanelHide = "Put the app's keyboard away and go back to the IME"
    }

    override val files = object : Strings.Files {
        override val home = "Home directory"
        override val parent = "Parent directory"
        override val openWith = "Open with"
        override val notAFile = "Not a file"
        override val empty = "This folder is empty"
        override val emptyBody =
            "Empty, or a folder this app may not read."
        override val folder = "Folder"
        override val link = "Link"
        override val file = "File"
        override val filterHint = "Filter this folder"
        override val sort = "Sort"
        override val sortName = "By name"
        override val sortModified = "By modified time"
        override val sortSize = "By size"
        override val actions = "File actions"
        override fun itemCount(count: Int) = if (count == 1) "1 item" else "$count items"
        override fun items(count: Int) = if (count == 1) "1 item" else "$count items"
        override fun noMatches(query: String) = "Nothing matches “$query”"
        override val noMatchesBody = "Try another word, or clear the filter."
        override val copyPath = "Copy path"
        override val pathCopied = "Path copied"
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
        override val updateAndRepair = "Maintenance & repair"
        override val updateAndRepairSubtitle = "Model list, repair packages"
        override val userManual = "User manual"
        override val userManualSubtitle = "How this app works"
        override val about = "About PiKit"
        override val aboutSubtitle = "Version, licence and app updates"
        override val language = "Language"
        override val languageSubtitle = "The language of the interface"
        override val personalization = "Personalization"
        override val personalizationSubtitle = "Launch, text size, theme, icon and avatars"
        override val personalizationConversation = "Conversation"
        override val personalizationAppearance = "Appearance"
        override val openNewOnLaunch = "Open a new conversation on launch"
        override val openNewOnLaunchSubtitle = "New chat on open; off resumes"
        override val showHistoryTime = "Show history times"
        override val showHistoryTimeSubtitle = "The date and time at the end of each history row"
        override val showMessageTime = "Show message times"
        override val showMessageTimeSubtitle = "The date and time under each message"
        override val theme = "Theme"
        override val themeSystem = "Follow system"
        override val themeLight = "Light"
        override val themeDark = "Dark"
        override val launcherIcon = "App icon"
        override val launcherIconBlack = "White on black"
        override val launcherIconWhite = "Black on white"
        override val avatars = "Avatars"
        override val showAvatars = "Show avatars"
        override val showAvatarsSubtitle = "An avatar over each message"
        override val aiAvatar = "Agent avatar"
        override val userAvatar = "Your avatar"
        override val avatarName = "Name"
        override val avatarNameHint = "Shown beside the avatar; leave it empty to show none"
        override val avatarCropTitle = "Crop the avatar"
        override val avatarCropHint = "Drag or zoom: the circle is what will be kept"
        override val avatarCropUse = "Use this part"
        override val avatarCropZoom = "Zoom"
        override val avatarColor = "Colour"
        override fun avatarColorName(color: AvatarColor) = when (color) {
            AvatarColor.BLUE -> "Blue"
            AvatarColor.INDIGO -> "Indigo"
            AvatarColor.TEAL -> "Teal"
            AvatarColor.GREEN -> "Green"
            AvatarColor.AMBER -> "Amber"
            AvatarColor.RED -> "Red"
            AvatarColor.PINK -> "Pink"
            AvatarColor.PURPLE -> "Purple"
        }
        override val avatarPicture = "Picture"
        override val avatarPicturePick = "Choose a picture"
        override val avatarPictureChange = "Change picture"
        override val avatarPictureClear = "Remove picture"
        override val avatarPictureHint = "A picture is drawn instead of the label"
        override val avatarPictureFailed = "That picture could not be read."
        override val fontSize = "Text size"
        override val fontSizeSubtitle = "Seven steps, applied when you let go"
        override val essentials = "Essentials"
        override val advancedSection = "Advanced"
        override val running = "Running"
        override val starting = "Starting…"
        override val stopped = "Stopped"
        override fun failedWith(message: String) = "Failed — $message"
        override val notFound = "Not found in the bundled runtime"
        override val storageAskTitle = "Let the agent reach your files?"
        override val storageAskBody =
            "The agent can only see its own private directory right now, so it cannot read " +
                "your photos, documents or downloads. To let it work on those, Android has to " +
                "grant \"All files access\" — it has no dialog for that permission, so it is " +
                "given on a system page.\n\n" +
                "After that, a folder on the phone can be picked as a workspace; what the " +
                "agent may write is then decided by the tier, and you can change it at any time."
        override val storageAskOpen = "Grant access"
        override val storageAskLater = "Not now"

        override val modeWork = "Work"
        override val modeChat = "Chat"
        override val workMode = "Work mode"
        override val chatMode = "Chat mode"
        override val chatModeSubtitle =
            "Sends only the preset's system prompt; loads no tools, skills or AGENTS.md."
        override val chatSystemPromptEditorHint =
            "Chat mode's only system prompt. {{time}} becomes the current time at launch; " +
                "leave it empty to send none."
        override val chatSystemPromptRestore = "Restore default"
        override val presetDefaultRow = "Default preset"
        override val presetDefaultSubtitle = "The preset a new chat starts with"
        override val presetDefaultBadge = "Default"
        override val presetAdd = "Add preset"
        override val presetNewName = "New preset"
        override val presetEditTitle = "Edit preset"
        override val presetName = "Name"
        override val presetNameHint = "Shown beside the avatar and over its answers; leave empty to use the personalization name."
        override val presetAvatarInherit = "Use the personalization avatar"
        override val presetAvatarInheritHint = "The picture and colour come from the personalization page"
        override val presetPrompt = "System prompt"
        override val presetPromptEmpty = "Sends no system prompt"
        override val presetPromptTime = "Sends only the current time"
        override val presetDelete = "Delete preset"
        override val presetDeleteHint = "The prompt and picture are removed; conversations that used it fall back to the default"
        override val presetDeleteTitle = "Delete this preset?"
        override val presetDeleteBody =
            "Its prompt and picture are removed. Conversations that used it switch to the " +
                "default preset; their messages stay."
        override val agentContextLead = "With every request, pi sends the model:"
        override val agentContextLeadTitle = "What the model receives"
        override val agentContextSummary = "System prompt, instruction files, tools and conversation"
        override val agentContextSystemTitle = "System prompt"
        override val agentContextSystemBody =
            "pi's own role, tool rules and documentation index. PiKit leaves it alone."
        override val agentContextInstructionsTitle = "Instruction files, AGENTS.md"
        override val agentContextInstructionsBody =
            "The global file is composed from the default instructions and yours; a project's own file is read too."
        override val agentContextRuntimeTitle = "Runtime"
        override val agentContextRuntimeBody =
            "The model, thinking level, session, workspace and permission tier."
        override val agentContextToolsTitle = "Tools"
        override val agentContextToolsBody =
            "pi's read, bash, edit, write, grep, find and ls, plus web search and fetch."
        override val agentContextHistoryTitle = "Conversation"
        override val agentContextHistoryBody =
            "Every message and tool result in this session."
        override val agentContextAuthorTitle = "Default instructions"
        override val agentContextAuthorSubtitle = "Maintained by PiKit, rewritten each build"
        override val agentContextUserTitle = "User instructions"
        override val agentContextUserSubtitle = "Yours alone; PiKit never rewrites it"
        override val agentContextUserEmpty = "Nothing yet — open it and write"
        override val agentContextInstructionsEditable = "Editable"
        override val agentContextReadOnly = "Read-only"
        override val agentContextSave = "Save"
        override val agentContextSaved = "Saved"
        override val agentContextFailed = "Could not save; the file is unchanged"

        override val profilesTitle = "Model & provider"
        override val profilesEmptyHint =
            "Save a profile before Pi can answer — a key and one model is enough."
        override val savedProfiles = "Saved profiles"
        override val editProfile = "Edit profile"
        override val deleteProfile = "Delete profile"
        override val noProfileProvider = "No provider"
        override val noModelChosen = "no model chosen"
        override val noKey = "no key"
        override val keySaved = "key saved"
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
        override fun pickNothingMatches(query: String) = "Nothing matches \u201c$query\u201d."
        override val modelPickSearch = "Search models"
        override fun keyPassedAs(envVar: String) = "Env var $envVar"
        override val notChosen = "Not chosen"
        override val apiKey = "API key"
        override val modelId = "Model id"
        override val modelIdPlaceholder = "e.g. deepseek-chat"
        override val models = "Models"
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
                "below is sent to this endpoint. Which way the version segment goes " +
                "follows the API protocol, not the URL box: the OpenAI shapes gain /v1 " +
                "on a bare host, and Anthropic Messages drops a trailing /v1 because its " +
                "client adds that segment itself. The provider list's `Custom endpoint` " +
                "row is a self-configured endpoint for any compatible relay, and its API " +
                "protocol can be changed."
        override val baseUrlOptionalNote =
            "Optional. Leave empty to use the provider's own endpoint; fill it in to " +
                "send requests through a proxy or relay (for example " +
                "https://relay.example.com/v1). The version segment follows the API " +
                "protocol: a bare host gains /v1 for the OpenAI shapes, and Anthropic " +
                "Messages drops a trailing /v1. The provider list's `Custom endpoint` " +
                "row is a self-configured endpoint for any compatible relay, and its API " +
                "protocol can be changed."
        override val needBaseUrl = "A custom endpoint needs its base URL."
        override val invalidBaseUrl =
            "The endpoint must be an http(s) URL with a host name, for example " +
                "https://relay.example.com/v1. `localhost:11434` is missing its scheme."
        override val apiType = "API"
        override val apiTypeSubtitle = "The wrong choice fails to parse"
        override val modelsJsonWriteFailed =
            "Could not write pi's models.json, so this profile's endpoint and model " +
                "settings will not reach pi. Check the endpoint URL and the app's " +
                "storage, then Retry."
        override val saveFailed =
            "Save failed: the config file could not be written. This change will be " +
                "lost on the next launch."
        override val fetchModels = "Fetch models"
        override val chooseProviderFirst = "Choose a provider first."
        override fun modelCount(count: Int) = "$count models"
        override val fromProvider = "Reported by the provider, so this key can use them."
        override val fromCatalog = "From pi's catalog; not checked against your key"
        override val save = "Save"
        override val needProvider = "Choose a provider before saving."
        override val needProviderSubtitle =
            "Choose a provider first — a model id means nothing without one."
        override val needModel = "A model id is required before saving."

        override fun discoveryProblem(problem: DiscoveryProblem): String = when (problem) {
            DiscoveryProblem.NeedApiKey ->
                "Enter an API key first — a model list cannot be fetched without one."
            DiscoveryProblem.NeedBaseUrl ->
                "Enter the endpoint's base URL first — the model list is fetched from " +
                    "`<endpoint>/models`."
            DiscoveryProblem.InvalidBaseUrl ->
                "The endpoint is not a URL this app can use: it must be an http(s) URL " +
                    "with a host name, for example https://relay.example.com/v1."
            is DiscoveryProblem.NoModelsEndpoint ->
                "${problem.provider} has no models endpoint pi knows how to read, so the " +
                    "list comes from pi's own catalog — type the id by hand if it is missing."
            is DiscoveryProblem.HttpRejected -> {
                val head =
                    "${problem.host} rejected the request: HTTP ${problem.code}" +
                        problem.detail
                if (problem.viaOverride) {
                    head + "\n\nThis is the endpoint from the profile's URL field. A wrong " +
                        "URL answers 401 just like a wrong key does — check the base URL " +
                        "(it must be the API root, for example https://relay.example.com/v1) " +
                        "before replacing the key."
                } else {
                    head
                }
            }
            is DiscoveryProblem.NoModelIds ->
                "${problem.host} answered with no model ids. It may have changed its " +
                    "response format; pi's own catalog is used instead."
            is DiscoveryProblem.Unreachable ->
                "Could not reach ${problem.host}: ${problem.cause}"
            is DiscoveryProblem.PiRefused ->
                "pi refused to list models: ${problem.detail}"
            is DiscoveryProblem.CatalogEmpty ->
                "pi's catalog returned no models for ${problem.provider}. Type the model " +
                    "id directly if you know it."
            is DiscoveryProblem.CliMissing ->
                "pi's catalog is not available either: the CLI is missing from the " +
                    "bundled runtime."
            is DiscoveryProblem.PiCannotStart ->
                "pi could not start to read its catalog: ${problem.cause}"
            DiscoveryProblem.ScratchDirFailed ->
                "PiKit could not prepare the scratch agent directory pi reads its catalog " +
                    "from."
        }
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

        override val agentConfig = "Agent settings"
        override val agentRunSection = "Runtime"
        override val agentStatus = "Status"
        override val defaultWorkspace = "Default workspace"
        override fun defaultWorkspaceSubtitle(name: String) =
            "New conversations start in $name"
        override val restartAgent = "Restart"
        override val restartedAgent = "Agent restarted"
        override val keepAliveTitle = "Keep alive"
        override val keepAliveGranted = "Battery optimisation is off for this app"
        override val keepAliveDenied = "Still subject to battery optimisation"
        override val keepAliveAsk = "Open settings"
        override val keepAliveAskBody =
            "With the screen off or the app in the background, the system may reclaim the " +
                "agent and cut a running answer short. Adding PiKit to the battery-optimisation " +
                "allowlist avoids that, at the cost of staying resident and draining a little " +
                "more. The next box is the system's own; PiKit cannot answer it for you."
        override val keepAliveAskOpen = "Open settings"
        override val keepAliveAskLater = "Not now"

        override val backupTitle = "Backup & restore"
        override val backupSubtitle = "Export and restore app data"
        override val backupImportSection = "Import"
        override val backupGroupConfig = "The app and the agent"
        override val backupGroupContent = "Your content"
        override val backupExportOptions = "Export options"
        override val backupDefaultNote =
            "The default selection is what a reinstall cannot do without: the settings, the " +
                "profiles and pi's config, and the conversations with the files they named. A " +
                "workspace and the exported pages are often large, so they start unticked."
        override val backupCategorySettings = "App settings"
        override val backupCategorySettingsSubtitle =
            "Theme, language and text size, launcher icon, avatars and names, chat presets, " +
                "the default workspace, the permission tier and mode — and the two documents " +
                "the agent's instructions are made of"
        override val backupCategoryModels = "Models, keys and pi's config"
        override val backupCategoryModelsSubtitle =
            "Every saved profile with the key in it, and pi's whole config directory — the " +
                "extensions you installed are in there too"
        override val backupCategoryConversations = "Conversations"
        override val backupCategoryConversationsSubtitle =
            "Every saved conversation, which are pinned and how each was started, and the " +
                "copies of the files their prompts named"
        override val backupCategoryWorkspace = "Workspace files"
        override val backupCategoryWorkspaceSubtitle =
            "The built-in workspace and every directory you added — a \$HOME workspace packs " +
                "the whole home directory. Usually the largest part of a backup"
        override val backupCategoryExports = "Exported HTML"
        override val backupCategoryExportsSubtitle =
            "The pages /export wrote into \$HOME/export"
        override val backupApiKeys = "Include API keys"
        override val backupApiKeysSubtitle = "Off strips keys from the archive"
        override val backupExport = "Export archive"
        override fun backupExportRunning(entries: Int) = "Packing… $entries entries written"
        override fun backupExportDone(name: String, entries: Int) =
            "Wrote $name — $entries entries."
        override val backupImport = "Choose an archive…"
        override fun backupImportRunning(entries: Int) = "Reading the archive… $entries entries read"
        override fun backupImportDone(entries: Int) =
            "$entries entries restored. The agent was restarted on the restored configuration."
        override val backupNotAnArchive =
            "That file is not a PiKit backup, or it was written by a newer format."
        override fun backupFailed(message: String) = "Failed: $message"
        override val backupReviewTitle = "This archive holds"
        override fun backupReviewFrom(version: String, date: String) =
            "Written by PiKit $version on $date"
        override val backupReviewKeys = "API keys included"
        override val backupReviewNoKeys = "No API keys"
        override val backupImportConfirm = "Restore"
        override val backupImportNote =
            "A restore only overwrites files of the same name and deletes nothing: what is " +
                "in the archive replaces what the device has, and every other file stays. " +
                "The agent is stopped for the restore and started again on the restored " +
                "configuration."
        override val backupNothingSelected =
            "Tick at least one group — an archive of nothing is an empty contents list."

        override val environment = "Runtime"
        override val prefix = "Prefix"
        override val home = "Home"
        override val appFiles = "App files"
        override val bundledTools = "Bundled tools"
        override val maintenanceSubtitle = "Model list and package repair"
        override val piAgent = "Pi agent"
        override val installedVersion = "Installed version"
        override val installedVersionSubtitle = "Read from the package inside the runtime"
        override val unknown = "unknown"
        override val modelList = "Model list"
        override val modelListRefresh = "Refresh"
        override val modelListRefreshSubtitle = "Updates the model list on demand"
        override val modelListRefreshing = "Contacting the providers…"
        override val modelListStateUpdated = "Updated"
        override val modelListStateCurrent = "Latest"
        override val modelListStateFailed = "Refresh failed"
        override val installedPackages = "Installed packages"
        override val relocate = "Package relocation"
        override val relocateSubtitle = "Rewrite a package that still points at Termux"
        override val relocateNow = "Repair"
        override val relocateBroken =
            "The package relocator in this runtime has been damaged, so a repair cannot " +
                "help. Rebuild the environment below: the runtime is unpacked fresh from the app."
        override val relocateProblems = "Finished with problems:"
        override val relocateStateScanning = "Checking"
        override val relocateStateRepaired = "Repaired"
        override val relocateStateClean = "Nothing to fix"
        override val relocateStateFailed = "Repair failed"

        override val packagesRow = "Packages"
        override val packagesRowSubtitle =
            "What you installed with pkg install, kept across app updates"
        override fun packagesInstalled(count: Int) = "${plural(count, "package", "packages")} installed"
        override fun packagesRestored(count: Int) =
            "${plural(count, "package", "packages")} restored"
        override fun packagesPending(count: Int) =
            "${plural(count, "package", "packages")} waiting to be restored"
        override fun packagesUpgradable(count: Int) =
            "${plural(count, "package", "packages")} with updates"

        override val packagesUpgrade = "Update packages"
        override fun packagesUpgradeSubtitle(count: Int) =
            "${plural(count, "package", "packages")} behind the bundled environment; " +
                "updating needs a network"
        override val packagesUpgradeNow = "Update"
        override val packagesUpgradeRunning = "Updating packages"
        override val packagesUpgradeDone = "Packages updated"
        override fun packagesUpgradeLeft(count: Int) =
            "Still behind the bundled environment: ${plural(count, "package", "packages")}. " +
                "The end of the log is below."

        override val packagesRestore = "Restore packages"
        override fun packagesRestoreSubtitle(count: Int) =
            "${plural(count, "package", "packages")} from the last update still missing; " +
                "restoring needs a network"
        override val packagesRestoreNow = "Restore"
        override val packagesRestoreRunning = "Restoring packages"
        override val packagesRestoreDone = "Packages restored"
        override fun packagesRestoreLeft(count: Int) =
            "${plural(count, "package", "packages")} could not be restored. " +
                "Their names are below."

        override val packagesRunIncomplete = "The run did not finish. Its last lines are below."
        override val packagesUpgradeFailed = "Update failed"
        override val packagesRestoreFailed = "Restore failed"
        override val rebuildEnvironment = "Rebuild the environment"
        override val rebuildEnvironmentSubtitle =
            "Unpack the runtime again; your installed packages are recorded and restored after"
        override val rebuildEnvironmentNow = "Rebuild"
        override val rebuildEnvironmentConfirm =
            "This stops any running conversation and unpacks the runtime again (about " +
                "half a minute). Packages you installed with pkg install are recorded " +
                "first and put back afterwards, which needs a network for anything apt " +
                "no longer holds a cached archive for."
        override val rebuildEnvironmentConfirmNow = "Rebuild"

        override val storageCheck = "Check storage"
        override val storageCheckSubtitle = "Read, write and delete safety, measured"
        override val storageCheckRun = "Check"
        override val storageCheckRunning = "Checking…"
        override val storageCheckPassed = "Passed"
        override val storageCheckFailed = "Some checks failed"

        override val aboutTitle = "About PiKit"
        override val application = "Application"
        override val appSubtitle = "A self-contained coding agent for Android"
        override val packageName = "Package"
        override val bundledPi = "Bundled pi"
        override val bundledToolsMissing = "missing"
        override val legalSection = "Legal"
        override val licenceSubtitle = "GPLv3, and what leaves the device"
        override val creditsSubtitle = "The open-source projects it uses"

        override val termuxEnvironment = "Termux environment"
        override val checkForUpdates = "Check for updates"
        override val checkForUpdatesSubtitle = "Look for a new release"
        override val updateChecking = "Asking GitHub…"
        override val updateFailedShort = "Check failed"
        override val updateUpToDate = "Up to date"
        override val updateAvailable = "A newer release is available"
        override val updateNoReleases = "No releases yet"

        override val searchTitle = "Web access"
        override val searchSubtitle = "Search the web and read pages"
        override val searchPageSubtitle =
            "The pikit-web extension and a new chat's web default"
        override val searchNotBundled =
            "This runtime has no web extension. Update the runtime under Maintenance & repair, " +
                "then open this page again."
        override val webAccess = "Web access"
        override val webAccessSubtitle = "Let the agent search the web and read pages"
        override val chatWebAccessDefault = "New chat conversations"
        override val chatWebAccessOn = "Search on"
        override val chatWebAccessOnSubtitle =
            "A new chat starts with web search and page fetching"
        override val chatWebAccessOff = "Search off"
        override val chatWebAccessOffSubtitle = "A new chat starts with no web tools"
        override val chatWebAccessLast = "Follow the last"
        override val chatWebAccessLastSubtitle =
            "Use the last switch setting from chat mode"
        override val searchSection = "Search"
        override val searchProvider = "Search provider"
        override val searchProviderSubtitle = "The service web_search asks first"
        override val providerAutomatic = "Automatic"
        override fun providerDescription(id: String) = when (id) {
            "auto" -> "Tries Exa Free, then DuckDuckGo, then the first provider with a key"
            "deepseek" -> "DeepSeek's search API; needs a key"
            "zai" -> "Z.ai web search; needs a key, with an optional regional endpoint"
            "exa" -> "Exa's index; needs a key"
            "exa-free" -> "Exa's free endpoint; no key, rate limited"
            "duckduckgo" -> "No key needed"
            "tavily" -> "Tavily search and page extraction; needs a key"
            "ollama" -> "Ollama Cloud search and page fetch; needs a key"
            "brave" -> "Brave Search; needs a key"
            "jina" -> "Jina search and reader; works without a key at a lower rate"
            "openai" -> "OpenAI's hosted web search; needs a key"
            "gemini" -> "Gemini with Google Search grounding; needs a key"
            "kimi" -> "Kimi's search API; needs a key"
            "xai" -> "Grok's web search; needs a key"
            "searxng" -> "Your own SearXNG instance; set its address"
            "cloudflare" -> "Cloudflare Web Search; needs a key and an account id"
            "searchapi" -> "Google results through SearchAPI; needs a key"
            "direct" -> "Direct HTTP; no key, no cookies, no JavaScript"
            else -> ""
        }
        override fun searchProviderFootnote(count: Int) =
            "All $count search providers the extension supports. A keyed provider needs its key below before it can answer."
        override val searchMaxResults = "Results per search"
        override val searchMaxResultsSubtitle = "1 to 50; the default is 8"
        override val fetchSection = "Fetch"
        override val fetchProvider = "Fetch provider"
        override val fetchProviderSubtitle = "How web_fetch retrieves a page"
        override val fetchAutomatic = "Automatic (direct HTTP)"
        override val fetchDirect = "Direct HTTP"
        override val fetchMaxChars = "Maximum page characters"
        override val fetchMaxCharsSubtitle = "Longer pages are cut at this many characters"
        override val networkSection = "Network"
        override val searchTimeout = "Search timeout (seconds)"
        override val searchTimeoutSubtitle = "One search request's deadline"
        override val fetchTimeout = "Fetch timeout (seconds)"
        override val fetchTimeoutSubtitle = "One fetch request's deadline"
        override val providerNeedsKey = "This provider needs a key before it can answer."
        override val providerApiKey = "API key"
        override val providerApiKeySubtitle = "Stored in web.json; an environment variable of the same service also works"
        override val providerBaseUrl = "Endpoint"
        override val providerBaseUrlSubtitle = "Leave blank for the service's default address"
        override val providerBaseUrlRequired = "Required: this provider runs on your own instance"
        override val providerAccountId = "Account ID"
        override val providerAccountIdSubtitle = "The Cloudflare account the search is billed to"
        override val providerModel = "Model"
        override val providerModelSubtitle = "Leave blank for the extension's default model"
        override val searchConfigUnreadable =
            "web.json is not the document this app writes, so the controls are hidden. Restore defaults below replaces it; nothing has been changed yet."
        override val searchConfigRestore = "Restore defaults"
        override val searchConfigRestoreSubtitle = "Back to the install defaults"
        override val searchConfigRestoreTitle = "Replace the web configuration?"
        override val searchConfigRestoreBody =
            "This replaces web.json with the document a fresh install has. Every provider key and every option you set is removed, and that cannot be undone from here."
        override val searchConfigRestored = "Restored. The web configuration is back to the install defaults."
        override val searchConfigFailed =
            "The web configuration could not be written. Check the terminal page for the reason."

    }

    override val manual = object : Strings.Manual {
        override val title = "User manual"
        override val subtitle = "How this app works, start to finish"
        override val contents = "Contents"
    }

    override val notes = object : Strings.Notes {
        override val licenceTitle = "Licence"
        override val licence =
            "PiKit is free software, distributed under the GNU General Public License " +
                "version 3. It bundles the Termux runtime and terminal emulator, which are " +
                "GPLv3, so the whole app is GPLv3 as well. The Pi coding agent is MIT " +
                "licensed; the web extension is part of PiKit.\n" +
                "There is no warranty, and no telemetry: nothing leaves the device except " +
                "the requests you make to your model provider and the searches the agent runs."
        override val creditsTitle = "Credits"
        override val credits = listOf(
            "Runtime, package manager and terminal emulator: Termux (GPLv3), whose " +
                "terminal-emulator and terminal-view packages are this app's terminal",
            "JavaScript runtime: Node.js (MIT)",
            "Agent: @earendil-works/pi-coding-agent (MIT)",
            "Web search and page reading: pikit-web, PiKit's own extension (GPLv3)",
            "Command-line tools bundled for the agent: ripgrep (MIT/Unlicense) and " +
                "fd (MIT/Apache-2.0)",
            "Interface: Jetpack Compose and Miuix (Apache-2.0), Kotlin and " +
                "kotlinx.serialization (Apache-2.0), Okio (Apache-2.0)",
            "Formula typesetting: JLaTeXMath, as the Android port jlatexmath-android " +
                "(GPL-2.0, and GPL-2.0 with a linking exception upstream)",
            "Model mark: Lucide (ISC)",
            "Select-text mark: Material Symbols (Apache-2.0)",
        ).joinToString("\n")
    }

    override val root = object : Strings.Root {
        override val appName = "PiKit"
        override val preparing = "Preparing"
        override val settingUp = "Setting up PiKit"
        override val setupSubtitle = "Unpacking the runtime for the first launch"
        override val setupNote =
            "This happens once. Termux, Node.js and the Pi agent are unpacked from the " +
                "app itself — no downloads needed."
        override val environmentIncomplete = "The environment is incomplete"
        override val couldNotPrepare = "Could not prepare the environment"
    }

    override val shade = object : Strings.Shade {
        override val title = "PiKit"
        override fun answering(count: Int) = if (count <= 1) "1 conversation is answering" else "$count conversations are answering"
        override val stop = "Stop"
        override val channelName = "Agent"
        override val channelDescription = "Shown only while the agent is writing an answer."
    }

    override val common = object : Strings.Common {
        override val ok = "OK"
        override val cancel = "Cancel"
        override val back = "Back"
        override val confirm = "Confirm"
        override val retry = "Retry"
        override val clear = "Clear"
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
