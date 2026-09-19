#!/data/data/pi.kit.mob/files/usr/bin/sh
# dpkg, with the prefix relocation done first.
#
# ## Why this exists *as well as* the apt hook
#
# `etc/apt/apt.conf.d/99pikit-relocate` binds the relocator to apt's
# `DPkg::Pre-Install-Pkgs`, which hands the hook the list of archives apt is
# about to give dpkg — so an ordinary `pkg install` is covered before dpkg sees
# anything, and the packages it rewrites are the private copies apt stages for
# the transaction.
#
# That leaves every installation apt did not mediate: `dpkg -i foo.deb`, an
# archive fetched by hand, a `dpkg` call from a script. Those reach a package
# whose every path names `/data/data/com.termux/files/usr`, which this app cannot
# read, and the failure — `unable to stat './data/data/com.termux'` — points at
# the archive rather than at the missing rewrite.
#
# So the relocation also happens here, on the exact arguments dpkg is about to
# act on. It is idempotent — a package the apt hook already rewrote contains no
# occurrence of the old prefix and this is a no-op — and it cannot be routed
# around by anything that goes through `dpkg`.
#
# The real dpkg is renamed to `dpkg.real` by the image build. If that rename did
# not happen (an image from before this wrapper existed), this script falls back
# to the bare name so nothing is left unrunnable.
#
# This file is listed in `prefix_patch.NEVER_REWRITE`: it names the upstream
# prefix in these comments, and the build's rewrite must not touch it.

SELF=$PREFIX/bin/dpkg
if [ -x "$PREFIX/bin/dpkg.real" ]; then
    REAL=$PREFIX/bin/dpkg.real
else
    REAL=$PREFIX/bin/dpkg
fi

# Collect the archive arguments. dpkg accepts `--unpack <file>`, `-i <file>`, and
# positional archives for `--install`.
ARCHIVES=
for arg in "$@"; do
    case "$arg" in
        *.deb)
            ARCHIVES="$ARCHIVES $arg"
            ;;
    esac
done

if [ -n "$ARCHIVES" ]; then
    # The log is opt-in: it is here because the one time this went wrong, the
    # symptom was an installed package that had not been rewritten, and there was
    # no way to tell from the outside whether the wrapper had run, what it was
    # pointed at, or whether the rewrite reached the file dpkg read.
    if [ -n "$PIKIT_DPKG_TRACE" ]; then
        echo "[pikit-dpkg] argv: $*" >&2
        echo "[pikit-dpkg] archives:$ARCHIVES" >&2
        for archive in $ARCHIVES; do
            if [ -f "$archive" ]; then
                echo "[pikit-dpkg] exists: $archive ($(wc -c < "$archive") bytes)" >&2
            else
                echo "[pikit-dpkg] MISSING: $archive" >&2
            fi
        done
    fi
    # shellcheck disable=SC2086
    "$PREFIX/bin/pikit-relocate" --debs-here $ARCHIVES
fi

exec "$REAL" "$@"
