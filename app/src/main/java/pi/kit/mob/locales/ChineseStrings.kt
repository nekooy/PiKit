package pi.kit.mob.locales

/**
 * Simplified Chinese catalog.
 *
 * Counts are rendered with a plain number rather than a measure word: the
 * counter for a conversation, a message and an image all differ in Chinese, and
 * the noun already follows the number, so "3 个会话" reads correctly everywhere
 * this catalog uses it.
 */
internal object ChineseStrings : Strings {
    override val tabs = object : Strings.Tabs {
        override val chat = "对话"
        override val terminal = "终端"
        override val files = "文件"
        override val settings = "设置"
    }

    override val header = object : Strings.Header {
        override val files = "文件"
        override val terminal = "终端"
        override val terminalSubtitleIdle = "内置环境中的 shell"
        override fun terminalSubtitle(count: Int) = "$count 个会话运行中"
    }

    override val chat = object : Strings.Chat {
        override val newConversation = "新对话"
        override val loadingModel = "正在加载模型…"
        override val noModel = "尚未配置模型"
        override val agentReady = "Agent 已就绪"
        override val agentStarting = "正在启动…"
        override val agentFailed = "Agent 启动失败"
        override val agentStopped = "Agent 已停止"
        override val removeAttachment = "移除"
        override fun attachmentCount(count: Int) = "$count 张图片"
        override val placeholder = "问 Pi 任何问题…"
        override val placeholderAttachment = "给这些图片补充说明…"
        override val send = "发送"
        override val stop = "停止"
        override val sessions = "历史对话"
        override val newSession = "新对话"
        override val compact = "压缩上下文"
        override val compacting = "正在压缩上下文…"
        override val showReasoning = "展开思考"
        override val hideReasoning = "收起思考"
        override fun reasoningLabel(chars: Int) =
            "思考 · " + if (chars < 1000) "$chars 字" else "%.1fk 字".format(chars / 1000.0)
        override val copyCode = "复制代码"
        override val copyOutput = "复制输出"
        override val copyMessage = "复制消息"
        override val expand = "展开"
        override val collapse = "收起"
        override fun outputLines(count: Int) = "输出 · $count 行"
        override val imageAttached = "已添加图片"
        override val codeBlockFallback = "代码"
        override val toolFallback = "工具"

        override fun stepsSummary(steps: Int) = "$steps 个步骤"
        override val stepsShow = "展开"
        override val stepsHide = "收起"
        override fun contextUsage(percent: Int) = "上下文 $percent%"
        override val thinkingLabel = "思考"
        override fun duration(seconds: Long) = when {
            seconds < 60 -> "$seconds 秒"
            seconds % 60 == 0L -> "${seconds / 60} 分"
            else -> "${seconds / 60} 分 ${(seconds % 60).toString().padStart(2, '0')} 秒"
        }

        override fun turnWorked(duration: String, steps: Int?) =
            if (steps == null) "工作 $duration" else "工作 $duration · $steps 个步骤"

        override val imageTooLarge = "图片过大（上限 4 MB）。"
        override fun imageLimit(max: Int) = "一条消息最多添加 $max 张图片。"
        override val imageNotSupported =
            "pi 认为当前模型不支持图片，图片不会被发送。可在「设置 → 模型与供应商」中为它打开" +
                "「支持图片输入」。"

        override val commands = "常用指令"
        override val shellCommandHint =
            "在这里输入一条终端命令，直接在这个环境里运行，不经过模型。" +
                "输出会留在对话里。在输入框里用 ! 开头是同一件事的另一种写法。"
        override val shellCommandPlaceholder = "例如 ls -la"
        override val commandNew = "开启一个新对话，当前对话会先中断"
        override val commandCompact = "立刻压缩上下文，把前文摘要成一段"
        override val commandStop = "停止正在进行的回答，并清空排队中的消息"
        override val commandClone = "把当前对话复制成一份新的，原对话保留"
        override val commandExport = "导出为 HTML 文件，存到 home 的 export 目录"
        override val commandClear = "清空输入框里的草稿"
        override val commandModel = "选择要用哪个模型回答，打开模型选择器"
        override val attachTitle = "添加到消息"
        override val attachImage = "图片"
        override val attachFile = "文件"
        override val attachCamera = "拍照"
        override val attachImageHint = "从相册或文件里挑一张已有图片"
        override val attachFileHint = "从文件里挑一个文档或图片，作为附件"
        override val attachCameraHint = "用相机现拍一张照片，直接加到消息里"
        override val cameraDenied = "没有相机权限，无法拍照。可在系统设置里为本应用打开权限。"
        override val cameraUnavailable = "这台设备上没有可用的相机应用。"
        override val thinkingLevelLabel = "思考等级"

        override fun thinkingLevelDescription(id: String) = when (id) {
            "off" -> "直接回答，不做思考"
            "minimal" -> "Pi 允许的最短思考"
            "low" -> "回答快，思考少"
            "medium" -> "速度与深度的默认平衡"
            "high" -> "回答前思考更久"
            "xhigh" -> "思考充分，更慢也更贵"
            "max" -> "不限制思考长度"
            else -> ""
        }

        override fun thinkingModelNote(levels: String) =
            "当前模型只有这些等级：$levels。模型没有的等级，pi 会自动往上取最接近的一档。"

        override val thinkingDisabled = "当前模型不支持思考，等级固定为 off。"

        override val switchModel = "模型"
        override val contextDetails = "上下文"
        override val contextEmpty = "尚未发送内容"
        override fun cacheHit(percent: Int) = "缓存命中 $percent%"
        override val cacheHitLabel = "提示缓存命中率"

        override val detailModel = "模型"
        override val detailProvider = "供应商"
        override val detailContext = "上下文窗口"
        override val detailUsed = "已使用"
        override val detailInput = "输入"
        override val detailOutput = "输出"
        override val detailCacheRead = "缓存读取"
        override val detailCacheWrite = "缓存写入"
        override val detailReasoning = "思考"
        override val detailCost = "花费"
        override val detailTotal = "本次会话合计"
        override val detailSession = "会话文件"
        override val detailTurns = "轮次"

        override fun contextOfWindow(percent: Int, windowK: Int) = "$percent% / ${windowK}k"
        override fun tokens(count: Long) = when {
            count < 1_000 -> count.toString()
            count < 1_000_000 -> "%.1fk".format(count / 1_000.0)
            else -> "%.1fM".format(count / 1_000_000.0)
        }

        override fun turnCount(count: Int) = "$count 轮"

        override val jumpToLatest = "回到最新"

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
                "${when_.get(java.util.Calendar.MONTH) + 1}月${when_.get(java.util.Calendar.DAY_OF_MONTH)}日 $clock"
            }
        }
    }

    override val sessions = object : Strings.Sessions {
        override val title = "历史对话"
        override val loading = "加载中…"
        override fun selected(count: Int) = "已选 $count 个"
        override fun saved(count: Int) = "共 $count 个"
        override val searchHint = "搜索标题或内容"
        override val searching = "正在搜索对话…"
        override fun contentMatch(snippet: String) = "匹配到对话内容：$snippet"
        override val select = "批量选择"
        override val deleteSelected = "删除所选"
        override val cancelSelection = "取消选择"
        override val back = "返回"
        override val empty = "还没有保存的对话。\nPi 回复之后，对话才会写入磁盘。"
        override fun nothingMatches(query: String) = "没有匹配「$query」的对话。"
        override val pinned = "已置顶"
        override val actions = "对话操作"
        override val rename = "重命名"
        override val pin = "置顶"
        override val unpin = "取消置顶"
        override val delete = "删除"
        override val untitled = "未命名"
        override val emptyTitle = "空对话"
        override fun messageCount(count: Int) = "$count 条消息"
        override val renameTitle = "重命名对话"
        override val renameLabel = "名称"
        override val renameActiveNote = "这个对话正在使用中，Pi 会直接改名。"
        override val renameClosedNote =
            "Pi 从文件里最后一条命名记录读取对话名称，这里会为该对话追加一条。"
        override val cancel = "取消"
        override val deleteTitleOne = "删除这个对话？"
        override fun deleteTitleMany(count: Int) = "删除 $count 个对话？"
        override fun deleteBodyOne(title: String) = "「$title」将从磁盘删除，且无法撤销。"
        override val deleteBodyMany = "以下对话将从磁盘删除，且无法撤销："
        override fun renameFailed(reason: String) = "重命名失败：$reason"
        override fun deleteFailed(reason: String) = "删除失败：$reason"
        override fun partiallyDeleted(removed: Int, total: Int) = "已删除 $removed / $total 个对话。"
        override val switchWhileWorking =
            "Agent 正在这个对话里输出，现在还不能删除它：删掉当前对话后要立刻新建一个会话，" +
                "而 pi 在回答期间不允许切换。请等它结束，或先按「停止」。"
        override val switchInterruptTitle = "中断当前回答？"
        override val switchInterruptBody =
            "Agent 正在这个对话里输出。pi 在切换会话时会中止正在进行的回合，" +
                "这次回答会立刻停止，停下来之后无法继续。"
        override val switchInterruptConfirm = "中断并切换"
    }

    override val terminal = object : Strings.Terminal {
        override val clear = "清屏"
        override val newTerminal = "新建终端"
        override val closeTerminal = "关闭当前终端"
        override val switchTerminal = "切换终端"
        override fun exited(code: Int?) = "已退出（$code）"
        override val sessionsRunOn = "离开此页面后会话仍在后台运行"
        override fun closeSession(label: String) = "关闭 $label"
        override val closeAllTerminals = "关闭全部终端"
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
        override val scrollToggleHint = "自动滚动：暂停后新输出不会把画面拉回底部，方便往上翻看"
        override val scrollShort = "滚动"
        override val newline = "换行"
    }

    override val files = object : Strings.Files {
        override val home = "主目录"
        override val parent = "上一级目录"
        override val close = "关闭"
        override val openWith = "用其它应用打开"
        override val notAFile = "不是普通文件"
        override fun tooLarge(size: String) =
            "文件大小为 $size，预览上限 64 KB。\n请在终端中打开它。"
        override fun binary(size: String) = "二进制文件（$size）"
        override fun unreadable(reason: String) = "无法读取文件：$reason"
        override val openFailed =
            "无法把这个文件交给其他应用打开。请先把它复制到 Agent 的工作区，再从那里打开。"
    }

    override val settings = object : Strings.Settings {
        override val title = "设置"
        override val none = "无"
        override val manageProfiles = "模型与供应商"
        override val manageProfilesSubtitle = "Pi 使用的 Key 与模型"
        override val thinkingLevel = "思考等级"
        override val workingDirectory = "工作目录"
        override val updateAndRepair = "维护与修复"
        override val updateAndRepairSubtitle = "更新 Pi、修复软件包"
        override val userManual = "使用手册"
        override val userManualSubtitle = "从零开始了解这个应用"
        override val about = "关于 PiKit"
        override val aboutSubtitle = "版本、许可、检查应用更新"
        override val language = "语言"
        override val languageSubtitle = "界面显示语言"
        override val essentials = "常用设置"
        override val advancedSection = "高级"
        override val bundlesNote =
            "PiKit 把完整的 Termux 运行环境、Node.js 和 Pi Agent 都打包在 APK 里：" +
                "无需额外安装任何组件，在向模型提问之前也不会产生任何下载。"
        override val running = "运行中"
        override val starting = "启动中…"
        override val stopped = "已停止"
        override fun failedWith(message: String) = "失败 — $message"
        override val notInstalled = "未安装"
        override val notFound = "在内置运行环境中未找到"
        override val storageTitle = "手机存储"
        override val storageSubtitle = "选择允许 Agent 访问哪些文件夹"
        override val storageGrant = "去授权"
        override val storageMissing = "未授权"
        override val storageNote =
            "Android 的这项权限只能在系统的「所有文件访问权限」页面授予，所以这个按钮会打开系统设置。" +
                "权限本身就是整台设备一起给的：Agent 与 PiKit 用同一个应用身份运行，所以这里打开的" +
                "文件夹不是 Android 筑起的墙，而是 PiKit 替你执行的规则——没有打开的文件夹，" +
                "Agent 的访问命令会被 PiKit 拒绝。"
        override val storageAskTitle = "让 Agent 访问你的文件？"
        override val storageAskBody =
            "Agent 现在只能看到自己内部的目录，读不到你的照片、文档或下载。" +
                "如果你希望它能处理这些文件，需要先在系统的「所有文件访问权限」页面授予权限" +
                "——Android 没有为这项权限提供弹窗，只能这样授予。\n\n" +
                "授予之后，还要回到「设置 → 手机存储」逐个打开允许访问的文件夹；" +
                "没有打开的文件夹，Agent 的访问命令会被 PiKit 拒绝，也可以随时撤销。"
        override val storageAskOpen = "去授权"
        override val storageAskLater = "稍后再说"

        override val storagePageTitle = "手机存储"
        override val storagePageSubtitle = "Agent 只能访问你打开的文件夹，其它访问会被 PiKit 拒绝。"
        override val storageNoAccessBody =
            "Agent 只能在它自己的主目录里读写。你的文件不会被碰，它也完全看不到。"
        override val storageAccessLevel = "访问范围"
        override val storageLevelNone = "无"
        override val storageLevelSelected = "指定文件夹"
        override val storageLevelAll = "全部文件"
        override val storageFolders = "文件夹"
        override val storageFolderShared = "整个共享存储"
        override val storageFolderDownloads = "下载"
        override val storageFolderDocuments = "文档"
        override val storageFolderPictures = "图片"
        override val storageFolderDcim = "相机"
        override val storageFolderMusic = "音乐"
        override val storageFolderMovies = "视频"
        override val storageBroadWarning = "这些文件通常无法找回，Agent 将可以删除它们。"
        override val storageConfirmGrantTitle = "确定开放给 Agent？"
        override fun storageConfirmGrantBody(name: String) =
            "「$name」里的文件很可能无法恢复。开放之后 Agent 不但能读取，也能删除。" +
                "只有确实需要时才打开。"
        override val storageCustomSection = "自定义文件夹"
        override val storageCustomAdd = "添加文件夹"
        override val storageCustomAddBody = "选择共享存储里的任意文件夹。"
        override val storageCustomSubsumed = "已经开放了全部共享存储"
        override val storageCustomRemove = "移除"
        override val storageCustomRemoveTitle = "移除这个文件夹？"
        override fun storageCustomRemoveBody(path: String) =
            "Agent 将不能再访问 `$path`。文件夹和里面的文件不会被删除。"
        override val storageCustomPickTitle = "选择文件夹"
        override val storageCustomPickUse = "使用这个文件夹"
        override val storageCustomPickUp = "上一级目录"
        override val storageRevokeAll = "收回全部权限"
        override val storageRevokeAllTitle = "收回全部权限？"
        override val storageRevokeAllBody =
            "Agent 会继续在自己的主目录里工作。手机上的文件不会被删除——只是取消了环境里的链接。"
        override val storageGrantPromptTitle = "需要 Android 权限"
        override val storageGrantPromptBody =
            "目前还访问不到共享存储，所以任何文件夹都打不开。PiKit 需要先向 Android 申请" +
            "「所有文件访问权限」；真正决定 Agent 能看到什么的，仍然是你在本页选择的文件夹。\n\n" +
            "在这个页面上要小心：把权限授予文件管理器或终端类应用，等于让那个应用也能动你的文件。"
        override val storageGrantPromptConfirm = "打开设置"

        override val profilesTitle = "模型与供应商"
        override val profilesSubtitle = "当前配置就是 Agent 启动时使用的配置"
        override val savedProfiles = "已保存的配置"
        override val active = "当前使用"
        override val editProfile = "编辑配置"
        override val deleteProfile = "删除配置"
        override val noProfileProvider = "未选择供应商"
        override val noModelChosen = "未选择模型"
        override val noKey = "无 Key"
        override val keySaved = "已保存 Key"
        override val tapToActivate =
            "点击配置即可切换为当前使用。Agent 会随之重启，让新的供应商、模型和 Key 生效" +
                "——它们在进程启动时通过命令行参数和环境变量传入。"
        override val addProfile = "新增配置"
        override val lastProfile = "至少需要保留一个配置。"
        override val deleteProfileTitle = "删除配置？"
        override fun deleteProfileBody(name: String) = "「$name」及其保存的 Key 会从配置文件中移除。"
        override val newProfile = "新建配置"
        override val name = "名称"
        override val nameSubtitle = "仅用于区分不同配置"
        override val namePlaceholder = "例如：工作用 DeepSeek"
        override val provider = "供应商"
        override val providerSubtitle = "选择该配置对应的供应商"
        override val providerPickSearch = "搜索供应商"
        override fun keyPassedAs(envVar: String) = "Key 会通过 $envVar 传给 pi"
        override val notChosen = "未选择"
        override val apiKey = "API Key"
        override val modelId = "模型 ID"
        override val modelIdPlaceholder = "例如：deepseek-chat"
        override val models = "模型"
        override val modelsSubtitle = "点击某个模型，Agent 之后就用它回答"
        override val addModel = "添加模型"
        override val removeModel = "移除模型"
        override val removeModelTitle = "移除这个模型？"
        override fun removeModelBody(model: String) =
            "「$model」会从列表里移除，你为它填的图片、上下文窗口和最大输出也一起丢掉。" +
                "配置本身、Key 以及列表里的其它模型都不受影响。"

        override val baseUrl = "接口地址"
        override val baseUrlPlaceholder = "https://relay.example.com/v1"
        override val baseUrlNote =
            "API 的基础地址，要包含服务商文档里写的版本段，例如 https://relay.example.com/v1。" +
                "下面的模型 ID 会发送到这个地址。"
        override val needBaseUrl = "自定义接口需要填写基础地址。"
        override val fetchModels = "获取模型列表"
        override val fetching = "获取中…"
        override val chooseProviderFirst = "请先选择供应商。"
        override val fetchNote =
            "获取列表会向供应商询问这个 Key 可用的模型；如果供应商没有提供列表，则回退到 " +
                "pi 自带的模型目录。你也可以手动输入模型 ID。"
        override fun modelCount(count: Int) = "$count 个模型"
        override val fromProvider = "由供应商直接返回，这个 Key 可以使用它们。"
        override val fromCatalog =
            "来自 pi 的模型目录。pi 不会用你的 Key 校验它们，因此其中某个可能在第一次对话时被拒绝。"
        override val save = "保存"
        override val cancel = "取消"
        override val needProvider = "保存前请先选择供应商。"
        override val needProviderSubtitle = "请先选择供应商——没有供应商，模型 id 无从判断"
        override val needModel = "保存前必须填写模型 ID。"
        override val unsavedTitle = "保存这次修改？"
        override val unsavedBody = "这个配置的修改还没有保存。"
        override val discard = "放弃修改"
        override val keepEditing = "继续填写"
        override val profile = "配置"
        override fun contextWindow(k: Int) = "${k}k 上下文"
        override val capabilityImages = "支持图片"
        override val capabilityReasoning = "支持推理"
        override val imageInputSection = "模型参数"
        override val imageInputRowSubtitle = "这个模型可以设置的项"
        override val imageInputCatalogRow = "目录里已有它，下面三项是目录的值"
        override val catalogueTitle = "pi 模型目录"
        override val imageInputChecking = "正在读取…"
        override val imageInputUnavailable = "没能读到，点这里重试"
        override val imageInput = "支持图片输入"
        override val contextWindowLabel = "上下文窗口"
        override val maxTokensLabel = "最大输出"
        override val modelNumbersNote = "留空表示不改这一项，按上面显示的数值使用。"
        override val imageInputNote =
            "打开等于声明这个模型能接收图片；模型本身认不出图片时，打开也没用。" +
                "关掉则会收回图片支持。"
        override val imageInputCustomNote =
            "自定义接口 pi 不认识，读不到它的模型目录，所以这三项只是起点：" +
                "不支持图片、窗口 128k、最大输出 16k。改哪一项就按你填的来。"
        override val terminalModelNote =
            "同一套供应商、模型和 Key 也会写入 pi 自己的 settings.json，因此终端页手动启动的" +
                "pi 用的也是同一个模型。"

        override val modelBehaviour = "思考"
        override val thinkingSubtitle =
            "要求模型回答前投入多少推理。改完立即生效，不用重启。可选等级取决于当前模型：" +
                "模型没有的等级，pi 会自动往上取最接近的一档。"
        override val workspace = "工作区"
        override fun workingDirSubtitle(workspace: String) = "留空时，Agent 在 $workspace 中工作"
        override val workingDirNote =
            "Agent 视为根目录、并且其 bash 工具启动时所在的目录。它也是唯一允许递归删除的目录——" +
                "它之外的一切递归删除都会被安全守卫拒绝。它只在进程启动时读取，" +
                "因此修改后需要重启 Agent。"
        override val process = "进程"
        override val agentProcess = "Agent 进程"
        override val restartAgent = "重启 Agent"
        override val stopAgent = "停止"
        override val failedStartNote =
            "启动失败几乎总是配置问题：确认当前配置填写了 Key，并且该供应商确实存在这个模型 ID。" +
                "手册里有更完整的排查清单。"

        override val environment = "运行环境"
        override val prefix = "前缀目录"
        override val home = "主目录"
        override val appFiles = "应用文件"
        override val installedImage = "已安装镜像"
        override val installedImageSubtitle = "从 APK 解包出来的运行环境版本"
        override val bundledTools = "内置工具"
        override val runtimePrefixNote = "在这里安装的软件包会自动重定位到这个前缀。"
        override val maintenanceSubtitle = "更新 Pi，并修复已安装的软件包"
        override val piAgent = "Pi Agent"
        override val installedVersion = "已安装版本"
        override val installedVersionSubtitle = "从运行环境中的包读取"
        override val unknown = "未知"
        override val updatePi = "更新 Pi"
        override val updatePiSubtitle =
            "在运行环境中依次执行 `pi update --all` 与 `pi update --models`：更新 pi 本体、" +
                "已安装的扩展以及模型目录"
        override val checkAndUpdate = "检查并更新"
        override val installing = "正在安装…"
        override val dismiss = "关闭"
        override fun updatedTo(version: String) = "已更新到 $version。Agent 正在重启。"
        override val unknownVersion = "未知版本"
        override val updateFailedNote =
            "除非 npm 报告成功，否则已安装版本不会改变。下面是它输出的末尾内容。"
        override val updateIdleNote =
            "更新需要网络连接。版本号从 pi.dev 读取，然后由 npm 把新版本安装到本应用的前缀目录；" +
                "其余功能在离线状态下照常可用。"
        override val installedPackages = "已安装的软件包"
        override val relocate = "软件包重定位"
        override val relocateSubtitle = "修复仍然指向 Termux 路径的软件包"
        override val relocateNow = "检测并修复"
        override val relocateNote =
            "用 pkg 或 apt 安装的软件包会在安装时自动完成重定位，所以这个按钮只用于" +
                "从别处装进来、启动报错的包。检测会读取运行环境里的每个文件并改写其中的路径；" +
                "环境之外的东西不会被改，也不会被删除。"
        override fun relocateScanning(files: Int) = "正在检测…已扫描 $files 个文件"
        override val relocateBroken =
            "当前运行环境里的包重定位器已经损坏，修复无法解决。请重新安装 PiKit —— " +
                "运行环境会从应用里重新解包。"
        override fun relocated(occurrences: Int, files: Int, symlinks: Int, modes: Int) =
            buildString {
                append("已重定位 $files 个文件中的 $occurrences 处引用，以及 $symlinks 个符号链接。")
                if (modes > 0) append(" 并恢复了 $modes 个文件的可执行权限。")
            }
        override val nothingToRelocate = "无需重定位：所有文件都已匹配本应用的前缀。"
        override val relocateProblems = "完成，但存在问题："

        override val storageCheck = "检查存储"
        override val storageCheckSubtitle = "实测读写与防误删是否真的有效"
        override val storageCheckRun = "运行检查"
        override val storageCheckRunning = "正在检查…"
        override val storageCheckPassed = "全部检查通过"
        override val storageCheckFailed = "有检查未通过"
        override val storageCheckNote =
            "它在运行环境里以子进程运行，位置和 Agent、终端一样，所以报告的是 Agent 实际能做到" +
                "什么，而不是 Android 记录了什么。它不会开放任何权限：列出 Agent 能访问的文件夹，" +
                "在每个里写入并删除一个探针文件，构造出防误删必须扛住的「链接 + 哨兵文件」，" +
                "再让重定位器拒绝运行环境之外的路径。过程中产生的临时文件会随手清除。"
        override val storageCheckTerminalHint =
            "这里只显示结论和未通过的行。想看完整列表，在终端里运行 pikit-storage-check。"

        override val aboutTitle = "关于 PiKit"
        override val application = "应用"
        override val appSubtitle = "一个自带完整运行环境的 Android 编程 Agent"
        override val packageName = "包名"
        override val bundledPi = "内置 pi"
        override val bundledToolsMissing = "缺失"

        override val termuxEnvironment = "Termux 环境"
        override val checkForUpdates = "检查更新"
        override val checkForUpdatesSubtitle = "到 GitHub 上看看有没有新版本"
        override val updateChecking = "正在询问 GitHub…"
        override val updateUpToDate = "已经是最新版本"
        override val updateAvailable = "有新版本可以更新"
        override val updateNoReleases = "还没有发布过版本"
        override val updateNote =
            "只在你点这一行时，才会向 github.com 询问本应用的最新版本，然后在浏览器里打开它的" +
                "发布页。PiKit 自己不会下载任何东西。"

        override val searchTitle = "搜索设置"
        override val searchSubtitle = "Agent 的联网搜索"
        override val searchPageSubtitle = "内置的 web-access 扩展"
        override val searchFreeNote =
            "这一页什么都不用改就能用。没有配置任何 Key 时，扩展会走 Exa 的免费接口" +
                "（不需要账号）；它可能因为繁忙而限流，在下面填一个 Key 就能解除。" +
                "不设置任何选项，Agent 也能联网搜索。"
        override val searchNotBundled =
            "当前运行环境里没有 web-access 扩展。请先在「维护与修复」中更新运行环境，" +
                "再回到本页。"
        override val webAccess = "联网访问"
        override val webAccessSubtitle = "搜索网页、读取页面、克隆 GitHub 链接"
        override val searchWorkflow = "搜索流程"
        override val searchWorkflowSubtitle = "搜索之后做什么"
        override val workflowNone = "直接返回结果"
        override val workflowAutoSummary = "自动生成摘要"
        override val workflowSummaryReview = "在浏览器里审阅"
        override fun workflowDescription(id: String) = when (id) {
            "none" -> "搜索结果原样交给模型"
            "auto-summary" -> "每次搜索先自动摘要，再交给模型"
            "summary-review" -> "打开审阅页面，逐条确认摘要"
            else -> ""
        }

        override val searchProvider = "搜索服务"
        override val searchProviderSubtitle = "优先询问哪一个搜索服务"
        override val providerAutomatic = "自动"
        override fun providerDescription(id: String) = when (id) {
            "auto" -> "先试 Exa 的免 Key 接口，再试配置了 Key 的服务"
            // 不需要 Key
            "exa" -> "无需 Key，有速率限制"
            "duckduckgo" -> "无需 Key"
            "searxng" -> "自建实例，地址写在下面的配置文件里"
            // 借用已有模型的联网能力
            "openai" -> "走 OpenAI 的联网搜索"
            "gemini" -> "走 Gemini 模型的联网搜索"
            "perplexity" -> "Perplexity 自带搜索"
            "kimi" -> "走 Kimi 模型的联网搜索"
            "xai" -> "Grok 的网页与 X 搜索"
            "mistral" -> "Mistral 的联网搜索工具"
            "ollama" -> "Ollama Cloud 的搜索"
            // 需要 Key 的搜索接口
            "brave" -> "需要 Brave Search 的 Key"
            "tavily" -> "需要 Tavily 的 Key"
            "jina" -> "需要 Jina 的 Key"
            "firecrawl" -> "需要 Firecrawl 的 Key"
            "serper" -> "需要 Serper 的 Key"
            "serpapi" -> "需要 SerpApi 的 Key"
            "serpbase" -> "需要 SerpBase 的 Key"
            "kagi" -> "需要 Kagi 的 Key"
            "valyu" -> "需要 Valyu 的 Key"
            "bocha" -> "需要 Bocha 的 Key"
            "querit" -> "需要 Querit 的 Key"
            "search1api" -> "需要 Search1API 的 Key"
            "searchinfinity" -> "需要 Searchinfinity 的 Key"
            "tinyfish" -> "需要 TinyFish 的 Key"
            "parallel" -> "需要 Parallel 的 Key"
            "parallel-mcp" -> "Parallel，走它的 MCP 接口"
            "anysearch" -> "需要 AnySearch 的 Key"
            "xcrawl" -> "需要 XCrawl 的 Key"
            "brightdata" -> "需要 Bright Data 的 Key 和 Zone 名称"
            "serpdive" -> "需要 SERPdive 的 Key，可选检索深度"
            else -> ""
        }

        override val searchContent = "内容与限制"
        override val inlineContentLimit = "内联内容上限（字符）"
        override val fetchTimeout = "抓取超时（秒）"
        override fun searchProviderFootnote(count: Int) =
            "扩展支持的全部 $count 个搜索服务，按它自己的优先顺序排列；" +
                "每个服务仍需对应的 Key，见下面的配置文件。"

        override val searchConfig = "配置项"
        override val searchConfigNote =
            "这里的内容都会写进扩展自己的 web-search.json，也就是 Agent 实际读取的文件。" +
                "上面的控件会写它们自己的键；下面的列表是文件里其余的键。添加不会覆盖任何东西，" +
                "而是在现有生效配置的基础上加一项。"
        override val searchConfigAdd = "添加配置项"
        override val searchConfigAddBody = "从扩展能读的键里选一项，填好值加进配置文件。"
        override val searchConfigEmpty = "还没有添加任何配置项。上面的控件就是全部配置。"
        override val searchConfigPickTitle = "扩展可读的配置项"
        override val searchConfigPickSearch = "筛选配置项"
        override val searchConfigPickFootnote =
            "内置扩展能读的全部键，每一项下面写明它的作用。值会按该项期望的形式保存：" +
                "文字存成字符串，开关存成 true/false，数字存成数字。"
        override fun searchConfigValueTitle(path: String) = path
        override val searchConfigValueLabel = "取值"
        override val searchConfigValueNote =
            "文字直接输入，不用加引号（会存成 JSON 字符串）；开关填 `true` 或 `false`；" +
                "数字填 `50`；结构化取值填 JSON，例如 `{\"host\": \"my-box.ts.net\"}`。"
        override val searchConfigValueInvalid =
            "这个取值该项不接受。文字不用加引号；开关填 `true` 或 `false`；数字填 `50`；" +
                "其他情况请填 JSON。"
        override val searchConfigValueAdd = "添加"
        override val searchConfigRemoveAction = "移除"
        override fun searchConfigRemove(path: String) = "移除 $path"
        override val searchConfigRemoved = "已移除，下次启动 Agent 时生效。"
        override val searchConfigRemoveTitle = "移除这个配置项？"
        override fun searchConfigRemoveBody(path: String) =
            "将从 web-search.json 中移除 `$path`，文件里其他键保持原样。"
        override val searchConfigInEffect = "生效"
        override val searchConfigHeader =
            "联网访问的配置（web-search.json）。\n" +
                "标着 [生效] 的行会写进文件，扩展真的会用。\n" +
                "其余带 // 的行只是说明和示例，没有生效。\n" +
                "启用一行：删掉行首的 //，\n" +
                "必要时给上一行补一个逗号，再按保存。\n" +
                "注释不会被保存（扩展只认严格 JSON）。\n" +
                "整段被注释掉的配置（如 searchRouting），\n" +
                "要连大括号一起取消注释。"
        override val searchConfigInvalid =
            "这还不是一个 JSON 对象，没有保存。检查是否漏了逗号或引号；注释和结尾多余的逗号没关系。"
        override val searchConfigSaved = "已保存，下次启动 Agent 时生效。"
        override val searchConfigFailed = "文件写入失败，原因见终端页。"
        override val searchConfigRestored =
            "已恢复。文件回到刚安装时的内容，之前配置过的内容都不在了；下次启动 Agent 时生效。"
        override val searchConfigSave = "保存"
        override val searchConfigRevert = "撤销修改"
        override val searchConfigRestore = "恢复默认"
        override val searchConfigRestoreSubtitle =
            "把 web-search.json 换回刚安装时的内容：上面所有配置都会重置。"
        override val searchConfigRestoreTitle = "恢复默认配置文件？"
        override val searchConfigRestoreBody =
            "会用刚安装时的内容替换 web-search.json：你填过的 API Key 和所有自定义项都会从文件里消失，" +
                "而且在这里无法撤销。"
        override val searchConfigUnreadable =
            "web-search.json 不是 JSON 对象，所以上面的控件都隐藏了：它们会从这个应用读不懂的文件出发去写，" +
                "而这个文件很可能正是你手写的。注释和结尾多余的逗号都可以，缺一个大括号不行。" +
                "这里没有改动任何内容。修改或替换下面的文本并保存，这一页就会恢复可编辑。"

        override val searchAdvanced = "网络"
        override val searchProxy = "代理"
        override val searchProxySubtitle = "填写后会作为联网访问的代理"
        override val searchRestartNote = "改动会在下次启动 Agent 时生效。"

    }

    override val manual = object : Strings.Manual {
        override val title = "使用手册"
        override val subtitle = "从零开始了解这个应用"
    }

    override val notes = object : Strings.Notes {
        override val licenceTitle = "许可证"
        override val licence =
            "PiKit 是自由软件，以 GNU 通用公共许可证第 3 版发布。它打包了同为 GPLv3 的 " +
                "Termux 运行环境与终端模拟器，因此整个应用也是 GPLv3；Pi 编程 Agent 与联网访问扩展都是 MIT 许可。\n" +
                "没有担保，也没有遥测：除了你主动向模型供应商发起的请求，以及 Agent 自己执行的搜索之外，" +
                "不会有任何数据离开设备。"
        override val creditsTitle = "致谢"
        override val credits = listOf(
            "运行环境、包管理器与终端模拟器：Termux（GPLv3），其中 terminal-emulator 与 " +
                "terminal-view 两个包构成本应用的终端",
            "JavaScript 运行时：Node.js（MIT）",
            "Agent：@earendil-works/pi-coding-agent（MIT）",
            "联网搜索、读取网页与 PDF 解析：Nico Bailon 的 pi-web-access（MIT），" +
                "并包含 turndown、@mozilla/readability、defuddle、linkedom、unpdf、undici、" +
                "p-limit 和 typebox",
            "为 Agent 内置的命令行工具：ripgrep（MIT/Unlicense）与 fd（MIT/Apache-2.0）",
            "界面：Jetpack Compose 与 Material 3（Apache-2.0）、Kotlin 与 " +
                "kotlinx.serialization（Apache-2.0）、Okio（Apache-2.0）",
            "公式排版：JLaTeXMath，即 Android 移植版 jlatexmath-android" +
                "（GPL-2.0，上游为 GPL-2.0 附链接例外）",
            "模型图标：Lucide（ISC）",
        ).joinToString("\n")
    }
    override val root = object : Strings.Root {
        override val preparing = "正在准备"
        override val settingUp = "正在初始化 PiKit"
        override val setupSubtitle = "首次启动需要解包运行环境"
        override val setupNote = "这一步只会执行一次。Termux、Node.js 和 Pi Agent 都从 APK 中解包，无需下载任何东西。"
        override val environmentIncomplete = "运行环境不完整"
        override val couldNotPrepare = "运行环境准备失败"
    }

    override val common = object : Strings.Common {
        override val ok = "好"
        override val cancel = "取消"
        override val back = "返回"
        override val confirm = "确定"
        override val retry = "重试"
    }
}
