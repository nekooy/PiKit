package pi.kit.mob.locales

/**
 * What the terminal prints when a shell opens.
 *
 * Termux's own banner is a welcome page: three links, a donate link, the upstream
 * version, and a summary of its package manager. On a phone that is most of a
 * screen of scrollback before the user can type, and every line of it describes
 * Termux rather than the thing they are holding — PiKit has no separate Termux to
 * document, and pi is already installed.
 *
 * What is left is three numbered parts, in the order a reader needs them:
 *
 * 1. **Common commands** — the handful that are non-obvious here.
 * 2. **What this shell can read and write** — the two paths, as two short
 *    paragraphs of ordinary prose: `/sdcard` is the phone's real storage and works
 *    whenever the app holds all-files access, and `~/storage` holds only the folders
 *    granted to it. They were a two-column list with the labels padded into a path
 *    column, which is a shape a reader has to decode; a sentence each needs no
 *    decoding, and the blank line between them is what keeps them two answers
 *    rather than one.
 * 3. **Disk space** — the fact a user is otherwise left to guess at: the agent
 *    downloads packages and files as it works, and that is normal.
 *
 * ## Three constraints, all measured
 *
 * **Width.** The terminal is about **48 columns** wide on a 1080px phone, and
 * `cat` does not wrap tidily — a long line turns into a two-line mess with the
 * tail against the margin, which is how the first version of this banner came out.
 * Every line below is **at most 48 columns**, counting a CJK glyph as two, and the
 * command column is padded to a fixed width so the descriptions line up.
 * `tools/check-locales.py` measures every line and is the check that keeps it so.
 *
 * **No shell expansion.** The text is written into a quoted heredoc
 * (`cat <<'PIKIT_MOTD'`) so no character in it needs escaping — which also means a
 * `$(...)` here would be printed literally rather than run. That is deliberate: a
 * `pi --version` would start a `node` process on every single shell.
 *
 * **One structure, three languages.** The numbered parts, the blank lines and the
 * paragraphs are the same in all three bodies; only the words differ, so the
 * banner reads as the same banner whichever language the interface is in.
 *
 * ## What was removed, and why it is not coming back
 *
 * The relocation paragraph ("packages are relocated as they install, so anything
 * from pkg or apt just runs") was the one line here that described something the
 * user never sees: a package that installs and runs is the expected case, and the
 * sentence turned a working default into a fact to remember. The sentence that
 * matters when it fails is in **Settings → Maintenance & repair**, next to the
 * button that fixes it, and in the manual.
 */
fun terminalBanner(lang: Lang): String = when (lang) {
    Lang.ENGLISH -> ENGLISH_BANNER
    Lang.CHINESE -> CHINESE_BANNER
    Lang.JAPANESE -> JAPANESE_BANNER
}

private val ENGLISH_BANNER = """
PiKit - Termux environment

1. Common commands

  pkg install <name>    install a package
  pkg search <query>    search for one
  pkg upgrade           upgrade everything
  termux-change-repo    switch mirrors
  termux-setup-storage  link ~/storage
  ls ~/storage          folders you granted
  pi                    the agent's interface

2. What this shell can read and write

/sdcard is the phone's real storage. This shell
reads and writes it whenever the app holds
all-files access.

~/storage holds links to the folders you have
granted.

3. Disk space

The agent downloads packages, libraries
and files while it works. They stay in the
app's own storage and can take up a lot of
space. That is normal.
""".trimIndent()

private val CHINESE_BANNER = """
PiKit - Termux 环境

1. 常用命令

  pkg install <包名>    安装软件包
  pkg search <关键字>   搜索
  pkg upgrade           升级全部
  termux-change-repo    换软件源
  termux-setup-storage  建 ~/storage
  ls ~/storage          已授权的文件夹
  pi                    Agent 界面

2. 终端能直接读写的手机路径

/sdcard 就是手机存储的真实路径；应用拿到「所
有文件访问权限」后，终端就能直接读写它。

~/storage 里只有你授权的那些文件夹的链接。

3. 占用存储的说明

Agent 工作时会下载软件包、依赖库或文件，
它们都留在应用内部，可能占用不少空间。
这是正常现象。
""".trimIndent()

private val JAPANESE_BANNER = """
PiKit - Termux 環境

1. よく使うコマンド

  pkg install <名前>    パッケージ導入
  pkg search <語>       検索
  pkg upgrade           すべて更新
  termux-change-repo    ミラー変更
  termux-setup-storage  ~/storage 作成
  ls ~/storage          許可したフォルダー
  pi                    エージェント画面

2. シェルが読み書きできる場所

/sdcard は端末の実際のストレージです。アプリ
が「すべてのファイルへのアクセス」を持って
いれば、シェルから直接読み書きできます。

~/storage には、許可したフォルダーへのリンク
だけが入ります。

3. ストレージの使用について

エージェントは作業中にパッケージやライブ
ラリ、ファイルをダウンロードします。それ
らはアプリ内に残り、多くの容量を使うこと
があります。異常ではありません。
""".trimIndent()
