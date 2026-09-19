# The model and thinking-level switches

*[Architecture](../ARCHITECTURE.md) §7, part 2 of 4.*

## One provider, many models

A saved profile is a **provider** — an endpoint and a key — and it carries a list
of models. It used to carry exactly one: trying a second model meant creating a
second profile and pasting the same key again, and the fetch-models picker could
only ever *replace* the single model, so the first one disappeared the moment a
second was chosen. The field is `ModelProfile.models`, with `modelId` kept as the
model in use — the field the launcher and pi's own `set_model` already speak in —
and a profile written before the list existed reads back as
`selectableModels == listOf(modelId)`, so nothing has to be migrated.

The picker follows: entries are grouped by provider (`PickerOption.group`, drawn as
one heading per run of equal groups) and each row is a model, so the chip and the
sheet answer "which model", not "which profile". The chip shows the profile's own
choice and falls back to the model pi reports — a profile's *name* stops
identifying anything once one provider holds four models.

**The store had to become state.** `ProfileStore` kept its document in a plain
field, so the UI read a snapshot with nothing to tell it the snapshot had changed.
That produced two bugs: the composer's model chip kept showing the model it had been
composed with, and the picker's tick — whose values were captured in the click
handler's closure — went on ticking the model from the *previous* composition, so
the chip and the tick could point at different rows at once (caught on the emulator:
chip `mimo-v2-extra`, tick on `mimo-v2.5`). The store now publishes a
`StateFlow<PiConfigSnapshot>`; the chip, the picker, the model page and the settings
root collect it, and the picker reads its list *inside* the sheet rather than through
the lambda that opened it. A body that must show current data has to read the source,
never a captured value.

**`models.json` has to list them all.** For a custom endpoint PiKit writes pi's
catalog entry, and pi answers `set_model` from the catalog it built at startup. A
provider registered with one model can therefore only be switched *away* from by
restarting the agent for a change pi would otherwise apply over RPC. Measured before
the fix: picking the second model of one custom endpoint logged
`launching: … --model mimo-v2.5`, where picking the first had been applied in
place. After it, both switches happen with no `launching:` line at all.

## The thinking levels offered are the model's, and pi's answer is what is saved

pi's seven levels are not a menu every model honours.
`getSupportedThinkingLevels` (`pi-ai/dist/models.js`) filters them through the
model's own `thinkingLevelMap`, and `set_thinking_level` **clamps** a request the
model cannot satisfy by walking *forwards* to the next level it can. DeepSeek's
catalog says `{minimal: null, low: "low", medium: null, high: "high", max: "max"}`
for `deepseek-v4-flash`, so the model has four levels, and asking it for `medium`
leaves pi on `high`.

Two bugs came out of ignoring that: the picker offered all seven, so tapping
`medium` left `high` on the chip — the level pi had actually set; and the saved
level was never brought up to date, so the saved `medium` was passed as `--thinking`
again on every start, the chip showed it, and `get_state` then moved the chip to
`high`. Once per launch, forever.

So the app asks pi for the model's levels (`get_available_thinking_levels`, folded
into `ConversationState.availableThinkingLevels` whenever a state refresh happens)
and offers exactly those. `clampThinkingLevel` mirrors pi's rule on the JVM for the
one case pi has not answered yet, and `ThinkingLevelTest` pins it against the maps in
the shipped catalog.

## The model catalogue is pi's, and stale is the default

pi resolves a model id its catalogue does not contain from a **copy of the provider's
default model** (`buildFallbackModel` in `core/model-resolver.js`): the copy keeps the
default model's `thinkingLevelMap`, context window, `maxTokens`, cost and `input`, and
pi says so on stderr — `Model "…" not found for provider "…". Using custom model id.`

That single mechanism produced three separate reports, and each one measured something
that does not look like a catalogue problem from the outside:

| report | what pi was actually doing |
| --- | --- |
| `deepseek-flash` shows **context 128K, max-out 16.4K** | the app had written a `models` definition that *replaced* pi's entry, so the window was the app's invented default |
| deepseek has **four** thinking levels, the app offered **three** | the fallback is `deepseek-v4-pro`, whose map is `{minimal, low, medium: null, high, max}` — three — where pi.dev's catalogue gives four |
| an OpenAI model has **six**, the app offered **five** | the fallback is `gpt-5.5` (five levels), and the model in question is not in the bundled snapshot |

