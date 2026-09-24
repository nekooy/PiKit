package pi.kit.mob.locales

/** Japanese catalog. */
internal object JapaneseStrings : Strings {
    override val tabs = object : Strings.Tabs {
        override val chat = "チャット"
        override val terminal = "ターミナル"
        override val files = "ファイル"
        override val settings = "設定"
    }

    override val header = object : Strings.Header {
        override val files = "ファイル"
        override val terminal = "ターミナル"
        override val terminalSubtitleIdle = "同梱環境のシェル"
        override fun terminalSubtitle(count: Int) = "$count 個のセッションが実行中"
    }

    override val chat = object : Strings.Chat {
        override val newConversation = "新しい会話"
        override val loadingModel = "モデルを読み込み中…"
        override val noModel = "モデルが未設定です"
        override val agentReady = "エージェント準備完了"
        override val agentStarting = "起動中…"
        override val agentFailed = "エージェントの起動に失敗"
        override val agentStopped = "エージェント停止中"
        override val removeAttachment = "削除"
        override fun attachmentCount(count: Int) = "画像 $count 枚"
        override val placeholder = "Pi に質問する…"
        override val placeholderAttachment = "この画像についての指示を入力…"
        override val send = "送信"
        override val stop = "停止"
        override val sessions = "会話履歴"
        override val newSession = "新しい会話"
        override val compact = "コンテキストを圧縮"
        override val compacting = "コンテキストを圧縮中…"
        override val showReasoning = "思考過程を表示"
        override val hideReasoning = "思考過程を隠す"
        override fun reasoningLabel(chars: Int) =
            "思考 · " + if (chars < 1000) "$chars 文字" else "%.1fk 文字".format(chars / 1000.0)
        override val copyCode = "コードをコピー"
        override val copyOutput = "出力をコピー"
        override val copyMessage = "メッセージをコピー"
        override val expand = "展開"
        override val collapse = "折りたたむ"
        override fun outputLines(count: Int) = "出力 · $count 行"
        override val imageAttached = "画像を添付しました"
        override val codeBlockFallback = "コード"
        override val toolFallback = "ツール"

        override fun stepsSummary(steps: Int) = "${steps} ステップ"
        override val stepsShow = "表示"
        override val stepsHide = "非表示"
        override fun contextUsage(percent: Int) = "コンテキスト $percent%"
        override val thinkingLabel = "思考"
        override fun duration(seconds: Long) = when {
            seconds < 60 -> "${seconds} 秒"
            seconds % 60 == 0L -> "${seconds / 60} 分"
            else -> "${seconds / 60} 分 ${(seconds % 60).toString().padStart(2, '0')} 秒"
        }

        override fun turnWorked(duration: String, steps: Int?) =
            if (steps == null) "作業 $duration" else "作業 $duration · $steps ステップ"

        override val imageTooLarge = "画像が大きすぎます（上限 4 MB）。"
        override fun imageLimit(max: Int) = "1 通に添付できる画像は最大 $max 枚です。"
        override val imageNotSupported =
            "pi はこのモデルをテキスト専用として報告しています。画像は送信されません。" +
                "「設定 → モデルとプロバイダー」で「画像入力に対応」を有効にしてください。"

        override val commands = "コマンド"
        override val shellCommandHint =
            "ここに端末コマンドを入力すると、モデルを介さずこの環境で実行します。" +
                "出力は会話に残ります。メッセージを ! で始めるのも同じことです。"
        override val shellCommandPlaceholder = "例: ls -la"
        override val commandNew = "新しい会話を始める（現在の会話は中断されます）"
        override val commandCompact = "今すぐコンテキストを圧縮し、ここまでを要約する"
        override val commandStop = "実行中の回答を止め、待機中のメッセージも破棄する"
        override val commandClone = "この会話を複製して新しい会話を作る（元はそのまま）"
        override val commandExport = "HTML ファイルとして home/export に書き出す"
        override val commandClear = "入力欄の下書きを捨てる"
        override val commandModel = "どのモデルが答えるかを選びます。モデル選択を開きます"
        override val attachTitle = "メッセージに追加"
        override val attachImage = "画像"
        override val attachFile = "ファイル"
        override val attachCamera = "写真を撮る"
        override val attachImageHint = "すでにある画像を選ぶ"
        override val attachFileHint = "ファイルから書類や画像を選ぶ"
        override val attachCameraHint = "その場で撮影してメッセージに追加する"
        override val cameraDenied =
            "カメラの権限がないため撮影できません。システム設定でこのアプリに権限を与えてください。"
        override val cameraUnavailable = "この端末には撮影できるカメラアプリがありません。"
        override val thinkingLevelLabel = "思考レベル"

        override fun thinkingLevelDescription(id: String) = when (id) {
            "off" -> "思考せずにすぐ回答します"
            "minimal" -> "Pi が許す最短の思考"
            "low" -> "素早い回答、軽い思考"
            "medium" -> "速度と深さの標準的なバランス"
            "high" -> "回答前に長く考えます"
            "xhigh" -> "十分に思考。遅く、コストも高い"
            "max" -> "思考の長さを制限しません"
            else -> ""
        }

        override fun thinkingModelNote(levels: String) =
            "このモデルにあるのは $levels だけです。モデルにないレベルを頼むと、pi が" +
                "より近い上位のレベルに置き換えます。"

        override val thinkingDisabled = "このモデルは思考に対応していません。レベルは off です。"

        override val switchModel = "モデル"
        override val contextDetails = "コンテキスト"
        override val contextEmpty = "まだ送信していません"
        override fun cacheHit(percent: Int) = "キャッシュ命中 $percent%"
        override val cacheHitLabel = "プロンプトキャッシュ命中率"

        override val detailModel = "モデル"
        override val detailProvider = "プロバイダー"
        override val detailContext = "コンテキスト枠"
        override val detailUsed = "使用量"
        override val detailInput = "入力"
        override val detailOutput = "出力"
        override val detailCacheRead = "キャッシュ読取"
        override val detailCacheWrite = "キャッシュ書込"
        override val detailReasoning = "思考"
        override val detailCost = "コスト"
        override val detailTotal = "このセッションの合計"
        override val detailSession = "セッションファイル"
        override val detailTurns = "ターン"

        override fun contextOfWindow(percent: Int, windowK: Int) = "$percent% / ${windowK}k"
        override fun tokens(count: Long) = when {
            count < 1_000 -> count.toString()
            count < 1_000_000 -> "%.1fk".format(count / 1_000.0)
            else -> "%.1fM".format(count / 1_000_000.0)
        }

        override fun turnCount(count: Int) = "$count ターン"

        override val jumpToLatest = "最新へ戻る"

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
        override val title = "会話履歴"
        override val loading = "読み込み中…"
        override fun selected(count: Int) = "$count 件を選択中"
        override fun saved(count: Int) = "全 $count 件"
        override val searchHint = "タイトルと内容を検索"
        override val searching = "会話を検索中…"
        override fun contentMatch(snippet: String) = "会話の内容に一致：$snippet"
        override val select = "複数選択"
        override val deleteSelected = "選択した項目を削除"
        override val cancelSelection = "選択を解除"
        override val back = "戻る"
        override val empty = "保存された会話はまだありません。\nPi が返信すると、会話がディスクに保存されます。"
        override fun nothingMatches(query: String) = "「$query」に一致する会話はありません。"
        override val pinned = "ピン留め"
        override val actions = "会話の操作"
        override val rename = "名前を変更"
        override val pin = "上部に固定"
        override val unpin = "固定を解除"
        override val delete = "削除"
        override val untitled = "無題"
        override val emptyTitle = "空の会話"
        override fun messageCount(count: Int) = "$count 件のメッセージ"
        override val renameTitle = "会話の名前を変更"
        override val renameLabel = "名前"
        override val renameActiveNote = "この会話は開いているため、Pi が直接名前を変更します。"
        override val renameClosedNote =
            "Pi はファイル内の最後の命名レコードから会話名を読み取ります。この会話には 1 件追記されます。"
        override val cancel = "キャンセル"
        override val deleteTitleOne = "この会話を削除しますか？"
        override fun deleteTitleMany(count: Int) = "$count 件の会話を削除しますか？"
        override fun deleteBodyOne(title: String) = "「$title」をディスクから削除します。元に戻せません。"
        override val deleteBodyMany = "以下をディスクから削除します。元に戻せません："
        override fun renameFailed(reason: String) = "名前を変更できませんでした：$reason"
        override fun deleteFailed(reason: String) = "削除できませんでした：$reason"
        override fun partiallyDeleted(removed: Int, total: Int) = "$total 件中 $removed 件を削除しました。"
        override val switchWhileWorking =
            "この会話ではエージェントがまだ応答中なので、まだ削除できません。開いている会話を" +
                "削除すると新しいセッションを始めることになり、pi は応答中にそれを許しません。" +
                "完了を待つか、停止を押してください。"
        override val switchInterruptTitle = "応答を中断しますか？"
        override val switchInterruptBody =
            "この会話ではエージェントがまだ応答中です。pi はセッション切り替え時に実行中の" +
                "ターンを中止するため、この応答はすぐに止まり、あとから続きは得られません。"
        override val switchInterruptConfirm = "中断して切り替え"
    }

