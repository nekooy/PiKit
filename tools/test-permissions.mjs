/**
 * Checks the permission tiers against a table of real tool calls.
 *
 * The extension is the only thing between an autonomous agent and the user's
 * files, so "it is described in a comment" is not good enough. This runs the same
 * `checkToolCall()` the extension runs, over the calls that have actually damaged
 * data, over the ordinary work that must keep working in each tier, and over the
 * one switch that decides which tier applies.
 *
 * Four axes are covered, because the contract is four axes: every tier must allow
 * the workspace; `workspace-write` must refuse a write outside it; the same call
 * in `full-access` must be allowed (the table derives that check from every
 * refusal below, so a refusal and its escape cannot drift apart); and `read-only`
 * must refuse every change while still letting the agent *look* at the project.
 *
 * The environment and the permission file are constructed here rather than
 * written to disk: `resolveMode()` is a pure function of `(env, contents)`, and
 * `guardCall()` takes the reader as an argument, which is also how "the file is
 * read on every call" is pinned — the same command changes answer between two
 * calls that return different contents.
 *
 * Run: `node tools/test-permissions.mjs`
 * (Node strips the extension's TypeScript as it imports it; this file is plain
 * JavaScript.)
 */

import {
  checkToolCall,
  guardCall,
  permissionFilePath,
  resolveMode,
} from "./pikit-permissions.ts";

const HOME = "/data/data/pi.kit.mob/files/home";
const WORKSPACE = `${HOME}/workspace`;
const PREFIX = "/data/data/pi.kit.mob/files/usr";
const PERMISSION_FILE = "/data/data/pi.kit.mob/files/pikit-permission.json";

/**
 * What the app hands the agent: the workspace, Downloads granted in both
 * spellings the app writes, and the `~/storage` farm it created. DCIM is
 * deliberately not in `PIKIT_STORAGE_ALLOWED` even though a link to it exists —
 * that is the case a rule that only matched absolute paths would miss.
 */
const ENV = {
  HOME,
  PREFIX,
  PIKIT_WORKSPACE: WORKSPACE,
  PIKIT_PERMISSION_FILE: PERMISSION_FILE,
  PIKIT_STORAGE_ALLOWED: "/sdcard/Download:/storage/emulated/0/Download",
  PIKIT_STORAGE_LINKS: [
    "downloads=/storage/emulated/0/Download",
    "dcim=/storage/emulated/0/DCIM",
    "shared=/storage/emulated/0",
  ].join("\n"),
};

/** The writable roots, as the refusal text lists them (used in one assertion). */
const ROOTS = [WORKSPACE, "/sdcard/Download", "/storage/emulated/0/Download"];

const bash = (command, allow) => ({ tool: "bash", input: { command }, allow });
const write = (path, allow) => ({ tool: "write", input: { path }, allow });
const edit = (path, allow) => ({ tool: "edit", input: { path }, allow });
const call = (tool, input, allow) => ({ tool, input, allow });

