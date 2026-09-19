/**
 * PiKit: keeps the agent from destroying data it was never asked to touch.
 *
 * ## Why this exists
 *
 * Pi is explicitly designed without a permission system — its philosophy section
 * says "No permission popups: run in a container, or build your own confirmation
 * flow with extensions" — and this app launches it with `--approve`. On a phone
 * there is no container, and until now there was no confirmation flow either. A
 * single `bash` tool call could therefore remove any file on the device, and an
 * app that can do that is one bad model turn away from exactly the outcome this
 * guard was written for.
 *
 * ## What it does
 *
 * It subscribes to `tool_call`, which pi fires *before* a tool runs and whose
 * return value can block the call outright. A blocked call comes back to the
 * model as a refusal with the reason attached, so the agent reports the problem
 * and asks instead of silently failing or retrying.
 *
 * It refuses five classes of command:
 *
 *  1. **Recursive deletes of a directory** — `rm -rf`, `rm -r`, `find -delete`,
 *     `find -exec rm`. Removing a *file* with `rm` is allowed, anywhere the agent
 *     can name one: an agent that cannot delete a stale file is not usable, and one
 *     file is recoverable in a way a tree is not. What is refused is a tree, and
 *     only a tree *outside the workspace* is refused outright.
 *  2. **Filesystem creation** — `mkfs`, `mke2fs`, `fdisk`, `wipefs`, and
 *     friends. Nothing an agent should ever do on the user's phone.
 *  3. **Writes to a raw block device** — `dd of=/dev/block/…`, `> /dev/block/…`.
 *     A wrong byte here is a bricked partition.
 *  4. **Deleting or overwriting a mount root** — anything that names `/sdcard`,
 *     `/storage/emulated/0`, `/mnt`, `/system`, `/data` or `/` itself as the
 *     target of a destructive operation.
 *  5. **Reaching into shared storage the user did not grant** — see below.
 *
 * A sixth rule covers a class the first five miss: **pi's own files**. The
 * workspace is `$HOME/workspace`; `$HOME/.pi` holds the guard itself, the
 * credentials, the settings and every saved conversation, and
 * `<app files>/pikit-config.json` holds the user's API keys in the clear. A
 * *single-file* `rm` there is not a recursive delete, so the first rule let it
 * through: `rm -f ~/.pi/agent/auth.json` was allowed while `rm -f ~/notes.md` was
 * too, and one of those is an agent deleting its own credentials and the other is
 * ordinary housekeeping. Those paths, and the operations that rewrite a file in
 * place (`mv`, `>`, `truncate`, `sed -i`, `chmod`), are refused — as is a `write` or
 * `edit` tool call naming one, because those carry a path and no command text for the
 * shell rules to read at all.
 *
 * ## The workspace
 *
 * `process.env.PIKIT_WORKSPACE`, which PiKit sets to the directory the agent was
 * spawned in — the same value the file browser and the working-directory setting
 * use. Absent (someone ran `pi` by hand) it falls back to `$HOME/workspace`, which
 * is what a fresh install has.
 *
 * Inside it a recursive delete is allowed at **any depth**, because that is where
 * projects are: the earlier rule allowed only the first level (`$HOME/node_modules`)
 * and refused `$HOME/myapp/node_modules`, which made cleaning up a project's build
 * output impossible while the setting that moved the workspace to `$HOME/workspace`
 * did not exist yet. Outside it, nothing is: `$HOME` itself, `$HOME/tmp`,
 * `~/storage/*`, `/sdcard`, `/data`.
 *
 * ## The storage policy, and why it is here rather than in the filesystem
 *
 * The storage page's switches cannot be enforced by Android. "All files access"
 * is granted to the *app*, the agent runs as the same uid, and there is no
 * unprivileged way to run it as anyone else — so a switch that turned a folder off
 * would be a dead letter if the app stopped there. What the switches can decide is
 * what PiKit *offers* the agent (`~/storage/*`, which the app creates only for the
 * folders that are on) and what PiKit *permits* it to name: this extension is the
 * only place the agent's actions pass through, so the policy is enforced there.
 *
 * The `~/storage/*` links are the one hole that had to be closed by translation
 * rather than by a rule, because they are the spelling PiKit itself hands the
 * agent: `~/storage/shared/Download/a.txt` names the user's shared storage without
 * containing any of its paths, so "granted nothing" still allowed reading it. PiKit
 * publishes the link farm it created (`PIKIT_STORAGE_LINKS`), and a path under
 * `$HOME/storage/<name>` is judged as the path it points at.
 *
 * It is a rule the agent is held to, not a wall: the guard matches paths in a
 * command's text, so a command that hides a path — base64, a variable built at
 * runtime, a program that constructs it — is not caught. That is the same bar as
 * the rest of this file, and it is deliberately not described to the user as a
 * sandbox. What it does catch is the case that matters in practice: a model that
 * was told to work in a folder reaching for the user's photos.
 *
 * ## What it deliberately does not do
 *
 * It does not try to parse shell. It splits a command line into segments on the
 * separators that are not inside quotes — so `git commit -m 'fix; rm -rf /sdcard'`
 * is one command and not two — and it looks inside `sh -c '…'`, `eval …` and a
 * heredoc fed to a shell, because those are the same command written one level
 * down. Where it is unsure it allows the call and leaves the decision to the
 * model's instructions in `AGENTS.md`. A guard that blocks legitimate work gets
 * removed by the user, and a removed guard protects nothing — so the bar for
 * blocking is "this is destructive and it is not aimed at the agent's own
 * workspace".
 *
 * It also does not touch reads, writes inside the workspace, `pkg`/`apt`, `git`, or
 * any ordinary build or edit command.
 */

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

