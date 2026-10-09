# 11. Vendored code

*[Architecture](../ARCHITECTURE.md) §11.*

`terminal-emulator/` and `terminal-view/` are copied from
[termux/termux-app](https://github.com/termux/termux-app) (GPLv3, revision `3b66f879`, v0.118.0),
with only their two `build.gradle.kts` files rewritten. Neither depends on `termux-shared`, which is
why the rest of Termux is not carried. `terminal-emulator` supplies the VT parser and, through one C
file (`src/main/jni/termux.c`), the `fork`/`execvp`/`/dev/ptmx` PTY allocator for the terminal tab;
it also ships a 20-file JUnit suite for the parser (`./gradlew :terminal-emulator:test`).

Both APKs assemble, debug and release, with `com/termux/terminal/JNI` surviving R8 in the
release dex — it is the class `termux.c` registers its native methods into — and the PTY
shim compiles for both ABIs. The release build is the one people install, and a native
entry point a shrinker removed fails at the first shell rather than at the build.

## javac's deprecation note, and why it stays

Compiling either module prints javac's own note — `TerminalSession.java uses or overrides a
deprecated API`, and the same line for `TerminalView.java` — because upstream uses three
members deprecated in the API 36 platform: `Handler`'s no-argument constructor
(`TerminalSession`'s `MainThreadHandler`, one line under the `@SuppressLint("HandlerLeak")`
upstream already carries for it), and in `TerminalView.onKeyDown`, `KeyEvent.ACTION_MULTIPLE`
with `KeyEvent.getCharacters()`.

**No compiler flag removes it.** Measured with javac 21 on these sources: `-nowarn` leaves the
note, `-Xlint:-deprecation` leaves it, `-Xlint:all,-deprecation` leaves it, and
`-Xlint:deprecation` replaces it with a per-line warning — more output rather than less. What
does silence it is `@SuppressWarnings("deprecation")` on those two members, and that is
deliberately not done: it would put two edits into the sources this chapter says are
upstream's, and a re-vendor would drop them again without anything failing, so the note would
come back with no record of why it had gone. Two lines of note per module, on a compile that
runs only when one of those modules changes, is the cheaper side of that trade.

---
