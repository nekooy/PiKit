/**
 * Checks the agent safety guard's rules against a table of real commands.
 *
 * The guard is what stands between an autonomous agent and the user's files, so
 * "it is described in a comment" is not good enough — this runs the same
 * `inspect()` the extension runs, over commands of the shape that has actually
 * destroyed data, and over the ordinary work that must keep working.
 *
 * Two things are being tested, and the second matters as much as the first: a
 * guard that blocks legitimate commands gets deleted by the user, and a deleted
 * guard protects nothing. Every allowed case below is a command a coding agent
 * genuinely needs on a phone.
 *
 * Four axes are covered, because the report that produced this version named all
 * four: a recursive delete outside the workspace must be refused; ordinary work
 * outside the workspace must not be; work *inside* the workspace must be; and the
 * guard's own files, pi's credentials and PiKit's configuration must survive an
 * agent that decides to tidy them up.
 *
 * Run: `node --experimental-strip-types tools/test-safety-guard.mjs`
 * (Node 22+ strips the types; the file is plain JavaScript apart from them.)
 */

import { guardEnabled, inspect, inspectFileTool, storagePolicyFromEnvironment } from "./pi-safety-guard.ts";

const HOME = "/data/data/pi.kit.mob/files/home";
const WORKSPACE = `${HOME}/workspace`;
/** The app's private files directory: `pikit-config.json` lives here. */
const APP_FILES = "/data/data/pi.kit.mob/files";

/**
 * The policy the app hands the agent for the cases below: Downloads and DCIM on,
 * everything else on the user's phone off. Both spellings of the root, because a
 * command may name either and the app writes both — plus the `~/storage` farm the
 * app created, which is how the link spelling is judged as the path it points at.
 */
const GRANTED_SOME = {
  roots: ["/storage/emulated/0", "/sdcard", "/storage/self/primary"],
  allowed: [
    "/storage/emulated/0/Download",
    "/sdcard/Download",
    "/storage/emulated/0/DCIM",
    "/sdcard/DCIM",
  ],
  links: {
    shared: "/storage/emulated/0",
    downloads: "/storage/emulated/0/Download",
    dcim: "/storage/emulated/0/DCIM",
  },
};

/** Nothing switched on, which is what the app defaults to: no farm exists. */
const GRANTED_NOTHING = { roots: GRANTED_SOME.roots, allowed: [], links: {} };

/**
 * Nothing granted, but with the farm present anyway — the case the report filed as
 * "the switch does nothing": `~/storage/shared/Download/a.txt` names no
 * shared-storage path at all, so only translating the link can refuse it.
 */
const GRANTED_NOTHING_LINKS = { roots: GRANTED_SOME.roots, allowed: [], links: GRANTED_SOME.links };

/**
 * The same policy in the spelling PiKit actually writes into the environment, so
 * the parser is exercised rather than bypassed: `PIKIT_STORAGE_ROOTS` and
 * `PIKIT_STORAGE_ALLOWED` are `:`-separated, the link farm is one `name=target`
 * per line.
 */
const GRANTED_ENV = {
  PIKIT_STORAGE_ROOTS: GRANTED_SOME.roots.join(":"),
  PIKIT_STORAGE_ALLOWED: GRANTED_SOME.allowed.join(":"),
  PIKIT_STORAGE_LINKS: Object.entries(GRANTED_SOME.links)
    .map(([name, target]) => `${name}=${target}`)
    .join("\n"),
};