    override val terminal = object : Strings.Terminal {
        override val clear = "画面を消去"
        override val newTerminal = "新しいターミナル"
        override val closeTerminal = "このターミナルを閉じる"
        override val switchTerminal = "ターミナルを切り替え"
        override fun exited(code: Int?) = "終了（$code）"
        override val sessionsRunOn = "このタブを離れてもセッションは動き続けます"
        override fun closeSession(label: String) = "$label を閉じる"
        override val closeAllTerminals = "すべてのターミナルを閉じる"
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
        override val scrollToggleHint = "自動スクロール：停止中は新しい出力で画面が下に引き戻されません"
        override val scrollShort = "スクロール"
        override val newline = "改行"
    }

    override val files = object : Strings.Files {
        override val home = "ホームディレクトリ"
        override val parent = "親ディレクトリ"
        override val close = "閉じる"
        override val openWith = "他のアプリで開く"
        override val notAFile = "ファイルではありません"
        override fun tooLarge(size: String) =
            "ファイルサイズは $size です。プレビューは 64 KB までです。\nターミナルから開いてください。"
        override fun binary(size: String) = "バイナリファイル（$size）"
        override fun unreadable(reason: String) = "ファイルを読み込めませんでした：$reason"
        override val openFailed =
            "このファイルを他のアプリに渡せませんでした。まずエージェントのワークスペースに" +
                "コピーして、そこから開いてください。"
    }

