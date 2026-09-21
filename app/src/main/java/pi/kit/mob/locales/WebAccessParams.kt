package pi.kit.mob.locales

/**
 * The options the bundled `pi-web-access` extension reads, as the settings page's
 * commented document shows them.
 *
 * ## Why this is here and not in the three catalogs
 *
 * `Strings` is for interface text: a short label per control, completed by the
 * compiler. This is *prose about another program's configuration* — around eighty
 * keys, each with a shape, an example and a sentence — and it is written the way
 * `TerminalBanner.kt` and `ManualText*.kt` are, as one parallel structure whose
 * three languages sit beside each other. Prose is the documented exception to the
 * catalog rule, and it is the right one here: a key's description is only readable
 * next to the key it describes, and a missing translation should be visible in the
 * same hunk rather than three files away. `WebAccessParamsTest` pins the parity the
 * compiler cannot.
 *
 * ## What it is used for
 *
 * `pi/WebSearchStore.kt` renders `$HOME/.pi/agent/web-search.json` from this list:
 * the keys PiKit owns are written uncommented with the values in effect, and every
 * other key is written as a comment — its path, its description, and a usable
 * example — so the file *is* the documentation. `example` is therefore JSON text,
 * not a Kotlin value: it is spliced into the document verbatim, inside a `//`.
 *
 * ## The facts, and where they come from
 *
 * Every row is the extension **0.30.0** — the version the runtime image vendors
 * (`tools/build-runtime-image.py`'s `WEB_ACCESS_VERSION`, recorded in the image's
 * `build-metadata.json`) — read from its own source rather than from its README. The
 * keys were checked against 0.30.0's modules one by one when the pin moved: it adds
 * six (`serplyApiKey`, `fetch.defaultMode`, `fetch.allowedModes`,
 * `webSearch.allowedProviders`, `openaiUseProviderBaseUrl`, `openaiUseAlphaSearch`)
 * and removes none, which is why the rows below could grow rather than be rewritten.
 *
 * The provider, type and description of each row were taken from the extension's
 * per-feature modules, which each re-parse the file with their own partial
 * interface — `index.ts` alone names roughly half the keys, so it is not a schema
 * and was not used as one.
 *
 * ## What is deliberately not a row
 *
 * Nothing that the extension does not read. There is no `retrieval` block, no
 * `search` block, no `maxResults` and no top-level `enabled`; the fetched-content
 * cache (one hour, 128 entries, 128 MiB) and the concurrency limits are hard-coded
 * and have no key at all. A document that invented them would be worse than a short
 * one.
 *
 * The order of this list is the order of the rendered file, and `toolNames` and
 * `authFetch` are shown as one concrete example key each — `toolNames.webSearch`,
 * `authFetch.example.com` — rather than as the pattern (`toolNames.*`) their
 * descriptions are written in, because a path with a `*` in it cannot be a key in a
 * document a user is meant to uncomment.
 */

/**
 * One row: the JSON path, the shape of its value, an example of it, and what it
 * does.
 *
 * [notes] is keyed by language and every row carries all three; the fallback is
 * English, and `WebAccessParamsTest` is what fails when a row is missing one.
 */
internal class WebAccessParam(
    val path: String,
    val type: String,
    /** JSON text, spliced into the rendered document inside a comment. */
    val example: String,
    /** Visible so a test can prove every language is present; see the class note. */
    internal val notes: Map<Lang, String>,
) {
    fun note(lang: Lang): String = notes[lang] ?: notes[Lang.ENGLISH].orEmpty()
}

/** A row written once for all three languages. */
private fun p(
    path: String,
    type: String,
    example: String,
    en: String,
    zh: String,
    ja: String,
) = WebAccessParam(
    path = path,
    type = type,
    example = example,
    notes = mapOf(Lang.ENGLISH to en, Lang.CHINESE to zh, Lang.JAPANESE to ja),
)

/**
 * A `<service>ApiKey` row.
 *
 * The service name is the same in every language, so only the clause around it is
 * written three times — which is what keeps thirty credential rows from being
 * ninety near-identical sentences. The example is the `$NAME` reference form, which
 * the extension resolves from the environment and treats as *not configured* while
 * the variable is unset: a commented example that is also harmless if uncommented
 * by accident.
 */
