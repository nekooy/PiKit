package pi.kit.mob.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which agent a conversation starts: the work agent, or the plain chat agent.
 *
 * The mode belongs to a conversation: [SessionModeStore] keeps one record per saved
 * session, and a conversation that has no file yet starts in
 * [NewConversationMode.startingMode] of [PiSettings.agentMode] — the mode the last one had,
 * unless the personalization page pins it. It decides how the *next* process is launched;
 * a conversation that is already answering keeps the process it was born with until it is
 * restarted.
 * [WORK] is the default and the behaviour every install had before chat mode existed,
 * so an install that never touches the switch keeps exactly the agent it had.
 *
 * [CHAT] is not the work agent with some switches turned off. It launches pi with
 * every source of ambient context disabled — tools, extensions, skills, prompt
 * templates, `AGENTS.md` — and replaces pi's own coding-assistant prompt with one
 * short document: the conversation's [ChatPreset]. The point is a request whose
 * leading context is only that document — and an empty document is no leading context
 * at all. The one exception the reader controls is the two network tools, lent by the
 * conversation's own switch. ARCHITECTURE §17 has the launch flags, the presets and the
 * measured request.
 */
enum class AgentMode(val code: String) {
    WORK("work"),
    CHAT("chat"),
    ;

    companion object {
        val DEFAULT = WORK

        /** A stored mode, or [DEFAULT] for a missing or unknown value. */
        fun fromCode(code: String?): AgentMode =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * What a conversation with **no session file yet** opens as.
 *
 * A saved conversation's mode is [SessionModeStore]'s answer and this preference never
 * touches it: the row only fills the gap for a conversation that has not written a file,
 * which is the one case with no record to read. [FOLLOW_LAST] is what the app did before
 * the row existed — a new conversation took the mode of the one last on screen, which
 * `PiHost.activate` and `switchMode` keep in [PiSettings.agentMode] — and it stays the
 * default so an install that never opens the row behaves exactly as it did.
 *
 * [WORK] and [CHAT] pin it instead. The two habits this answers are real: a reader who
 * translates something in chat mode every morning otherwise has to flip the drawer's
 * switch back after each conversation opened in work mode, and a reader who never wants
 * chat mode in the first place should not have a stray tap change what the next `+`
 * creates.
 *
 * The drawer's foot switch **overrides** this, because that control's whole meaning is
 * "open one in *this* mode": it applies the tapped mode to the conversation it creates
 * (`PiKitRoot`'s `openNew`). This is only the answer when nothing names a mode.
 */
enum class NewConversationMode(val code: String) {
    /** The mode of the conversation last on screen — every install's behaviour before the row. */
    FOLLOW_LAST("follow-last"),

    WORK("work"),
    CHAT("chat"),
    ;

    /**
     * The mode a conversation with no file starts in, given the mode of [last].
     *
     * The one rule, so the reader of the preference (`PiAgentSession`'s initial mode) and
     * its test cannot disagree about what "default" means.
     */
    fun startingMode(last: AgentMode): AgentMode = when (this) {
        FOLLOW_LAST -> last
        WORK -> AgentMode.WORK
        CHAT -> AgentMode.CHAT
    }

    companion object {
        val DEFAULT = FOLLOW_LAST

        /** A stored choice, or [DEFAULT] for a missing or unknown value. */
        fun fromCode(code: String?): NewConversationMode =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * The token the chat-mode editor shows instead of a concrete timestamp.
 *
 * The editor edits the *template*, not one launch's answer: the same template is
 * rendered again on every launch, and moving this token is how the reader says where
 * in their prompt the time belongs. Keeping the token in the stored text is also what
 * keeps the editor from displaying a stale time that looks editable but is not.
 */
const val CHAT_TIME_PLACEHOLDER = "{{time}}"

/**
 * What chat mode sends before the reader has edited anything: the current time.
 *
 * A bare placeholder is deliberate. It is the smallest useful prompt — the model
 * knows "now" without being told who it is — and anything the reader wants can be
 * written around it without first deleting an example paragraph that was never
 * theirs. An empty document is still a valid choice and sends no system prompt at
 * all; the extension is what makes that empty case clean (ARCHITECTURE §17).
 */
const val DEFAULT_CHAT_SYSTEM_PROMPT = CHAT_TIME_PLACEHOLDER

/**
 * The template with every [CHAT_TIME_PLACEHOLDER] replaced by [now] in [zone].
 *
 * `yyyy-MM-dd HH:mm:ss XXX` keeps the date, the time and the offset on one line: a
 * model reading a timestamp needs the offset to know what "now" means, and a phone
 * that has travelled is in a different zone from the one it was bought in. Every
 * occurrence is replaced, so a reader who mentions the time twice gets the same
 * instant twice rather than one real time and one literal token.
 */
fun renderChatSystemPrompt(
    template: String,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = template.replace(
    CHAT_TIME_PLACEHOLDER,
    CHAT_TIME_FORMAT.format(now.atZone(zone)),
)

private val CHAT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT)