    override val settings = object : Strings.Settings {
        override val title = "設定"
        override val none = "なし"
        override val manageProfiles = "モデルとプロバイダー"
        override val manageProfilesSubtitle = "Pi が使うキーとモデル"
        override val thinkingLevel = "思考レベル"
        override val workingDirectory = "作業ディレクトリ"
        override val updateAndRepair = "メンテナンスと修復"
        override val updateAndRepairSubtitle = "モデル一覧、パッケージの修復"
        override val userManual = "ユーザーマニュアル"
        override val userManualSubtitle = "このアプリの使い方を最初から"
        override val about = "PiKit について"
        override val aboutSubtitle = "バージョン・ライセンス・アプリの更新確認"
        override val language = "言語"
        override val languageSubtitle = "インターフェースの表示言語"
        override val personalization = "パーソナライズ"
        override val personalizationSubtitle = "起動時の挙動とテーマ"
        override val personalizationConversation = "会話"
        override val personalizationAppearance = "外観"
        override val openNewOnLaunch = "起動時に新しい会話を開く"
        override val openNewOnLaunchSubtitle =
            "アプリを開くと新しい会話になります。オフにすると前回の会話を続けます"
        override val theme = "テーマ"
        override val themeSubtitle = "ライト・ダーク・システムに合わせる"
        override val themeSystem = "システムに合わせる"
        override val themeLight = "ライト"
        override val themeDark = "ダーク"
        override val personalizationNote =
            "「起動時」とは、アプリを完全に閉じてから開き直すときです。セッション途中の" +
                "エージェント再起動（設定変更など）では、いまの会話が続きます。テーマは" +
                "すぐに反映されます。"
        override val essentials = "基本設定"
        override val advancedSection = "詳細"
        override val bundlesNote =
            "PiKit は完全な Termux 実行環境、Node.js、Pi エージェントを APK に同梱しています。" +
                "別途インストールするものはなく、モデルに質問するまでダウンロードも発生しません。"
        override val running = "実行中"
        override val starting = "起動中…"
        override val stopped = "停止"
        override fun failedWith(message: String) = "失敗 — $message"
        override val notInstalled = "未インストール"
        override val notFound = "同梱の実行環境に見つかりません"
        override val storageTitle = "共有ストレージ"
        override val storageSubtitle = "エージェントに許可するフォルダーを選びます"
        override val storageGrant = "許可する"
        override val storageMissing = "未許可"
        override val storageNote =
            "この権限は Android の「すべてのファイルへのアクセス」画面でしか付与できないため、" +
                "ボタンはシステム設定を開きます。権限そのものはアプリ全体に与えられ、エージェントは" +
                "このアプリと同じ権限で動くため、ここでオンにしたフォルダーは Android の壁ではなく、" +
                "PiKit がエージェントに守らせる規則です——オンにしていないフォルダーを指定した" +
                "アクセスは PiKit が拒否します。"
        override val storageAskTitle = "エージェントにファイルを許可しますか？"
        override val storageAskBody =
            "現在エージェントは自分の専用ディレクトリしか見えず、写真・書類・ダウンロードを" +
                "読めません。これらを扱わせるには Android の「すべてのファイルへのアクセス」が" +
                "必要ですが、この権限にはダイアログがなく、システム画面で付与します。\n\n" +
                "付与したあと「設定 → 端末のストレージ」で許可するフォルダーを個別にオンに" +
                "してください。オフのままのフォルダーを指定したアクセスは PiKit が拒否し、" +
                "いつでも取り消せます。"
        override val storageAskOpen = "許可する"
        override val storageAskLater = "あとで"

        override val storagePageTitle = "共有ストレージ"
        override val storagePageSubtitle =
            "オンにしたフォルダーだけをエージェントに許可し、それ以外のアクセスは PiKit が拒否します。"
        override val storageNoAccessBody =
            "エージェントは自身のホームディレクトリの中だけで読み書きできます。端末のファイルには" +
                "一切触れず、参照もできません。"
        override val storageAccessLevel = "アクセス範囲"
        override val storageLevelNone = "なし"
        override val storageLevelSelected = "選択したフォルダー"
        override val storageLevelAll = "すべてのファイル"
        override val storageFolders = "フォルダー"
        override val storageFolderShared = "共有ストレージ全体"
        override val storageFolderDownloads = "ダウンロード"
        override val storageFolderDocuments = "ドキュメント"
        override val storageFolderPictures = "画像"
        override val storageFolderDcim = "カメラ"
        override val storageFolderMusic = "音楽"
        override val storageFolderMovies = "動画"
        override val storageBroadWarning =
            "通常は復元できないファイルです。エージェントは削除もできるようになります。"
        override val storageConfirmGrantTitle = "エージェントに許可しますか？"
        override fun storageConfirmGrantBody(name: String) =
            "「$name」には取り返しのつかないファイルが含まれている可能性があります。許可すると" +
                "読み取りだけでなく削除もできるようになります。本当に必要なときだけオンにしてください。"
        override val storageCustomSection = "追加したフォルダー"
        override val storageCustomAdd = "フォルダーを追加"
        override val storageCustomAddBody = "共有ストレージ内の任意のフォルダーを選べます。"
        override val storageCustomSubsumed = "共有ストレージ全体がすでに許可されています"
        override val storageCustomRemove = "削除"
        override val storageCustomRemoveTitle = "このフォルダーを削除しますか？"
        override fun storageCustomRemoveBody(path: String) =
            "エージェントは `$path` にアクセスできなくなります。フォルダーと中のファイルは" +
                "削除されません。"
        override val storageCustomPickTitle = "フォルダーを選択"
        override val storageCustomPickUse = "このフォルダーを使う"
        override val storageCustomPickUp = "親フォルダー"
        override val storageRevokeAll = "すべてのアクセスを解除"
        override val storageRevokeAllTitle = "すべてのアクセスを解除しますか？"
        override val storageRevokeAllBody =
            "エージェントはホームディレクトリの中で作業を続けます。端末上のファイルは削除されません" +
                "——環境からのリンクが外れるだけです。"
        override val storageGrantPromptTitle = "Android の権限が必要です"
        override val storageGrantPromptBody =
            "まだ共有ストレージに到達できないため、どのフォルダーもオンにできません。PiKit が" +
                "「すべてのファイルへのアクセス」を Android に要求します。実際にエージェントが" +
                "参照できる範囲は、この画面で選んだフォルダーが決めます。\n\n" +
                "この画面では注意してください。ファイルマネージャーやターミナルアプリに許可すると、" +
                "そのアプリも同じようにあなたのファイルを扱えるようになります。"
        override val storageGrantPromptConfirm = "設定を開く"

        override val storageGuardSection = "安全拡張"
        override val storageGuardTitle = "安全拡張"
        override val storageGuardSubtitle = "ツール実行前に検査し、危険な操作を拒否します"
        override val storageGuardMissing = "このランタイムイメージには安全拡張が含まれていません"
        override val storageGuardNote =
            "拒否するのは、ワークスペース外の再帰削除、ファイルシステムの作成・変更、生デバイスへの" +
                "書き込み、pi 自身の認証情報と設定の削除・上書き、そして許可していない共有ストレージ" +
                "のパスを名指ししたコマンドです。オフにするとこれらは検査されなくなります。拡張は" +
                "インストールされたまま読み込まれ、効果がなくなるだけで、いつでも戻せます。"
        override val storageGuardOffTitle = "安全拡張をオフにしますか？"
        override val storageGuardOffBody =
            "オフにすると、エージェントのツール呼び出しは検査されなくなります。ワークスペース外の" +
                "再帰削除、ファイルシステムのコマンド、生デバイスへの書き込み、pi 自身の認証情報や" +
                "設定の削除はそのまま実行されます。オフにしたフォルダーは ~/storage に現れませんが、" +
                "実際のパスを直接書いたコマンドは拒否されなくなります。\n\n" +
                "アンインストールはされません。いつでもオンに戻せます。変更を反映するため、" +
                "エージェントを今すぐ再起動します。"
        override val storageGuardOffConfirm = "オフにする"

        override val agentContextTitle = "エージェントのコンテキスト"
        override val agentContextSubtitle = "モデルが毎回のリクエストで受け取る内容"
        override val agentContextSection = "コンテキストの内容"
        override val agentContextLead = "モデルは毎回のリクエストで、pi からこれらを一緒に受け取ります："
        override val agentContextSystemTitle = "システムプロンプト"
        override val agentContextSystemBody =
            "pi 自身の役割（コーディングアシスタント）、ツール一覧と呼び出し規則（edit は正確な" +
                "置換、cat の代わりに read など）、pi のドキュメント索引。PiKit は変更しません。"
        override val agentContextInstructionsTitle = "指示ファイル AGENTS.md"
        override val agentContextInstructionsBody =
            "pi は AGENTS.md を 2 か所から読みます：\n" +
                "・グローバル：\$HOME/.pi/agent/AGENTS.md。PiKit がインストール時に書き込む、次の行の" +
                "ファイルで、ここで編集できます。\n" +
                "・ワークスペース：プロジェクトが作業ディレクトリに置く AGENTS.md（例：" +
                "~/workspace/AGENTS.md）。pi は作業ディレクトリから上へたどるので、プロジェクトに" +
                "近いものほど具体的です。プロジェクトが自前で持つこともできます（今はまだありません）。"
        override val agentContextRuntimeTitle = "実行環境"
        override val agentContextRuntimeBody =
            "モデルとプロバイダー、思考レベル、セッション ID とセッションファイルのパス、ワーク" +
                "スペース（PIKIT_WORKSPACE）、共有ストレージ（PIKIT_STORAGE_ROOTS と " +
                "PIKIT_STORAGE_ALLOWED）、テレメトリーのスイッチ（PI_TELEMETRY=0）。"
        override val agentContextToolsTitle = "ツール"
        override val agentContextToolsBody =
            "pi 自身の read、bash、edit、write、grep、find、ls に加えて、PiKit が入れた " +
                "web_search、fetch_content、source_check、get_search_content、そして危険な呼び出しを" +
                "拒否する pikit-safety-guard。"
        override val agentContextHistoryTitle = "会話"
        override val agentContextHistoryBody =
            "現在のセッションです。ツール呼び出しと結果も含めて毎回のメッセージと一緒に送られ、" +
                "セッションファイルに書き込まれます。"
        override val agentContextInstructions = "グローバル AGENTS.md"
        override val agentContextInstructionsEditable = "編集可"
        override val agentContextNote =
            "このファイルは pi が起動のたびに読み込み、保存するとエージェントが再起動します。" +
                "それ以外はここでは読み取り専用です。モデルと思考レベルは「モデルとプロバイダー」、" +
                "ワークスペースは「エージェントのプロセス」、共有ストレージは「共有ストレージ」の" +
                "各ページで変更します。"
        override val agentContextEditorNote =
            "pi は起動時にこのファイルを読みます。保存するとエージェントが再起動し、変更が反映" +
                "されます。"
        override val agentContextSave = "保存"
        override val agentContextSaved = "保存しました。エージェントを再起動しています"
        override val agentContextFailed = "保存できませんでした。ファイルは変更されていません"
        override val agentContextRestore = "既定に戻す"
        override val agentContextRestoreTitle = "既定の内容に戻しますか？"
        override val agentContextRestoreBody = "ファイルは PiKit の初期内容に戻り、書いた内容は失われます。"
        override val agentContextRestoreConfirm = "戻す"

        override val profilesTitle = "モデルとプロバイダー"
        override val profilesSubtitle = "使用中のプロファイルでエージェントが起動します"
        override val savedProfiles = "保存済みのプロファイル"
        override val active = "使用中"
        override val editProfile = "プロファイルを編集"
        override val deleteProfile = "プロファイルを削除"
        override val noProfileProvider = "プロバイダー未選択"
        override val noModelChosen = "モデル未選択"
        override val noKey = "キーなし"
        override val keySaved = "キー保存済み"
        override val tapToActivate =
            "プロファイルをタップすると使用中になります。新しいプロバイダー・モデル・キーを" +
                "反映するためエージェントは再起動します。これらは起動時にコマンドラインと" +
                "環境変数で渡されます。"
        override val addProfile = "プロファイルを追加"
        override val lastProfile = "プロファイルは最低 1 つ必要です。"
        override val deleteProfileTitle = "プロファイルを削除しますか？"
        override fun deleteProfileBody(name: String) =
            "「$name」とそこに保存されたキーが設定ファイルから削除されます。"
        override val newProfile = "新しいプロファイル"
        override val name = "名前"
        override val nameSubtitle = "プロファイルの区別にのみ使用します"
        override val namePlaceholder = "例：仕事用 DeepSeek"
        override val provider = "プロバイダー"
        override val providerSubtitle = "このプロファイルのプロバイダーを選択します"
        override val providerPickSearch = "プロバイダーを検索"
        override fun keyPassedAs(envVar: String) = "キーは $envVar として pi に渡されます"
        override val notChosen = "未選択"
        override val apiKey = "API キー"
        override val modelId = "モデル ID"
        override val modelIdPlaceholder = "例：deepseek-chat"
        override val models = "モデル"
        override val modelsSubtitle = "モデルをタップすると、エージェントはそれで答えます"
        override val addModel = "モデルを追加"
        override val removeModel = "モデルを削除"
        override val removeModelTitle = "このモデルを削除しますか？"
        override fun removeModelBody(model: String) =
            "「$model」は一覧から外れ、設定した画像対応・コンテキストウィンドウ・最大出力も" +
                "一緒に消えます。プロファイル本体、キー、他のモデルはそのままです。"

        override val baseUrl = "エンドポイント"
        override val baseUrlPlaceholder = "https://relay.example.com/v1"
        override val baseUrlNote =
            "API のベース URL です。プロバイダーのドキュメントにあるバージョン部分も含めて" +
                "ください（例：https://relay.example.com/v1）。下のモデル ID がここに送られます。" +
                "ホスト名のみの場合は /v1 が自動的につきます。"
        override val baseUrlOptionalNote =
            "任意です。空欄ならプロバイダー既定のエンドポイントを使います。" +
                "埋めるとリクエストはこのアドレス（プロキシ／リレー）へ送られます" +
                "（例：https://relay.example.com/v1）。ホスト名のみの場合は /v1 が自動的につきます。"
        override val needBaseUrl = "カスタムエンドポイントにはベース URL が必要です。"
        override val saveFailed =
            "保存できませんでした：設定ファイルを書き込めませんでした。" +
                "次回起動時に今回の変更は失われます。"
        override val fetchModels = "モデルを取得"
        override val fetching = "取得中…"
        override val chooseProviderFirst = "先にプロバイダーを選択してください。"
        override val fetchNote =
            "取得すると、このキーで使えるモデルをプロバイダーに問い合わせます。プロバイダーが" +
                "一覧を提供しない場合は pi のカタログにフォールバックします。ID を手入力もできます。"
        override fun modelCount(count: Int) = "$count 個のモデル"
        override val fromProvider = "プロバイダーが返した一覧です。このキーで使用できます。"
        override val fromCatalog =
            "pi のカタログの内容です。pi はキーとの照合を行わないため、最初のプロンプトで" +
                "拒否される可能性があります。"
        override val save = "保存"
        override val cancel = "キャンセル"
        override val needProvider = "保存する前にプロバイダーを選択してください。"
        override val needProviderSubtitle =
            "まずプロバイダーを選択してください。モデル ID は単体では判断できません。"
        override val needModel = "保存するにはモデル ID が必要です。"
        override val unsavedTitle = "変更を保存しますか？"
        override val unsavedBody = "このプロファイルには保存されていない変更があります。"
        override val discard = "破棄"
        override val keepEditing = "入力を続ける"
        override val profile = "プロファイル"
        override fun contextWindow(k: Int) = "${k}k コンテキスト"
        override val capabilityImages = "画像対応"
        override val capabilityReasoning = "推論対応"
        override val imageInputSection = "モデルパラメータ"
        override val imageInputRowSubtitle = "このモデルで設定できる項目"
        override val imageInputCatalogRow = "カタログにあるモデルです。下の値はカタログのもの"
        override val catalogueTitle = "pi のカタログ"
        override val imageInputChecking = "読み込み中…"
        override val imageInputUnavailable = "読み込めませんでした。タップして再試行"
        override val imageInput = "画像入力に対応"
        override val contextWindowLabel = "コンテキストウィンドウ"
        override val maxTokensLabel = "最大出力"
        override val modelNumbersNote = "空欄ならこの項目は変更しません。上の値がそのまま使われます。"
        override val imageInputNote =
            "オンにすると「このモデルは画像を受け取れる」と宣言します。対応していない" +
                "モデルでは、オンにしても画像は認識されません。オフにすると画像対応を外します。"
        override val imageInputCustomNote =
            "カスタムエンドポイントは pi が知らないためカタログがなく、この 3 項目は" +
                "あくまで出発点です（画像なし・128k・16k）。変更した項目はその値で使われます。"
        override val terminalModelNote =
            "同じプロバイダー・モデル・キーは pi 自身の settings.json にも書き込まれるため、" +
                "ターミナルタブで起動した pi も同じモデルを使います。"

        override val modelBehaviour = "思考"
        override val thinkingSubtitle =
            "回答前にモデルへ求める推論の量です。変更はすぐ反映され、再起動は不要です。" +
                "選べるレベルは使用中のモデルによります。モデルにないレベルを頼むと、" +
                "pi がより近い上位のレベルに置き換えます。"
        override val workspace = "ワークスペース"
        override fun workingDirSubtitle(workspace: String) = "空欄の場合、エージェントは $workspace で作業します"
        override val workingDirNote =
            "エージェントがルートとして扱い、bash ツールが開始するディレクトリです。" +
                "再帰的な削除が許可される唯一のディレクトリでもあり、その外側は安全ガードが拒否します。" +
                "プロセス起動時に一度だけ読まれるため、変更後は再起動してください。"
        override val process = "プロセス"
        override val agentProcess = "エージェントのプロセス"
        override val restartAgent = "エージェントを再起動"
        override val stopAgent = "停止"
        override val failedStartNote =
            "起動失敗のほとんどは設定が原因です。使用中のプロファイルにキーがあるか、" +
                "そのプロバイダーにモデル ID が存在するかを確認してください。" +
                "マニュアルに詳しいチェックリストがあります。"

        override val environment = "実行環境"
        override val prefix = "プレフィックス"
        override val home = "ホーム"
        override val appFiles = "アプリのファイル"
        override val installedImage = "インストール済みイメージ"
        override val installedImageSubtitle = "APK から展開された実行環境のリビジョン"
        override val bundledTools = "同梱ツール"
        override val runtimePrefixNote = "ここで入れたパッケージは自動的にこのプレフィックスへ再配置されます。"
        override val maintenanceSubtitle = "モデル一覧と、インストール済みパッケージの修復"
        override val piAgent = "Pi エージェント"
        override val installedVersion = "インストール済みバージョン"
        override val installedVersionSubtitle = "実行環境内のパッケージから読み取ります"
        override val unknown = "不明"
        override val modelList = "モデル一覧"
        override val modelListRefresh = "今すぐ更新"
        override val modelListRefreshSubtitle = "必要なときにモデル一覧を更新します"
        override val modelListRefreshing = "各プロバイダに問い合わせています…"
        override val modelListChanged =
            "モデル一覧が更新されました。読み込むためエージェントを再起動します。"
        override val modelListUnchanged = "モデル一覧は最新です。"
        override val modelListNote =
            "通常は何も押す必要はありません。モデル一覧は 4 時間ごとに自動で更新され、" +
                "このボタンが要るのは公開されたばかりのモデルを今すぐ使いたいときだけです。" +
                "更新は設定済みの各プロバイダにカタログを問い合わせるため、ネットワーク接続が" +
                "必要です。それ以外はオフラインでも動作します。Pi 本体はここでは更新されません" +
                "（アプリの一部であり、PiKit の更新と一緒に届きます）。"
        override val dismiss = "閉じる"
        override val installedPackages = "インストール済みパッケージ"
        override val relocate = "パッケージの再配置"
        override val relocateSubtitle = "Termux のパスを指したままのパッケージを直します"
        override val relocateNow = "確認して修復"
        override val relocateNote =
            "インストールしたものが既に起動できないときだけ必要です。通常は押す必要はありません。" +
                "pkg や apt で入れたパッケージはインストール時に自動で再配置されます。別の経路で" +
                "入って起動しないパッケージのために、この確認は実行環境のすべてのファイルを読み、" +
                "その中のパスを書き換えます。環境の外には触れず、削除もしません。"
        override fun relocateScanning(files: Int) = "確認中…$files ファイルを走査"
        override val relocateBroken =
            "この実行環境のパッケージ再配置ツールが壊れているため、修復では直りません。" +
                "PiKit を再インストールしてください（実行環境はアプリから展開し直されます）。"
        override fun relocated(occurrences: Int, files: Int, symlinks: Int, modes: Int) =
            buildString {
                append("$files 個のファイル内の $occurrences 箇所と、$symlinks 個のシンボリックリンクを再配置しました。")
                if (modes > 0) append(" また $modes 個のファイルの実行権限を復元しました。")
            }
        override val nothingToRelocate = "再配置は不要です。すべてのファイルがこのアプリのプレフィックスと一致しています。"
        override val relocateProblems = "完了しましたが問題があります："

        override val storageCheck = "ストレージを確認"
        override val storageCheckSubtitle = "読み書きと削除の安全性を実測します"
        override val storageCheckRun = "確認を実行"
        override val storageCheckRunning = "実行中…"
        override val storageCheckPassed = "すべての確認に合格"
        override val storageCheckFailed = "失敗した確認があります"
        override val storageCheckNote =
            "実行環境の中で、このアプリの子プロセスとして動きます。エージェントやターミナルと" +
                "同じ立場なので、Android が記録した内容ではなく、エージェントが実際にできることを" +
                "報告します。何も許可しません：エージェントが参照できるフォルダーを一覧し、" +
                "それぞれに探針ファイルを書いて削除し、削除ガードが耐えるべき「リンクと番人の" +
                "ファイル」を作り、再配置ツールに実行環境の外のパスを拒否させます。" +
                "作業用の一時ファイルはその場で消します。"
        override val storageCheckTerminalHint =
            "ここに出るのは結果と失敗した行だけです。すべての行はターミナルで " +
                "pikit-storage-check を実行すると見られます。"

        override val aboutTitle = "PiKit について"
        override val application = "アプリケーション"
        override val appSubtitle = "実行環境を内蔵した Android 用コーディングエージェント"
        override val packageName = "パッケージ"
        override val bundledPi = "同梱の pi"
        override val bundledToolsMissing = "見つかりません"

        override val termuxEnvironment = "Termux 環境"
        override val checkForUpdates = "更新を確認"
        override val checkForUpdatesSubtitle = "GitHub に新しいリリースがあるか問い合わせます"
        override val updateChecking = "GitHub に問い合わせ中…"
        override val updateUpToDate = "これが最新リリースです"
        override val updateAvailable = "新しいリリースがあります"
        override val updateNoReleases = "まだリリースが公開されていません"
        override val updateNote =
            "この行をタップしたときだけ github.com に本アプリの最新リリースを問い合わせ、" +
                "そのリリースページをブラウザで開きます。PiKit が自分でダウンロードすることは" +
                "ありません。"

        override val searchTitle = "検索設定"
        override val searchSubtitle = "エージェントのウェブ検索"
        override val searchPageSubtitle = "同梱の web-access 拡張"
        override val searchFreeNote =
            "このページは何も変更しなくても使えます。キーを設定していなければ、拡張は Exa の" +
                "無料エンドポイントを使います（アカウント不要）。混雑時はレート制限がかかる" +
                "ことがあり、下にキーを入れると解除されます。何も設定しなくてもエージェントは" +
                "ウェブ検索できます。"
        override val searchNotBundled =
            "この実行環境には web-access 拡張がありません。「メンテナンスと修復」で" +
                "実行環境を更新してから、このページを開き直してください。"
        override val webAccess = "ウェブアクセス"
        override val webAccessSubtitle = "検索・ページ読み込み・GitHub のクローン"
        override val searchWorkflow = "検索ワークフロー"
        override val searchWorkflowSubtitle = "検索後にすること"
        override val workflowNone = "結果をそのまま返す"
        override val workflowAutoSummary = "要約を生成"
        override val workflowSummaryReview = "ブラウザーで確認"
        override fun workflowDescription(id: String) = when (id) {
            "none" -> "検索結果をそのままモデルに渡します"
            "auto-summary" -> "検索ごとに要約してからモデルに渡します"
            "summary-review" -> "確認ページを開き、要約を一つずつ見ます"
            else -> ""
        }

        override val searchProvider = "検索プロバイダー"
        override val searchProviderSubtitle = "最初に問い合わせる検索サービス"
        override val providerAutomatic = "自動"
        override fun providerDescription(id: String) = when (id) {
            "auto" -> "まず Exa のキー不要エンドポイント、次にキーを設定したサービス"
            // キー不要
            "exa" -> "キー不要。レート制限あり"
            "duckduckgo" -> "キー不要"
            "searxng" -> "自前のインスタンス。アドレスは下の設定ファイルに書きます"
            // 既存モデルの検索機能を使う
            "openai" -> "OpenAI のホスト型ウェブ検索を使います"
            "gemini" -> "Gemini モデル経由で検索します"
            "perplexity" -> "Perplexity 自身の検索"
            "kimi" -> "Kimi モデル経由で検索します"
            "xai" -> "Grok のウェブ検索と X 検索"
            "mistral" -> "Mistral のウェブ検索ツール"
            "ollama" -> "Ollama Cloud の検索"
            // キーが必要な検索 API
            "brave" -> "Brave Search のキーが必要です"
            "tavily" -> "Tavily のキーが必要です"
            "jina" -> "Jina のキーが必要です"
            "firecrawl" -> "Firecrawl のキーが必要です"
            "serper" -> "Serper のキーが必要です"
            "serpapi" -> "SerpApi のキーが必要です"
            "serpbase" -> "SerpBase のキーが必要です"
            "serply" -> "Google の結果。Serply のキーが必要で、自動選択はされません"
            "kagi" -> "Kagi のキーが必要です"
            "valyu" -> "Valyu のキーが必要です"
            "bocha" -> "Bocha のキーが必要です"
            "querit" -> "Querit のキーが必要です"
            "search1api" -> "Search1API のキーが必要です"
            "searchinfinity" -> "Searchinfinity のキーが必要です"
            "tinyfish" -> "TinyFish のキーが必要です"
            "parallel" -> "Parallel のキーが必要です"
            "parallel-mcp" -> "Parallel。MCP エンドポイント経由です"
            "baizhi" -> "Baizhi MCP。明示的に選んだ場合のみ使われます"
            "anysearch" -> "AnySearch のキーが必要です"
            "xcrawl" -> "XCrawl のキーが必要です"
            "brightdata" -> "Bright Data のキーと Zone 名が必要です"
            "serpdive" -> "SERPdive のキーが必要です。検索の深さも選べます"
            else -> ""
        }

        override val searchContent = "内容と上限"
        override val inlineContentLimit = "インライン内容の上限（文字）"
        override val fetchTimeout = "取得タイムアウト（秒）"
        override fun searchProviderFootnote(count: Int) =
            "拡張がルーティングできる $count 個の検索サービスを、拡張自身の優先順に並べています。" +
                "各サービスにはそれぞれキーが必要です。下の設定ファイルを参照してください。"

        override val searchConfig = "設定項目"
        override val searchConfigNote =
            "ここに書いた内容は拡張自身の web-search.json、つまりエージェントが実際に読む" +
                "ファイルに書き込まれます。上のコントロールはそれぞれのキーを書き、下の一覧は" +
                "ファイルにある残りのキーです。追加は既存の設定を置き換えず、有効な設定の上に" +
                "1 項目を足します。"
        override val searchConfigAdd = "設定項目を追加"
        override val searchConfigAddBody =
            "拡張が読むキーを 1 つ選び、値を入れてファイルに追加します。"
        override val searchConfigEmpty = "まだ何も追加していません。上のコントロールが設定のすべてです。"
        override val searchConfigPickTitle = "拡張が読む項目"
        override val searchConfigPickSearch = "項目を絞り込む"
        override val searchConfigPickFootnote =
            "同梱の拡張が読むすべてのキーと、それぞれの説明です。値は項目が期待する形で保存" +
                "されます。文字は文字列、スイッチは true/false、数値は数値として保存されます。"
        override fun searchConfigValueTitle(path: String) = path
        override val searchConfigValueLabel = "値"
        override val searchConfigValueNote =
            "文字は引用符を付けずにそのまま入力します（JSON の文字列として保存されます）。" +
                "スイッチは `true` か `false`、数値は `50`、構造化された値は " +
                "`{\"host\": \"my-box.ts.net\"}` のような JSON です。"
        override val searchConfigValueInvalid =
            "この項目が受け取れない値です。文字に引用符は不要、スイッチは `true` か `false`、" +
                "数値は `50`、それ以外は JSON で入力してください。"
        override val searchConfigValueAdd = "追加"
        override val searchConfigRemoveAction = "削除"
        override fun searchConfigRemove(path: String) = "$path を削除"
        override val searchConfigRemoved = "削除しました。次にエージェントを起動したときに反映されます。"
        override val searchConfigRemoveTitle = "この設定項目を削除しますか？"
        override fun searchConfigRemoveBody(path: String) =
            "web-search.json から `$path` を削除します。ファイル内の他のキーはそのままです。"
        override val searchConfigInEffect = "有効"
        override val searchConfigHeader =
            "ウェブアクセスの設定（web-search.json）。\n" +
                "[有効] の行はファイルに書き込まれます。\n" +
                "// で始まる行は説明と例だけです。\n" +
                "有効にするには行頭の // を消し、必要なら\n" +
                "前の行にカンマを足して保存します。\n" +
                "コメントはファイルに書かれません。\n" +
                "まるごとコメントの設定は中カッコも外す。"
        override val searchConfigInvalid =
            "まだ JSON オブジェクトではありません。保存していません。カンマや引用符の抜けを確認してください。" +
                "コメントと末尾の余分なカンマは問題ありません。"
        override val searchConfigSaved = "保存しました。次にエージェントを起動したときに反映されます。"
        override val searchConfigFailed = "ファイルを書き込めませんでした。理由は端末タブで確認できます。"
        override val searchConfigRestored =
            "既定に戻しました。ファイルはインストール直後の内容になり、設定していた内容は失われています。" +
                "次にエージェントを起動したときに反映されます。"
        override val searchConfigSave = "保存"
        override val searchConfigRevert = "変更を破棄"
        override val searchConfigRestore = "既定に戻す"
        override val searchConfigRestoreSubtitle =
            "web-search.json をインストール直後の内容に戻します。上の設定はすべてリセットされます。"
        override val searchConfigRestoreTitle = "既定のファイルに戻しますか？"
        override val searchConfigRestoreBody =
            "web-search.json をインストール直後の内容で置き換えます。入力した API キーと設定した項目は" +
                "すべてファイルから消え、ここから元に戻すことはできません。"
        override val searchConfigUnreadable =
            "web-search.json が JSON オブジェクトではないため、上のコントロールは隠しています。" +
                "このアプリが読めないファイルをもとに書き込んでしまうからです。手書きのファイルである" +
                "可能性も高いです。コメントと末尾の余分なカンマは問題ありませんが、括弧の閉じ忘れは別です。" +
                "ここまでの内容は変更していません。下のテキストを直すか置き換えて保存すれば、" +
                "このページは再び編集できます。"

        override val searchAdvanced = "ネットワーク"
        override val searchProxy = "プロキシ"
        override val searchProxySubtitle = "設定するとウェブアクセスのプロキシとして使われます"
        override val searchRestartNote = "変更は次にエージェントを起動したときに反映されます。"

    }

