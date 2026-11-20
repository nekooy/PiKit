/**
 * The audit of chat mode's request: what the model is actually sent.
 *
 * Chat mode makes one promise — the leading context is the reader's preset and nothing
 * else — and one exception the reader controls: the two network tools, when the
 * conversation's switch is on. Both are *launch flags*, and the only way to know what pi
 * does with a flag is to give it to pi. This script therefore runs the vendored pi
 * itself, against a local OpenAI-compatible endpoint that records the request, and
 * asserts on the body:
 *
 *  - **switch off** — no tool is declared, and the only system message is the preset's
 *    text, byte for byte. No `<cwd>` block, no `AGENTS.md`, no skills, no pi preamble.
 *  - **switch on** — the declared tools are exactly `web_search` and `web_fetch`
 *    (the extension registers two; the allowlist is the promise), and the system text
 *    is still only the preset.
 *
 * That is the measurement ARCHITECTURE §17 records. It is a process test rather than a
 * unit test by necessity: the flags are pi's interface, and a fixture that agreed with
 * our reading of them is the exact mistake this file exists to catch.
 *
 * It uses the build cache's vendored pi and this repository's own web extension, so it
 * skips cleanly on a machine that has not built a runtime image. Run from the
 * repository root:
 *
 *     node --experimental-strip-types tools/test-chat-context.mjs
 */

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const PI_ROOT = join(ROOT, ".runtime-build", "cache", "pi");
const CLI = join(PI_ROOT, "node_modules", "@earendil-works", "pi-coding-agent", "dist", "bundle", "cli.js");
const WEB_EXTENSION = join(ROOT, "tools", "pikit-web.ts");
const CHAT_PROMPT_EXTENSION = join(ROOT, "tools", "pikit-chat-prompt.ts");

if (!existsSync(CLI) || !existsSync(WEB_EXTENSION) || !existsSync(CHAT_PROMPT_EXTENSION)) {
  console.log("chat context: skipped (no vendored pi / web extension in .runtime-build)");
  process.exit(0);
}

const MARKER = "PRESET-MARKER: answer as the preset says.\n";

/** One recorded request body, with the bits the audit cares about. */
function requestShape(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const text = (message) => {
    if (typeof message.content === "string") return message.content;
    if (Array.isArray(message.content)) {
      return message.content.map((part) => part?.text ?? "").join("");
    }
    return "";
  };
  return {
    system: messages
      .filter((message) => message.role === "system" || message.role === "developer")
      .map(text),
    user: messages.filter((message) => message.role === "user").map(text),
    tools: Array.isArray(body.tools) ? body.tools.map((tool) => tool?.function?.name ?? tool?.name) : [],
    raw: JSON.stringify(body),
  };
}

/** Answers every chat completion with a short streamed reply and records the request. */
function startMock() {
  const requests = [];
  const server = createServer((request, response) => {
    let raw = "";
    request.on("data", (chunk) => {
      raw += chunk;
    });
    request.on("end", () => {
      let body = {};
      try {
        body = JSON.parse(raw);
      } catch {
        // A non-JSON probe (a health check or a models list) is answered empty.
      }
      if (!request.url.includes("/chat/completions")) {
        response.writeHead(200, { "Content-Type": "application/json" });
        response.end(JSON.stringify({ object: "list", data: [] }));
        return;
      }
      requests.push(requestShape(body));
      const chunk = (delta, finish, usage) =>
        `data: ${JSON.stringify({
          id: "chatcmpl-pikit-test",
          object: "chat.completion.chunk",
          created: Math.floor(Date.now() / 1000),
          model: body.model ?? "mock",
          choices: [{ index: 0, delta, finish_reason: finish }],
          ...(usage ? { usage } : {}),
        })}\n\n`;
      if (body.stream === false) {
        response.writeHead(200, { "Content-Type": "application/json" });
        response.end(
          JSON.stringify({
            id: "chatcmpl-pikit-test",
            object: "chat.completion",
            created: Math.floor(Date.now() / 1000),
            model: body.model ?? "mock",
            choices: [
              {
                index: 0,
                message: { role: "assistant", content: "pong" },
                finish_reason: "stop",
              },
            ],
            usage: { prompt_tokens: 20, completion_tokens: 1, total_tokens: 21 },
          }),
        );
        return;
      }
      response.writeHead(200, {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        Connection: "keep-alive",
      });
      response.write(chunk({ role: "assistant", content: "pong" }, null));
      response.write(
        chunk({}, "stop", { prompt_tokens: 20, completion_tokens: 1, total_tokens: 21 }),
      );
      response.write("data: [DONE]\n\n");
      response.end();
    });
  });
  return new Promise((resolveServer, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      resolveServer({ server, requests, port: server.address().port });
    });
  });
}

/** A directory shape that makes the extension's imports resolve. */
function stageExtension(directory) {
  // `pikit-web.ts` imports only Node built-ins at runtime; the type import from
  // pi's package is erased by jiti. Its path is therefore used directly.
  return WEB_EXTENSION;
}

