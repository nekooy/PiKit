# 11. Vendored code

*[Architecture](../ARCHITECTURE.md) §11.*

`terminal-emulator/` and `terminal-view/` are copied from
[termux/termux-app](https://github.com/termux/termux-app) (GPLv3, revision
`3b66f879`, v0.118.0). They were chosen because they are self-contained: the only
changes made are the two rewritten `build.gradle.kts` files. Specifically,
neither module depends on `termux-shared`, which is why this project does not
carry the rest of Termux. `terminal-emulator` supplies the VT parser and, through
a single C file (`src/main/jni/termux.c`), the `fork`/`execvp`/`/dev/ptmx` PTY
allocator used for the terminal tab. It also ships a 20-file JUnit suite for the
parser, which runs with `./gradlew :terminal-emulator:test`.

---