    override val manual = object : Strings.Manual {
        override val title = "ユーザーマニュアル"
        override val subtitle = "このアプリの使い方を最初から"
    }

    override val notes = object : Strings.Notes {
        override val licenceTitle = "ライセンス"
        override val licence =
            "PiKit は GNU 一般公衆利用許諾契約書 第3版 の下で配布される自由ソフトウェアです。" +
                "GPLv3 の Termux 実行環境と端末エミュレーターを同梱しているため、アプリ全体も GPLv3 です。" +
                "Pi コーディングエージェントとウェブアクセス拡張は MIT ライセンスです。\n" +
                "保証はなく、テレメトリーもありません。モデルプロバイダーへの、あなたが行ったリクエストと、" +
                "エージェント自身が実行する検索以外にデバイスから出るものはありません。"
        override val creditsTitle = "クレジット"
        override val credits = listOf(
            "実行環境・パッケージマネージャー・端末エミュレーター：Termux（GPLv3）。" +
                "その terminal-emulator と terminal-view がこのアプリの端末を構成しています",
            "JavaScript ランタイム：Node.js（MIT）",
            "エージェント：@earendil-works/pi-coding-agent（MIT）",
            "ウェブ検索・ページ読み込み・PDF 解析：Nico Bailon の pi-web-access（MIT）。" +
                "turndown、@mozilla/readability、defuddle、linkedom、unpdf、undici、p-limit、" +
                "typebox も含まれます",
            "エージェント用に同梱したコマンドラインツール：ripgrep（MIT/Unlicense）と " +
                "fd（MIT/Apache-2.0）",
            "インターフェース：Jetpack Compose と Material 3（Apache-2.0）、Kotlin と " +
                "kotlinx.serialization（Apache-2.0）、Okio（Apache-2.0）",
            "数式の組版：JLaTeXMath（Android 移植版 jlatexmath-android。" +
                "GPL-2.0、上流はリンク例外付き GPL-2.0）",
            "モデルのアイコン：Lucide（ISC）",
        ).joinToString("\n")
    }
    override val root = object : Strings.Root {
        override val preparing = "準備中"
        override val settingUp = "PiKit をセットアップ中"
        override val setupSubtitle = "初回起動のため実行環境を展開しています"
        override val setupNote = "これは一度だけ行われます。Termux、Node.js、Pi エージェントは APK から展開されるため、ダウンロードは不要です。"
        override val environmentIncomplete = "実行環境が不完全です"
        override val couldNotPrepare = "実行環境を準備できませんでした"
    }

    override val common = object : Strings.Common {
        override val ok = "OK"
        override val cancel = "キャンセル"
        override val back = "戻る"
        override val confirm = "OK"
        override val retry = "再試行"
    }
}
