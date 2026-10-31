package pi.kit.mob.data

/**
 * How much the agent may change, in three steps rather than two.
 *
 * ## Why three
 *
 * This app shipped one switch: PiKit's own tool-call guard, either in force or not, and
 * underneath it a scoped shared-storage grant. That is two answers to a question with
 * three useful ones — "let me look" is not "let me build" and neither is "do whatever
 * you want" — and the middle answer, which is what a coding agent is *for*, was the one
 * the app could not say. The three are the DeepSeek Harness's own tiers, taken whole
 * because the model's behaviour is trained around them: an agent told `read-only` reports
 * what it would have changed instead of trying, and an agent told `full-access` stops
 * asking permission for `npm install`.
 *
 * ## What enforces them
 *
 * Not this app: a tool call is the only place the agent's actions pass through, and the
 * app is not in that path. `tools/pikit-permissions.ts` — a pi extension the runtime
 * image carries and the installer copies into `$HOME/.pi/agent/extensions` — reads this
 * mode from a file on every `tool_call` and refuses the call. [code] is the wire value in
 * that file and the value a saved preference round-trips as; a build that knows fewer
 * modes than the file holds falls back to [DEFAULT] rather than guessing.
 *
 * The mode is *not* published through the environment, deliberately: an environment
 * variable is read once when pi loads, so every change would restart the agent process
 * and take the conversation with it. The file is read per call, which is what makes the
 * composer's control take effect on the very next thing the agent does.
 */
enum class PermissionMode(val code: String) {

    /** Inspection only: reads anywhere, changes nothing. */
    READ_ONLY("read-only"),

    /**
     * The default. Reads anywhere; writes only under the workspace — and under the
     * shared-storage folders the user granted on the storage page, because those
     * switches are a grant the user already gave folder by folder.
     */
    WORKSPACE_WRITE("workspace-write"),

    /** No refusal at all. The tier for someone who knows what they asked for. */
    FULL_ACCESS("full-access"),
    ;

    companion object {
        val DEFAULT = WORKSPACE_WRITE

        /** The mode [code] names, or [DEFAULT] when nothing or nobody names one. */
        fun fromCode(code: String?): PermissionMode =
            entries.firstOrNull { it.code == code } ?: DEFAULT

        /** Whether [mode] can be switched to from here without a restart — all of them. */
        val all: List<PermissionMode> get() = entries
    }
}