/** Where the agent may work, and where it may not destroy anything. */
export interface GuardRoots {
  /** `$HOME`, from the environment PiKit sets. */
  home: string;
  /** The one directory under `home` a recursive delete may target. */
  workspace: string;
  /**
   * The directory the session's shell starts in, which a relative target is
   * resolved against.
   *
   * Equal to [workspace] when PiKit launched the agent — the child's working
   * directory is what `PIKIT_WORKSPACE` names — and `$HOME` otherwise, because that
   * is where a `pi` run by hand in the Terminal tab starts. Getting this wrong is
   * not cosmetic: resolving `rm -rf node_modules` against the workspace when the
   * shell is actually standing in `$HOME` would allow a delete of `$HOME/node_modules`
   * by a rule written for the workspace.
   */
  cwd: string;
}

/**
 * `$HOME`, `$PREFIX` and the workspace, from the environment PiKit sets.
 *
 * All three have defaults, because the guard also runs wherever someone starts pi
 * by hand and a missing variable must not turn the guard off.
 */
export function environmentRoots(): GuardRoots & { prefix: string } {
  const prefix = process.env.PREFIX ?? "/data/data/pi.kit.mob/files/usr";
  const home = process.env.HOME ?? `${prefix.replace(/\/usr$/, "")}/home`;
  const declared = process.env.PIKIT_WORKSPACE?.trim();
  const workspace = declared || defaultWorkspace(home);
  return { home, prefix, workspace, cwd: declared ? workspace : home };
}

/** `$HOME/workspace`, the default the app and this guard agree on. */
function defaultWorkspace(home: string): string {
  return `${home.replace(/\/+$/, "")}/workspace`;
}

/**
 * Places whose contents are the user's data, not the agent's workspace.
 *
 * Data rather than a rule: every whole-tree check compares a resolved path against
 * this list, so "which paths are roots" is one array instead of a condition spelled
 * out at each use. Exported with [inspect] because it is the part of the policy a
 * reader — or `tools/test-safety-guard.mjs` — can check without re-deriving it.
 */
export const PROTECTED_ROOTS = [
  "/sdcard",
  "/storage",
  "/mnt",
  "/system",
  "/vendor",
  "/data",
  "/",
];

/**
 * Trees the agent may never destroy, however it spells the path.
 *
 * `$HOME/.pi` is the whole of pi's own state — the guard extension itself, the
 * credentials in `auth.json`, `settings.json` and `models.json`, the rendered
 * `web-search.json`, the `AGENTS.md` PiKit writes, and every saved conversation —
 * and `<app files>/pikit-config.json` sits beside `$HOME` and holds the user's
 * profiles and API keys in the clear. A file under one of these is refused for
 * *any* destructive operation, not only a recursive one: it is the difference
 * between `rm -f ~/notes.md` (allowed, ordinary) and `rm -f ~/.pi/agent/auth.json`
 * (an agent deleting its own credentials), and the path is the only thing that
 * tells them apart.
 */
export function protectedPaths(home: string): string[] {
  const clean = home.replace(/\/+$/, "");
  // `$HOME` is `<app files>/home`, so its parent is the app's private files
  // directory — where `pikit-config.json` lives. `dirname` by hand rather than by
  // importing `node:path`: this file is bundled and loaded by pi, and staying on
  // string operations keeps it free of any resolution this app would have to trust.
  const appFiles = clean.includes("/") ? clean.slice(0, clean.lastIndexOf("/")) : clean;
  return [
    `${clean}/.pi`,
    `${appFiles}/pikit-config.json`,
    `${appFiles}/pi-sessions`,
  ];
}

/**
 * Where the user's shared storage begins, and what of it the user switched on.
 *
 * Both are lists of spellings rather than one path each, because `/sdcard/DCIM`
 * and `/storage/emulated/0/DCIM` are the same directory and a command may name
 * either. PiKit writes both (see `StorageAccess.spellingsOf`), and nothing here
 * resolves symlinks: the same textual rule as the rest of the file.
 */
export interface StoragePolicy {
  /** Every spelling of the shared storage root, e.g. `/sdcard`. */
  roots: string[];
  /** Every spelling of each granted folder. Empty means nothing was granted. */
  allowed: string[];
  /**
   * The `~/storage` link farm PiKit created: link name to absolute target.
   *
   * This is what makes the link spelling enforceable. `~/storage/shared/Download/a.txt`
   * contains no shared-storage path at all, so a rule that only matched the roots
   * let the whole policy be stepped around by the one spelling PiKit itself offers
   * (measured: allowed with nothing granted, while `/sdcard/Download/a.txt` was
   * refused).
   */
  links?: Record<string, string>;
}

