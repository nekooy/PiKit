/**
 * PiKit: the permission tiers pi's tool calls run under.
 *
 * ## Why this exists
 *
 * pi is designed without a permission system — its own philosophy says "No
 * permission popups: run in a container, or build your own confirmation flow with
 * extensions" — and PiKit launches it with `--approve`. On a phone there is no
 * container, so PiKit supplies the missing half: this extension subscribes to
 * `tool_call`, which pi fires *before* a tool runs and whose return value can
 * block the call outright. A blocked call comes back to the model as a refusal
 * with the reason attached, so the agent reports the problem and asks.
 *
 * ## The three tiers
 *
 * The model is borrowed from dsh (the DeepSeek Harness coding agent): one control
 * in the composer with three answers, and no per-command popup.
 *
 *  - `read-only` — nothing may change the filesystem. `write` and `edit` are
 *    refused; `bash` is allowed only when every segment is an inspection command
 *    from the allow-list below. Non-shell tools (`read`, `grep`, `ls`, `find`,
 *    `web_fetch`, …) are untouched, because they cannot write.
 *  - `workspace-write` — reads anywhere, writes only inside the writable roots:
 *    the workspace, plus the shared-storage folders the user granted. `write` and
 *    `edit` are judged by their path; `bash` is split into segments and every
 *    segment that mutates is judged by the paths it names.
 *  - `full-access` — nothing is refused. The handler is still registered, because
 *    the tier can change while pi runs.
 *
 * The tier lives in a small JSON file (`PIKIT_PERMISSION_FILE`) the app rewrites
 * when the composer's control moves, and it is read on every `tool_call` — not
 * from an environment variable, which is read once at process start and would
 * make every change restart pi. A missing, unreadable or unrecognised file is
 * `workspace-write`, never "off"; a hand-run `pi` falls back to
 * `<HOME>/.pikit/permission.json`.
 *
 * ## What is a rule, and what is not
 *
 * This is a rule the agent is held to, not a sandbox: it matches paths in a
 * command's *text*, so a path built at runtime — base64, a variable assigned
 * earlier, a program that constructs it — is not caught, and neither is a
 * command whose writes are not in [mutationOf]'s vocabulary. That is
 * deliberately not described to the user as a sandbox. What it catches is the
 * case that matters: a model told to work in a folder reaching for the photos,
 * or an `rm -rf ~` written a little too wide.
 *
 * ## What survived the rewrite
 *
 * The extension this replaced (about a thousand lines) enumerated five
 * destructive classes — recursive deletes, filesystem creation, raw-device
 * writes, mount roots, ungranted storage — plus a sixth for pi's own files. All
 * of them are *replaced* by "a write outside the writable roots": `/sdcard`,
 * `/system`, `/data`, `~/.pi` and the app's own files are all outside the
 * workspace. What survives is the machinery — splitting, `~`/`$HOME` expansion
 * that respects single quotes, `~/storage` link translation, mutation
 * classification — because a command line is the only thing there is to read.
 */

import { readFileSync } from "node:fs";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

/** The file the app rewrites whenever the composer's control moves. */
export const PERMISSION_FILE_VAR = "PIKIT_PERMISSION_FILE";
/** The directory the agent was spawned in: writable root and relative base. */
export const WORKSPACE_VAR = "PIKIT_WORKSPACE";
/** `:`-separated absolute spellings of the shared-storage folders granted. */
export const STORAGE_ALLOWED_VAR = "PIKIT_STORAGE_ALLOWED";
/** `name=target` pairs, one per line, for the `~/storage` link farm. */
export const STORAGE_LINKS_VAR = "PIKIT_STORAGE_LINKS";
/** Where a hand-run `pi` keeps its permission file when the app set no variable. */
export const DEFAULT_HOME = "/data/data/pi.kit.mob/files/home";
/** The runtime's own prefix; only used to name what a package install writes. */
export const DEFAULT_PREFIX = "/data/data/pi.kit.mob/files/usr";
/** The tier a missing, unreadable or unrecognised permission file means. */
export const DEFAULT_MODE: PermissionMode = "workspace-write";

export type PermissionMode = "read-only" | "workspace-write" | "full-access";

const MODES: readonly string[] = ["read-only", "workspace-write", "full-access"];

/** The file the handler reads on every call: the app's choice, or the fallback. */
export function permissionFilePath(env: Record<string, string | undefined>): string {
  const declared = (env[PERMISSION_FILE_VAR] ?? "").trim();
  const home = collapse((env.HOME ?? "").trim() || DEFAULT_HOME);
  return declared !== "" ? declared : `${home}/.pikit/permission.json`;
}

/**
 * The tier a permission file's contents mean.
 *
 * Pure, and exported, because `tools/test-permissions.mjs` pins every answer —
 * in particular that an absent file is `workspace-write`. Only the exact three
 * strings are accepted: a corrupted file falls back to the default, never to
 * "least restrictive".
 */