private fun key(
    path: String,
    service: String,
    env: String,
    en: String = "API key.",
    // A space after the service name in both CJK languages: Latin text running
    // straight into a Han character or a kana particle is the one typographic
    // mistake mixed script makes easy, and this row is written thirty times.
    zh: String = " 的 API Key。",
    ja: String = " の API キー。",
) = WebAccessParam(
    path = path,
    type = "string",
    example = "\"\$$env\"",
    notes = mapOf(
        Lang.ENGLISH to "$service $en",
        Lang.CHINESE to "$service$zh",
        Lang.JAPANESE to "$service$ja",
    ),
)

/** An interface-address override for a service whose default is the vendor's. */
private fun endpoint(path: String, service: String, url: String) = WebAccessParam(
    path = path,
    type = "string",
    example = "\"$url\"",
    notes = mapOf(
        Lang.ENGLISH to "$service endpoint — a gateway or a self-hosted copy.",
        Lang.CHINESE to "$service 的接口地址，用于自建网关或自建服务。",
        Lang.JAPANESE to "$service の接続先。ゲートウェイや自前サーバー向け。",
    ),
)

/**
 * Every row, in the order the document is rendered: what a search is, then how a
 * page becomes text, then what a link becomes, then the switches, then the
 * workflow and the network, and the credentials last.
 */
