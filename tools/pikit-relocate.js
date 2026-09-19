/*
 * Relocates Termux packages onto this app's prefix.
 *
 * There is deliberately **no `#!` line**. Termux's `env` lives at
 * `$PREFIX/bin/env`, not `/usr/bin/env`, so a `#!/usr/bin/env node` shebang
 * would be a path that does not exist on the device — and the image verifier
 * rejects exactly that (`tools/verify-runtime-image.py` checks every shebang
 * under `libexec/`). The script is never executed directly in any case: the
 * `pikit-relocate` wrapper in `$PREFIX/bin` runs it as
 * `"$PREFIX/bin/node" "$PREFIX/libexec/pikit/relocate.js" "$@"`, which is both
 * the correct interpreter and one that is guaranteed to be present.
 *
 * ## The invariant this file depends on
 *
 * This is the one file in the image that has to *name* the upstream application
 * id, so the build's prefix rewrite skips it (`prefix_patch.NEVER_REWRITE`).
 * That exclusion is load-bearing. It was missing once: the build rewrote
 * `OLD_ID` below into this app's id, so `OLD_ID === NEW_ID`, every archive was
 * found to contain zero occurrences, and every package from outside the bundled
 * image failed to unpack — while the relocator reported success. The
 * substitution is indistinguishable from a correct run from the outside, which
 * is why `assertConfigured()` refuses to operate at all in that state, why
 * `--selfcheck` exists for the image build to call, and why
 * `tools/test-relocate.py` runs the copy extracted from `overlay.zip` rather
 * than this source file.
 *
 * ## The problem this solves
 *
 * Termux `.deb` packages are compiled against the absolute prefix
 * `/data/data/com.termux/files/usr`. It is baked into three places that nothing
 * can edit at runtime: the `DT_RUNPATH` of every ELF, the shebang of every
 * script, and a pile of config and dpkg metadata. PiKit ships the official
 * bootstrap with that string rewritten to its own application id — which works
 * because `com.termux` and `pi.kit.mob` are both exactly 10 bytes, so the
 * replacement is length-preserving and no offset in any binary moves.
 *
 * The bundled image is already relocated when it is built. Anything the user
 * installs afterwards is not: `pkg install foo` downloads a package built for
 * `com.termux`, and a binary whose `DT_RUNPATH` points at a directory this app
 * cannot read fails at exec time and looks like a broken package.
 *
 * ## How it is made transparent
 *
 * Two things invoke it, and both are needed: each was measured covering a case
 * the other does not.
 *
 * 1. **apt's `DPkg::Pre-Install-Pkgs` hook.** `etc/apt/apt.conf.d/99pikit-relocate`
 *    binds `--apt-list`, which reads the archives apt is about to hand to dpkg
 *    from stdin — the same list dpkg receives — and rewrites them in place. It
 *    covers a single package and a batch of ninety-six alike, including the
 *    copies apt stages under `$PREFIX/tmp/apt-dpkg-install-*` for a batch, which
 *    are the files dpkg actually unpacks.
 *
 *    It replaces a `DPkg::Pre-Invoke` hook that scanned apt's archive cache.
 *    That was wrong twice over: apt's cache is not `$PREFIX/var/cache/apt` (see
 *    `aptArchiveDirs`), and rewriting the cache changes the size of a file apt
 *    compares against the index, so apt re-downloaded the original over the
 *    relocated copy on the next install.
 *
 * 2. **The `dpkg` wrapper.** `$PREFIX/bin/dpkg` is a shell script (the real
 *    binary was renamed to `dpkg.real` at image build time) that runs this
 *    script with `--debs-here` over its own arguments, then execs the real dpkg.
 *    It covers an archive apt never mediated — `dpkg -i foo.deb`, or one fetched
 *    by hand — and it is the reason the relocation cannot be routed around.
 *
 * Both are idempotent, so a package one has already rewritten is a no-op for the
 * other.
 *
 * ## What it deliberately does not do
 *
 * It never touches `$HOME`, and it never touches shared storage: the prefix is
 * the only thing compiled against the old package id, and rewriting a user's
 * files would be both pointless and dangerous. `--prefix` refuses a root that is
 * not under the app's own data directory.
 *
 * Usage:
 *   pikit-relocate.js --apt-list                      rewrite the .deb paths on stdin
 *   pikit-relocate.js --debs-here <file.deb>...       rewrite these exact archives
 *   pikit-relocate.js --debs [--dir <apt-archives>]   rewrite cached .deb files
 *   pikit-relocate.js --prefix <path>                 rewrite an installed tree
 *   pikit-relocate.js --selfcheck                     prove the two ids differ
 *   any of the above with --check                     report what would change
 */

'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const { execFileSync } = require('node:child_process');

/**
 * The string Termux packages are compiled against. Never change it alone.
 *
 * See the header: the image build's prefix rewrite must not reach this file, and
 * `assertConfigured` is what turns that from a convention into a check.
 */
const OLD_ID = 'com.termux';

/** PiKit's length-preserving replacement. Both are exactly 10 bytes. */
const NEW_ID = 'pi.kit.mob';