export function resolveMode(
  _env: Record<string, string | undefined>,
  contents: string | null | undefined,
): PermissionMode {
  if (typeof contents !== "string" || contents.trim() === "") return DEFAULT_MODE;
  let parsed: unknown;
  try {
    parsed = JSON.parse(contents);
  } catch {
    return DEFAULT_MODE;
  }
  const mode = (parsed as { mode?: unknown } | null)?.mode;
  return typeof mode === "string" && MODES.includes(mode) ? (mode as PermissionMode) : DEFAULT_MODE;
}

/** Where this process may write, and how a `~/storage` link spelling is judged. */
export interface PermissionEnvironment {
  home: string;
  workspace: string;
  prefix: string;
  /** The file that states the tier; every refusal names it so a retelling stays actionable. */
  permissionFile: string;
  /** Workspace plus every granted storage spelling. */
  writable: string[];
  /** Link name to absolute target, from the farm the app actually created. */
  links: Record<string, string>;
}

/** The roots and link farm this process was handed; none of it depends on the tier. */
export function environmentFrom(env: Record<string, string | undefined>): PermissionEnvironment {
  const home = collapse((env.HOME ?? "").trim() || DEFAULT_HOME);
  const declared = (env[WORKSPACE_VAR] ?? "").trim();
  const workspace = collapse(
    declared === "" ? `${home}/workspace` : declared.startsWith("/") ? declared : `${home}/${declared}`,
  );
  // `/` is dropped on purpose: it would make every path writable, and no switch
  // on the storage page means "the whole phone".
  const granted = (env[STORAGE_ALLOWED_VAR] ?? "")
    .split(":")
    .map((entry) => collapse(entry.trim()))
    .filter((path) => path !== "" && path !== "/");
  const links: Record<string, string> = {};
  for (const line of (env[STORAGE_LINKS_VAR] ?? "").split("\n")) {
    const at = line.indexOf("=");
    const target = at > 0 ? collapse(line.slice(at + 1).trim()) : "/";
    if (target !== "/") links[line.slice(0, at).trim()] = target;
  }
  return {
    home,
    workspace,
    prefix: collapse((env.PREFIX ?? "").trim() || DEFAULT_PREFIX),
    permissionFile: permissionFilePath(env),
    writable: [workspace, ...granted].filter((path, index, all) => all.indexOf(path) === index),
    links,
  };
}

/** What a tool call's judge decided. `block: false` is the only allowed shape. */
export interface Verdict {
  block: boolean;
  reason?: string;
}

/**
 * Judges one tool call in one tier, with nothing read from disk.
 *
 * Pure so the table in `tools/test-permissions.mjs` can exercise every mode
 * without a real permission file: the mode arrives as an argument.
 */
export function checkToolCall(
  toolName: string,
  input: unknown,
  env: Record<string, string | undefined>,
  mode: PermissionMode,
): Verdict {
  // full-access allows immediately — the tier can change while pi runs, so the
  // decision is per call, not per load.
  if (mode === "full-access") return { block: false };
  const roots = environmentFrom(env);
  const fields = (input ?? {}) as { command?: unknown; path?: unknown };

  if (toolName === "write" || toolName === "edit") {
    const path = typeof fields.path === "string" && fields.path.trim() !== "" ? fields.path : null;
    // Both tools always change a file, so in read-only the path is only what the
    // refusal names — it is not the reason.
    if (mode === "read-only") return { block: true, reason: readOnlyFileRefusal(toolName, path, roots) };
    if (path === null) return { block: false };
    // A relative path resolves against the workspace, because that is where pi's
    // file tools are rooted: the app launches pi there and publishes it as the base.
    const resolved = resolveTarget(path, roots, roots.workspace);
    return insideWritable(resolved, roots)
      ? { block: false }
      : { block: true, reason: outsideRefusal(`the ${toolName} tool targets`, resolved, roots) };
  }

  if (toolName !== "bash") return { block: false };
  const command = typeof fields.command === "string" ? fields.command : null;
  if (command === null || command.trim() === "") return { block: false };
  return walk(command, roots, roots.workspace, 0, mode === "read-only" ? READ_ONLY_TIER : WORKSPACE_TIER);
}

/**
 * The exact function the extension registers: resolve the tier from a freshly
 * read file, then judge. `readText` is a parameter rather than a direct read so
 * the test can prove the file is consulted on *every* call, at the published path.
 */
export function guardCall(
  toolName: string,
  input: unknown,
  env: Record<string, string | undefined>,
  readText: (path: string) => string | null,
): Verdict {
  return checkToolCall(toolName, input, env, resolveMode(env, readText(permissionFilePath(env))));
}

// --------------------------------------------------------------------------- //
// Paths
// --------------------------------------------------------------------------- //