internal val WEB_ACCESS_PARAMS: List<WebAccessParam> = listOf(

    // ------------------------------------------------------------ search
    p(
        "provider", "string | string[]", "\"auto\"",
        "The service that answers a search. An array searches several at once. This is " +
            "what the page's picker writes.",
        "执行搜索的服务。写数组可以同时搜索多个。本页上方的选择器写的就是它。",
        "検索を実行するサービス。配列にすると同時に複数検索します。ページ上部の選択が書き込む値です。",
    ),
    p(
        "searchProvider", "string | string[]", "\"exa\"",
        "An alias of `provider`. When both are present this one wins, so the picker above " +
            "cannot show or change the value in effect.",
        "`provider` 的别名。两者同时存在时以它为准，因此上方的选择器显示的将不是实际生效的值。",
        "`provider` の別名。両方あるとこちらが優先されるため、上部の選択では実際の値を表示・変更できません。",
    ),
    p(
        "searchModel", "string", "\"gemini-3.6-flash\"",
        "The model Gemini-API searches run on.",
        "走 Gemini 接口搜索时使用的模型。",
        "Gemini API 検索で使うモデル。",
    ),
    p(
        "searchRouting.providers", "string[]", "[\"exa\", \"brave\"]",
        "The candidates, in order. Used only while `provider` is unset.",
        "候选服务，按顺序尝试。只在没有设置 `provider` 时生效。",
        "候補を順番に並べます。`provider` 未設定のときだけ使われます。",
    ),
    p(
        "searchRouting.useCurrentModel", "boolean", "false",
        "Run the `openai` step through the model this conversation is already using.",
        "用当前对话所用的模型来执行其中的 openai 一步。",
        "`openai` の段を、この会話で使用中のモデルで実行します。",
    ),
    p(
        "searchRouting.fallbackOn", "string[]", "[\"transient\", \"quota\"]",
        "Which failures move on to the next candidate: `transient`, `quota`, `network`, " +
            "`invalid-response`, `unsupported`.",
        "哪些失败继续尝试下一个候选：transient、quota、network、invalid-response、unsupported。",
        "次へ進む失敗の種類：transient、quota、network、invalid-response、unsupported。",
    ),
    p(
        "summaryModel", "string", "\"anthropic/claude-haiku-4-5\"",
        "The model that writes summaries. A `:high` suffix sets its thinking level.",
        "写摘要的模型。可以加 `:high` 之类的思考等级后缀。",
        "要約を書くモデル。`:high` などの思考レベルを付けられます。",
    ),
    p(
        "summaryGenerationDeadlineMs", "number", "30000",
        "How long a summary may take, in milliseconds.",
        "生成一份摘要的最长耗时（毫秒）。",
        "要約生成の上限時間（ミリ秒）。",
    ),
    p(
        "curatorTimeoutSeconds", "number", "20",
        "How long the review page waits for an answer, in seconds. 60 when it is remote.",
        "审阅页面的等待秒数；开放给远程设备时为 60。",
        "レビュー画面の待ち時間（秒）。リモート時は 60。",
    ),

    // ------------------------------------------------------------ fetching
    p(
        "fetch.timeout", "number", "30",
        "How long one page fetch may take, in seconds.",
        "抓取单个网页的超时秒数。",
        "1 ページの取得にかけられる秒数。",
    ),
    p(
        "fetch.answerProvider", "string", "\"openai\"",
        "Answer a question instead of returning the page. Set together with " +
            "`fetch.answerModel`; one without the other is ignored.",
        "让抓取直接回答问题而不是返回正文。必须与 `fetch.answerModel` 成对设置，单独写会被忽略。",
        "ページを返さず質問に答えさせます。`fetch.answerModel` と対で設定し、片方だけでは無視されます。",
    ),
    p(
        "fetch.answerModel", "string", "\"gpt-5.6\"",
        "The model used for that answer.",
        "回答该问题所用的模型。",
        "その回答に使うモデル。",
    ),
    p(
        "fetch.defaultMode", "string", "\"readable\"",
        "Which mode `fetch_content` uses when a call does not name one: `readable` " +
            "(markdown), `raw` (the textual body over plain HTTP) or `answer`. It must be " +
            "one of `fetch.allowedModes`, or the extension refuses to start the tool.",
        "调用时未指定模式时 `fetch_content` 使用的模式：`readable`（Markdown 正文）、" +
            "`raw`（仅走 HTTP 取原始文本）或 `answer`。必须是 `fetch.allowedModes` 中的一项，" +
            "否则扩展会拒绝启用该工具。",
        "呼び出しでモードを指定しないとき `fetch_content` が使うモード。`readable`（Markdown）、" +
            "`raw`（HTTP のみで本文テキスト）、`answer`。`fetch.allowedModes` のいずれかである必要があり、" +
            "そうでないと拡張がツールの登録を拒否します。",
    ),
    p(
        "fetch.allowedModes", "string[]", "[\"readable\", \"raw\"]",
        "The modes the tool offers. Anything outside `readable`, `raw` and `answer` is " +
            "rejected, as is an empty list or a repeated entry — the document is checked " +
            "at load, and a mistake here disables `fetch_content` rather than one mode.",
        "该工具提供的模式。除 `readable`、`raw`、`answer` 之外的值会被拒绝，空数组或重复项也会。" +
            "读取时即校验：这里写错会让整个 `fetch_content` 不可用，而不只是某个模式。",
        "ツールが提供するモード。`readable`・`raw`・`answer` 以外、空配列、重複は拒否されます。" +
            "読み込み時に検証されるため、誤ると特定のモードではなく `fetch_content` 全体が使えなくなります。",
    ),
    p(
        "fetchRouting.providers", "string[]", "[\"http\", \"firecrawl\", \"jina\"]",
        "Extraction fallback order. The default is http, firecrawl, crawl4ai, jina, " +
            "tinyfish, search1api, querit, kagi, ollama, parallel, brightdata, gemini.",
        "正文提取的降级顺序，默认为 http、firecrawl、crawl4ai、jina、tinyfish、" +
            "search1api、querit、kagi、ollama、parallel、brightdata、gemini。",
        "本文抽出のフォールバック順。既定は http、firecrawl、crawl4ai、jina、tinyfish、" +
            "search1api、querit、kagi、ollama、parallel、brightdata、gemini。",
    ),
    p(
        "fetchRouting.allowRemoteHostedProviders", "boolean", "false",
        "Allow a remote site's contents to be sent to a third-party extractor.",
        "允许把外网站点的内容交给第三方抓取服务。",
        "外部サイトの内容を第三者の抽出サービスへ送ることを許可します。",
    ),
    p(
        "fetchContent.domainPolicy.allow", "string[]", "[\"example.com\"]",
        "Host names the fetch tool may read.",
        "允许抓取的主机名。",
        "取得を許可するホスト名。",
    ),
    p(
        "fetchContent.domainPolicy.deny", "string[]", "[\"blocked.example.com\"]",
        "Host names it may not. Deny wins over allow.",
        "禁止抓取的主机名，deny 优先于 allow。",
        "取得を禁止するホスト名。deny が allow に優先します。",
    ),
    p(
        "maxInlineContentChars", "number", "30000",
        "How much fetched text is handed to the model, up to 200000. This is the page's " +
            "own field.",
        "交给模型的抓取正文上限，最大 200000。就是本页的「内联内容上限」。",
        "モデルに渡す本文の上限（最大 200000）。ページの「インライン内容の上限」です。",
    ),

    // ------------------------------------------------------------ attachments
    p(
        "image.enabled", "boolean", "true",
        "Read images — screenshots, charts, diagrams — with the model.",
        "用模型读取图片（截图、图表、示意图）。",
        "画像（スクリーンショット・グラフ・図）をモデルで読みます。",
    ),
    p(
        "pdf.enabled", "boolean", "true",
        "Extract PDF files at all.",
        "是否允许解析 PDF。",
        "PDF 解析を行うかどうか。",
    ),
    p(
        "pdf.provider", "string", "\"auto\"",
        "`auto`, `datalab`, `gemini`, or `unpdf` for on-device extraction with no upload. " +
            "An unrecognised value falls back to auto.",
        "auto、datalab、gemini，或完全在本机解析、不上传的 unpdf。无法识别的值会退回 auto。",
        "auto、datalab、gemini、アップロードせず端末内で解析する unpdf。不明な値は auto になります。",
    ),
    p(
        "pdf.maxSizeMB", "number", "20",
        "Largest PDF accepted, in megabytes, up to 50.",
        "允许的 PDF 大小上限（MB，最大 50）。",
        "受け付ける PDF の最大サイズ（MB、上限 50）。",
    ),
    p(
        "pdf.maxPages", "number", "100",
        "Pages extracted per PDF.",
        "单个 PDF 解析的页数上限。",
        "1 つの PDF で解析するページ数。",
    ),
    p(
        "pdf.datalabMode", "string", "\"balanced\"",
        "Datalab's accuracy mode: `fast`, `balanced` or `accurate`.",
        "Datalab 的精度模式：fast、balanced、accurate。",
        "Datalab の精度モード：fast、balanced、accurate。",
    ),
    p(
        "pdf.datalabTimeoutMs", "number", "120000",
        "Datalab's own timeout, in milliseconds, up to 300000.",
        "Datalab 的超时毫秒数（最大 300000）。",
        "Datalab のタイムアウト（ミリ秒、最大 300000）。",
    ),
    p(
        "youtube.enabled", "boolean", "true",
        "Read a YouTube link's transcript instead of scraping the page.",
        "遇到 YouTube 链接时读取字幕，而不是抓取页面。",
        "YouTube リンクはページを読まず字幕を取得します。",
    ),
    p(
        "youtube.preferredModel", "string", "\"gemini-3.6-flash\"",
        "The model that reads the transcript.",
        "读取字幕所用的模型。",
        "字幕の読み取りに使うモデル。",
    ),
    p(
        "video.enabled", "boolean", "true",
        "Analyse a local video file.",
        "分析本机视频文件。",
        "ローカルの動画ファイルを解析します。",
    ),
    p(
        "video.preferredModel", "string", "\"gemini-3.6-flash\"",
        "The model that watches it.",
        "观看视频所用的模型。",
        "動画を見るモデル。",
    ),
    p(
        "video.maxSizeMB", "number", "50",
        "Largest video accepted, in megabytes.",
        "视频大小上限（MB）。",
        "動画の最大サイズ（MB）。",
    ),

    // ------------------------------------------------------------ GitHub
    p(
        "githubClone.enabled", "boolean", "true",
        "Clone a repository link instead of reading the page — what makes a " +
            "`github.com/owner/repo` link answerable.",
        "遇到仓库链接时改为克隆，而不是读页面；这是 `github.com/owner/repo` 能被读懂的原因。",
        "リポジトリのリンクはページではなく clone します。`github.com/owner/repo` を読めるのはこのためです。",
    ),
    p(
        "githubClone.maxRepoSizeMB", "number", "350",
        "Refuse to clone anything larger, in megabytes.",
        "超过该大小的仓库不克隆（MB）。",
        "これより大きいリポジトリは clone しません（MB）。",
    ),
    p(
        "githubClone.cloneTimeoutSeconds", "number", "30",
        "How long a clone may take, in seconds.",
        "克隆的超时秒数。",
        "clone のタイムアウト（秒）。",
    ),
    p(
        "githubClone.clonePath", "string", "\"/tmp/pi-github-repos\"",
        "Where clones are kept. They are temporary working copies.",
        "克隆的存放位置，属于临时工作副本。",
        "clone の保存先。一時的な作業コピーです。",
    ),
    p(
        "githubPrIssue.enabled", "boolean", "true",
        "Read a pull request or issue link as a conversation.",
        "把 PR / issue 链接读成讨论内容。",
        "PR・issue のリンクを会話として読みます。",
    ),

    // ------------------------------------------------------------ tools
    p(
        "webSearch.enabled", "boolean", "true",
        "The extension's older shorthand. Off unregisters only `web_search` and " +
            "`source_check` — the four keys below cover the rest. The page's master switch " +
            "writes all five together.",
        "扩展的旧写法。关闭只停用 web_search 与 source_check，其余四个由下面的键控制。" +
            "本页的联网访问开关会同时写这五个。",
        "拡張の旧形式。false で無効になるのは web_search と source_check だけで、残り 4 つは下のキーで決まります。" +
            "ページの主スイッチは 5 つをまとめて書き込みます。",
    ),
    p(
        "webSearch.allowedProviders", "string[]", "[\"exa\", \"tavily\"]",
        "Restrict which search services may be used at all: the picker's value, " +
            "`searchRouting.providers` and the Curator are all checked against this list, " +
            "and a list naming nothing is a rejected document.",
        "限定只能用哪些搜索服务：选择器的值、`searchRouting.providers` 以及 Curator 都会逐一" +
            "对照这个列表，列表为空会被拒绝。",
        "使用を許可する検索サービスを限定します。選択の値、`searchRouting.providers`、" +
            "Curator のすべてがこのリストと照合され、空のリストは拒否されます。",
    ),
    p("tools.webSearch.enabled", "boolean", "true", "The `web_search` tool.", "web_search 工具。", "web_search ツール。"),
    p("tools.sourceCheck.enabled", "boolean", "true", "The `source_check` tool.", "source_check 工具。", "source_check ツール。"),
    p("tools.fetchContent.enabled", "boolean", "true", "The `fetch_content` tool.", "fetch_content 工具。", "fetch_content ツール。"),
    p(
        "tools.getSearchContent.enabled", "boolean", "true",
        "The `get_search_content` tool, which reads back a search's full results.",
        "get_search_content 工具，用来读回某次搜索的完整结果。",
        "get_search_content ツール。検索結果の全文を読み戻します。",
    ),
    p(
        "toolNames.webSearch", "string", "\"web_search\"",
        "Rename a tool — one key per tool, named after it. The name must match " +
            "`^[A-Za-z][A-Za-z0-9_-]{0,63}$`.",
        "给工具改名，每个工具一个键，键名就是工具名。需匹配 `^[A-Za-z][A-Za-z0-9_-]{0,63}$`。",
        "ツール名の変更（ツールごとに 1 キー）。`^[A-Za-z][A-Za-z0-9_-]{0,63}$` に一致する必要があります。",
    ),
    p("commands.websearch.enabled", "boolean", "true", "The `/websearch` command.", "/websearch 命令。", "/websearch コマンド。"),
    p("commands.curator.enabled", "boolean", "true", "The `/curator` command.", "/curator 命令。", "/curator コマンド。"),
    p("commands.search.enabled", "boolean", "true", "The `/search` command.", "/search 命令。", "/search コマンド。"),
    p(
        "commands.google-account.enabled", "boolean", "true",
        "The `/google-account` command, for Gemini through a Google account.",
        "/google-account 命令，用于用 Google 账号访问 Gemini。",
        "/google-account コマンド。Google アカウント経由の Gemini 用です。",
    ),

    // ------------------------------------------------------------ workflow
    p(
        "workflow", "string", "\"none\"",
        "`none` returns the results, `auto-summary` summarises them for the model, " +
            "`summary-review` opens the curator page for you to check each one. PiKit " +
            "writes `none`; the extension's own default is `summary-review`, which needs a " +
            "browser. This is the page's own picker.",
        "none 原样返回结果，auto-summary 先自动摘要，summary-review 打开审阅页让你逐条确认。" +
            "PiKit 写入的是 none；扩展自己的默认值是 summary-review，需要浏览器。就是本页的「搜索流程」。",
        "none は結果をそのまま、auto-summary は要約してから、summary-review はレビュー画面で確認。" +
            "PiKit が書き込むのは none です。拡張自身の既定は summary-review で、ブラウザーが必要です。",
    ),
    p(
        "autoOpenBrowser", "boolean", "true",
        "Open the curator page without being asked. Off by default for a remote curator.",
        "自动打开审阅页面；开放给远程设备时默认关闭。",
        "レビュー画面を自動で開きます。リモート公開時は既定でオフ。",
    ),
    p(
        "curatorRemote", "boolean | object", "{\"host\": \"my-box.ts.net\", \"bind\": \"100.101.102.103\"}",
        "Serve the curator to another machine. `{host, bind}` chooses the name and the " +
            "address it listens on.",
        "把审阅页面开放给其他设备。`{host, bind}` 指定访问名与监听地址。",
        "レビュー画面を他端末へ公開します。`{host, bind}` で名前と待ち受け先を指定。",
    ),
    p(
        "allowBrowserCookies", "boolean", "false",
        "Let the curator reuse a desktop browser's cookies for a site.",
        "允许审阅页复用桌面浏览器在该站点的 Cookie。",
        "レビュー画面でデスクトップブラウザの Cookie を再利用します。",
    ),
    p(
        "browserCookies.browser", "string", "\"helium\"",
        "Which browser to borrow from: helium, chrome, brave, arc, chromium or edge.",
        "借用哪个浏览器：helium、chrome、brave、arc、chromium 或 edge。",
        "借用するブラウザ：helium、chrome、brave、arc、chromium、edge。",
    ),
    p(
        "browserCookies.profile", "string", "\"Profile 2\"",
        "The browser profile directory to read.",
        "要读取的浏览器配置目录名。",
        "読み取るブラウザのプロファイル名。",
    ),
    p(
        "shortcuts.curate", "string", "\"ctrl+shift+s\"",
        "Keyboard shortcut for the desktop curator command.",
        "桌面端审阅命令的快捷键。",
        "デスクトップのレビューコマンドのショートカット。",
    ),
    p(
        "shortcuts.activity", "string", "\"ctrl+shift+w\"",
        "Keyboard shortcut for the desktop activity log.",
        "桌面端活动日志的快捷键。",
        "デスクトップのアクティビティログのショートカット。",
    ),

    // ------------------------------------------------------------ network
    p(
        "proxy", "string", "\"http://127.0.0.1:8080\"",
        "Send every request through this proxy. http, https, socks4, socks4a, socks5 and " +
            "socks5h are accepted. This is the page's own field.",
        "所有请求走这个代理，支持 http、https、socks4、socks4a、socks5、socks5h。" +
            "就是本页「网络」里的代理。",
        "すべてのリクエストをこのプロキシ経由にします。http、https、socks4、socks4a、" +
            "socks5、socks5h に対応。ページの「ネットワーク」の項目です。",
    ),
    p(
        "ssrf.allowRanges", "string[]", "[\"198.18.0.0/15\"]",
        "Extra CIDR ranges a fetch may reach — for a service on your own network.",
        "允许抓取到的额外 CIDR 网段，用于自己局域网内的服务。",
        "取得を許可する追加の CIDR。自宅ネットワーク内のサービス向け。",
    ),
    p(
        "ssrf.trustEnvProxy", "boolean", "false",
        "Trust the configured proxy's own address checks instead of repeating them.",
        "信任代理自身的地址检查，不再重复检查。",
        "プロキシ側のアドレス検査を信頼し、こちらでは繰り返しません。",
    ),
    p(
        "authFetch.example.com", "object", "{\"hosts\": [\"example.com\"]}",
        "Reuse a desktop browser's login for one host — one key per host, named after it. " +
            "A host list, or `{hosts, chromeProfile, redirects, cache}`.",
        "对某个站点复用桌面浏览器的登录状态：每个站点一个键，键名就是主机名。" +
            "可以是主机名数组，或 `{hosts, chromeProfile, redirects, cache}`。",
        "特定ホストでデスクトップのログインを再利用します（ホストごとに 1 キー）。" +
            "ホスト配列か `{hosts, chromeProfile, redirects, cache}`。",
    ),

    // ------------------------------------------------------------ credentials
    key("openaiApiKey", "OpenAI", "OPENAI_API_KEY", "key, used for Responses web search."),
    endpoint("openaiResponsesUrl", "OpenAI Responses", "https://api.openai.com/v1/responses"),
    p(
        "openaiSearchModel", "string", "\"gpt-5.6\"",
        "Pin the OpenAI search model instead of letting the newest one be chosen.",
        "固定使用的 OpenAI 搜索模型，不写则由扩展选择最新的。",
        "OpenAI の検索モデルを固定します。未指定なら最新が選ばれます。",
    ),
    p(
        "openaiSearchProviders", "string[]", "[\"openai-codex\", \"openai\"]",
        "Which Pi model providers OpenAI credentials may be read from, in order. An empty " +
            "array skips them so the key above is used.",
        "从哪些 Pi 模型供应商读取 OpenAI 凭据，按顺序尝试。写空数组则跳过它们，只用上面的 Key。",
        "OpenAI の資格情報を読む Pi モデルプロバイダー（順番）。空配列にすると上のキーだけを使います。",
    ),
    p(
        "openaiUseProviderBaseUrl", "boolean", "false",
        "Reuse a Pi provider's own URL and credentials for OpenAI search, so a gateway " +
            "does not have to be configured twice. `openaiResponsesUrl` above still wins " +
            "when both are set.",
        "复用某个 Pi 供应商的接口地址与凭据来做 OpenAI 搜索，网关不必配置两遍。" +
            "同时设置时，上面的 `openaiResponsesUrl` 优先。",
        "OpenAI 検索で Pi プロバイダーの URL と資格情報を再利用し、ゲートウェイの二重設定を避けます。" +
            "両方ある場合は上の `openaiResponsesUrl` が優先されます。",
    ),
    p(
        "openaiUseAlphaSearch", "boolean", "false",
        "Use standalone OpenAI search instead of Responses search — and *only* then do " +
            "the source limit, recency and allowed-domain options apply. Responses " +
            "remains the default.",
        "改用独立的 OpenAI 搜索，而不是 Responses 搜索；只有在这种模式下，来源数量、" +
            "时间范围、允许域名等选项才生效。默认仍然是 Responses。",
        "Responses 検索ではなく単体の OpenAI 検索を使います。出典数・期間・許可ドメインの" +
            "指定が効くのはこのモードだけです。既定は Responses のままです。",
    ),
    key("braveApiKey", "Brave Search", "BRAVE_API_KEY"),
    endpoint("braveBaseUrl", "Brave Search", "https://api.search.brave.com"),
    key("tavilyApiKey", "Tavily", "TAVILY_API_KEY"),
    endpoint("tavilyBaseUrl", "Tavily", "https://api.tavily.com"),
    key("exaApiKey", "Exa", "EXA_API_KEY", "key. Without it Exa's keyless endpoint is used, with a rate limit."),
    endpoint("exaBaseUrl", "Exa", "https://api.exa.ai"),
    key("jinaApiKey", "Jina", "JINA_API_KEY", "key, for both Jina Search and Jina Reader."),
    key("firecrawlApiKey", "Firecrawl", "FIRECRAWL_API_KEY"),
    endpoint("firecrawlBaseUrl", "Firecrawl", "https://api.firecrawl.dev"),
    p(
        "firecrawlApiVersion", "string", "\"v2\"",
        "`v1` or `v2`. Only relevant for a self-hosted Firecrawl.",
        "v1 或 v2，只在自建 Firecrawl 时需要。",
        "v1 または v2。自前の Firecrawl でのみ意味があります。",
    ),
    p(
        "firecrawlFreshScrape", "boolean", "false",
        "Allow scrapes that are not in lockdown mode.",
        "允许非 lockdown 模式的抓取。",
        "lockdown モード以外のスクレイプを許可します。",
    ),
    endpoint("crawl4aiBaseUrl", "Crawl4AI", "https://crawl4ai.example.com"),
    key(
        "crawl4aiApiToken", "Crawl4AI", "CRAWL4AI_API_TOKEN",
        "token — this field is `Token`, not `ApiKey`.",
        "的 Token——这个字段是 `ApiToken`，不是 `ApiKey`。",
        "のトークン。この欄は `ApiToken` で `ApiKey` ではありません。",
    ),
    key("kagiApiKey", "Kagi", "KAGI_API_KEY", "key, for Kagi Search and Extract."),
    key("serperApiKey", "Serper", "SERPER_API_KEY"),
    key("serpapiApiKey", "SerpApi", "SERPAPI_KEY"),
    key("serpbaseApiKey", "SerpBase", "SERPBASE_API_KEY"),
    key("serplyApiKey", "Serply", "SERPLY_API_KEY", "key, for Google results through Serply."),
    key("valyuApiKey", "Valyu", "VALYU_API_KEY"),
    key("bochaApiKey", "Bocha", "BOCHA_API_KEY"),
    key("queritApiKey", "Querit", "QUERIT_API_KEY"),
    key("search1apiApiKey", "Search1API", "SEARCH1API_KEY"),
    key("searchinfinityApiKey", "Searchinfinity", "SEARCHINFINITY_API_KEY"),
    key("tinyfishApiKey", "TinyFish", "TINYFISH_API_KEY"),
    key("parallelApiKey", "Parallel", "PARALLEL_API_KEY", "key. The same key is also used for `parallel-mcp`."),
    key("anysearchApiKey", "AnySearch", "ANYSEARCH_API_KEY"),
    key("xcrawlApiKey", "XCrawl", "XCRAWL_API_KEY"),
    key("brightdataApiKey", "Bright Data", "BRIGHTDATA_API_KEY"),
    p(
        "brightdataSerpZone", "string", "\"pi_serp\"",
        "The `serp` zone name. Bright Data needs a zone, not only a key — this one has no " +
            "default.",
        "serp 类型的 Zone 名称。Bright Data 除了 Key 还必须有 Zone，这一项没有默认值。",
        "`serp` タイプの Zone 名。Bright Data はキーだけでなく Zone が必要で、既定値はありません。",
    ),
    p(
        "brightdataUnlockerZone", "string", "\"pi_unlocker\"",
        "The `unblocker` zone name, for reading pages.",
        "unblocker 类型的 Zone 名称，用于读取网页。",
        "`unblocker` タイプの Zone 名。ページ取得に使います。",
    ),
    key("serpdiveApiKey", "SERPdive", "SERPDIVE_API_KEY"),
    p(
        "serpdiveModel", "string", "\"krill\"",
        "Retrieval depth: `krill`, `mako` or `moby`. An unknown value falls back to krill.",
        "检索深度：krill、mako 或 moby；无法识别的值退回 krill。",
        "検索の深さ：krill、mako、moby。不明な値は krill になります。",
    ),
    key("xaiApiKey", "xAI", "XAI_API_KEY"),
    p(
        "xaiSearchModel", "string", "\"grok-4\"",
        "Pin the xAI model instead of letting one be chosen.",
        "固定使用的 xAI 模型，不写则由扩展选择。",
        "xAI のモデルを固定します。未指定なら自動選択。",
    ),
    p(
        "xaiSearchTools", "string[]", "[\"web_search\"]",
        "`web_search`, `x_search`, or both. `[\"x_search\"]` searches X only.",
        "web_search、x_search 或两者；写 `[\"x_search\"]` 表示只搜 X。",
        "web_search、x_search、または両方。`[\"x_search\"]` なら X のみ。",
    ),
    key("mistralApiKey", "Mistral", "MISTRAL_API_KEY"),
    p(
        "mistralSearchModel", "string", "\"mistral-small-latest\"",
        "The Mistral model used for search.",
        "搜索所用的 Mistral 模型。",
        "検索に使う Mistral モデル。",
    ),
    p(
        "mistralSearchTool", "string", "\"web_search\"",
        "`web_search` or the billable `web_search_premium`.",
        "web_search 或需要额外计费的 web_search_premium。",
        "web_search または課金対象の web_search_premium。",
    ),
    key("ollamaApiKey", "Ollama Cloud", "OLLAMA_API_KEY"),
    key("perplexityApiKey", "Perplexity", "PERPLEXITY_API_KEY", "key. The extension also applies its own rate cap."),
    key("cloudflareApiKey", "Cloudflare Workers AI", "CLOUDFLARE_API_KEY"),
    key("datalabApiKey", "Datalab", "DATALAB_API_KEY", "key, for hosted PDF extraction."),
    key("geminiApiKey", "Google Gemini", "GEMINI_API_KEY"),
    endpoint("geminiBaseUrl", "Gemini", "https://generativelanguage.googleapis.com"),
    p(
        "geminiAuth", "string", "\"adc\"",
        "Set to `adc` to authenticate through Google Cloud instead of an API key.",
        "写 `adc` 表示改用 Google Cloud 的应用默认凭据，而不是 API Key。",
        "`adc` を指定すると API キーではなく Google Cloud の既定資格情報を使います。",
    ),
    p(
        "geminiProject", "string", "\"my-gcp-project\"",
        "The Google Cloud project, for `adc`.",
        "使用 adc 时的 Google Cloud 项目。",
        "`adc` を使うときの Google Cloud プロジェクト。",
    ),
    p(
        "geminiLocation", "string", "\"us-central1\"",
        "The Google Cloud region, for `adc`.",
        "使用 adc 时的 Google Cloud 区域。",
        "`adc` を使うときの Google Cloud リージョン。",
    ),
    p(
        "searxngBaseUrl", "string", "\"https://search.example.com\"",
        "Your own SearXNG instance. http or https, no credentials in the URL, and a " +
            "trailing slash is ignored.",
        "自建 SearXNG 实例的地址。必须是 http 或 https，不能带用户名密码，末尾斜杠会被忽略。",
        "自前の SearXNG のアドレス。http か https のみ、認証情報は不可、末尾のスラッシュは無視されます。",
    ),
    p(
        "searxngHeaders", "object", "{\"Authorization\": \"Bearer \$SEARXNG_TOKEN\"}",
        "Extra headers for that instance, sent only to your own server. Used behind a " +
            "reverse proxy or with an instance that wants a token.",
        "发给该实例的额外请求头，只会发给你自己的服务器。用于反向代理之后或需要令牌的实例。",
        "そのインスタンスへ送る追加ヘッダー。自分のサーバーにのみ送信されます。" +
            "リバースプロキシ配下やトークン必須のインスタンス向け。",
    ),
)