/** `host:host:…`, the shape PiKit writes into the environment. */
function pathList(value: string | undefined): string[] {
  if (!value) return [];
  return value
    .split(":")
    .map((entry) => collapse(entry.trim()))
    .filter((entry) => entry !== "" && entry !== "/");
}

/** `name=target` pairs on their own lines, the shape of `PIKIT_STORAGE_LINKS`. */
function linkList(value: string | undefined): Record<string, string> {
  const links: Record<string, string> = {};
  if (!value) return links;
  for (const line of value.split("\n")) {
    const entry = line.trim();
    if (!entry) continue;
    const at = entry.indexOf("=");
    if (at <= 0) continue;
    const name = entry.slice(0, at).trim();
    const target = collapse(entry.slice(at + 1).trim());
    if (name && target !== "/") links[name] = target;
  }
  return links;
}

/**
 * The policy PiKit handed this process, or null when it did not.
 *
 * Null rather than an empty policy when the variables are absent: the guard also
 * runs wherever someone starts pi by hand, and policing shared storage on a
 * device that never told us which folders are the user's would refuse everything
 * for no reason. An empty *allowed* list with roots present is different and is
 * respected — that is "granted nothing", which is the app's default.
 */
export function storagePolicyFromEnvironment(
  env: Record<string, string | undefined> = process.env,
): StoragePolicy | null {
  const roots = pathList(env.PIKIT_STORAGE_ROOTS);
  if (roots.length === 0) return null;
  return {
    roots,
    allowed: pathList(env.PIKIT_STORAGE_ALLOWED),
    links: linkList(env.PIKIT_STORAGE_LINKS),
  };
}

interface Verdict {
  block: boolean;
  reason?: string;
}

/**
 * True when `token` names a protected root rather than a path inside one.
 *
 * `/sdcard/Download/x` is not a root; `/sdcard` and `/storage/emulated/0` are.
 * Matching this way avoids blocking every ordinary use of shared storage while
 * still catching the case that matters — a destructive command pointed at the
 * whole tree.
 */