Reproduced against the bundled runtime on the desktop bundle, with a fresh agent
directory and one dummy key:

```
$ node cli.js --mode rpc --approve --provider deepseek --model deepseek-flash
Warning: Model "deepseek-flash" not found for provider "deepseek". Using custom model id.
{"command":"get_available_thinking_levels","data":{"levels":["off","high","max"]}}   # 3
```

The catalogue pi consults is `$HOME/.pi/agent/models-store.json`, and **nothing pi
does on the RPC path writes it**: `refreshModelCatalogs` is imported by
`interactive-mode.js` and the model selector, and by nothing else. RPC mode *does*
start a fire-and-forget `modelRuntime.refresh()` at launch (`main()`:
`!offlineMode && appMode === "rpc"`), but that goes through `Models.refresh`, not
through the CLI's `refreshModelCatalogs`, and it only republishes the overlay
**inside the running process**; the store on disk is untouched. So an RPC-mode agent
reads whatever is in that store and never writes a newer one, however online it is,
and its knowledge is whatever the app last refreshed — on a fresh install, the
snapshot bundled with the release (`generatedAt: 2026-09-05`, against pi.dev's
catalogues of `2026-09-15`).

**PiKit runs pi's own refresh** — `pi update --models`
(`package-manager-cli.js`, `refreshModelCatalogs`: `ModelRuntime.refresh` with
`allowNetwork: true, force: true`) — and the same probe afterwards, with no change to
the agent's argv:

```
$ node cli.js update --models
Model catalogs refreshed
$ node cli.js --mode rpc --approve --provider deepseek --model deepseek-flash
{"command":"get_available_thinking_levels","data":{"levels":["off","low","high","max"]}}  # 4
```

Six properties of that command decide how the app uses it:

- **It visits configured providers only**, and "configured" means a credential
  *resolves* (`Models.refresh` skips a provider whose `resolveRefreshCredential`
  answers nothing). Measured with one key: 0.67 s, exit 0, one `models-store.json`
  entry carrying pi.dev's own `lastModified`; with no credentials at all it finishes
  in 0.25 s having written nothing.
- **The app gives it a credential for every built-in provider**, through
  `PiLaunchOptions.extraEnv` (`catalogueRefreshEnvironment`), because the model the
  user cannot select is usually in a provider they are not currently on. The value
  plays no part in downloading a catalogue — pi.dev's per-provider catalogue is
  public, and the credential only decides *whether* pi asks — so a placeholder is
  enough. Measured across all of them: ~757 KB, thirty-two concurrent requests,
  roughly one round trip. The real key is written over the placeholder for the
  provider it names, and the map never reaches the *agent*: pi builds the
  available-model list from the same "configured" test, so an agent launched with
  thirty-two placeholders would report every model of every provider as available and
  offer accounts the user does not have (`PiProcessLauncherTest`).
- **This is also what makes the store worth reading.** pi.dev serves a catalogue for
  every provider pi's own key table knows — measured directly: fourteen of them
  answer `200` with a model list, `minimax`, `xiaomi`, `kimi-coding`, `opencode`,
  `zai-coding-cn` and `ant-ling` among them, at byte counts matching the bundled
  `providers/data/*.json` — so a store holding one or two providers was only ever a
  refresh that had one provider's credentials. Widened, it becomes pi.dev's *complete*
  catalogue on disk, and the model page's "does pi know this id" question is answered
  from the file with no process at all (ARCHITECTURE §6).
- **The key goes through the environment, never argv.** `update` is parsed by the
  *package* CLI, which rejects an option it does not know, so `--api-key` here would
  fail the command outright — and a key in argv is readable through `/proc` anyway.