/**
 * Prefixes whose contents are invisible to a byte scan, so an in-place edit
 * would destroy the stream. Detected by magic bytes rather than by extension,
 * because a `.dat` can be gzip and a `.png` is not always compressed.
 */
const COMPRESSED_MAGIC = [
  Buffer.from([0x1f, 0x8b]), // gzip
  Buffer.from([0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00]), // xz
  Buffer.from([0x28, 0xb5, 0x2f, 0xfd]), // zstd
  Buffer.from([0x42, 0x5a, 0x68]), // bzip2
  Buffer.from([0x04, 0x22, 0x4d, 0x18]), // lz4
];

/** Zip containers are opened and patched member-wise instead of skipped. */
const ZIP_MAGIC = [Buffer.from('PK\x03\x04', 'binary'), Buffer.from('PK\x05\x06', 'binary')];

const DEX_MAGIC = /^dex\n0(3[5-9]|40)\x00/;

/**
 * Where dpkg keeps each package's file list and hashes. Only `--prefix` mode
 * needs this: a `.deb` is patched before dpkg ever records anything.
 */
const DPKG_INFO = 'var/lib/dpkg/info';

const MAX_DEPTH = 3;

/**
 * Silences [log] in `--apt-list` mode.
 *
 * That mode runs from apt's `DPkg::Pre-Install-Pkgs` hook, and the hook's stdout
 * is printed in the middle of the user's `apt-get install` output. A line per
 * archive would bury the progress that matters, so success is silent there and
 * `warn` is the only voice.
 */
let quiet = false;

function log(message) {
  if (quiet) return;
  process.stdout.write(`[pikit-relocate] ${message}\n`);
}

/** Always printed: a skipped archive is a real problem, whatever the mode. */
function warn(message) {
  process.stderr.write(`[pikit-relocate] ${message}\n`);
}

/**
 * Refuses to run when the two ids are not what they must be.
 *
 * The image build rewrites `/data/data/com.termux` to this app's prefix
 * throughout the runtime tree, and this file is excluded from that pass. When the
 * exclusion was missing, `OLD_ID` became `pi.kit.mob`, every `countOccurrences`
 * returned 0, every archive was reported as "nothing to relocate", and 96 of 96
 * packages failed to unpack with a `Permission denied` on
 * `./data/data/com.termux` — a message that points at the archive rather than at
 * the relocator. Failing loudly here is the only way that mistake stays cheap.
 */
function assertConfigured() {
  if (OLD_ID === NEW_ID) {
    throw new Error(
      `relocator misconfigured: OLD_ID === NEW_ID (both ${JSON.stringify(OLD_ID)}); ` +
        'the image build rewrote this file, so nothing would ever be relocated',
    );
  }
  if (OLD_ID.length !== NEW_ID.length) {
    throw new Error(
      `relocator misconfigured: ${JSON.stringify(OLD_ID)} is ${OLD_ID.length} bytes and ` +
        `${JSON.stringify(NEW_ID)} is ${NEW_ID.length}; a rewrite that changes the length ` +
        'would corrupt every ELF it touches',
    );
  }
}

// ---------------------------------------------------------------------------
// Byte rewriting
// ---------------------------------------------------------------------------

function isCompressed(blob) {
  return COMPRESSED_MAGIC.some(
    (magic) => blob.length >= magic.length && blob.subarray(0, magic.length).equals(magic),
  );
}

function isZip(blob) {
  return ZIP_MAGIC.some((magic) => blob.length >= magic.length && blob.subarray(0, magic.length).equals(magic));
}

function isDex(blob) {
  return DEX_MAGIC.test(blob.subarray(0, 8).toString('latin1'));
}

function isElf(blob) {
  return blob.subarray(0, 4).toString('latin1') === '\x7fELF';
}

function countOccurrences(blob, needle) {
  let count = 0;
  let from = 0;
  for (;;) {
    const at = blob.indexOf(needle, from);
    if (at < 0) return count;
    count += 1;
    from = at + needle.length;
  }
}

/**
 * Replaces every occurrence of `needle` with `substitute`.
 *
 * Both must be the same length: a different length would shift every offset in
 * an ELF — section headers, the dynamic section, the string table — and corrupt
 * the file. That is the entire reason PiKit's application id is exactly ten
 * characters, and it is asserted rather than assumed.
 */
function replaceAll(blob, needle, substitute) {
  if (needle.length !== substitute.length) {
    throw new Error(
      `refusing to rewrite: ${JSON.stringify(needle.toString())} and ` +
        `${JSON.stringify(substitute.toString())} differ in length ` +
        `(${needle.length} vs ${substitute.length}); that would corrupt every ELF`,
    );
  }
  const out = Buffer.from(blob);
  let from = 0;
  for (;;) {
    const at = out.indexOf(needle, from);
    if (at < 0) return out;
    substitute.copy(out, at);
    from = at + needle.length;
  }
}

// ---------------------------------------------------------------------------
// ELF and dex fix-ups (needed only when the *file* moves, not for a .deb)
// ---------------------------------------------------------------------------