/** Commands that must be refused with no storage policy at all. */
const MUST_BLOCK = [
  // The one that started all of this: a recursive delete of shared storage.
  "rm -rf /sdcard",
  "rm -rf /sdcard/",
  "rm -rf ~/storage/shared",
  "rm -rf /storage/emulated/0",
  "rm -rf /",
  "rm -rf /data",
  "rm -r /sdcard/DCIM",
  "sudo rm -rf /sdcard/*",
  "rm -fr /sdcard/Pictures",
  "cd /sdcard && rm -rf .",
  "cd /sdcard && rm -rf *",
  // A delete of an absolute path outside the workspace, even a narrow one.
  "rm -rf /data/local/tmp/build",
  "rm -rf /system/etc",
  // The workspace's own boundary: `$HOME` is not the workspace any more, and the
  // workspace root itself is not a scratch directory.
  "rm -rf ~",
  "rm -rf $HOME",
  "rm -rf ${HOME}",
  'rm -rf "$HOME"',
  "rm -rf $HOME/../..",
  'rm -rf "${HOME}/"',
  "rm -rf ~/workspace",
  "rm -rf $HOME/workspace",
  "rm -rf ~/workspace/",
  // `$HOME` used to expand only as `~`, which made one command have two answers:
  // `rm -rf ~/tmp/x` was refused and `rm -rf "$HOME/tmp/x"` was allowed. Inside
  // **single** quotes the shell does not expand, and neither does the guard — the
  // second case here is why that distinction is load bearing rather than symmetry.
  "rm -rf ~/tmp/x",
  'rm -rf "$HOME/tmp/x"',
  "find ~ -delete",
  'find "$HOME" -delete',
  "find $HOME -delete",
  "cd /sdcard && rm -rf '$HOME/workspace/x'",
  "rm -rf ~/.pi",
  // Through `find`, which is how a "clean up old files" task goes wrong.
  "find /sdcard -name '*.jpg' -delete",
  "find /sdcard -type f -exec rm {} \\;",
  "find /storage/emulated/0 -delete",
  "find . -delete",
  "find ~ -delete",
  "find /sdcard -name x -delete",
  // Not `$HOME` any more: `~/storage` is a symlink into the user's real storage,
  // and `find` used to walk it because the rule only asked "is it under `$HOME`".
  "find ~/storage/shared/ -delete",
  "find ~/storage/shared/ -name '*.jpg' -delete",
  "find ~/storage/downloads/ -type f -exec rm {} \\;",
  // A three-line script, which the old text-wide patterns never looked at: the
  // command has to be at the start of a line, and a newline was not a separator.
  "cd /sdcard\nrm -rf .",
  "echo start\nrm -rf /sdcard/DCIM\necho done",
  // The same command written one level down. A quoted string handed to a shell is
  // a command, and it is inspected as one.
  "sh -c 'rm -rf ~'",
  'bash -c "rm -rf /sdcard"',
  "eval 'rm -rf $HOME'",
  "bash <<'EOF'\nrm -rf /sdcard\nEOF",
  // Filesystem creation.
  "mkfs.ext4 /dev/block/mmcblk0p1",
  "mke2fs -t ext4 /dev/block/by-name/userdata",
  "fdisk /dev/block/mmcblk0",
  "wipefs -a /dev/block/mmcblk0p1",
  // Raw device writes.
  "dd if=/dev/zero of=/dev/block/mmcblk0",
  "echo x > /dev/block/mmcblk0p1",
  // Irrecoverable destruction.
  "shred -u /sdcard/secret.key",
  // Recursive permission changes on a root the system owns.
  "chmod -R 000 /system",
  "sudo chown -R nobody /data",
];

/**
 * Commands that must be refused because they rewrite or delete pi's own files.
 *
 * The first five rules are about trees and about the user's storage; these are
 * single files in the agent's own home, and `rm -f` on a single file is allowed
 * everywhere else. That is the whole point of the list: the path is the only thing
 * that separates "delete a stale note" from "delete the credentials", "delete the
 * guard that is refusing the next call", or "blank the app's API keys".
 */
const MUST_BLOCK_PROTECTED = [
  "rm -rf ~/.pi",
  "rm -rf ~/.pi/agent",
  "rm -rf ~/.pi/agent/extensions",
  "rm -f ~/.pi/agent/extensions/pi-safety-guard.ts",
  "rm -f $HOME/.pi/agent/extensions/pi-safety-guard.ts",
  "mv ~/.pi/agent/extensions ~/.pi/agent/extensions.off",
  "mv ~/.pi/agent/extensions/pi-safety-guard.ts /tmp/",
  "sed -i s/block:true/false/ ~/.pi/agent/extensions/pi-safety-guard.ts",
  "perl -i -pe 's/true/false/' ~/.pi/agent/extensions/pi-safety-guard.ts",
  "> ~/.pi/agent/extensions/pi-safety-guard.ts",
  ":> ~/.pi/agent/extensions/pi-safety-guard.ts",
  "truncate -s 0 ~/.pi/agent/extensions/pi-safety-guard.ts",
  "tee ~/.pi/agent/extensions/pi-safety-guard.ts",
  "rm -f ~/.pi/agent/auth.json",
  "rm -f ~/.pi/agent/settings.json",
  "rm -f ~/.pi/agent/models.json",
  "rm -f ~/.pi/agent/web-search.json",
  "rm -f ~/.pi/agent/AGENTS.md",
  "rm -rf ~/.pi/agent/sessions",
  "rm -f ~/.pi/agent/sessions/2026-01-01.jsonl",
  "cp /tmp/empty.json ~/.pi/agent/auth.json",
  "echo '{}' > ~/.pi/agent/web-search.json",
  "chmod -R 000 ~/.pi/agent",
  "chmod 000 ~/.pi/agent/auth.json",
  `rm -f ${APP_FILES}/pikit-config.json`,
  `> ${APP_FILES}/pikit-config.json`,
  `rm -rf ${APP_FILES}/pi-sessions`,
  // The same, reached with `~` expanded by the shell rather than by the guard.
  `rm -f "${HOME}/.pi/agent/auth.json"`,
];

