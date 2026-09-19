# Custom endpoints, and the two things that hid the failure

*[Architecture](../ARCHITECTURE.md) §6, part 1 of 2.*

## The provider list is pi's, minus what a phone form cannot fill

The picker used to carry ten providers, chosen by hand as "the ones people use" — which
is a guess, and the report was the shape a guess makes: *where is Kimi? where is Xiaomi?*
The list is now **pi's own**, taken from `getApiKeyEnvVars` in the bundled
`pi-ai/providers/all` (the same table pi's help text prints, so it is also the table
`--provider` and the `$VARIABLE` reference in a provider's `apiKey` agree with). Thirty-odd
of them, `PiProviderTest` pins the whole map against a copy of that table, and the sheet
gained a filter field because a list of *things that exist* is a list to search rather
than a handful of rows to compare.

Two filters are applied, and both are decisions rather than omissions:

- **OAuth-only providers are out** (`openai-codex`, `github-copilot`). Their normal
  credential is an interactive login, which PiKit has no flow for, so a key field would
  ask for a token the user has no way to obtain.
- **Providers that need a second input are out**: `amazon-bedrock` (AWS credentials),
  `azure-openai-responses` (a resource endpoint), `google-vertex` (a project and a
  location), the two Cloudflare providers (an account id, plus a gateway slug for one) and
  `radius` (a gateway URL whose model configuration has to be fetched from it first). Each
  needs a field this page does not have; offering the id without it would produce a
  provider that looks configurable and cannot work. `llama.cpp` is absent for the opposite
  reason — a local server with no credential at all, so there is nothing for the key field
  to hold.

Regional variants stay as separate rows, because pi treats them as separate providers with
separate endpoints and separate model sets (`moonshotai` and `moonshotai-cn`, the four
Xiaomi ones, Qwen's three). Where one variable serves two of them — pi reads
`MOONSHOT_API_KEY` for both Moonshot regions — the labels differ and the picker still
tells them apart; what the user is choosing between is the catalogue behind the id, and the
row says which id it is.

Anything not in the list goes through the custom endpoint below, which is the same option
it has always been.

A relay — an OpenAI-compatible gateway that is not one of pi's built-in providers —
has no `--base-url` flag. The only supported way to add one is a provider entry in
`~/.pi/agent/models.json`, which pi reads at startup. PiKit merges that file on
every launch from the active profile. Getting it to actually authenticate took two
discoveries, both of which produced the *same* symptom — `Connection error`
followed by three retries, with no mention of the real cause.


## `models.json` is pi's file, so it is merged and never deleted

It used to be rewritten wholesale, and **deleted** whenever the active provider was
one of pi's built-ins — the reasoning being that a stale custom entry should not
shadow anything after a switch back. That was wrong, and a user's report is what
settled it: they edited `models.json` by hand (which pi's own `docs/models.md`
invites: "the file is meant to be edited"), restarted the app, and found it gone.
PiKit cannot tell its entry from the user's, so a writer that owns the whole
document will eventually delete something it did not write.

The rules now:

- **Nothing is ever deleted**, and the file is never replaced: it is read as a
  `JsonObject` and merged into. A file that does not parse is left exactly as it
  is, with a log line — pi tolerates `//` comments and this app's parser does not,
  and refusing to write is better than normalising away a comment the user put
  there.
- PiKit withdraws exactly one key of its own, `providers["pikit-custom"]`, and only
  when a custom endpoint is not in use. Reserved ids are how a merge stays honest.
- The file is written only when the encoded document differs, so a launch that
  changes nothing does not touch its timestamp.


## The first write to a `models.json` that does not exist is the one that matters

"Written only when it differs" was implemented as `if (target.readText() != encoded)`,
and `File.readText()` throws `FileNotFoundException` on a file that is not there. The
whole writer is wrapped in `runCatching { … }.onFailure { Log.w(…) }`, so the throw was
logged as `could not merge models.json` and the file was **never created**. pi was then
launched with `--provider pikit-custom`, found that name in no `models.json`, and
`findInitialModel` in its `core/model-resolver.js` answered

```
Unknown provider "pikit-custom". Use --list-models to see available providers/models.
```

and called `process.exit(1)`. That is the whole of the report "custom endpoint
configured, then the chat page says `pi exit with code 1`": from the user's side a
correctly filled-in form produced a launch failure with a message about a provider
name they never typed. The bug only bites on the *first* custom endpoint — a file that
already exists takes the other branch — which is why the end-to-end run in §6 passed
and a fresh install did not. `writePiDefaults` below had always guarded with
`!target.isFile ||`; this writer lost it when it stopped replacing the document
wholesale.


## A launch failure had no way back

Two things about the same report. `selectProfile` committed the new profile to the
store and then did `val client = client ?: return@runCommand` — with the agent dead
there is no client, so picking another provider's model stored the change and did
nothing else. And the failure banner was a `Text` in a `Surface` whose only gesture was
"dismiss", while a `Failed` agent also disables the composer
(`enabled = agent is Running`), so nothing on the chat page could bring pi back. The
user's workaround was to switch the model *and* restart the app, which is exactly what
that combination predicts.

The first is now a relaunch (`scheduleRestart`, since the profile is already
committed), and the second is a **Retry** button on the banner wired to
`restartAgent()` — the same restart without the 1.5 s debounce that exists for
settings that change repeatedly.


## An exit this app caused is not a failure

`attach()` reports an exit when the record flow completes, which is stdout EOF. An
intentional stop cancels that collector first, but cancellation is only observed at a
suspension point: when pi's stdout had *already* closed as the cancel landed, `collect`
returned normally and the tail ran — writing `pi exited with code 1` over the top of the
process that was starting to replace it. The agent was fine and the UI refused to send
to it, because `enabled = agent is Running` and the status was `Failed`. The guard is
the client identity: after a restart the completed flow's source is no longer the
attached client, so it has nothing to report.


## A session switch ends the turn that is running, so it is refused

pi has one live session at a time, and `RuntimeHost.teardownCurrent` — the first thing
`switch_session` and `new_session` do — starts with `await this.session.abort()`
(verified in the bundled bundle, not inferred). There is no way to move to another
session and leave the running turn alone, so the app had two options and was taking
the wrong one: it sent the command and the answer that was streaming stopped, with
nothing on screen to say why. That is the report — "switching sessions while the agent
is answering stops the answer".

The app now refuses while `turnInFlight` (`isStreaming` **and** the agent is
`Running`; the second half matters, because a process that died mid-turn leaves
`isStreaming` stuck true with no `agent_settled` coming, and refusing on that alone
would lock the user out of their own conversation list for ever). The refusal comes
back as `TurnInFlightException`, so the page says it in the interface's language
rather than in the process's, and the text names the way out: wait, or press Stop and
then switch. Deleting the *open* session is refused for the same reason and at the
same moment: it needs a `new_session` to move off the file being deleted, and that is
exactly the command pi will not accept mid-turn.

Two designs were considered and rejected. **Deferring** the switch until the turn
settles looks friendlier and is worse: the tap appears to do nothing for as long as
the turn takes, which is indistinguishable from a dropped tap — the same failure this
app has fixed twice elsewhere. **Switching anyway behind a confirmation** ("this will
end the current answer") is honest and would be the right answer if the request had
been "let me switch and lose the answer"; the request was the opposite, so the default
does not kill work that a single tap could destroy. The app's own Stop button is that
consent, one control away.


## The key must be in argv, not only in the environment

PiKit exports the key as the provider's environment variable, and that is enough
for every built-in provider. It is **not** enough for one registered through
`models.json`. Measured, on a device, with the identical 51-character key:

| how the key is supplied | result |
| --- | --- |
| `PIKIT_API_KEY` only, with `models.json` `apiKey: "$PIKIT_API_KEY"` | `401 无效的令牌` |
| `--api-key <key>` | success |

pi does not resolve the `$VAR` reference in a custom provider's `apiKey`, and the
credentials pi stores from `/login` are what it actually consults for a provider
it did not ship with. So a custom endpoint only works with `--api-key`.
`PiProcessLauncher.buildCommand` had described `--api-key` in a comment and never
passed it. Nothing caught that because every built-in provider reads its
environment variable, so the missing flag was unreachable — the whole project
worked until a relay was configured. `PiProcessLauncherTest` now asserts the flag.
The cost is real and accepted: the key ends up in argv, which `/proc` exposes to
anything that can see this app's processes. The alternative is a custom endpoint
that cannot authenticate at all, since asking a phone user to run `pi /login` is
not something the UI can offer. The launch log masks the value.


## An emulator cannot resolve names, so the request cannot leave

The guest's `getaddrinfo` goes through `netd`, so `resolv.conf` is ignored, and the
Play-Store system image is a `user` build, so `/etc/hosts` is not writable. Measured
on the guest: TLS to the relay's address with the correct SNI succeeds and
`GET /v1/models` returns 200, while the same request by name fails with
`ENOTFOUND`. The network is fine; only the lookup is missing.
`tools/dns-pin.cjs` supplies that lookup inside the Node process, loaded through
`NODE_OPTIONS` and inert unless `PIKIT_DNS_PINS` is present. PiKit adds the pin to
the agent's environment only when it finds that variable, or a `dns-pins.txt` in
its own private directory — neither of which a user can set, and neither of which
a device with working DNS would consult. This is a test affordance that happens to
be shipped, not a feature; without it the provider path could not be exercised on
an emulator at all.


## What the end-to-end run established

With the pin in place, a prompt through the app produced, on the device:

```
user:       run echo PIKIT_E2E_OK and tell me the output
assistant   stopReason=toolUse  tools=[bash]   usage in=833 out=49
toolResult  'PIKIT_E2E_OK\n'
assistant   stopReason=stop     text='The output is:\n\n```\nPIKIT_E2E_OK\n```…'
```

and the chat page rendered it folded: the prompt, a `用时 8s · 2 个步骤` row
directly under it, and the reply.

---