/** The table. `allow` is what the tier must answer, not what a shell would do. */
const SECTIONS = [
  {
    title: "workspace-write: ordinary work inside the workspace is allowed",
    mode: "workspace-write",
    cases: [
      write("notes.md", true),
      edit("src/main.ts", true),
      bash("rm -rf node_modules", true),
      bash("mkdir -p src/foo && touch src/foo/.keep", true),
      bash("npm install --omit=dev", true),
      // The separator inside a quoted argument is one command, not two — the
      // false positive the old table named.
      bash("git commit -m 'fix; rm -rf /sdcard case'", true),
      bash("sed -i 's/a/b/' src/main.ts", true),
      // A heredoc written to a file only stores text, even when the text is a
      // command; refusing it is the false positive the old table named.
      bash("cat > build.sh <<'EOF'\nrm -rf build\nEOF", true),
      bash("sh -c 'rm -rf build'", true),
      bash("git clone https://github.com/termux/termux-packages", true),
    ],
  },
  {
    title: "workspace-write: a write outside the workspace is refused",
    mode: "workspace-write",
    cases: [
      write("/sdcard/Music/song.mp3", false),
      edit("~/.pi/agent/settings.json", false),
      bash("rm -rf /sdcard/Music", false),
      bash("rm -rf ~", false),
      bash("rm -rf /", false),
      bash("mv ~/notes.md /sdcard/Music/", false),
      bash("echo x > /sdcard/Music/x.txt", false),
      // `cd` first: the target looks relative, and lands outside the workspace.
      bash("cd /sdcard && rm -rf .", false),
      // The same command written one level down.
      bash("sh -c 'rm -rf /sdcard/x'", false),
      bash("bash <<'EOF'\nrm -rf /sdcard/x\nEOF", false),
      // `find` deletes the tree it starts in, however narrow the filter is.
      bash("find /sdcard -name '*.jpg' -delete", false),
      // Filesystem creation and raw-device writes fall out of the path rule
      // (their target is outside the workspace); the refusal says which.
      bash("mkfs.ext4 /dev/block/mmcblk0p1", false),
      bash("dd if=/dev/zero of=/dev/block/mmcblk0", false),
      bash("echo x > /dev/block/mmcblk0", false),
      // A package install writes the runtime, not the workspace.
      bash("pkg install ripgrep", false),
      // The app publishes `$PREFIX` into every shell, so naming the runtime
      // through it is naming the runtime — not a relative path in the workspace.
      bash("rm -rf $PREFIX", false),
    ],
  },
  {
    title: "workspace-write: reads are free, anywhere",
    mode: "workspace-write",
    cases: [
      bash("cat /sdcard/Music/song.mp3", true),
      bash("grep -rn TODO /sdcard/Notes", true),
      bash("find /sdcard -name '*.pdf'", true),
      // `sed` without `-i` reads and prints; only the in-place form writes.
      bash("sed 's/a/b/' /sdcard/Music/x", true),
      bash("git -C /sdcard/repo status", true),
      bash("cat ~/.pi/agent/settings.json", true),
    ],
  },
  {
    title: "workspace-write: the storage page's switches are writable roots",
    mode: "workspace-write",
    cases: [
      write("/sdcard/Download/notes.md", true),
      // The only spelling PiKit itself hands the agent: judged as the path the
      // link points at, or the grant could be stepped around by its own farm.
      write("~/storage/downloads/notes.md", true),
      bash("echo hi > /sdcard/Download/out.txt", true),
      bash("rm -f ~/storage/downloads/old.txt", true),
      // Reading an ungranted folder is free; the write lands in the workspace.
      bash("cp /sdcard/DCIM/photo.jpg ~/workspace/tmp/", true),
      // The link resolves into shared storage the user did not grant.
      write("~/storage/shared/Music/song.mp3", false),
      bash("rm -rf ~/storage/shared/Music", false),
    ],
  },
  {
    title: "read-only: the file tools are always refused",
    mode: "read-only",
    cases: [
      write("notes.md", false),
      edit("~/workspace/src/main.ts", false),
      write("/sdcard/Download/notes.md", false),
      // Non-shell tools that cannot write are untouched.
      call("read", { path: "src/main.ts" }, true),
      call("web_fetch", { url: "https://example.com" }, true),
    ],
  },
  {
    title: "read-only: a mutating command is refused",
    mode: "read-only",
    cases: [
      bash("rm -rf build", false),
      bash("echo hi > out.txt", false),
      bash("sed -i 's/a/b/' src/main.ts", false),
      bash("npm install", false),
      bash("pkg install ripgrep", false),
      bash("git commit -m 'change'", false),
      bash("find . -delete", false),
      // A command nothing has vouched for is refused, not assumed harmless.
      bash("python -c 'print(1)'", false),
      // A shell running a string is judged by the string.
      bash("sh -c 'rm -rf build'", false),
    ],
  },
  {
    title: "read-only: inspection commands are allowed",
    mode: "read-only",
    cases: [
      bash("cat notes.md", true),
      bash("grep -rn TODO src", true),
      bash("rg 'fn main' .", true),
      bash("find . -name '*.kt'", true),
      bash("sed -n '1,5p' src/x", true),
      bash("git status", true),
      bash("git log --oneline", true),
      bash("git branch -a", true),
      bash("git config --get user.name", true),
      bash("node -v", true),
      bash("python --version", true),
      // Read-only is about the filesystem, not about the workspace: looking at
      // an ungranted folder is exactly what inspection is for.
      bash("cat /sdcard/Music/song.mp3", true),
      bash("cd /sdcard && ls", true),
      bash("ls -la 2>/dev/null", true),
    ],
  },
];

let failures = 0;
let total = 0;

function report(ok, label, detail) {
  console.log(`  ${ok ? "ok  " : "FAIL"}  ${label}`);
  if (!ok) {
    failures += 1;
    if (detail) console.log(`        ${detail}`);
  }
}

/** What a case calls itself in a verdict line. */
function label(testCase) {
  const input = testCase.input;
  const value =
    typeof input.command === "string"
      ? input.command.replace(/\n/g, " ⏎ ")
      : typeof input.path === "string"
        ? input.path
        : JSON.stringify(input);
  return `${testCase.tool}: ${value}`;
}