/** Commands that must be refused because they reach into ungranted shared storage. */
const MUST_BLOCK_STORAGE = [
  // A folder that is switched off, named directly.
  "cat /sdcard/Music/song.mp3",
  "cat /storage/emulated/0/Music/song.mp3",
  "ls /sdcard/DCIM/../Music",
  "cp -r /sdcard/Movies ~/tmp/",
  "find /sdcard/Pictures -name '*.jpg'",
  "python3 -c \"open('/sdcard/Documents/notes.txt')\"",
  // The tree itself, which is nobody's granted folder.
  "ls -la /sdcard",
  "ls /storage/emulated/0",
  "du -sh /sdcard",
  "tar czf out.tgz /sdcard",
  // Reached by a `cd` first, which is how a relative target becomes absolute.
  "cd /sdcard/Music && cat song.mp3",
  // The app's own convenience links. These name the user's storage without
  // containing any of its paths, and every one of them used to be allowed.
  "cat ~/storage/shared/Music/song.mp3",
  "cat ~/storage/shared/Download/../Music/song.mp3",
  "ls ~/storage/shared",
  "tail -f ~/storage/shared/Movies/song.mp4",
];

/** Commands that must be allowed under the same policy, because they are inside it. */
const MUST_ALLOW_STORAGE = [
  // The granted folders, in every spelling.
  "cat /sdcard/Download/notes.txt",
  "cat /storage/emulated/0/Download/notes.txt",
  "cp /sdcard/DCIM/photo.jpg ~/workspace/tmp/",
  "ls -la /sdcard/DCIM/2026",
  "mkdir -p /sdcard/Download/project",
  "echo hello > /sdcard/Download/out.txt",
  // The app's own `~/storage` links, translated to the folders that are on.
  "ls ~/storage/downloads",
  "cat ~/storage/dcim/photo.jpg",
  "cat ~/storage/shared/Download/notes.txt",
  "cat ~/storage/shared/DCIM/2026/photo.jpg",
  // The workspace, which the policy says nothing about.
  "rm -rf ~/workspace/tmp/build",
  "cat ~/notes.md",
  "git clone https://github.com/termux/termux-packages",
  "pkg install ripgrep",
];

/**
 * Commands that must be allowed, because an agent needs them.
 *
 * The workspace is where these run: an agent's shell starts there, so a relative
 * `node_modules` is `~/workspace/node_modules`.
 */
const MUST_ALLOW = [
  // Ordinary workspace work, at any depth. The old whitelist knew only the first
  // level under `$HOME` (`$HOME/node_modules`), so `rm -rf ~/myapp/node_modules`
  // — the inside of a project — was refused while the top-level one was allowed.
  "rm -rf node_modules",
  "rm -rf ./dist",
  "rm -rf build",
  `rm -rf ${WORKSPACE}/tmp/build`,
  "rm -rf ~/workspace/tmp/cache",
  "rm -rf ~/workspace/myapp/node_modules",
  "rm -rf ~/workspace/a/b/c/d/e",
  "rm -rf $HOME/workspace/old",
  'rm -rf "$HOME/workspace/old"',
  "rm -f stale.txt",
  "rm package-lock.json",
  // Reading and searching.
  "find . -name '*.log' -delete",
  "find . -name '*.tmp' -delete",
  `find ${WORKSPACE}/tmp -type f -delete`,
  `find ${WORKSPACE}/project -delete`,
  "find ~/workspace -name '*.tmp' -delete",
  // A single file outside the workspace is still the agent's to remove: a tree is
  // not recoverable and a named file is, and an agent that cannot delete a stale
  // file is not usable.
  "rm -f ~/notes.md",
  "rm -f $HOME/tmp/stale.log",
  `rm -f ${APP_FILES}/usr/tmp/scratch.txt`,
  "rm -f ~/storage/shared/Download/old.txt",
  // The three false positives the report named. Each is one command with a
  // separator inside a quoted string, and the old patterns read the text as two.
  "git commit -m 'fix; rm -rf /sdcard case'",
  "grep -rn '&& rm -rf' .",
  "echo 'a; rm -rf /tmp/x'",
  "cat > build.sh <<'EOF'\nrm -rf build\nEOF",
  "cat > notes.md <<'MARKDOWN'\nnever run `rm -rf /`\nMARKDOWN",
  // Ordinary use of shared storage, in its granted spelling.
  "cp /sdcard/Download/photo.jpg ~/workspace/tmp/",
  // Package management, which is what the relocation work is about.
  "pkg install ripgrep",
  "apt update && apt install -y fd",
  "npm install --omit=dev",
  "git clone https://github.com/termux/termux-packages",
  // A bare `find` with no destructive action.
  "find /sdcard -name '*.pdf'",
  "find ~/storage/shared -type f",
  // Reading pi's own files is how the agent answers questions about itself.
  "cat ~/.pi/agent/settings.json",
  "grep -n defaultTools ~/.pi/agent/settings.json",
];

