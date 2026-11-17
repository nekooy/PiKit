/**
 * Pins the chat prompt extension's three answers.
 *
 * The extension is what makes chat mode's promise true: pi appends a `<cwd>` block to
 * any `--system-prompt` and falls back to its coding prompt for an empty one. This
 * test drives the extension's `before_agent_start` with a fake pi API and checks that
 * it is inert without the environment variable, returns the document exactly with it,
 * and turns an empty document into the empty string that sends no system message.
 *
 * Run from the repository root:
 *
 *     node tools/test-chat-prompt.mjs
 */

import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import extension, { CHAT_PROMPT_FILE_VAR } from "./pikit-chat-prompt.ts";

const handlers = new Map();
extension({
  on(event, handler) {
    handlers.set(event, handler);
  },
});

const handler = handlers.get("before_agent_start");
assert.ok(handler, "the extension must register before_agent_start");

const directory = mkdtempSync(join(tmpdir(), "pikit-chat-prompt-"));
try {
  const prompt = join(directory, "prompt.txt");
  const empty = join(directory, "empty.txt");
  writeFileSync(prompt, "You are a plain conversation.\n");
  writeFileSync(empty, " ");

  delete process.env[CHAT_PROMPT_FILE_VAR];
  assert.equal(await handler({}), undefined, "without the variable it must do nothing");

  process.env[CHAT_PROMPT_FILE_VAR] = prompt;
  assert.deepEqual(
    await handler({}),
    { systemPrompt: "You are a plain conversation.\n" },
    "the document must arrive unchanged",
  );

  process.env[CHAT_PROMPT_FILE_VAR] = empty;
  assert.deepEqual(
    await handler({}),
    { systemPrompt: "" },
    "a blank document must become no system prompt at all",
  );

  console.log("chat prompt extension: exact, empty, inert");
} finally {
  delete process.env[CHAT_PROMPT_FILE_VAR];
  rmSync(directory, { recursive: true, force: true });
}