/** One table row, and — for every refusal — the same call in full-access. */
function check(testCase, mode) {
  const verdict = checkToolCall(testCase.tool, testCase.input, ENV, mode);
  total += 1;
  report(
    verdict.block === !testCase.allow,
    label(testCase),
    verdict.block === !testCase.allow
      ? undefined
      : testCase.allow
        ? `was blocked: ${verdict.reason}`
        : "was allowed",
  );
  if (!testCase.allow && mode !== "full-access") {
    // The escape hatch is part of the contract for every refusal, and deriving it
    // from the table is what stops a new rule being added without one.
    const escape = checkToolCall(testCase.tool, testCase.input, ENV, "full-access");
    total += 1;
    report(!escape.block, `  full-access allows: ${label(testCase)}`);
  }
}

for (const section of SECTIONS) {
  console.log(`=== ${section.title}`);
  for (const testCase of section.cases) check(testCase, section.mode);
}

console.log("=== the permission file decides the tier");
{
  const cases = [
    [null, "workspace-write", "a missing file is workspace-write, never off"],
    ["", "workspace-write", "an empty file is workspace-write"],
    ['{"mode":"off"}', "workspace-write", 'the old switch\'s "off" is not a mode'],
    ["not json", "workspace-write", "unparseable JSON is workspace-write"],
    ['{"mode":"FULL-ACCESS"}', "workspace-write", "the modes are lower case and exact"],
    ['{"permission":"read-only"}', "workspace-write", "the key has to be `mode`"],
    ['{"mode":"read-only"}', "read-only", "read-only is read"],
    ['{"mode":"workspace-write"}', "workspace-write", "workspace-write is read"],
    ['{"mode":"full-access"}', "full-access", "full-access is read"],
  ];
  for (const [contents, want, labelText] of cases) {
    total += 1;
    const mode = resolveMode(ENV, contents);
    report(mode === want, labelText, mode === want ? undefined : `got ${mode}`);
  }
}

console.log("=== the permission file is where the app said it is");
{
  total += 1;
  report(
    permissionFilePath(ENV) === PERMISSION_FILE,
    "PIKIT_PERMISSION_FILE is honoured",
    permissionFilePath(ENV),
  );
  total += 1;
  const fallback = permissionFilePath({ HOME });
  report(
    fallback === `${HOME}/.pikit/permission.json`,
    "without the variable: <HOME>/.pikit/permission.json",
    fallback,
  );
  total += 1;
  const appDefault = permissionFilePath({});
  report(
    appDefault === "/data/data/pi.kit.mob/files/home/.pikit/permission.json",
    "without HOME: the app's fixed home",
    appDefault,
  );
}

console.log("=== a missing file is workspace-write, through the real entry point");
{
  const allowed = guardCall("bash", { command: "rm -rf build" }, ENV, () => null);
  total += 1;
  report(!allowed.block, "rm -rf build is allowed when the file is absent", allowed.reason);
  const refused = guardCall("bash", { command: "rm -rf /sdcard/Music" }, ENV, () => null);
  total += 1;
  report(refused.block, "rm -rf /sdcard/Music is refused when the file is absent");
}

console.log("=== the tier is read on every call, not once at load");
{
  // The point of the file rather than an environment variable: the user moves
  // the composer's control while pi is idle, and the next call must obey it.
  const first = guardCall("bash", { command: "rm -rf build" }, ENV, () => '{"mode":"read-only"}');
  total += 1;
  report(first.block, "the first call sees read-only");
  const second = guardCall("bash", { command: "rm -rf build" }, ENV, () => '{"mode":"full-access"}');
  total += 1;
  report(!second.block, "the next call sees full-access");

  let seen = null;
  guardCall("bash", { command: "ls" }, ENV, (path) => {
    seen = path;
    return null;
  });
  total += 1;
  report(seen === PERMISSION_FILE, "the reader is given the app's path", seen);
}

console.log("=== the refusal tells the model what would allow the call");
{
  const verdict = checkToolCall("bash", { command: "rm -rf /sdcard/Music" }, ENV, "workspace-write");
  const reason = verdict.reason ?? "";
  total += 1;
  report(reason.includes('"workspace-write"'), "the mode is named", reason);
  total += 1;
  report(reason.includes("/sdcard/Music"), "the refused path is named", reason);
  total += 1;
  report(ROOTS.every((root) => reason.includes(root)), "the writable roots are named", reason);
  total += 1;
  report(reason.includes(PERMISSION_FILE), "the file that states the tier is named", reason);
  total += 1;
  report(/composer/.test(reason), "the permission control is named", reason);

  const device = checkToolCall(
    "bash",
    { command: "dd if=/dev/zero of=/dev/block/mmcblk0" },
    ENV,
    "workspace-write",
  );
  total += 1;
  report(
    /block device/.test(device.reason ?? ""),
    "a block-device refusal says why the device matters",
    device.reason,
  );
}

console.log();
if (failures > 0) {
  console.log(`FAIL — ${failures} of ${total} cases wrong`);
  process.exit(1);
}
console.log(`PASS — ${total} cases`);