/**
 * A target as the shell would resolve it: `~`/`$HOME` expanded (except inside
 * single quotes), relative paths against the current directory, `.`/`..`
 * collapsed, `~/storage/<name>` translated to its target.
 *
 * Textual on purpose: following a symlink to decide whether a path is inside the
 * workspace would make the answer depend on whether `$HOME/storage` exists.
 */
function resolveTarget(raw: string, roots: PermissionEnvironment, cwd: string): string {
  const token = unquote(raw.trim());
  if (token === "") return collapse(cwd);
  // Single quotes are load bearing rather than symmetry: `cd /sdcard && rm -rf
  // '$HOME/x'` removes `/sdcard/$HOME/x`, so expanding it would turn a refusal
  // into an allow.
  const base = roots.home.replace(/\/+$/, "");
  const quoted = /^\s*'/.test(raw);
  const expand = (value: string) => {
    if (quoted) return value;
    if (value === "~" || value === "$HOME" || value === "${HOME}") return base;
    if (value.startsWith("~/")) return base + value.slice(1);
    if (value.startsWith("$HOME/")) return base + value.slice("$HOME".length);
    return value.startsWith("${HOME}/") ? base + value.slice("${HOME}".length) : value;
  };
  const expanded = expandPrefix(expand(token), roots.prefix, quoted);
  const path = collapse(expanded.startsWith("/") ? expanded : `${cwd}/${expanded}`);
  const farm = `${base}/storage/`;
  if (!path.startsWith(farm)) return path;
  const rest = path.slice(farm.length);
  const slash = rest.indexOf("/");
  const target = roots.links[slash === -1 ? rest : rest.slice(0, slash)];
  return target === undefined ? path : slash === -1 ? target : target + rest.slice(slash);
}

/** `$PREFIX` and `${PREFIX}` name the runtime, which the app publishes into every shell. */
function expandPrefix(token: string, prefix: string, quoted: boolean): string {
  const base = prefix.replace(/\/+$/, "");
  if (quoted) return token;
  if (token === "$PREFIX" || token === "${PREFIX}") return base;
  if (token.startsWith("$PREFIX/")) return base + token.slice("$PREFIX".length);
  return token.startsWith("${PREFIX}/") ? base + token.slice("${PREFIX}".length) : token;
}

/** Resolves `.` and `..` textually, without touching the filesystem. */
function collapse(path: string): string {
  const parts: string[] = [];
  for (const segment of path.split("/")) {
    if (segment === "" || segment === ".") continue;
    if (segment === "..") parts.pop();
    else parts.push(segment);
  }
  return `/${parts.join("/")}`;
}

/** True when [path] is [root] itself or lives inside it. */
function isUnder(path: string, root: string): boolean {
  const clean = root.replace(/\/+$/, "");
  return clean !== "" && clean !== "/" && (path === clean || path.startsWith(`${clean}/`));
}

function insideWritable(path: string, roots: PermissionEnvironment): boolean {
  return roots.writable.some((root) => isUnder(path, root));
}

/** Raw devices fall out of the path rule, but the refusal says why they matter. */
const BLOCK_DEVICE = /^\/dev\/(block\/|sd[a-z]|mmcblk|nvme)/;

// --------------------------------------------------------------------------- //
// Command text
// --------------------------------------------------------------------------- //

/** Splits outside quotes on [separate]; the quotes stay in the token. */
function scan(text: string, separate: (ch: string) => boolean): string[] {
  const out: string[] = [];
  let current = "";
  let started = false;
  let quote: string | null = null;
  for (const ch of text) {
    if (quote) {
      current += ch;
      if (ch === quote) quote = null;
    } else if (ch === "'" || ch === '"') {
      quote = ch;
      current += ch;
      started = true;
    } else if (separate(ch)) {
      if (started) out.push(current);
      current = "";
      started = false;
    } else {
      current += ch;
      started = true;
    }
  }
  if (started) out.push(current);
  return out;
}

/**
 * The command line split at the separators the *shell* treats as separators.
 *
 * Quote-aware, which keeps the three false positives out: `git commit -m 'fix;
 * rm -rf /sdcard'`, `grep -rn '&& rm -rf'` and a heredoc that merely mentions a
 * dangerous command are one command each. Newlines separate too, so a three-line
 * script with `rm -rf .` on its own line is seen.
 */
function segments(text: string): string[] {
  return scan(text, (ch) => ";&|\n".includes(ch))
    .map((segment) => segment.trim())
    .filter((segment) => segment !== "");
}

/** The words of a segment; `cp "my file.txt" /sdcard/` is three of them. */
function splitWords(text: string): string[] {
  return scan(text, (ch) => /\s/.test(ch));
}

/** One layer of matching quotes, removed. */
function unquote(token: string): string {
  const text = token.trim();
  const first = text[0];
  return text.length >= 2 && (first === "'" || first === '"') && text.endsWith(first)
    ? text.slice(1, -1)
    : text;
}

