package pi.kit.mob.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which agent a conversation starts: the work agent, or the plain chat agent.
 *
 * The mode belongs to a conversation: [SessionModeStore] keeps one record per saved
 * session, and [PiSettings.agentMode] only supplies the starting mode for a conversation
 * that has no file yet. It decides how the *next* process is launched; a conversation
 * that is already answering keeps the process it was born with until it is restarted.
 * [WORK] is the default and the behaviour every install had before chat mode existed,
 * so an install that never touches the switch keeps exactly the agent it had.
 *
 * [CHAT] is not the work agent with some switches turned off. It launches pi with
 * every source of ambient context disabled — tools, extensions, skills, prompt
 * templates, `AGENTS.md` — and replaces pi's own coding-assistant prompt with one
 * short document held in [PiSettings.chatSystemPrompt]. The point is a request whose
 * leading context is only that document, which is what makes the mode useful for an
 * ordinary conversation rather than a coding task. ARCHITECTURE §17 has the launch
 * flags and the prompt template's editor.
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
 * The token the chat-mode editor shows instead of a concrete timestamp.
 *
 * The editor edits the *template*, not one launch's answer: the same template is
 * rendered again on every launch, and moving this token is how the reader says where
 * in their prompt the time belongs. Keeping the token in the stored text is also what
 * keeps the editor from displaying a stale time that looks editable but is not.
 */
const val CHAT_TIME_PLACEHOLDER = "{{time}}"

/**
 * What chat mode sends before the reader has edited anything: only the current time.
 *
 * A bare placeholder is deliberate. It is the smallest prompt that is still a
 * prompt, it is the same in every interface language, and anything the reader wants
 * the model to be can be written around it without first deleting an example
 * paragraph that was never theirs.
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
