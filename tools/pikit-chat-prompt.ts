/**
 * PiKit: chat mode's system prompt is exactly the document the reader wrote.
 *
 * ## Why this exists
 *
 * pi's own prompt builder never leaves a custom prompt alone. In 1.5.0
 * `buildSystemPrompt()` returns the `--system-prompt` text and then appends a
 * `<cwd>…</cwd>` block built from the process's working directory; a capture of a
 * chat-mode launch showed the model receiving
 *
 *     <the reader's one-line prompt>
 *
 *     <cwd>
 *     /data/user/0/pi.kit.mob/files/home/workspace
 *     </cwd>
 *
 * as one `developer` message. That block is true of a coding agent and wrong for a
 * plain conversation: it is a second piece of context the reader did not write.
 * And the obvious escape — an empty file — does not work, because pi's
 * `resolvePromptInput()` returns `undefined` for an empty input and
 * `buildSystemPrompt()` then falls back to the whole coding-assistant preamble.
 *
 * ## What it does instead
 *
 * `before_agent_start` may return `{ systemPrompt }`, and pi replaces the complete
 * prompt for that turn with it (docs/extensions.md, "before_agent_start"). The
 * extension reads the file the app writes immediately before the launch and
 * returns its text unchanged; an empty document returns `""`, which makes pi send
 * no system message at all. pi loads an explicit `--extension` even beside
 * `--no-extensions`, which is what lets chat mode keep every discovery door shut
 * and still load this one file.
 *
 * The file is written by `PiAgentSession.writeChatPrompt` and its path reaches the
 * process as `PIKIT_CHAT_PROMPT_FILE`. Without that variable the extension does
 * nothing, so a hand-run `pi` — or a work-mode launch, which never sets it — is
 * not touched by this file even if it is discovered.
 */

import { readFileSync } from "node:fs";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

/** The environment variable PiKit sets to the rendered chat prompt's path. */
export const CHAT_PROMPT_FILE_VAR = "PIKIT_CHAT_PROMPT_FILE";

export default function (pi: ExtensionAPI) {
  pi.on("before_agent_start", async () => {
    const file = process.env[CHAT_PROMPT_FILE_VAR];
    if (!file) return undefined;
    const text = readFileSync(file, "utf8");
    // `writeChatPrompt` writes one space for an empty template so that the
    // `--system-prompt` fallback cannot restore the coding preamble; the reader's
    // empty document is still empty, and must reach the model as no prompt at all.
    return { systemPrompt: text.trim().length === 0 ? "" : text };
  });
}