/**
 * Recomputes the dex header's Adler-32 and SHA-1.
 *
 * Editing a `classes.dex` invalidates both, and ART may refuse to load it. Order
 * matters: the signature covers bytes from offset 32, and the checksum covers
 * bytes from offset 12 *including* the signature — so the signature is written
 * first and the checksum last.
 */
function fixDexHeaders(blob) {
  if (blob.length < 32) return blob;
  const out = Buffer.from(blob);
  const crypto = require('node:crypto');
  const signature = crypto.createHash('sha1').update(out.subarray(32)).digest();
  signature.copy(out, 12);
  out.writeUInt32LE(zlib.adler32(out.subarray(12)) >>> 0, 8);
  return out;
}

// ---------------------------------------------------------------------------
// Zip containers
// ---------------------------------------------------------------------------

/**
 * Rewrites a nested zip (for example the `am.apk` inside `termux-am`).
 *
 * A zip's central directory holds absolute offsets, so shrinking or growing a
 * member would invalidate every one of them; the members here are the same size
 * by construction (the id rewrite is length-preserving), so the directory stays
 * valid and only the two per-member CRCs need recomputing.
 *
 * Measured: no package in the Termux repository that this app installs contains
 * a nested archive needing this. It exists because the one that does —
 * `libexec/termux-am/am.apk` in the bootstrap — is handled at build time by the
 * same rule, and a package that arrived with one should not silently break.
 */
function patchNestedZip(blob, depth) {
  const out = [];
  let at = 0;
  let occurrences = 0;

  while (at + 30 <= blob.length && blob.readUInt32LE(at) === 0x04034b50) {
    const method = blob.readUInt16LE(at + 8);
    const compressedSize = blob.readUInt32LE(at + 18);
    const nameLength = blob.readUInt16LE(at + 26);
    const extraLength = blob.readUInt16LE(at + 28);
    const nameStart = at + 30;
    const dataStart = nameStart + nameLength + extraLength;
    const dataEnd = dataStart + compressedSize;
    if (dataEnd > blob.length) return { blob, changed: false, occurrences: 0 };

    const name = blob.subarray(nameStart, nameStart + nameLength).toString('utf8');
    let data = blob.subarray(dataStart, dataEnd);

    if (method === 0) {
      const patched = rewrite(data, name, depth + 1);
      if (patched.changed) {
        occurrences += patched.occurrences;
        data = patched.blob;
      }
    } else if (method === 8) {
      const patched = rewrite(zlib.inflateRawSync(data), name, depth + 1);
      if (patched.changed) {
        occurrences += patched.occurrences;
        data = zlib.deflateRawSync(patched.blob, { level: 9 });
      }
    }

    if (data.length !== compressedSize) {
      // Cannot happen with a length-preserving rewrite, and if it ever did the
      // central directory would be wrong. Refuse rather than corrupt the zip.
      return { blob, changed: false, occurrences: 0 };
    }

    // The local header is copied verbatim and only its CRC is refreshed.
    const header = Buffer.from(blob.subarray(at, dataStart));
    if (occurrences > 0) {
      header.writeUInt32LE(crc32(data) >>> 0, 14);
    }
    out.push(header, data);
    at = dataEnd;
  }

  if (occurrences === 0) return { blob, changed: false, occurrences: 0 };
  if (at === 0) return { blob, changed: false, occurrences: 0 };
  // Anything after the last local header is the central directory plus the
  // end-of-central-directory record, copied unchanged. Their offsets still hold
  // because no member changed size.
  out.push(Buffer.from(blob.subarray(at)));
  return { blob: Buffer.concat(out), changed: true, occurrences };
}

/** Standard zip CRC-32, table built once. */
const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(blob) {
  let crc = -1;
  for (let i = 0; i < blob.length; i++) {
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ blob[i]) & 0xff];
  }
  return (crc ^ -1) >>> 0;
}

// ---------------------------------------------------------------------------
// The rewrite itself
// ---------------------------------------------------------------------------

/**
 * Rewrites the package id in one blob, recursing into nested containers.
 *
 * Returns `{ blob, changed, occurrences }`.
 */
function rewrite(blob, label, depth = 0) {
  const needle = Buffer.from(OLD_ID, 'latin1');
  const substitute = Buffer.from(NEW_ID, 'latin1');

  if (isCompressed(blob)) return { blob, changed: false, occurrences: 0, skippedCompressed: true };

  if (isZip(blob)) {
    if (depth >= MAX_DEPTH) return { blob, changed: false, occurrences: 0 };
    // A zip is never byte-rewritten as a whole. Its members carry CRCs, so a
    // flat replace over the container would leave every patched member failing
    // its own checksum. patchNestedZip handles the members and their CRCs, or
    // reports nothing to do.
    return patchNestedZip(blob, depth);
  }

  const hits = countOccurrences(blob, needle);
  if (hits === 0) return { blob, changed: false, occurrences: 0 };

  const out = replaceAll(blob, needle, substitute);
  if (isDex(out)) return { blob: fixDexHeaders(out), changed: true, occurrences: hits };
  return { blob: out, changed: true, occurrences: hits, elf: isElf(out) };
}

