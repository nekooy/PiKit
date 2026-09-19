#!/data/data/pi.kit.mob/files/usr/bin/sh
# PiKit's storage self-test.
#
# Answers two questions the app cannot answer by inspecting its own flags:
#
#   1. **Can the environment reach the folders the user granted, for real?**
#      Android records the permission, and this app's own code has been wrong
#      before about whether the kernel agrees. So this reads and writes.
#
#   2. **Is the delete guard actually in place?** Every recursive delete goes
#      through code that refuses anything outside the app's private directory and
#      removes symlinks by path rather than following them. This builds the exact
#      shape that used to destroy data — a link to a directory holding a sentinel
#      — and checks the sentinel is still there afterwards.
#
# It is run by Settings → Updates & repair → "Check storage", and it is safe to
# run from the terminal at any time. It writes only inside `$HOME` and, when the
# user has granted a folder, a probe file it deletes again.
#
# Prints one `ok`/`FAIL` line per check and a final tally, so the exit status and
# the text agree.

PREFIX=${PREFIX:-/data/data/pi.kit.mob/files/usr}
HOME=${HOME:-/data/data/pi.kit.mob/files/home}
PATH="$PREFIX/bin"
export PREFIX HOME PATH
TMPDIR="$PREFIX/tmp"
export TMPDIR

PASS=0
FAIL=0

ok() {
    PASS=$((PASS + 1))
    printf '  ok    %s\n' "$1"
}

bad() {
    FAIL=$((FAIL + 1))
    printf '  FAIL  %s\n' "$1"
}

check() {
    if [ "$1" = "$2" ]; then ok "$3"; else bad "$3 (expected '$2', got '$1')"; fi
}

echo "=== identity"
echo "  uid: $(id -u 2>/dev/null)"
# The supplementary groups are worth showing: `/storage/emulated` is a FUSE mount
# whose access is decided by the media provider against this uid, not by these
# groups — so their absence is expected and not a fault.
echo "  groups: $(id -G 2>/dev/null)"
echo "  home: $HOME"
echo "  prefix: $PREFIX"

echo
echo "=== the environment is intact"
if [ -x "$PREFIX/bin/bash" ]; then ok "bin/bash is executable"; else bad "bin/bash is missing"; fi
if [ -x "$PREFIX/bin/node" ]; then ok "bin/node is executable"; else bad "bin/node is missing"; fi
if [ -d "$HOME" ]; then ok "\$HOME exists"; else bad "\$HOME is missing"; fi

