/*
 * Captures the real pi RPC wire traffic for one turn that uses a tool.
 *
 * Written because the class's unit tests could not settle whether the client's
 * parser matches pi's actual output: the test fixtures were written by hand, by
 * the same person who wrote the parser, so they encode the same assumption twice.
 * The only way to know is to drive a real pi process and record what it emits.
 *
 * Usage:
 *   node tools/capture-pi-rpc.mjs <baseUrl> <modelId> <apiKey> [outFile]
 *
 * Environment for the child:
 *   - PIKIT_API_KEY        the key, read from the environment by models.json
 *   - PI_CODING_AGENT_DIR  a scratch dir, so the real ~/.pi/agent is untouched
 *
 * It prints every record it receives, and writes them to `outFile` (default
 * `.runtime-build/pi-rpc-capture.jsonl`) for the parser to be checked against.
 */

import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");

const [baseUrl, modelId, apiKey, outArg] = process.argv.slice(2);
if (!baseUrl || !modelId || !apiKey) {
  console.error("usage: capture-pi-rpc.mjs <baseUrl> <modelId> <apiKey> [outFile]");
  process.exit(2);
}

const cli = resolve(
  repoRoot,
  ".runtime-build/staging/x86_64/overlay/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js",
);
if (!existsSync(cli)) {
  console.error(`pi CLI not found at ${cli}`);
  process.exit(2);
}

// A scratch agent dir: sessions, models.json and the extension lives here, so a
// capture run cannot disturb whatever the developer has configured.
const agentDir = resolve(repoRoot, ".runtime-build/pi-rpc-agent");
mkdirSync(agentDir, { recursive: true });

// `baseUrl` must end in /v1 for the OpenAI-compatible path.
const models = {
  providers: {
    "pikit-capture": {
      baseUrl,
      api: "openai-completions",
      apiKey: "$PIKIT_API_KEY",
      models: [
        {
          id: modelId,
          name: modelId,
          reasoning: true,
          input: ["text"],
          contextWindow: 128000,
          maxTokens: 8192,
        },
      ],
    },
  },
};
writeFileSync(resolve(agentDir, "models.json"), JSON.stringify(models, null, 2));

// The built-in tools go in pi's own settings file, not on the command line: pi
// applies `--tools` as a strict allowlist over built-in, extension *and* SDK
// tools, so a capture that passed it would describe a launch the app no longer
// performs — and could never capture the tool of an installed extension.
writeFileSync(
  resolve(agentDir, "settings.json"),
  JSON.stringify({ defaultTools: ["read", "bash", "edit", "write", "grep", "find", "ls"] }, null, 2),
);

// No `--offline` and no `PI_OFFLINE`: the app stopped passing it, because pi reads
// it as "the model catalogue has no network" as well as "skip the update checks",
// and a capture has to describe the launch the app actually performs. Telemetry is
// off by name instead, which is what the app sets.
const child = spawn(process.execPath, [cli, "--mode", "rpc", "--approve",
  "--provider", "pikit-capture", "--model", modelId,
  "--session-dir", resolve(agentDir, "sessions"),
], {
  cwd: repoRoot,
  env: {
    ...process.env,
    PIKIT_API_KEY: apiKey,
    PI_CODING_AGENT_DIR: agentDir,
    PI_TELEMETRY: "0",
    NO_COLOR: "1",
  },
  stdio: ["pipe", "pipe", "pipe"],
});

const captured = [];
let buffer = "";

function record(line) {
  if (line.trim().length === 0) return;
  captured.push(line);
  // Print a trimmed form so a tool result does not bury the interesting fields.
  const shown = line.length > 400 ? `${line.slice(0, 400)}… (${line.length} chars)` : line;
  console.log(shown);
}

child.stdout.setEncoding("utf8");
child.stdout.on("data", (chunk) => {
  buffer += chunk;
  // LF only: pi emits U+2028/U+2029 unescaped inside JSON strings, so a generic
  // line reader would split records that are meant to be one.
  let at;
  while ((at = buffer.indexOf("\n")) >= 0) {
    record(buffer.slice(0, at));
    buffer = buffer.slice(at + 1);
  }
});

child.stderr.setEncoding("utf8");
child.stderr.on("data", (chunk) => {
  process.stderr.write(`[pi stderr] ${chunk}`);
});

const send = (raw) => child.stdin.write(`${raw}\n`);

let id = 0;
const nextId = () => `c${++id}`;

async function main() {
  // The envelope is `{ id, type, ...payload }` — there is no nested `command`
  // field. Measured: sending `{type:"command",command:"get_state"}` gets
  // `{"success":false,"error":"Unknown command: command"}` back, because pi reads
  // the *type* as the command name.
  //
  // `get_state` doubles as the readiness probe: pi has no handshake.
  send(JSON.stringify({ id: nextId(), type: "get_state" }));

  await new Promise((r) => setTimeout(r, 1500));

  send(JSON.stringify({
    id: nextId(),
    type: "prompt",
    message: "Use the bash tool to run `echo pikit-capture-ok`, then reply with the single word DONE.",
  }));

  // Long enough for a tool round trip plus the final answer.
  await new Promise((r) => setTimeout(r, 45_000));

  child.stdin.end();
  await new Promise((r) => child.on("exit", r));
}

main().then(() => {
  const out = outArg ?? resolve(repoRoot, ".runtime-build/pi-rpc-capture.jsonl");
  writeFileSync(out, `${captured.join("\n")}\n`);
  console.log(`\n=== ${captured.length} record(s) written to ${out}`);
}).catch((error) => {
  console.error(error);
  process.exit(1);
});