/**
 * Creates `target` if it became a different file, atomically.
 *
 * A rename rather than a write in place: the running environment may be reading
 * these files, and a half-written binary is worse than a stale one — as is a
 * half-written *archive*, which is what is being replaced here.
 *
 * The temporary name carries the process id because two runs can overlap: apt's
 * `DPkg::Pre-Install-Pkgs` hook and the `dpkg` wrapper see the same archive
 * seconds apart, and a user can run `pikit-relocate --debs` by hand while an
 * install is in flight. With a fixed name the second process would rename the
 * first one's half-written file into place.
 */
function writeAtomic(target, blob) {
  const temp = `${target}.pikit-${process.pid}.tmp`;
  fs.writeFileSync(temp, blob);
  fs.chmodSync(temp, fs.statSync(target).mode & 0o7777);
  fs.renameSync(temp, target);
}

function patchFile(target, { dryRun }) {
  let blob;
  try {
    blob = fs.readFileSync(target);
  } catch (error) {
    return { changed: false, occurrences: 0, error: error.message };
  }
  let result;
  try {
    result = rewrite(blob, target);
  } catch (error) {
    return { changed: false, occurrences: 0, error: error.message };
  }
  if (!result.changed) return { changed: false, occurrences: 0 };
  if (!dryRun) writeAtomic(target, result.blob);
  return { changed: true, occurrences: result.occurrences };
}

// ---------------------------------------------------------------------------
// .deb handling
// ---------------------------------------------------------------------------

/** Split an `ar` archive into its named members without unpacking to disk. */
function readArMembers(blob) {
  if (blob.subarray(0, 8).toString('latin1') !== '!<arch>\n') {
    throw new Error('not an ar archive');
  }
  const members = [];
  let at = 8;
  while (at + 60 <= blob.length) {
    const header = blob.subarray(at, at + 60);
    const name = header.subarray(0, 16).toString('latin1').trim().replace(/\/$/, '');
    const size = parseInt(header.subarray(48, 58).toString('latin1').trim(), 10);
    if (!Number.isFinite(size)) break;
    const dataStart = at + 60;
    const dataEnd = dataStart + size;
    if (dataEnd > blob.length) break;
    members.push({ name, data: blob.subarray(dataStart, dataEnd), header });
    at = dataEnd + (size % 2); // members are 2-byte aligned
  }
  return members;
}

function writeArMembers(members) {
  const parts = [Buffer.from('!<arch>\n', 'latin1')];
  for (const member of members) {
    const header = Buffer.from(member.header);
    // The size field is decimal, left-justified in 10 bytes, and the member's
    // own name field is kept verbatim so a long-name table (`//`) stays valid.
    header.write(String(member.data.length).padEnd(10, ' '), 48, 'latin1');
    parts.push(header, member.data);
    // Members are padded to an even offset with a single newline.
    if (member.data.length % 2 !== 0) parts.push(Buffer.from('\n', 'latin1'));
  }
  return Buffer.concat(parts);
}

/** Every `control.tar`, `control.tar.xz`, `data.tar`, `data.tar.gz` … */
const TAR_MEMBER = /\.tar(\.[A-Za-z0-9]+)?$/;

/**
 * Rewrites one `.deb` in place.
 *
 * **Every tar member is rewritten, not just `data.tar`.** `control.tar` carries
 * `conffiles`, the maintainer scripts and (for a Debian package, though not for
 * a Termux one) `md5sums`, and all of them name the prefix too. Measured: with
 * only `data.tar` rewritten, `dpkg` unpacked the package and then failed to
 * configure it, with `unable to stat config file
 * '/data/data/com.termux/files/usr/etc/...': Permission denied` for every
 * conffile, and `unable to execute .../python.postinst` because that script's
 * shebang still named the interpreter under the old prefix. dpkg records the
 * control member's contents in `var/lib/dpkg/info/`, so leaving it unpatched
 * also leaves broken `postinst`/`conffiles` on disk for good.
 *
 * The rewrite happens on the **uncompressed tar stream**, not on an extracted
 * tree. That matters more than it looks: `tar -x` recreates directories from the
 * names in the archive, so extracting a package that names
 * `data/data/com.termux/files/usr` gives you a directory *called* `com.termux`,
 * and `tar -c` then writes that name straight back into the archive. The bytes
 * inside the files would be patched and the paths would not, which is the one
 * outcome that must not happen — dpkg would record files under a prefix the app
 * never uses, and the package would be installed but unreachable.
 *
 * Working on the stream rewrites both, in one pass, with no temporary tree:
 * every occurrence in a tar is either a header's name field (NUL-padded, so a
 * plain byte replace is safe) or file content, and both want the same change.
 * The stream is re-compressed afterwards because it was decompressed to be
 * edited, so the member — and therefore the archive — changes size; see the note
 * in docs/ARCHITECTURE.md about why that is not padded back to the original.
 */