let failures = 0;

function report(ok, label, detail) {
  console.log(`  ${ok ? "ok  " : "FAIL"}  ${label}`);
  if (!ok) {
    failures += 1;
    if (detail) console.log(`        ${detail}`);
  }
}

function check(command, want, storage = null, label = command) {
  const verdict = inspect(command, HOME, storage, WORKSPACE);
  if (want) {
    report(verdict.block, label, verdict.block ? undefined : "was allowed");
  } else {
    report(!verdict.block, label, verdict.block ? `was blocked: ${verdict.reason}` : undefined);
  }
}

console.log("=== commands that must be refused");
for (const command of MUST_BLOCK) check(command, true);

console.log("=== pi's own files and the app's configuration");
for (const command of MUST_BLOCK_PROTECTED) check(command, true);

console.log("=== commands that must be allowed");
for (const command of MUST_ALLOW) check(command, false);

console.log("=== storage the user granted: refused outside it");
for (const command of MUST_BLOCK_STORAGE) check(command, true, GRANTED_SOME);

console.log("=== storage the user granted: allowed inside it");
for (const command of MUST_ALLOW_STORAGE) check(command, false, GRANTED_SOME);

console.log("=== nothing granted: the granted-folder cases are refused too");
for (const command of [
  "cat /sdcard/Download/notes.txt",
  "ls /sdcard/Download",
  "cat ~/storage/shared/Download/notes.txt",
  "ls ~/storage/downloads",
]) {
  check(command, true, GRANTED_NOTHING_LINKS);
}

console.log("=== the whole tree granted: everything under it is allowed");
for (const command of ["ls -la /sdcard", "cat /sdcard/Music/song.mp3", "rm -f /sdcard/Download/x"]) {
  check(command, false, { roots: GRANTED_SOME.roots, allowed: GRANTED_SOME.roots, links: GRANTED_SOME.links });
}

console.log("=== no policy at all: the guard is inert (someone ran pi by hand)");
for (const command of ["ls -la /sdcard", "cat /sdcard/Music/song.mp3"]) {
  check(command, false, null);
}

console.log("=== the storage policy, in both spellings, from the environment");
for (const command of ["cat /sdcard/Download/notes.txt", "cat ~/storage/shared/Download/notes.txt"]) {
  report(
    !inspect(command, HOME, storagePolicyFromEnvironment(GRANTED_ENV), WORKSPACE).block,
    command,
  );
}
for (const command of ["cat /sdcard/Music/song.mp3", "cat ~/storage/shared/Music/song.mp3"]) {
  const verdict = inspect(command, HOME, storagePolicyFromEnvironment(GRANTED_ENV), WORKSPACE);
  report(verdict.block, command, verdict.block ? undefined : "was allowed");
}

console.log("=== the policy comes from the environment PiKit sets");
{
  const none = storagePolicyFromEnvironment({});
  report(none === null, "no variables: no policy", none === null ? undefined : "got one");
  const empty = storagePolicyFromEnvironment({ PIKIT_STORAGE_ROOTS: "/sdcard,/storage/emulated/0" });
  report(
    empty !== null && empty.allowed.length === 0,
    "roots without an allowed list: granted nothing",
    empty === null ? "got none" : JSON.stringify(empty),
  );
  const some = storagePolicyFromEnvironment({
    PIKIT_STORAGE_ROOTS: "/sdcard",
    PIKIT_STORAGE_ALLOWED: "/sdcard/DCIM",
  });
  report(
    some !== null && some.allowed.length === 1 && some.roots.length === 1,
    "both lists are read",
    some === null ? "got none" : JSON.stringify(some),
  );
  const links = storagePolicyFromEnvironment({
    PIKIT_STORAGE_ROOTS: "/sdcard",
    PIKIT_STORAGE_ALLOWED: "/sdcard/DCIM",
    PIKIT_STORAGE_LINKS: "dcim=/sdcard/DCIM\ndownloads=/sdcard/Download",
  });
  report(
    links?.links?.dcim === "/sdcard/DCIM" && links?.links?.downloads === "/sdcard/Download",
    "the link farm is read, one `name=target` per line",
    links === null ? "got none" : JSON.stringify(links),
  );
  const noWorkspace = inspect("rm -rf ~/workspace/x", HOME);
  report(!noWorkspace.block, "the workspace defaults to `$HOME/workspace`", noWorkspace.reason);
}

