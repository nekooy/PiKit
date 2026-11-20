/**
 * The web extension's pure half: configuration, HTML extraction, provider parsing.
 *
 * The network calls are not exercised here — a test that needs a provider's key
 * is not a test the build can run. What *is* pinned is the part that breaks
 * silently: the config file's shape, the HTML cleaner that must drop active
 * content, the three provider response shapes that differ enough to need their
 * own parsers, and the normalized result formatting the model reads.
 *
 * Loaded through pi's own jiti, the same loader the image uses, so a syntax or
 * module-resolution mistake fails here instead of on a phone.
 */

import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const JITI = join(ROOT, ".runtime-build", "cache", "pi", "node_modules", "jiti", "lib", "jiti.mjs");

if (!existsSync(JITI)) {
  console.log("web extension: skipped (no jiti under .runtime-build/cache/pi)");
  process.exit(0);
}

const { createJiti } = await import(`file://${JITI.replaceAll("\\", "/")}`);
const jiti = createJiti(import.meta.filename);
const web = await jiti.import(resolve(ROOT, "tools", "pikit-web.ts"));

// --- HTML extraction -------------------------------------------------------
const page = `<html><head><title>Doc</title><style>.x{}</style></head><body>
  <script>alert("bad")</script>
  <article><h1>Heading</h1><p>Hello <b>world</b>.</p>
  <ul><li>One</li><li>Two</li></ul>
  <div style="display:none">hidden text</div>
  <a href="https://example.com/x">a link</a></article></body></html>`;
const extracted = web.htmlToText(page);
assert.match(extracted, /Heading/);
assert.match(extracted, /Hello world/);
assert.match(extracted, /- One/);
assert.match(extracted, /\[a link\]\(https:\/\/example\.com\/x\)/);
assert.ok(!extracted.includes("alert"), "script text survived");
assert.ok(!extracted.includes("hidden text"), "hidden text survived");
assert.ok(!extracted.includes(".x{}"), "style text survived");

// --- provider parsers ------------------------------------------------------
const exa = web.parseExaMcpText(
  "Title: First\nURL: https://a.example/\nText: alpha\n\nTitle: Second\nURL: https://b.example/\nText: beta",
  5,
);
assert.deepEqual(exa.map((source) => source.url), ["https://a.example/", "https://b.example/"]);
assert.equal(exa[0].title, "First");

const responses = web.parseResponsesApi(
  {
    output: [
      { type: "message", content: [{ type: "output_text", text: "The answer." }] },
      { type: "web_search_call", action: { sources: [{ url: "https://c.example/", title: "C" }] } },
    ],
  },
  5,
);
assert.equal(responses.answer, "The answer.");
assert.equal(responses.sources[0].url, "https://c.example/");

// --- result merge and formatting ------------------------------------------
const merged = web.mergeSearchResults(
  [
    { provider: "one", sources: [{ url: "https://a/", title: "A" }, { url: "https://b/" }] },
    { provider: "two", sources: [{ url: "https://b/" }, { url: "https://c/" }] },
  ],
  10,
);
assert.deepEqual(merged.sources.map((source) => source.url), ["https://a/", "https://b/", "https://c/"]);
assert.deepEqual(merged.providers, ["one", "two"]);

const formatted = web.formatSearchText("Answer", merged.sources, false);
assert.match(formatted, /External web content follows/);
assert.match(formatted, /- \[A\]\(https:\/\/a\/\)/);
assert.match(formatted, /Cite the relevant URLs/);
assert.match(web.formatFetchText({ url: "https://x/", status: 200, kind: "text", content: "body" }, 100), /Fetched https:\/\/x\/ \(HTTP 200\)/);

// --- configuration ---------------------------------------------------------
const directory = mkdtempSync(join(tmpdir(), "pikit-web-test-"));
try {
  const file = join(directory, "web.json");
  writeFileSync(file, JSON.stringify({
    enabled: true,
    search: { provider: "brave", maxResults: 4, timeoutSeconds: 9 },
    fetch: { provider: "direct", maxChars: 1000, timeoutSeconds: 7 },
  }));
  const config = web.readConfig({ [web.CONFIG_VAR]: file, HOME: directory });
  assert.equal(config.search.provider, "brave");
  assert.equal(config.search.maxResults, 4);

  writeFileSync(file, "{ broken");
  assert.throws(() => web.readConfig({ [web.CONFIG_VAR]: file, HOME: directory }), /not valid JSON/);

  assert.deepEqual(
    web.autoSearchCandidates({ search: { provider: "auto" } }).slice(0, 2),
    ["exa-free", "duckduckgo"],
    "a fresh install must search without a key",
  );
} finally {
  rmSync(directory, { recursive: true, force: true });
}

console.log("web extension: config, HTML extraction and provider parsing okay");