/** Runs one launch and resolves with the recorded request for the test prompt. */
function runLaunch({ directory, port, webAccess }) {
  const agentDir = join(directory, "agent");
  const home = join(directory, "home");
  const sessionDir = join(directory, "sessions");
  const workspace = join(directory, "workspace");
  for (const path of [agentDir, home, sessionDir, workspace]) mkdirSync(path, { recursive: true });

  writeFileSync(
    join(agentDir, "models.json"),
    JSON.stringify({
      providers: {
        "pikit-mock": {
          name: "pikit-mock",
          api: "openai-completions",
          baseUrl: `http://127.0.0.1:${port}/v1`,
          apiKey: "sk-pikit-test",
          models: [
            {
              id: "mock",
              name: "mock",
              input: ["text"],
              contextWindow: 131072,
              maxTokens: 4096,
              cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
            },
          ],
        },
      },
    }),
  );

  const promptFile = join(directory, "prompt.txt");
  writeFileSync(promptFile, MARKER);
  // PiKit writes pi's `settings.json` before every launch, chat ones included: the
  // seven built-in `defaultTools`, and the bundled extension as a package. Both are
  // ambient context chat mode must override rather than inherit — this is the pair the
  // audit would miss if it only passed CLI flags to an empty agent directory.
  writeFileSync(
    join(agentDir, "settings.json"),
    JSON.stringify({
      defaultTools: ["read", "bash", "edit", "write", "grep", "find", "ls"],
      packages: [],
    }),
  );
  // A file named AGENTS.md where a work launch would read it: if chat mode leaked it,
  // the request would carry its text and the assertion below would say so.
  writeFileSync(join(workspace, "AGENTS.md"), "LEAKED-AGENTS-MD");

  const extension = stageExtension(directory);
  const args = [
    CLI,
    "--mode", "rpc",
    "--approve",
    "--no-extensions",
    "--no-skills",
    "--no-prompt-templates",
    "--no-context-files",
    "--no-mcp",
  ];
  if (webAccess) {
    args.push("--tools", "web_search,web_fetch", "--extension", extension);
  } else {
    args.push("--no-tools");
  }
  args.push(
    "--extension", CHAT_PROMPT_EXTENSION,
    "--system-prompt", promptFile,
    "--provider", "pikit-mock",
    "--model", "mock",
    "--api-key", "sk-pikit-test",
    "--session-dir", sessionDir,
  );

  return new Promise((resolveRun, reject) => {
    const child = spawn(process.execPath, args, {
      cwd: workspace,
      env: {
        ...process.env,
        HOME: home,
        PI_CODING_AGENT_DIR: agentDir,
        PIKIT_CHAT_PROMPT_FILE: promptFile,
        PIKIT_WORKSPACE: workspace,
      },
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => finish(new Error(`pi did not settle; stderr:\n${stderr}`)), 60_000);

    function finish(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        child.stdin.end();
      } catch {
        // Already closed.
      }
      setTimeout(() => {
        try {
          child.kill();
        } catch {
          // Already gone.
        }
      }, 2_000).unref();
      if (error) reject(error);
      else resolveRun();
    }

    child.stdout.on("data", (chunk) => {
      stdout += chunk;
      for (const line of stdout.split("\n")) {
        if (!line.trim()) continue;
        try {
          const record = JSON.parse(line);
          if (record.type === "agent_settled") finish();
        } catch {
          // A partial line; the next chunk completes it.
        }
      }
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("exit", (code) => {
      if (!settled) finish(new Error(`pi exited (${code}) before settling; stderr:\n${stderr}`));
    });
    child.stdin.write(`${JSON.stringify({ id: "p1", type: "prompt", message: "Hello" })}\n`);
  });
}

async function main() {
  const { server, requests, port } = await startMock();
  const directories = [];
  try {
    for (const webAccess of [false, true]) {
      const directory = mkdtempSync(join(tmpdir(), `pikit-chat-context-${webAccess ? "web" : "plain"}-`));
      directories.push(directory);
      requests.length = 0;
      await runLaunch({ directory, port, webAccess });
      const request = requests.find((candidate) =>
        candidate.user.some((text) => text.includes("Hello")),
      );
      assert.ok(request, `no request carried the test prompt (${requests.length} recorded)`);

      const label = webAccess ? "with web access" : "without web access";
      assert.deepEqual(
        request.system,
        [MARKER],
        `${label}: the only system text must be the preset, exactly`,
      );
      assert.ok(
        !request.raw.includes("<cwd>"),
        `${label}: pi's <cwd> block leaked into the request`,
      );
      assert.ok(
        !request.raw.includes("LEAKED-AGENTS-MD"),
        `${label}: AGENTS.md leaked into the request`,
      );
      if (webAccess) {
        assert.deepEqual(
          request.tools.slice().sort(),
          ["web_fetch", "web_search"],
          `${label}: the allowlist must name exactly the two network tools`,
        );
      } else {
        assert.deepEqual(
          request.tools,
          [],
          `${label}: no tool may be declared when the switch is off`,
        );
      }
      console.log(
        `chat context ${label}: ${request.tools.length} tool(s), system text ${JSON.stringify(request.system)}`,
      );
    }
    console.log("chat context: clean prompt, and exactly the two network tools when on");
  } finally {
    server.close();
    for (const directory of directories) {
      try {
        rmSync(directory, { recursive: true, force: true });
      } catch {
        // A leftover temp directory is not a test failure.
      }
    }
  }
}

main().catch((error) => {
  console.error(`chat context: FAILED — ${error.message}`);
  process.exit(1);
});