console.log("=== a relative target is resolved against where the shell really is");
{
  // PiKit launches the agent in the workspace, so `rm -rf node_modules` is a project
  // cleanup. A `pi` run by hand in the Terminal tab starts in `$HOME` (`~`), where
  // the same text names `$HOME/node_modules` — and allowing that would be a rule
  // written for the workspace applied to a directory that is not in it.
  const inWorkspace = inspect("rm -rf node_modules", HOME, null, WORKSPACE, WORKSPACE);
  report(!inWorkspace.block, "in the workspace: allowed", inWorkspace.reason);
  const inHome = inspect("rm -rf node_modules", HOME, null, WORKSPACE, HOME);
  report(inHome.block, "hand-run from $HOME: refused", inHome.block ? undefined : "was allowed");
  // An absolute target behaves the same from either starting point.
  for (const cwd of [HOME, WORKSPACE]) {
    const verdict = inspect(`rm -rf ${WORKSPACE}/build`, HOME, null, WORKSPACE, cwd);
    report(!verdict.block, `absolute target from ${cwd}`, verdict.reason);
  }
}

console.log("=== the file tools, which carry no command text");
{
  // `write` and `edit` take a path and a body; a `bash` rule never sees them, and
  // `write ~/.pi/agent/auth.json` blanks the credentials with no shell involved.
  const blocked = [
    `${HOME}/.pi/agent/auth.json`,
    "~/.pi/agent/auth.json",
    "~/.pi/agent/extensions/pi-safety-guard.ts",
    "~/.pi/agent/settings.json",
    "~/.pi/agent/AGENTS.md",
    "~/.pi/agent/sessions/2026-01-01.jsonl",
    `${APP_FILES}/pikit-config.json`,
  ];
  for (const path of blocked) {
    const verdict = inspectFileTool(path, HOME, WORKSPACE);
    report(verdict.block, path, verdict.block ? undefined : "was allowed");
  }
  const allowed = [
    "~/workspace/notes.md",
    "notes.md",
    "~/workspace/myapp/.pi/settings.json",
    `${WORKSPACE}/src/main.kt`,
    `${HOME}/.termux/termux.properties`,
  ];
  for (const path of allowed) {
    const verdict = inspectFileTool(path, HOME, WORKSPACE);
    report(!verdict.block, path, verdict.block ? `was blocked: ${verdict.reason}` : undefined);
  }
}

console.log("=== the switch that takes the guard out of force");
{
  // The app writes `PIKIT_SAFETY_GUARD` into every process it spawns. Only the exact
  // string `off` may disable the guard: a missing variable is a `pi` started outside
  // PiKit, and a missing variable must never be what turns a guard off.
  report(guardEnabled({}), "unset means the guard runs");
  report(guardEnabled({ PIKIT_SAFETY_GUARD: "on" }), "`on` means the guard runs");
  report(!guardEnabled({ PIKIT_SAFETY_GUARD: "off" }), "`off` takes it out of force");
  report(guardEnabled({ PIKIT_SAFETY_GUARD: "OFF" }), "only the lower-case `off` does");
  report(guardEnabled({ PIKIT_SAFETY_GUARD: "false" }), "`false` is not the magic value");
  report(guardEnabled({ PIKIT_SAFETY_GUARD: "" }), "an empty value leaves it running");
}

const total =
  MUST_BLOCK.length +
  MUST_BLOCK_PROTECTED.length +
  MUST_ALLOW.length +
  MUST_BLOCK_STORAGE.length +
  MUST_ALLOW_STORAGE.length +
  4 + // nothing granted
  3 + // whole tree granted
  2 + // no policy
  4 + // policy in both spellings
  7 + // the environment block
  4 + // where the shell really is
  12 + // the file tools
  6; // the effectiveness switch
console.log();
if (failures > 0) {
  console.log(`FAIL — ${failures} of ${total} cases wrong`);
  process.exit(1);
}
console.log(`PASS — ${total} cases`);