function patchDeb(deb, { dryRun }) {
  const members = readArMembers(fs.readFileSync(deb));
  const needle = Buffer.from(OLD_ID, 'latin1');
  const substitute = Buffer.from(NEW_ID, 'latin1');

  const tars = members.filter((member) => TAR_MEMBER.test(member.name));
  if (tars.length === 0) return { files: 0, occurrences: 0, error: 'no tar member' };

  let occurrences = 0;
  let rewritten = 0;

  for (const member of tars) {
    let tar;
    try {
      tar = decodeMember(member.name, member.data);
    } catch (error) {
      return { files: 0, occurrences: 0, error: `cannot decompress ${member.name}: ${error.message}` };
    }
    if (tar === null) {
      return { files: 0, occurrences: 0, error: `no decompressor for ${member.name}` };
    }

    const hits = countOccurrences(tar, needle);
    if (process.env.PIKIT_DEBUG) {
      log(
        `${path.basename(deb)}: ${member.name} ${tar.length} bytes, ` +
          `${member.data.length} bytes on disk, ${hits} occurrence(s)`,
      );
    }
    if (hits === 0) continue;

    const patched = replaceAll(tar, needle, substitute);
    // The names changed, so every header checksum that covered one is now stale.
    fixTarChecksums(patched);

    let recompressed;
    try {
      recompressed = encodeMember(member.name, patched);
    } catch (error) {
      return { files: 0, occurrences: 0, error: `cannot recompress ${member.name}: ${error.message}` };
    }
    if (recompressed === null) {
      return { files: 0, occurrences: 0, error: `no compressor for ${member.name}` };
    }

    member.data = recompressed;
    occurrences += hits;
    rewritten += 1;
  }

  if (rewritten === 0) return { files: 0, occurrences: 0, changed: false };

  if (!dryRun) {
    const bytes = writeArMembers(members);
    const before = fs.statSync(deb).size;
    writeAtomic(deb, bytes);
    const after = fs.statSync(deb).size;
    // Tracing is opt-in and only ever set by hand. It exists because the failure
    // it was written for was invisible from the outside: the counts said the
    // archive had been rewritten and the bytes on disk had not changed.
    if (process.env.PIKIT_DEBUG) {
      log(
        `${path.basename(deb)}: rewrote ${rewritten} member(s), ${occurrences} occurrence(s), ` +
          `on disk ${before} -> ${after}`,
      );
    }
  }
  return { files: rewritten, occurrences, changed: true, members: rewritten };
}

/**
 * Recomputes the checksum field of every tar header in a stream.
 *
 * A tar header carries a checksum over its own bytes, and rewriting a header's
 * `name` field — which is what relocating an archive from `com.termux` to this
 * app's id does — invalidates it. GNU tar warns and dpkg's own reader may reject
 * the archive outright, which would turn a successful install into a hard
 * failure.
 *
 * This is **not** a violation of the length-preserving rule: the checksum field
 * is 8 bytes before and after, and it cannot contain the needle. Recomputing it
 * keeps the archive valid, which is the point.
 *
 * The walk is deliberately dumb — header, size, skip the data, repeat — because
 * that is all a tar is. GNU long names (`L`/`K`) and PAX headers (`x`/`g`) are
 * ordinary entries with their own size, so they are handled by the same step.
 */
function fixTarChecksums(tar) {
  let at = 0;
  let fixed = 0;

  while (at + 512 <= tar.length) {
    const header = tar.subarray(at, at + 512);
    // Two zero blocks end an archive.
    if (header.every((byte) => byte === 0)) break;

    const rawSize = header.subarray(124, 136).toString('latin1').replace(/\0.*$/, '').trim();
    let size;
    try {
      size = rawSize === '' ? 0 : parseInt(rawSize, 8);
    } catch {
      break;
    }
    if (!Number.isFinite(size) || size < 0) break;

    // The checksum is computed with its own field read as eight spaces.
    let sum = 0;
    for (let i = 0; i < 512; i++) {
      sum += i >= 148 && i < 156 ? 0x20 : header[i];
    }
    const field = `${sum.toString(8).padStart(6, '0')}\0 `;
    const current = header.subarray(148, 156).toString('latin1');
    if (current !== field) {
      header.write(field, 148, 8, 'latin1');
      fixed += 1;
    }

    at += 512 + Math.ceil(size / 512) * 512;
  }
  return fixed;
}

/** The decompressor for each `data.tar.*` suffix, as an argv list. */
const DECOMPRESSORS = {
  '.gz': ['gzip', ['-dc']],
  '.xz': ['xz', ['-dc']],
  '.bz2': ['bzip2', ['-dc']],
  '.zst': ['zstd', ['-dc']],
  '.lzma': ['xz', ['-dc', '--format=lzma']],
  '.Z': ['uncompress', ['-c']],
};

/**
 * The compressor for each tar suffix, as an argv list.
 *
 * Not `-9`: the archive is unpacked by dpkg seconds after this runs and is never
 * kept, so the only thing a slower setting buys is wall-clock time on a phone.
 * The re-compressed member is nevertheless a different length from the original
 * — the id was rewritten, so the stream before it is different — which is the
 * one promise the header used to make and could not keep. That is why nothing
 * scans apt's archive cache any more: apt compares a cached archive's size
 * against the index and re-downloads the original over a rewritten copy.
 */
