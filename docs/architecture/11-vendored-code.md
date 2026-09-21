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

---