- **`force: true` steps over pi's own four-hour window**, so the app rate-limits it
  itself: `CATALOGUE_REFRESH_WINDOW_MS` is pi's own
  `REMOTE_CATALOG_REFRESH_INTERVAL_MS` (four hours), a failure buys half an hour of
  quiet (`CATALOGUE_RETRY_MS`), and the maintenance page's button is the manual
  override. Zero was the policy while the refresh covered one provider and is not one
  now that it covers thirty-two: ~757 KB per launch is not a launch-path cost on a
  phone.
- **It runs after the handshake, in the background, and nothing waits on it.** When
  the store actually changed, the running agent is holding the old catalogue — pi
  reads it at startup — so it is relaunched, and only while the agent is idle: a turn
  in flight is worth more than a correct level list, and the fresh catalogue is on
  disk for the next launch either way.
- **"Actually changed" is a comparison of the catalogue, not of the file.** The store's
  file is rewritten by *every* refresh, including the one with nothing to report: on a
  `304 Not Modified`, `remote-catalog-provider.js` persists `{ ...stored, checkedAt }`
  and `FileModelsStore.write` writes the whole document without comparing it to what is
  there. Comparing the file's `(mtime, length)` therefore reported a change on every
  run, and while `CATALOGUE_REFRESH_WINDOW_MS` was zero the "relaunch it" branch fed
  itself: agent start → refresh → file mtime moves → restart → agent start. The report
  is the chat header flickering between "starting" and "ready" for as long as the app
  is open. `catalogueRevision` projects out the two fields the agent never reads
  (`checkedAt`, pi's four-hour window; `etag`, its revalidation validator) and compares
  each provider's `models` and `lastModified` — what `remoteModels` consults when it
  decides whether the stored overlay beats the built-in catalogue. A restart is then
  never followed by another one, because the second refresh finds the same catalogue it
  just wrote. `CatalogueRevisionTest` pins the rule, and the window is what keeps it
  from being a loop even when no restart is scheduled.

If pi.dev's catalogue does not have the id either, the fallback **is** pi's answer,
and the app showing it is correct rather than stale. This app cannot know more about
a model than pi does.

## The level shown is pi's id, and the level saved is the user's

The app persisted **pi's answer**: `reconcileThinkingLevel` wrote whatever `get_state`
or `thinking_level_changed` reported back into `PiSettings.thinkingLevel`. The level is
a *preference across models* — one setting, every model the user switches between —
while pi's answer is about the one model that happens to be loaded, and pi clamps it.
So a model that clamps rewrote the user's choice permanently, and the worst case is
not subtle: `getSupportedThinkingLevels` answers `["off"]` for a model with no
reasoning support, so using one such model **once** wrote `off` into the preference,
and every launch afterwards passed `--thinking off` — for reasoning models too.

What replaced it is a remembered *list*: `PiSettings.availableThinkingLevels` holds
the last levels pi reported — **with the model id they were reported for**
(`availableThinkingLevelsFor`), because the list is a property of one model's
`thinkingLevelMap`: `deepseek-v4-pro` has three levels and `deepseek-flash` has four,
and remembering the levels without the model is how a picker comes to show the previous
model's menu for the second between a switch and pi's answer. `thinkingLevelsFor(live,
remembered)` gives the chip something to clamp against during that second, and a list
whose key is not the model in use reads as no memory at all. The preference itself is
never written by pi's answer. Two smaller things went with it:

- **The names are pi's ids, not translations of them.** The chip used to read `中`
  where `pi --list-models` and `--thinking` say `medium`; the level's *name* is the
  value that is sent, in every language, and only the sentence explaining it is
  translated. `Strings.Chat.thinkingLevelDescription` is that sentence;
  `thinkingLevelOptions` labels every row with the id.
- **A model that does not reason gets its own sentence.** pi's answer for such a
  model is exactly `["off"]`, and the footnote's "this model offers only these levels"
  branch described a choice where there is none. `thinkingLevelFootnote` is one
  function for both call sites — the composer's sheet and the settings row used to
  duplicate the expression — and it says `thinkingDisabled` for that case.

The picker's list is pi's, so a level this build has never heard of is offered when a
newer pi reports it, and `set_thinking_level` forwards it: the app is not the
authority on which levels exist, and pi clamps what it does not know. The command
builder's `require` used to throw for exactly that case — an error banner on a level
pi had just reported as available.