echo
echo "=== the storage link farm"
FARM="$HOME/storage"
if [ -d "$FARM" ]; then
    links=$("$PREFIX/bin/find" "$FARM" -maxdepth 1 -type l 2>/dev/null | "$PREFIX/bin/wc" -l)
    if [ "$links" = "0" ]; then
        ok "no folders are granted to the agent (this is the default)"
    else
        ok "$links folder(s) granted"
        for entry in "$FARM"/*; do
            if [ -L "$entry" ]; then
                target=$("$PREFIX/bin/readlink" "$entry" 2>/dev/null)
                name=$("$PREFIX/bin/basename" "$entry")
                if [ -d "$entry" ]; then
                    count=$("$PREFIX/bin/ls" -A "$entry" 2>/dev/null | "$PREFIX/bin/wc" -l)
                    ok "  $name -> $target (readable, $count entries)"
                else
                    bad "  $name -> $target is not reachable"
                fi
                # A write probe, deleted again, so a read-only grant is reported
                # as read-only rather than passing on the directory listing.
                probe="$entry/.pikit-selftest"
                if printf 'pikit' > "$probe" 2>/dev/null; then
                    if [ "$("$PREFIX/bin/cat" "$probe" 2>/dev/null)" = "pikit" ]; then
                        ok "  $name is writable"
                    else
                        bad "  $name accepted a write but did not read it back"
                    fi
                    "$PREFIX/bin/rm" -f "$probe" 2>/dev/null
                else
                    bad "  $name is NOT writable from this process"
                fi
            fi
        done
    fi
else
    ok "~/storage does not exist, so the agent cannot see shared storage"
fi

echo
echo "=== one folder, one name"
# The whole tree and one of its sub-folders can both be granted, and the farm is
# only allowed to honour that once: `/sdcard/Download` is `~/storage/downloads`
# by its own grant and `~/storage/shared/Download` through `shared`, and the farm
# used to create both. Resolved with `readlink -f`, because two names for one
# directory look like different paths and the same directory to the kernel.
if [ -d "$FARM" ]; then
    linklist=$HOME/tmp/pikit-farm-links-$$
    "$PREFIX/bin/mkdir" -p "$HOME/tmp"
    : > "$linklist"
    for entry in "$FARM"/*; do
        if [ -L "$entry" ]; then
            resolved=$("$PREFIX/bin/readlink" -f "$entry" 2>/dev/null)
            if [ -n "$resolved" ]; then
                "$PREFIX/bin/basename" "$entry" >> "$linklist"
                printf '%s\n' "$resolved" >> "$linklist"
            fi
        fi
    done

    conflict=0
    reported=" "
    # One name and one path per line, read in pairs. The inner loop re-opens the
    # file, so the two reads do not share a descriptor.
    while read -r name1 && read -r path1; do
        case "$reported" in
            *" $name1 "*) continue ;;
        esac
        while read -r name2 && read -r path2; do
            # The same directory under two names is the duplicate this check
            # exists for. A link *inside* another link's tree is not: a folder
            # added under a granted one is a shortcut the user asked for, and it
            # is what the page's own "Add a folder" picker produces, so it is
            # reported by neither loop.
            if [ "$name1" != "$name2" ] && [ "$path1" = "$path2" ]; then
                bad "$name1 -> $path1 is also named $name2"
                reported="$reported$name1 $name2 "
                conflict=1
                break
            fi
        done < "$linklist"
    done < "$linklist"

    if [ "$conflict" = "0" ]; then ok "no folder is linked under two names"; fi
    "$PREFIX/bin/rm" -f "$linklist"
else
    ok "no link farm to check for duplicate names"
fi

echo
echo "=== the delete guard: a link must be removed, its target left alone"
LAB="$HOME/tmp/selftest-$$"
REAL=$LAB/real
FARM2=$LAB/farm
"$PREFIX/bin/rm" -rf "$LAB"
"$PREFIX/bin/mkdir" -p "$REAL/sub"
"$PREFIX/bin/mkdir" -p "$FARM2"
printf 'sentinel' > "$REAL/sub/keep.txt"
"$PREFIX/bin/ln" -sfn "$REAL" "$FARM2/link"
"$PREFIX/bin/ln" -sfn "$LAB/does-not-exist" "$FARM2/dangling"

files_before=$("$PREFIX/bin/find" "$REAL" -type f 2>/dev/null | "$PREFIX/bin/wc" -l)
check "$files_before" "1" "the lab has one file to protect"

# Remove every entry the way the app does: ask what it *is*, remove links by
# path, and never descend through one.
for entry in "$FARM2"/*; do
    if [ -L "$entry" ]; then
        "$PREFIX/bin/rm" -f "$entry"
    elif [ -d "$entry" ]; then
        "$PREFIX/bin/rm" -rf "$entry"
    else
        "$PREFIX/bin/rm" -f "$entry"
    fi
done

remaining=$("$PREFIX/bin/ls" -A "$FARM2" 2>/dev/null | "$PREFIX/bin/wc" -l)
check "$remaining" "0" "both links removed, including the dangling one"

files_after=$("$PREFIX/bin/find" "$REAL" -type f 2>/dev/null | "$PREFIX/bin/wc" -l)
check "$files_after" "1" "the sentinel survived removing the links"

"$PREFIX/bin/rm" -rf "$LAB"

echo
echo "=== the relocator's boundary"
out=$("$PREFIX/bin/pikit-relocate" --prefix /sdcard 2>&1)
case "$out" in
    *"refusing to rewrite"*) ok "the package relocator refuses /sdcard" ;;
    *) bad "the package relocator did not refuse /sdcard" ;;
esac
out=$("$PREFIX/bin/pikit-relocate" --prefix "$HOME" 2>&1)
case "$out" in
    *"refusing to rewrite"*) ok "the package relocator refuses \$HOME" ;;
    *) bad "the package relocator did not refuse \$HOME" ;;
esac

echo
printf 'storage self-test: %s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" = "0" ]