const COMPRESSORS = {
  '.gz': ['gzip', ['-6c']],
  '.xz': ['xz', ['-6c']],
  '.bz2': ['bzip2', ['-9c']],
  '.zst': ['zstd', ['-3c', '--quiet']],
  '.lzma': ['xz', ['-6c', '--format=lzma']],
  '.Z': ['compress', ['-c']],
};

function suffixOf(memberName) {
  const at = memberName.indexOf('.tar');
  if (at < 0) return '.gz';
  const rest = memberName.slice(at + 4);
  return rest === '' ? '' : rest;
}

/**
 * Expands a compressed `data.tar.*` member.
 *
 * Returns null when no decompressor for the suffix is available, which is a
 * skipped package rather than a failed install. On a device every tool here is
 * in the bundled image (`bin/gzip`, `bin/xz`, `bin/zstd`, `bin/bzip2`); the
 * `PIKIT_RELOCATE_HELPER` fallback exists so the Windows build host — which has
 * none of them — can still exercise this path in tests. The app never sets that
 * variable.
 */
function decodeMember(memberName, data) {
  const suffix = suffixOf(memberName);
  if (suffix === '') return Buffer.from(data);
  const entry = DECOMPRESSORS[suffix];
  if (!entry) return null;
  const [tool, args] = entry;
  try {
    return execFileSync(tool, args, { input: data, maxBuffer: MAX_BUFFER, stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (error) {
    // Every tool is a fallback candidate, `gzip` included: it is present in the
    // image but not on a Windows host, and an earlier version of this function
    // re-threw for it, which made the host test skip every `.tar.gz` package.
    return viaHelper('decompress', data, suffix);
  }
}

function encodeMember(memberName, tar) {
  const suffix = suffixOf(memberName);
  if (suffix === '') return Buffer.from(tar);
  const entry = COMPRESSORS[suffix];
  if (!entry) return null;
  const [tool, args] = entry;
  try {
    return execFileSync(tool, args, { input: tar, maxBuffer: MAX_BUFFER, stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (error) {
    return viaHelper('compress', tar, suffix);
  }
}

/**
 * The host-only fallback: a Python helper that speaks in files rather than
 * pipes, so a restricted sandbox cannot break it.
 */
function viaHelper(command, data, suffix) {
  const helper = process.env.PIKIT_RELOCATE_HELPER;
  if (!helper || !fs.existsSync(helper)) return null;

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pikit-compress-'));
  try {
    const input = path.join(dir, 'in.bin');
    const output = path.join(dir, 'out.bin');
    fs.writeFileSync(input, data);
    const args = [helper, command, input, output];
    if (command === 'compress') args.push(suffix);
    execFileSync(process.env.PIKIT_PYTHON || 'python', args, { stdio: 'pipe' });
    return fs.readFileSync(output);
  } catch {
    return null;
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

/** Large enough for any uncompressed package in the Termux repository. */
const MAX_BUFFER = 512 * 1024 * 1024;

/**
 * Walks a tree and rewrites every regular file, removing links by path.
 *
 * Symlinks are metadata rather than content, so no byte scan can reach them:
 * they are read, rewritten and recreated explicitly.
 */
function walkAndPatch(root, dryRun) {
  const stack = [root];
  let files = 0;
  let occurrences = 0;
  let changed = false;
  const skipped = [];

  while (stack.length > 0) {
    const dir = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      let stat;
      try {
        stat = fs.lstatSync(full);
      } catch {
        continue;
      }
      if (stat.isSymbolicLink()) {
        const target = fs.readlinkSync(full);
        if (path.isAbsolute(target) && target.includes(OLD_ID)) {
          if (!dryRun) {
            fs.unlinkSync(full);
            fs.symlinkSync(target.split(OLD_ID).join(NEW_ID), full);
          }
          occurrences += 1;
          changed = true;
        }
        continue;
      }
      if (stat.isDirectory()) {
        stack.push(full);
        continue;
      }
      if (!stat.isFile()) continue;

      const result = patchFile(full, { dryRun });
      files += 1;
      if (result.occurrences > 0) {
        occurrences += result.occurrences;
        changed = true;
      }
      if (result.error) skipped.push(`${entry.name}: ${result.error}`);
    }
  }
  return { files, occurrences, changed, skipped };
}

// ---------------------------------------------------------------------------
// dpkg bookkeeping (only for --prefix mode)
// ---------------------------------------------------------------------------

/**
 * Regenerates the md5 records dpkg keeps for each installed file.
 *
 * Without this, `dpkg --verify` reports every rewritten file as modified and
 * dpkg treats a patched conffile as locally edited. A `.deb` patched before
 * unpacking needs none of it, which is why the apt hook is the primary path.
 */
function refreshDpkgHashes(prefix) {
  const infoDir = path.join(prefix, DPKG_INFO);
  if (!fs.existsSync(infoDir)) return 0;
  const crypto = require('node:crypto');
  let updated = 0;

  for (const name of fs.readdirSync(infoDir)) {
    if (!name.endsWith('.md5sums')) continue;
    const listFile = path.join(infoDir, name);
    let lines;
    try {
      lines = fs.readFileSync(listFile, 'utf8').split('\n');
    } catch {
      continue;
    }
    let dirty = false;
    const out = lines.map((line) => {
      // `<md5>  <path>` with no leading slash, e.g. `data/data/.../bin/bash`.
      const match = /^([0-9a-f]{32})(\s+)(.+)$/.exec(line);
      if (!match) return line;
      const target = path.join(prefix, match[3]);
      let hash;
      try {
        hash = crypto.createHash('md5').update(fs.readFileSync(target)).digest('hex');
      } catch {
        return line;
      }
      if (hash === match[1]) return line;
      dirty = true;
      return `${hash}${match[2]}${match[3]}`;
    });
    if (dirty) {
      fs.writeFileSync(listFile, out.join('\n'));
      updated += 1;
    }
  }
  return updated;
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

function findDebs(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((name) => name.endsWith('.deb'))
    .map((name) => path.join(dir, name));
}

/** The runtime prefix, from the environment or from where `node` itself lives. */
function prefixDir() {
  return process.env.PREFIX || path.dirname(path.dirname(process.execPath));
}

/** `/data/data/<app-id>`, i.e. the prefix without `/files/usr`. */
function appDataDir() {
  const prefix = prefixDir();
  return prefix.endsWith('/files/usr')
    ? prefix.slice(0, -'/files/usr'.length)
    : path.dirname(prefix);
}

/**
 * The directories apt keeps downloaded archives in.
 *
 * Not `$PREFIX/var/cache/apt/archives`, which is where this started and which is
 * empty on a device. Termux builds apt with its cache compiled in as
 * `/data/data/<app-id>/cache/apt` — the *app's* cache directory, not the
 * prefix's — which is visible as a literal in `lib/libapt-pkg.so` and as
 * `Dir::Cache "data/data/<app-id>/cache/apt"` in `apt-config dump`. Scanning the
 * wrong directory is silent in the worst way: the relocator printed "nothing to
 * relocate" and exited 0 for every run, so the hook looked healthy while the
 * cache beside it was never touched.
 *
 * `apt-config` is asked first because it is the authority and the path is a
 * build-time choice that could change; the two fallbacks exist for the state in
 * which `--debs` is most useful as a repair tool, namely apt missing or broken.
 */
function aptArchiveDirs() {
  const dirs = [];
  try {
    const dump = execFileSync('apt-config', ['dump'], {
      encoding: 'utf8',
      maxBuffer: 8 * 1024 * 1024,
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    const read = (key) => {
      const pattern = new RegExp(`^${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s+"?([^"\\n]*)"?\\s*$`, 'm');
      const match = pattern.exec(dump);
      return match ? match[1].trim() : null;
    };
    const root = read('Dir') || '/';
    const cache = read('Dir::Cache');
    const archives = read('Dir::Cache::archives') || '';
    // `Dir::Cache` is relative to `Dir` and `archives/` relative to `Dir::Cache`;
    // `path.resolve` applies them in that order, which is apt's own join.
    if (cache) dirs.push(path.resolve(root, cache, archives));
  } catch {
    // apt-config is absent or unusable; the compiled-in path below still applies.
  }
  dirs.push(path.join(appDataDir(), 'cache', 'apt', 'archives'));
  dirs.push(path.join(prefixDir(), 'var/cache/apt/archives'));
  return [...new Set(dirs)];
}

/**
 * Rewrites each archive in [files], reporting the ones it could not.
 *
 * A failure is reported and skipped rather than thrown: the caller is in the
 * middle of an install the user asked for, and an unrelocated package is
 * recoverable — the app can repair a tree, and the next `pikit-relocate` run
 * sees the same archive — while a `dpkg` run aborted mid-transaction is not.
 */
function relocateFiles(files, { dryRun }) {
  let relocated = 0;
  let occurrences = 0;
  for (const file of files) {
    if (!fs.existsSync(file)) {
      warn(`missing, skipping: ${file}`);
      continue;
    }
    if (process.env.PIKIT_DEBUG) log(`patching ${file} (${fs.statSync(file).size} bytes)`);
    const result = patchDeb(file, { dryRun });
    if (result.error) {
      warn(`${path.basename(file)}: skipped (${result.error})`);
      continue;
    }
    if (result.changed) {
      relocated += 1;
      occurrences += result.occurrences;
    }
  }
  if (relocated > 0) log(`relocated ${relocated} archive(s), ${occurrences} occurrence(s)`);
  return { relocated, occurrences };
}

function main(argv) {
  const dryRun = argv.includes('--check');
  const args = argv.filter((a) => a !== '--check');

  try {
    assertConfigured();
  } catch (error) {
    warn(error.message);
    return 4;
  }

  // `--selfcheck`: the check the image build runs over the relocator it packed,
  // so a build that rewrote this file fails at the build rather than at the
  // ninety-sixth package installation. Exercising `rewrite` rather than only
  // comparing the constants is deliberate: it is the function whose silence
  // hides the fault.
  if (args.includes('--selfcheck')) {
    const sample = Buffer.from(`/data/data/${OLD_ID}/files/usr/bin/bash\0`, 'latin1');
    const patched = rewrite(sample, 'selfcheck');
    const expected = Buffer.from(NEW_ID, 'latin1');
    if (!patched.changed || !patched.blob.includes(expected) || patched.blob.includes(Buffer.from(OLD_ID, 'latin1'))) {
      warn(`self-check failed: ${JSON.stringify(OLD_ID)} was not rewritten to ${JSON.stringify(NEW_ID)}`);
      return 1;
    }
    log(`self-check ok: ${OLD_ID} -> ${NEW_ID}, ${patched.occurrences} occurrence(s)`);
    return 0;
  }

  // `--apt-list`: the archives apt writes to our stdin, one absolute path per
  // line, immediately before dpkg is handed the same list. `DPkg::Pre-Install-Pkgs`
  // runs this hook for a single package and for a batch alike, and the paths are
  // the ones dpkg will unpack — including the `tmp/apt-dpkg-install-*` copies apt
  // stages for a batch, which no archive-cache scan can see.
  if (args.includes('--apt-list')) {
    quiet = true;
    let input;
    try {
      input = fs.readFileSync(0, 'utf8');
    } catch (error) {
      warn(`cannot read the archive list from stdin: ${error.message}`);
      return 5;
    }
    const files = input
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.endsWith('.deb'));
    const { relocated } = relocateFiles(files, { dryRun });
    // Silence is the contract with apt. Only a *failure* to relocate any of the
    // archives at all is worth interrupting an install for, and even then the
    // install is allowed to continue: dpkg will report the archive's own error,
    // and `Settings -> Upgrade Pi & repair` can repair the tree afterwards.
    if (relocated === 0 && files.length > 0 && process.env.PIKIT_DEBUG) {
      log(`${files.length} archive(s) already relocated`);
    }
    return 0;
  }

  // `--debs-here <file>...`: relocate these exact archives, whatever directory
  // they are in. Used by the `dpkg` wrapper on the arguments dpkg is about to
  // unpack, which covers an installation apt never mediated.
  if (args.includes('--debs-here')) {
    const at = args.indexOf('--debs-here');
    const files = args.slice(at + 1).filter((name) => name.endsWith('.deb'));
    if (process.env.PIKIT_DEBUG) {
      log(`--debs-here received ${files.length} archive(s): ${files.join(', ')}`);
    }
    relocateFiles(files, { dryRun });
    return 0;
  }

  if (args.includes('--debs')) {
    const at = args.indexOf('--dir');
    const dirs = at >= 0 ? [args[at + 1]] : aptArchiveDirs();
    let relocated = 0;
    let occurrences = 0;
    for (const dir of dirs) {
      const found = relocateFiles(findDebs(dir), { dryRun });
      relocated += found.relocated;
      occurrences += found.occurrences;
    }
    if (relocated > 0) {
      log(`relocated ${relocated} package(s), ${occurrences} occurrence(s)`);
    } else {
      log(`nothing to relocate in ${dirs.join(', ')}`);
    }
    return 0;
  }

  if (args.includes('--prefix')) {
    const at = args.indexOf('--prefix');
    const root = args[at + 1];
    if (!root) {
      log('--prefix needs a path');
      return 2;
    }
    const resolved = path.resolve(root);
    // Derived rather than hard-coded so a build with a different application id
    // still refuses the right directories. `NEW_ID` stays the literal app id for
    // the same reason: it is the one string this file must own.
    const filesDir = path.join(appDataDir(), 'files');
    // The guard that makes this safe to run unattended: it must never be aimed
    // at shared storage or at $HOME. Only the prefix is compiled against the old
    // package id, and rewriting a user's files would be pointless and dangerous.
    if (!resolved.startsWith(filesDir + '/')) {
      log(`refusing to rewrite ${resolved}: outside ${filesDir}`);
      return 3;
    }
    if (resolved.endsWith('/home') || resolved.includes('/home/')) {
      log(`refusing to rewrite ${resolved}: that is $HOME`);
      return 3;
    }
    const walked = walkAndPatch(resolved, dryRun);
    const hashes = dryRun ? 0 : refreshDpkgHashes(resolved);
    log(
      `scanned ${walked.files} file(s), rewrote ${walked.occurrences} occurrence(s), ` +
        `${hashes} md5 record file(s) refreshed`,
    );
    if (walked.skipped.length > 0) log(`skipped: ${walked.skipped.slice(0, 5).join('; ')}`);
    return 0;
  }

  log('nothing to do: pass --apt-list, --debs-here, --debs or --prefix <path>');
  return 2;
}

if (require.main === module) {
  process.exitCode = main(process.argv.slice(2));
}

module.exports = {
  rewrite,
  patchDeb,
  walkAndPatch,
  aptArchiveDirs,
  assertConfigured,
  OLD_ID,
  NEW_ID,
};