/** A command that runs a quoted string as a command of its own, anchored to a segment. */
const RUNS_A_STRING = /^\s*(sudo\s+)?(sh|bash|dash|zsh|ksh|busybox\s+sh|eval|source)\b/;

/**
 * The quoted strings in [segment] a shell would execute — the same command one
 * level down. Only a segment that *invokes* a shell contributes, so `git commit
 * -m 'fix; rm -rf /sdcard'` contributes nothing; the heredoc delimiter is quoted
 * too and is not a command, so it is removed first.
 */
function innerCommands(segment: string): string[] {
  if (!RUNS_A_STRING.test(segment)) return [];
  const text = segment.replace(/<<-?\s*(['"])([A-Za-z_][A-Za-z0-9_]*)\1/g, "<<$2");
  const out: string[] = [];
  for (const match of text.matchAll(/'([^']*)'|"((?:\\.|[^"\\])*)"/g)) {
    const body = (match[1] ?? match[2] ?? "").trim();
    if (body !== "") out.push(body.replace(/\\(["'`$\\])/g, "$1"));
  }
  return out;
}

/**
 * A command line with heredoc bodies removed, and the bodies a shell will run.
 *
 * `cat > build.sh <<'EOF'` only stores text, so its body is dropped; `bash
 * <<'EOF'` executes it, so its body is inspected as commands. The opener line
 * stays either way, which keeps its redirect target visible.
 */
function splitHeredocs(text: string): { text: string; bodies: string[] } {
  const lines = text.split("\n");
  const kept: string[] = [];
  const bodies: string[] = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index]!;
    kept.push(line);
    const opener = /<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1/.exec(line);
    if (!opener) {
      index += 1;
      continue;
    }
    index += 1;
    const body: string[] = [];
    while (index < lines.length && lines[index]!.trim() !== opener[2]) body.push(lines[index++]!);
    if (index < lines.length) index += 1;
    kept.push("");
    if (RUNS_A_STRING.test(line.split("<<")[0] ?? "")) bodies.push(body.join("\n"));
  }
  return { text: kept.join("\n"), bodies };
}

/**
 * The files a segment's redirections write to, `>` and `>>` and bash's `&>`.
 *
 * The mutation with no command word, and how `echo x > ~/.pi/...` blanked the
 * credentials with nothing for a command rule to see. `>&2`, `2>&1` and `>&-` are
 * descriptor moves, not files; `/dev/null` means "discard this".
 */
function redirectTargets(text: string): string[] {
  const out: string[] = [];
  let quote: string | null = null;
  for (let index = 0; index < text.length; index += 1) {
    const ch = text[index]!;
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === "'" || ch === '"') {
      quote = ch;
      continue;
    }
    if (ch !== ">") continue;
    let at = index + 1;
    if (text[at] === ">") at += 1;
    if (text[at] === "&") continue;
    while (at < text.length && /\s/.test(text[at]!)) at += 1;
    let token = "";
    while (at < text.length && !/[\s;&|<>]/.test(text[at]!)) token += text[at++];
    if (token !== "") out.push(token);
    index = at - 1;
  }
  return out;
}

/** A command word and its arguments, as shell words. */
interface Call {
  name: string;
  rest: string[];
}

/** The command word: leading `NAME=value` assignments skipped, its path stripped. */
function commandName(words: string[]): string {
  let index = 0;
  while (index < words.length && /^[A-Za-z_][A-Za-z0-9_]*=/.test(words[index]!)) index += 1;
  return index < words.length ? baseName(unquote(words[index]!)) : "";
}

function baseName(word: string): string {
  return word.slice(word.lastIndexOf("/") + 1);
}

/** Words that only stand in front of the command that actually runs. */
const WRAPPERS = new Set(["sudo", "command", "env", "nohup", "time", "nice", "stdbuf", "busybox", "xargs"]);
/** Wrapper flags that take the next word (`sudo -u root`, `xargs -n 1`). */
const WRAPPER_VALUES = new Set(["-u", "-n", "-I", "-i", "-P", "-s", "-a", "--user", "--max-args", "--replace", "--max-procs"]);

/**
 * The command a segment really runs, with `sudo`, `env`, `xargs` and their
 * leading assignments stripped: `env FOO=1 rm -rf /sdcard` is the deleting
 * command it names, not a tool the rule has never heard of.
 */
function unwrap(words: string[]): Call {
  let index = 0;
  while (index < words.length) {
    if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(words[index]!)) {
      index += 1;
      continue;
    }
    if (!WRAPPERS.has(baseName(unquote(words[index]!)))) break;
    index += 1;
    while (index < words.length && unquote(words[index]!).startsWith("-")) {
      if (WRAPPER_VALUES.has(unquote(words[index]!))) index += 1;
      index += 1;
    }
  }
  return index >= words.length
    ? { name: "", rest: [] }
    : { name: baseName(unquote(words[index]!)), rest: words.slice(index + 1) };
}

// --------------------------------------------------------------------------- //
// The two tiers' judgments, and the walk they share
// --------------------------------------------------------------------------- //

/** Depth limit for `sh -c 'sh -c …'`: three levels is more than any real command. */
const MAX_NESTING = 3;

/** One tier's judgment of one segment, and of a redirect target. */
interface Tier {
  redirect(target: string, segment: string, roots: PermissionEnvironment): Verdict;
  segment(
    segment: string,
    words: string[],
    roots: PermissionEnvironment,
    directory: string,
  ): Verdict | null;
}

/**
 * Walks a command line once, for either tier: the splitting, `cd` tracking,
 * heredoc execution and `sh -c` recursion are the same questions in both, so
 * only what a segment is judged to *be* differs.
 */
function walk(
  text: string,
  roots: PermissionEnvironment,
  cwd: string,
  depth: number,
  tier: Tier,
): Verdict {
  const { text: body, bodies } = splitHeredocs(text.replace(/\\\r?\n/g, " "));
  if (depth < MAX_NESTING) {
    for (const heredoc of bodies) {
      const verdict = walk(heredoc, roots, cwd, depth + 1, tier);
      if (verdict.block) return verdict;
    }
  }
  let directory = cwd;
  for (const segment of segments(body)) {
    const words = splitWords(segment);
    // `cd` decides where every later relative target lands: `cd /sdcard && rm
    // -rf .` is a delete of the user's storage however relative it looks.
    if (commandName(words) === "cd") {
      directory = directoryAfterCd(words, roots, directory);
      continue;
    }
    for (const raw of redirectTargets(segment)) {
      const target = resolveTarget(raw, roots, directory);
      if (target === "/dev/null") continue;
      const verdict = tier.redirect(target, segment, roots);
      if (verdict.block) return verdict;
    }
    const inner = depth < MAX_NESTING ? innerCommands(segment) : [];
    if (inner.length > 0) {
      // A shell running a string is transparent: the string is the command and it
      // was just judged. Its own argv (`-c '<script>'`) is not a command.
      for (const innerText of inner) {
        const verdict = walk(innerText, roots, directory, depth + 1, tier);
        if (verdict.block) return verdict;
      }
      continue;
    }
    const verdict = tier.segment(segment, words, roots, directory);
    if (verdict !== null) return verdict;
  }
  return { block: false };
}

/** The directory a `cd` segment moves to. `cd` alone means `$HOME`; `cd -` is unknown. */
function directoryAfterCd(words: string[], roots: PermissionEnvironment, cwd: string): string {
  const target = words.length > 1 ? unquote(words[1]!) : "";
  if (target === "") return roots.home;
  return target === "-" ? cwd : resolveTarget(words[1]!, roots, cwd);
}

const WORKSPACE_TIER: Tier = {
  redirect(target, _segment, roots) {
    return insideWritable(target, roots)
      ? { block: false }
      : { block: true, reason: outsideRefusal("that command redirects output to", target, roots) };
  },
  segment(_segment, words, roots, directory) {
    const mutation = mutationOf(words, roots, directory);
    if (mutation === null) return null;
    for (const raw of mutation.targets) {
      const target = resolveTarget(raw, roots, directory);
      if (!insideWritable(target, roots)) {
        return { block: true, reason: outsideRefusal(mutation.subject, target, roots) };
      }
    }
    return null;
  },
};

const READ_ONLY_TIER: Tier = {
  redirect(target, segment, roots) {
    return { block: true, reason: readOnlyRefusal(`it redirects output onto ${target}`, segment, roots) };
  },
  segment(segment, words, roots, directory) {
    const reason = readOnlyReason(words, roots, directory);
    return reason === null ? null : { block: true, reason: readOnlyRefusal(reason, segment, roots) };
  },
};

// --------------------------------------------------------------------------- //
// workspace-write
// --------------------------------------------------------------------------- //

/** A mutation a segment performs, and the raw path words that say where. */
interface Mutation {
  /** What a refusal calls it, ending with the verb: `that command writes into`. */
  subject: string;
  targets: string[];
}

/** Commands whose every operand is a write or a delete target. */
const ALL_TARGETS = new Set([
  "rm", "rmdir", "unlink", "shred", "touch", "mkdir", "mkfifo", "truncate", "mv", "tee",
  "gzip", "gunzip", "bzip2", "bunzip2", "xz", "unxz", "zstd", "unzstd",
  "mke2fs", "mkswap", "fdisk", "sfdisk", "parted", "wipefs", "mkdosfs",
]);
/** Commands whose first operand is a mode or owner, not a path. */
const MODE_AND_OWNER = new Set(["chmod", "chown", "chgrp", "chattr", "setfacl"]);

/**
 * Commands whose install writes into the runtime rather than the workspace.
 *
 * The old table allowed `pkg install ripgrep` because nothing in its rules was
 * about *installing*. Here it is a write, and where it writes is `$PREFIX` —
 * outside the workspace by construction, because the workspace is where the
 * agent's projects live and the prefix is the environment they run on. So a
 * package install needs `full-access`.
 */
const PACKAGE_TOOLS = new Set(["pkg", "apt", "apt-get", "aptitude", "dpkg", "pip", "pip3", "gem", "npm", "yarn", "pnpm"]);
const PACKAGE_WRITES = new Set([
  "install", "i", "add", "remove", "uninstall", "upgrade", "update", "full-upgrade",
  "dist-upgrade", "purge", "autoremove", "autoclean", "clean", "reinstall", "ci", "link", "unlink",
]);

/**
 * What a segment mutates, or null when it is not a known mutating command.
 *
 * A curated vocabulary rather than a shell parser — the same choice the old
 * guard made, and now the permission model's deliberate limit: a command whose
 * writes are not listed is treated as non-mutating. The list is the ways a phone
 * is damaged: delete, copy, move, create, chmod, truncate, in-place edit,
 * redirect, package install, git write.
 */
function mutationOf(words: string[], roots: PermissionEnvironment, cwd: string): Mutation | null {
  const { name, rest } = unwrap(words);
  if (name === "") return null;
  if (ALL_TARGETS.has(name) || name === "mkfs" || name.startsWith("mkfs.")) {
    return { subject: "that command names", targets: operandsOf(rest) };
  }
  if (name === "cp" || name === "install" || name === "ln") {
    // The destination, not the source: copying a photo *out of* the user's
    // storage into the workspace reads their file and writes only in the
    // workspace, and a model about writes must not refuse that.
    const at = rest.findIndex((w) => ["-t", "--target-directory"].includes(unquote(w)));
    const declared = at !== -1 ? rest.slice(at + 1, at + 2) : [];
    return { subject: "that command writes into", targets: declared.length > 0 ? declared : operandsOf(rest).slice(-1) };
  }
  if (MODE_AND_OWNER.has(name)) return { subject: "that command changes", targets: operandsOf(rest).slice(1) };
  if (name === "dd") {
    const targets = rest.map(unquote).filter((w) => w.startsWith("of=")).map((w) => w.slice(3));
    return { subject: "that command writes to", targets };
  }
  if (name === "sed") {
    const files = inPlaceFiles(rest);
    return files === null ? null : { subject: "that command edits", targets: files };
  }
  if (name === "find") {
    if (!rest.map(unquote).some((w) => FIND_UNSAFE.has(w))) return null;
    return { subject: "that find starts in", targets: findStartingPoints(rest) };
  }
  if (name === "git") return gitMutation(rest, roots, cwd);
  if (PACKAGE_TOOLS.has(name)) return packageMutation(name, rest, roots, cwd);
  return null;
}

/** `-i`, `-ni`, `-i.bak`, `--in-place`: sed's files, or null when it only reads. */
function inPlaceFiles(rest: string[]): string[] | null {
  const words = rest.map(unquote);
  if (!words.some((w) => w === "--in-place" || w.startsWith("--in-place=") || /^-[a-zA-Z]*i/.test(w))) {
    return null;
  }
  const files: string[] = [];
  let sawScript = false;
  for (let index = 0; index < rest.length; index += 1) {
    const word = words[index]!;
    if (["-e", "--expression", "-f", "--file"].includes(word)) {
      sawScript = true;
      index += 1;
    } else if (!word.startsWith("-")) {
      if (sawScript) files.push(rest[index]!);
      sawScript = true;
    }
  }
  return files;
}

/** `find` flags that run a command of their own or write a file of their own. */
const FIND_UNSAFE = new Set([
  "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls",
]);

/**
 * `find`'s starting points: non-flag words before the first flag. Without that
 * split the value of `-name` reads as a target, and ordinary housekeeping would
 * be refused.
 */
function findStartingPoints(rest: string[]): string[] {
  const points: string[] = [];
  let index = 0;
  while (index < rest.length && ["-H", "-L", "-P"].includes(unquote(rest[index]!))) index += 1;
  for (; index < rest.length && !unquote(rest[index]!).startsWith("-"); index += 1) {
    points.push(rest[index]!);
  }
  return points;
}

/** A `git` call's subcommand, its arguments and its own `-C`. */
interface GitCall {
  sub: string;
  args: string[];
  directory: string | null;
}

function parseGit(rest: string[]): GitCall {
  let index = 0;
  let directory: string | null = null;
  while (index < rest.length) {
    const word = unquote(rest[index]!);
    if (word === "-C" && index + 1 < rest.length) {
      directory = rest[index + 1]!;
      index += 2;
    } else if (word.startsWith("-")) {
      index += 1;
    } else {
      break;
    }
  }
  return index >= rest.length
    ? { sub: "", args: [], directory }
    : { sub: unquote(rest[index]!), args: rest.slice(index + 1).map(unquote), directory };
}

const GIT_READ = new Set([
  "status", "log", "diff", "show", "blame", "cat-file", "rev-parse", "ls-files", "describe",
  "ls-tree", "ls-remote", "show-ref", "for-each-ref", "shortlog", "reflog", "grep", "whatchanged",
  "merge-base", "name-rev", "count-objects", "fsck", "version", "help", "rev-list", "show-branch",
  "check-ignore", "check-attr",
]);

/**
 * Whether a git call is an inspection. `branch`, `tag`, `remote`, `config`,
 * `stash` and `worktree` read or write depending on their arguments: `git branch
 * -a` is an inspection, `git branch -D x` is not.
 */
function gitReadOnly(call: GitCall): boolean {
  if (GIT_READ.has(call.sub)) return true;
  const flagsOnly = call.args.every((arg) => arg.startsWith("-"));
  if (call.sub === "branch" || call.sub === "remote") return flagsOnly;
  if (call.sub === "tag") return call.args.length === 0 || call.args.includes("-l") || call.args.includes("--list");
  if (call.sub === "config") return call.args.some((arg) => arg === "-l" || arg === "--list" || arg.startsWith("--get"));
  if (call.sub === "stash") return ["list", "show"].includes(call.args[0] ?? "");
  return call.sub === "worktree" && call.args[0] === "list";
}

function gitMutation(rest: string[], roots: PermissionEnvironment, cwd: string): Mutation | null {
  const call = parseGit(rest);
  if (call.sub === "" || gitReadOnly(call)) return null;
  // `--global` writes `<home>/.gitconfig`, outside the workspace.
  if (call.sub === "config" && call.args.includes("--global")) {
    return { subject: "that command writes", targets: [roots.home] };
  }
  // The repository's own directory, plus any path the operands name: `git clone`
  // with a destination outside the workspace puts the checkout there.
  const directory = call.directory === null ? cwd : resolveTarget(call.directory, roots, cwd);
  return { subject: "that command writes into", targets: [directory, ...operandsOf(rest)] };
}

function packageMutation(
  name: string,
  rest: string[],
  roots: PermissionEnvironment,
  cwd: string,
): Mutation | null {
  const words = rest.map(unquote);
  const sub = words.find((word) => !word.startsWith("-")) ?? "";
  if (!PACKAGE_WRITES.has(sub) && !words.includes("-i") && !words.includes("--install")) return null;
  // A project install writes `node_modules` where it runs; `-g` writes the
  // runtime, which is the same thing `pkg install` does.
  const local = (name === "npm" || name === "yarn" || name === "pnpm") &&
    !words.includes("-g") && !words.includes("--global");
  return local
    ? { subject: "that command writes into", targets: [cwd] }
    : { subject: "the package install writes into", targets: [roots.prefix] };
}

/** The non-flag words of a command, after a `--` and its own flags are dropped. */
function operandsOf(words: string[]): string[] {
  const out: string[] = [];
  for (let index = 0; index < words.length; index += 1) {
    const bare = unquote(words[index]!);
    if (bare === "--") {
      out.push(...words.slice(index + 1));
      break;
    }
    if (bare === "" || (bare.startsWith("-") && bare.length > 1)) continue;
    out.push(words[index]!);
  }
  return out;
}

// --------------------------------------------------------------------------- //
// read-only
// --------------------------------------------------------------------------- //

/**
 * The commands read-only allows, deliberately as a list: in this tier a command
 * nobody has thought about is refused, because the alternative is guessing that
 * it does not write. The exceptions in [readOnlyReason] exist because the command
 * *can* write with the right flag.
 */
const READ_ONLY = new Set([
  "cat", "head", "tail", "less", "more", "wc", "file", "stat", "du", "df", "tree",
  "pwd", "echo", "printf", "which", "type", "date", "uname", "id", "whoami", "ps", "free",
  "ls", "basename", "dirname", "realpath", "readlink",
  "grep", "rg", "fd", "find", "sed", "sort", "uniq", "cut", "tr", "jq", "diff", "cmp",
  "git", "sha256sum", "md5sum",
]);

/** Flags that turn an otherwise reading command into a write. */
const WRITE_FLAGS: Record<string, string[]> = {
  sort: ["-o", "--output"],
  tree: ["-o", "--output"],
  date: ["-s", "--set"],
};

/** sed's `w`/`W` script commands write a named file, with or without `-i`. */
const SED_WRITES = /(^|[;{}\s/])[wW](\s|$)/;

function readOnlyReason(
  words: string[],
  roots: PermissionEnvironment,
  directory: string,
): string | null {
  let index = 0;
  while (index < words.length && /^[A-Za-z_][A-Za-z0-9_]*=/.test(words[index]!)) index += 1;
  if (index >= words.length) return null; // a bare assignment changes no file
  const name = baseName(unquote(words[index]!));
  const rest = words.slice(index + 1).map(unquote);
  const refuse = (why: string) => `"${name}" ${why}`;

  // One vocabulary serves both tiers: whatever workspace-write calls a write is
  // a write here too, and read-only refuses it without asking where it would
  // land. That is what gives `sed -i`, `find -delete` and `git commit` their
  // answer without a case each.
  if (mutationOf(words, roots, directory) !== null) return refuse("would change the filesystem");
  if (name === "sed") {
    if (rest.some((word) => word === "-f" || word === "--file")) return refuse("with a script file cannot be inspected");
    const scripts: string[] = [];
    for (let at = 0; at < rest.length; at += 1) {
      if (rest[at] === "-e" || rest[at] === "--expression") scripts.push(rest[at + 1] ?? "");
      else if (!rest[at]!.startsWith("-")) {
        scripts.push(rest[at]!);
        break;
      }
    }
    return scripts.some((script) => SED_WRITES.test(script)) ? refuse("uses the w command, which writes a file") : null;
  }
  if (["node", "python", "python3"].includes(name)) {
    return rest.length > 0 && rest.every((word) => word === "-v" || word === "--version" || word === "-V")
      ? null
      : refuse("is allowed only with --version in read-only mode");
  }
  if (name === "npm") {
    // `npm run …` executes a project script, which is exactly the unknown this
    // tier refuses; only the listings are inspections.
    return ["ls", "list", "--version", "-v"].includes(rest[0] ?? "")
      ? null
      : refuse("is allowed only with ls or --version in read-only mode");
  }
  if (name === "env") {
    return rest.filter((word) => !word.startsWith("-")).length > 0 ? refuse("with an operand runs it") : null;
  }
  if (name === "uniq") {
    return rest.filter((word) => !word.startsWith("-")).length > 1 ? refuse("with two files writes the second") : null;
  }
  const flag = (WRITE_FLAGS[name] ?? []).find((candidate) => rest.some((word) => word.startsWith(candidate)));
  if (flag !== undefined) return refuse(`${flag} writes, and read-only mode allows inspection only`);
  return READ_ONLY.has(name) ? null : refuse("is not one of the read-only commands");
}

// --------------------------------------------------------------------------- //
// Refusals
// --------------------------------------------------------------------------- //

/**
 * The refusal a `workspace-write` call gets: the mode, the path, the roots it
 * *did* allow, the file that states the tier, and the way forward. The reader
 * usually sees the model's retelling, so each of those has to be in the text.
 */
function outsideRefusal(subject: string, target: string, roots: PermissionEnvironment): string {
  const device = BLOCK_DEVICE.test(target)
    ? ` ${target} is a raw block device, not a file: a wrong byte there can brick the phone.`
    : "";
  return (
    `PiKit's permission mode is "workspace-write": ${subject} ${target}, which is outside ` +
    `the writable roots (${roots.writable.join(", ")}).${device} Reads are free anywhere; ` +
    "edits and commands inside those roots work as usual. The tier is stated in " +
    `${roots.permissionFile}, which the composer's permission control rewrites — the user ` +
    'can set it to "full access" there.'
  );
}

/** The refusal a `read-only` call gets, naming the mode and the inspection rule. */
function readOnlyRefusal(what: string, segment: string, roots: PermissionEnvironment): string {
  const shown = segment.length > 160 ? `${segment.slice(0, 157)}…` : segment;
  return (
    `PiKit's permission mode is "read-only": ${what}, and read-only mode allows inspection ` +
    "commands only — cat, ls, grep, rg, find without -delete, sed without -i, git " +
    "status/log/diff and the like. A redirect other than to /dev/null counts as a write. " +
    `The refused command was: ${shown}. The tier is stated in ${roots.permissionFile}, ` +
    "which the composer's permission control rewrites; the user can set it to " +
    '"workspace write" there.'
  );
}

/** The refusal `write` and `edit` get in read-only, which is always. */
function readOnlyFileRefusal(toolName: string, path: string | null, roots: PermissionEnvironment): string {
  const named = path === null ? "" : ` (its target was ${path})`;
  return (
    `PiKit's permission mode is "read-only": the ${toolName} tool always changes a file${named}, ` +
    "and read-only mode allows inspection only. The tier is stated in " +
    `${roots.permissionFile}, which the composer's permission control rewrites; the user ` +
    'can set it to "workspace write" there.'
  );
}

// --------------------------------------------------------------------------- //
// Extension registration
// --------------------------------------------------------------------------- //

export default function (pi: ExtensionAPI) {
  // Registered unconditionally, including full-access: the tier is re-read per
  // call, so a control moved while pi runs takes effect without a restart. A
  // missing or unreadable file is `workspace-write`, never "off".
  pi.on("tool_call", async (event) => {
    const verdict = guardCall(
      event.toolName,
      event.input,
      process.env as Record<string, string | undefined>,
      (path) => {
        try {
          return readFileSync(path, "utf8");
        } catch {
          return null;
        }
      },
    );
    if (!verdict.block) return;
    return { block: true, reason: verdict.reason ?? "" };
  });
}