function isProtectedRoot(token: string): boolean {
  const cleaned = token.replace(/^["']|["']$/g, "").replace(/\/+$/, "");
  if (cleaned === "") return false;
  for (const root of PROTECTED_ROOTS) {
    if (cleaned === root) return true;
    // `/storage/emulated/0` and `/storage/emulated` are roots in their own right
    if (root === "/storage" && (cleaned === "/storage/emulated" || cleaned === "/storage/emulated/0")) {
      return true;
    }
  }
  return false;
}

/**
 * True when the target names the workspace root itself, or anything outside it.
 *
 * One predicate for both because a recursive `rm` treats them the same: the
 * workspace root is not a scratch directory, and everything above it belongs to the
 * user or to the system. `rm -rf ~/workspace` and `rm -rf ~/workspace/../notes` are
 * the two halves of it.
 */
function isWorkspaceRoot(token: string, roots: GuardRoots, cwd: string): boolean {
  const collapsed = collapseTarget(token, roots.home, cwd);
  const workspace = roots.workspace.replace(/\/+$/, "");
  return collapsed === workspace || !collapsed.startsWith(`${workspace}/`);
}

/** Resolves a target as the shell would: `~` expanded, `.`/`..` collapsed. */
function collapseTarget(token: string, home: string, cwd: string): string {
  const cleaned = expandHome(token, home);
  const absolute = cleaned.startsWith("/")
    ? cleaned
    : `${cwd.replace(/\/+$/, "")}/${cleaned}`;
  return collapse(absolute);
}

/**
 * `~`, `~/x`, `$HOME`, `$HOME/x` and the `${HOME}` spellings name `$HOME`, which
 * the shell expands before the command runs.
 *
 * `$HOME` used to be absent from this list, which made the two spellings of one
 * command disagree: `rm -rf ~/tmp/x` was refused and `rm -rf "$HOME/tmp/x"` was
 * allowed, `find ~ -delete` was refused and `find "$HOME" -delete` was allowed.
 * Inside **single** quotes the shell does not expand, and neither does this — that
 * line is load bearing and not symmetry: `cd /sdcard && rm -rf '$HOME/workspace/x'`
 * removes `/sdcard/$HOME/workspace/x`, so expanding it would turn a refusal into an
 * allow.
 */
function expandHome(token: string, home: string): string {
  const singleQuoted = /^\s*'/.test(token);
  const cleaned = token.replace(/^["']|["']$/g, "");
  if (singleQuoted) return cleaned;
  const base = home.replace(/\/+$/, "");
  if (cleaned === "~" || cleaned === "$HOME" || cleaned === "${HOME}") return base;
  if (cleaned.startsWith("~/")) return base + cleaned.slice(1);
  if (cleaned.startsWith("$HOME/")) return base + cleaned.slice("$HOME".length);
  if (cleaned.startsWith("${HOME}/")) return base + cleaned.slice("${HOME}".length);
  return cleaned;
}

/**
 * Resolves `.` and `..` textually, without touching the filesystem.
 *
 * Textual is the right answer here: the guard must not follow a symlink into
 * shared storage to decide whether a path is inside the workspace, and `$HOME`
 * contains exactly those links.
 */
function collapse(path: string): string {
  const parts: string[] = [];
  for (const segment of path.split("/")) {
    if (segment === "" || segment === ".") continue;
    if (segment === "..") {
      parts.pop();
      continue;
    }
    parts.push(segment);
  }
  return `/${parts.join("/")}`;
}

/**
 * The command line split at the separators the *shell* treats as separators.
 *
 * Quote-aware, and that is what stops the three false positives the report named:
 * `git commit -m 'fix; rm -rf /sdcard'`, `grep -rn '&& rm -rf'` and a heredoc whose
 * body merely mentions a dangerous command are one command each, not two. The guard
 * still refuses `sh -c 'rm -rf /sdcard'`, because a quoted string that is handed to
 * a shell *is* a command — [innerCommands] finds those and they are inspected too.
 *
 * Newlines separate as well, which is the case the old text-wide regular
 * expressions missed entirely: a three-line script with `rm -rf .` on its own line
 * was never looked at, because the pattern required the line to start with the
 * command.
 *
 * A backslash-escaped quote inside a double-quoted string is not modelled; it ends
 * the string here. That is the conservative direction — the text is then analysed
 * in smaller pieces, never in larger ones.
 */
function segments(text: string): string[] {
  const out: string[] = [];
  let current = "";
  let quote: string | null = null;
  for (const ch of text) {
    if (quote) {
      current += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === "'" || ch === '"') {
      quote = ch;
      current += ch;
      continue;
    }
    if (ch === ";" || ch === "&" || ch === "|" || ch === "\n") {
      out.push(current);
      current = "";
      continue;
    }
    current += ch;
  }
  out.push(current);
  // Trimmed, and that is what lets the rules be anchored to the start of a
  // segment: the separator used to be part of the pattern, so `foo && rm -rf x`
  // matched a rule written as `(^|[;&|]\s*)rm` while the segment for the second
  // command begins with a space.
  return out.map((segment) => segment.trim()).filter((segment) => segment !== "");
}

/**
 * A command that runs a quoted string as a command of its own.
 *
 * Anchored to the start of a segment, because segments are commands: the version of
 * this that matched a separator anywhere in the text also matched text that only
 * *mentioned* a shell.
 */
const RUNS_A_STRING = /^\s*(sudo\s+)?(sh|bash|dash|zsh|busybox\s+sh|eval|source)\b/;

/**
 * The quoted strings in [segment] that a shell would execute.
 *
 * `sh -c 'rm -rf ~'` is the same command as `rm -rf ~` written one level down, and
 * the whole point of segmenting is defeated if the text inside the quotes is never
 * looked at. Only the strings of a segment that *invokes* a shell are returned, so
 * `git commit -m 'fix; rm -rf /sdcard'` contributes nothing.
 */
function innerCommands(segment: string): string[] {
  if (!RUNS_A_STRING.test(segment)) return [];
  const out: string[] = [];
  for (const match of segment.matchAll(/'([^']*)'|"((?:\\.|[^"\\])*)"/g)) {
    const body = match[1] ?? match[2] ?? "";
    if (body.trim() === "") continue;
    out.push(body.replace(/\\(["'`$\\])/g, "$1"));
  }
  return out;
}

/**
 * A command line with heredoc bodies removed, and the bodies that a shell will run.
 *
 * Writing a script with `cat > build.sh <<'EOF'` and *running* one with
 * `bash <<'EOF'` look the same to a regular expression and are not the same thing:
 * the first only stores text, and refusing it is the false positive the report
 * named (its workaround — use the `write` tool — is real but not obvious). The
 * body is handed back separately when the heredoc's own command is a shell, which
 * is the case that does execute it.
 *
 * The placeholder keeps the line count, so a reason that quotes a line number
 * would still point at the right one.
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
    const terminator = opener[2]!;
    const body: string[] = [];
    while (index < lines.length && lines[index]!.trim() !== terminator) {
      body.push(lines[index]!);
      index += 1;
    }
    // The terminator line itself, when it is there.
    if (index < lines.length) index += 1;
    kept.push("");
    if (RUNS_A_STRING.test(line.split("<<")[0] ?? "")) bodies.push(body.join("\n"));
  }
  return { text: kept.join("\n"), bodies };
}

/**
 * True for a target whose whole content is "here" or "up".
 *
 * `rm -rf .` and `rm -rf ..` are only safe when the shell happens to be standing
 * somewhere disposable, and the guard cannot know that it is — the agent's own
 * `cd` is what decided it, and a mistake there removes everything below.
 */
function isCurrentOrParent(token: string): boolean {
  const cleaned = token.replace(/^["']|["']$/g, "").replace(/\/+$/, "");
  return cleaned === "." || cleaned === "..";
}

/**
 * The directory the destructive command runs in.
 *
 * It starts at [GuardRoots.cwd] — the workspace when PiKit spawned the agent, `$HOME`
 * when someone ran `pi` by hand in the Terminal tab. Starting at `$HOME` always, which
 * is what this did before the workspace existed, resolved `rm -rf node_modules` to
 * `$HOME/node_modules` and refused the ordinary project cleanup; starting at the
 * workspace always would allow a delete of `$HOME/node_modules` by a hand-run agent
 * standing in `$HOME`.
 */
function directoryAfterCd(text: string, start: string, home: string): string {
  let cwd = start;
  const pattern = /(?:^|[;&|]\s*)cd\s+([^\s;&|]+)/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(text)) !== null) {
    // `~` is expanded against `$HOME` even when the shell starts in the workspace,
    // because that is what the shell does: `cd ~/workspace` names the same directory
    // from either.
    const target = expandHome(match[1]!, home);
    if (target.startsWith("/")) cwd = collapse(target);
    else cwd = collapse(`${cwd}/${target}`);
  }
  return cwd;
}

/**
 * Classifies one shell command. Returns the first refusal that applies.
 *
 * `forbidden` is checked before anything else: a command that writes to a block
 * device is refused whatever else it contains.
 *
 * [storage] is the policy from the environment, or null when PiKit did not hand
 * one over — see [storagePolicyFromEnvironment]. [workspace] is the one directory a
 * recursive delete may target; it defaults to `$HOME/workspace`. [cwd] is where the
 * shell starts, which a relative target resolves against, and it defaults to
 * [workspace] because a caller that names a workspace is the app having done so.
 */
export function inspect(
  command: string,
  home: string,
  storage: StoragePolicy | null = null,
  workspace: string = defaultWorkspace(home),
  cwd: string = workspace,
): Verdict {
  const roots: GuardRoots = { home, workspace, cwd };
  return inspectText(command, roots, storage, 0);
}

/** Depth limit for `sh -c 'sh -c …'`: three levels is more than any real command. */
const MAX_NESTING = 3;

function inspectText(
  command: string,
  roots: GuardRoots,
  storage: StoragePolicy | null,
  depth: number,
): Verdict {
  const text = command.replace(/\\\r?\n/g, " ");
  const { text: flat, bodies } = splitHeredocs(text);

  // A heredoc fed to a shell is a command; one being written to a file is text.
  if (bodies.length > 0 && depth < MAX_NESTING) {
    for (const body of bodies) {
      const verdict = inspectText(body, roots, storage, depth + 1);
      if (verdict.block) return verdict;
    }
  }

  // --- raw devices and filesystem creation --------------------------------
  if (/(^|[;&|]\s*)(mkfs|mke2fs|mkdosfs|mkfs\.\w+|fdisk|sfdisk|parted|wipefs)\b/.test(flat)) {
    return {
      block: true,
      reason:
        "that command creates or modifies a filesystem. PiKit refuses it: on a phone a " +
        "mistake here destroys a partition rather than a directory.",
    };
  }
  if (/\bdd\b[^;&|]*\bof=\s*\/dev\/(block|sd|mmcblk)/.test(flat)) {
    return {
      block: true,
      reason:
        "that command writes directly to a block device. PiKit refuses it: it can brick " +
        "the device, and nothing a coding agent is asked to do needs it.",
    };
  }
  if (/>\s*\/dev\/(block|sd|mmcblk)/.test(flat)) {
    return {
      block: true,
      reason: "that command redirects output onto a block device, which PiKit refuses.",
    };
  }
  if (/(^|[;&|]\s*)(sudo\s+)?(shred|wipe)\b/.test(flat)) {
    return {
      block: true,
      reason: "that command destroys file contents irrecoverably, which PiKit refuses.",
    };
  }

  // Where a relative target would land: `cd /sdcard && rm -rf .` is a recursive
  // delete of shared storage, and it has to be judged by where it runs, not by
  // how the target is spelled.
  const cwd = directoryAfterCd(flat, roots.cwd, roots.home);

  for (const segment of segments(flat)) {
    // The same command one level down, when it was handed to a shell as a string.
    if (depth < MAX_NESTING) {
      for (const inner of innerCommands(segment)) {
        const verdict = inspectText(inner, roots, storage, depth + 1);
        if (verdict.block) return verdict;
      }
    }

    const verdict =
      inspectRecursiveDelete(segment, roots, cwd) ??
      inspectFind(segment, roots, cwd) ??
      inspectRecursivePermission(segment, roots, cwd) ??
      inspectProtected(segment, roots, cwd) ??
      inspectStorage(segment, roots, storage, cwd);
    if (verdict) return verdict;
  }

  return { block: false };
}

/**
 * `rm` with a recursive flag, in any of the orders a person or a model writes.
 *
 * The workspace is the whole of what may be removed as a tree. Everything else
 * under `$HOME` is refused with it, which is the change the workspace exists for:
 * `$HOME` holds pi's configuration and the user's `storage` links, so "inside
 * `$HOME`" was never the right line.
 */
function inspectRecursiveDelete(
  segment: string,
  roots: GuardRoots,
  cwd: string,
): Verdict | null {
  // Anchored to the segment: a recursive `rm` is a command, and the pattern used
  // to fire on the same text inside a quoted argument — `git commit -m 'fix; rm -rf
  // /sdcard case'` was read as a commit followed by a delete, which is the false
  // positive the report named.
  const rmRecursive = /^\s*(sudo\s+)?rm\s+(-[a-z]*r[a-z]*f?|-[a-z]*f[a-z]*r[a-z]*|--recursive)/i;
  if (!rmRecursive.test(segment)) return null;

  const targets = targetsAfter(segment, /\brm\b/);
  const here = targets.some(isCurrentOrParent);
  const rootHit = targets.find(isProtectedRoot);
  if (targets.length === 0 || here || rootHit) {
    return {
      block: true,
      reason: here
        ? `that is a recursive delete of the directory it runs in (${cwd}), which may be ` +
          "the user's own storage. PiKit refuses it: name the path you mean to remove, " +
          "inside the workspace."
        : "that is a recursive delete of a whole storage root. PiKit refuses it because " +
          "it would remove the user's own files, not the agent's workspace.",
    };
  }
  if (targets.some((target) => isWorkspaceRoot(target, roots, cwd))) {
    const named = targets.find((target) => isWorkspaceRoot(target, roots, cwd))!;
    return {
      block: true,
      reason:
        `that is a recursive delete of ${named}, which is outside the agent's workspace ` +
        `(${roots.workspace}). Removals are allowed inside it, at any depth — and a single ` +
        "file anywhere with `rm -f <path>` — but a whole tree only in there. Ask the user " +
        "to confirm, or work inside the workspace.",
    };
  }
  return null;
}

/**
 * `find` with a destructive action, judged by its *starting points*.
 *
 * Everything before the first flag is a path and nothing after it is, which is what
 * keeps `-name '*.log'` from reading as a target. The starting point must be inside
 * the workspace: `find /sdcard …` reaches the user's files however narrow the
 * filter is, and `find ~/storage/shared/ -delete` reached them through a symlink
 * while looking like a path inside `$HOME` — the exception the report named as
 * severe, and the reason this rule no longer asks "is it under `$HOME`".
 *
 * A filter still matters at the workspace root: `find ~/workspace -delete` removes
 * every project, while `find ~/workspace -name '*.tmp' -delete` is ordinary
 * housekeeping. Below the root no filter is needed, because the user's answer to
 * "may I clear this directory" is what put it in the workspace.
 */
function inspectFind(segment: string, roots: GuardRoots, cwd: string): Verdict | null {
  if (!/^\s*(sudo\s+)?find\b[^;&|]*(-delete\b|-exec\s+rm\b)/.test(segment)) return null;

  const targets = startingPointsOf(segment, /\bfind\b/);
  const narrows = /-(name|iname|path|ipath|regex)\b/.test(segment);
  const rootHit = targets.some(isProtectedRoot);
  // Resolved once, because "outside the workspace" has to be told apart from "at
  // the workspace root": the first is always refused, and the second only without a
  // filter. `find . -name '*.log' -delete` starts *at* the root of the workspace and
  // is the ordinary housekeeping this rule must not break.
  const workspace = roots.workspace.replace(/\/+$/, "");
  const resolved = targets.map((target) => collapseTarget(target, roots.home, cwd));
  const outside = resolved.some((path) => path !== workspace && !path.startsWith(`${workspace}/`));
  const atWorkspaceRoot = resolved.some((path) => path === workspace);

  if (targets.length === 0 || rootHit || outside || (atWorkspaceRoot && !narrows)) {
    return {
      block: true,
      reason:
        "that is a recursive delete through `find`, aimed at shared storage or at a " +
        `directory outside the agent's workspace (${roots.workspace}). PiKit refuses it: ` +
        "start the search inside the workspace, and narrow it with `-name` or `-path` if it " +
        "starts at the workspace root.",
    };
  }
  return null;
}

/**
 * Refuses a recursive permission or ownership change on a root the system owns.
 *
 * `chmod -R 000 /system` is not a delete, so none of the rules above saw it, and it
 * is one of the ways a device is bricked softly: every file under the tree becomes
 * unreadable and the only way back is a reinstall. The storage roots and the mount
 * roots are refused; a `chmod` on something the agent owns is its own business.
 */
function inspectRecursivePermission(
  segment: string,
  roots: GuardRoots,
  cwd: string,
): Verdict | null {
  if (!/^\s*(sudo\s+)?ch(mod|own|grp)\b[^\n]*\s-R/.test(segment)) return null;
  const hit = namedPaths(segment, roots.home, cwd).find(isProtectedRoot);
  if (hit === undefined) return null;
  return {
    block: true,
    reason:
      `that changes permissions recursively on ${hit}, which is part of the system rather ` +
      "than of the agent's workspace. PiKit refuses it: a tree that becomes unreadable can " +
      "make the device unusable, and nothing a coding agent is asked to do needs it.",
  };
}

/**
 * The verbs, redirects and in-place edits that rewrite or remove the file they name.
 *
 * A verb is only a *precondition* here: the rule blocks when a protected path is
 * named and one of these is present, so the boundary is a plain word boundary and
 * not the start of the command. That is what catches `xargs rm -f
 * ~/.pi/agent/auth.json`, where the command word is `xargs` and the deletion is an
 * argument to it.
 */
const DESTRUCTIVE_ON_PATH =
  /(^|[\s;&|])(sudo\s+)?(rm|mv|cp|dd|truncate|tee|unlink|rmdir|install|shred|wipe)\b|(^|[\s;&|])(sudo\s+)?(sed|perl)\s+-[a-zA-Z]*i|(^|[\s;&|])(sudo\s+)?ch(mod|own|grp)\b/;

/** `> file`, `>> file` and the no-clobber `:>`, with their target. */
const REDIRECT_TARGET = /(>>?|:>)\s*("[^"]*"|'[^']*'|[^\s;&|<>]+)/g;

/**
 * Refuses a command that deletes or rewrites one of pi's own files.
 *
 * The rule the first five could not express: they are about destroying *trees* and
 * about the user's storage, and the credentials, the guard and the app's own
 * config are single files in the agent's own home. `rm -f ~/.pi/agent/extensions/pi-safety-guard.ts`
 * was allowed — an agent could delete the guard that was refusing its next call —
 * and so were `mv` (rename it and the extension stops loading), `>` (empty it),
 * `sed -i` (turn every `block: true` into `false`) and `chmod -R 000`.
 *
 * Reads are untouched: `cat ~/.pi/agent/settings.json` is how the agent answers
 * questions about its own configuration, and nothing here blocks one.
 */
function inspectProtected(segment: string, roots: GuardRoots, cwd: string): Verdict | null {
  const protected_ = protectedPaths(roots.home);
  const destructive = DESTRUCTIVE_ON_PATH.test(segment);
  const redirects = [...segment.matchAll(REDIRECT_TARGET)].map((match) => match[2]!);
  if (!destructive && redirects.length === 0) return null;

  const named = [...namedPaths(segment, roots.home, cwd), ...redirects.map((target) => collapseTarget(target, roots.home, cwd))];
  const hit = named.find((path) => protected_.some((root) => isUnder(path, root)));
  if (hit === undefined) return null;

  return {
    block: true,
    reason:
      `that would delete or overwrite ${hit}, which belongs to pi rather than to the ` +
      "agent: the guard, the credentials, the settings, the saved conversations or " +
      "PiKit's own configuration. PiKit refuses it. Ask the user if a change there is " +
      "really wanted — it is not something a task should do on its own.",
  };
}

/** True when [path] is [root] itself or lives inside it. */
function isUnder(path: string, root: string): boolean {
  const clean = root.replace(/\/+$/, "");
  return path === clean || path.startsWith(`${clean}/`);
}

/**
 * Refuses a command that names a path inside shared storage the user did not
 * grant, and allows one that stays inside a granted folder.
 *
 * Reads are policed as well as writes, which is the difference between this rule
 * and every other one here: the switches on the storage page are about *reaching*
 * the user's files at all, not only about destroying them. A folder that is off is
 * one the agent may not name, and the refusal says which folders are on so the
 * model can ask for the one it needs.
 *
 * A path under `$HOME/storage/<name>` is judged as the path that link points at,
 * because that is the spelling PiKit itself puts in front of the agent. Without
 * that translation the switches were defeated by their own convenience links.
 */
function inspectStorage(
  segment: string,
  roots: GuardRoots,
  storage: StoragePolicy | null,
  cwd: string,
): Verdict | null {
  if (storage === null || storage.roots.length === 0) return null;

  for (const path of namedPaths(segment, roots.home, cwd)) {
    const resolved = resolveLink(path, roots.home, storage);
    if (!storage.roots.some((root) => isUnder(resolved, root))) continue;
    if (storage.allowed.some((folder) => isUnder(resolved, folder))) continue;
    return {
      block: true,
      reason:
        `that command names ${resolved}, which is in the user's shared storage but outside ` +
        "the folders they granted PiKit" +
        (storage.allowed.length > 0
          ? ` (granted: ${storage.allowed.join(", ")})`
          : " (they have granted none of it)") +
        ". PiKit refuses it: the switches on the storage page decide what the agent may " +
        "reach, and the user can turn the folder on in one tap.",
    };
  }
  return null;
}

/**
 * The real path behind a `~/storage/<name>` link, when PiKit published the farm.
 *
 * `$HOME/storage/shared/Download/a.txt` and `/sdcard/Download/a.txt` are one file;
 * only the second contains a path the policy is written in. The mapping comes from
 * the app (`PIKIT_STORAGE_LINKS`, built from the farm it actually created), so a
 * revoked folder has no name to translate and a name it never created cannot be
 * invented by the command.
 */
function resolveLink(path: string, home: string, storage: StoragePolicy): string {
  const root = `${home.replace(/\/+$/, "")}/storage/`;
  if (!path.startsWith(root)) return path;
  const rest = path.slice(root.length);
  const slash = rest.indexOf("/");
  const name = slash === -1 ? rest : rest.slice(0, slash);
  const target = storage.links?.[name];
  if (target === undefined) return path;
  return slash === -1 ? target : target + rest.slice(slash);
}

/**
 * The paths a command names, as the shell would resolve them.
 *
 * Every whitespace-separated word is a candidate: the test that follows is
 * "does this resolve inside shared storage", and a word that does not is
 * discarded there. A word with an `--option=value` shape contributes its value
 * (`--file=/sdcard/x`), which is where a tool call that names a file outside the
 * granted folders most often hides it.
 */
function namedPaths(text: string, home: string, cwd: string): string[] {
  const paths: string[] = [];
  for (const raw of text.split(/[\s;&|()<>]+/)) {
    const token = raw.trim();
    if (token.length === 0) continue;
    const value = token.includes("=") ? token.slice(token.indexOf("=") + 1) : token;
    if (value.length === 0) continue;
    paths.push(collapseTarget(value, home, cwd));
  }
  return paths;
}

/**
 * The arguments of a command, up to the next separator.
 *
 * Used for `rm`, where every non-flag argument is a target.
 */
function targetsAfter(text: string, command: RegExp): string[] {
  const match = command.exec(text);
  if (!match) return [];
  const rest = text.slice(match.index + match[0].length);
  const segment = rest.split(/[;&|]/)[0]!;
  return segment
    .split(/\s+/)
    .map((token) => token.trim())
    .filter((token) => token.length > 0 && !token.startsWith("-"));
}

/**
 * A `find` command's starting points: its non-flag arguments before the first
 * flag.
 *
 * `find`'s grammar is `find <paths...> <expression>`, so everything before the
 * first `-flag` is a path and nothing after it is. Without this split, the value
 * of `-name` reads as a target: `find . -name '*.log' -delete` becomes "delete
 * `$HOME/*.log`", and a guard that refuses that refuses ordinary housekeeping.
 */
function startingPointsOf(text: string, command: RegExp): string[] {
  const match = command.exec(text);
  if (!match) return [];
  const rest = text.slice(match.index + match[0].length);
  const segment = rest.split(/[;&|]/)[0]!;
  const paths: string[] = [];
  for (const raw of segment.split(/\s+/)) {
    const token = raw.trim();
    if (token.length === 0) continue;
    if (token.startsWith("-")) break;
    paths.push(token);
  }
  return paths;
}

/**
 * The refusal for pi's file tools — `write` and `edit` — aimed at one of pi's own
 * files.
 *
 * The `bash` rules cannot see these: `write` takes a path and a body and no shell is
 * involved, so `write ~/.pi/agent/auth.json` blanks the credentials with nothing for
 * the text matcher to look at, and `edit` rewrites a line of the guard itself. The
 * path is all there is to judge, and the same protected list decides it. Everything
 * outside that list is the agent's to write, including a project's own
 * `<project>/.pi/settings.json`, which is not under `$HOME/.pi`.
 */
export function inspectFileTool(
  path: string,
  home: string,
  workspace: string = defaultWorkspace(home),
  cwd: string = workspace,
): Verdict {
  const resolved = collapseTarget(path, home, cwd);
  const hit = protectedPaths(home).find((root) => isUnder(resolved, root));
  if (hit === undefined) return { block: false };
  return { block: true, reason: fileToolRefusal(resolved) };
}

/** Why a file tool aimed at one of pi's own files is refused. */
function fileToolRefusal(resolved: string): string {
  return (
    `that writes to ${resolved}, which belongs to pi rather than to the agent: the ` +
    "guard, the credentials, the settings, the saved conversations or PiKit's own " +
    "configuration. PiKit refuses it. Ask the user if a change there is really wanted " +
    "— it is not something a task should do on its own."
  );
}

export default function (pi: ExtensionAPI) {
  const roots = environmentRoots();
  // Read once, at startup, the way the storage policy reaches this process: PiKit
  // restarts the agent when a switch changes, so a cached copy cannot go stale.
  const storage = storagePolicyFromEnvironment();
  const { home, workspace, cwd } = roots;

  pi.on("tool_call", async (event) => {
    const input = event.input as { command?: unknown; path?: unknown } | undefined;

    // The two file tools, which carry a path and no command text at all. A relative
    // path resolves against the directory the session runs in, which is [cwd].
    if (event.toolName === "write" || event.toolName === "edit") {
      const path = typeof input?.path === "string" ? input.path : null;
      if (!path) return;
      const verdict = inspectFileTool(path, home, workspace, cwd);
      if (!verdict.block) return;
      return { block: true, reason: refusal(verdict.reason ?? "", workspace) };
    }

    if (event.toolName !== "bash") return;

    const command = typeof input?.command === "string" ? input.command : null;
    if (!command) return;

    const verdict = inspect(command, home, storage, workspace, cwd);
    if (!verdict.block) return;

    return { block: true, reason: refusal(verdict.reason ?? "", workspace) };
  });
}

/** What the model is told, whatever rule refused the call. */
function refusal(reason: string, workspace: string): string {
  return (
    `${reason}\n\n` +
    `Tell the user what you were about to do and ask before retrying. Shared storage ` +
    `(\`/sdcard\`, the \`~/storage\` links) holds their real files; \`${workspace}\` is your ` +
    "workspace, and a single file outside it can still be removed with `rm -f <path>`."
  );
}
