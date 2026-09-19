package pi.kit.mob.env

/**
 * Which files in the runtime must be executable.
 *
 * The prefix is unpacked from a zip, and a zip entry does not carry a Unix mode
 * that the JVM will apply — every extracted file lands with the app's default
 * permissions. So the executable bit has to be decided by path, and it has to be
 * decided here rather than inferred from the archive.
 *
 * The node_modules case is the one that bites: `bin/npm` is a symlink into
 * `lib/node_modules/npm/bin/npm-cli.js`, so the link resolves happily while the
 * target cannot be executed. `pi update` shells out to `npm`, so that only shows
 * up when a newer pi actually exists to install — long after anyone would think
 * to look for it.
 */
internal fun needsExecutableBit(entryName: String): Boolean {
    val path = entryName.replace('\\', '/').trimStart('/')
    return path.startsWith("bin/") ||
        path.startsWith("libexec") ||
        path.startsWith("lib/apt/apt-helper") ||
        path.startsWith("lib/apt/methods") ||
        // npm's layout for a package's executables: <package>/bin/<file>, with
        // scoped packages under an extra segment.
        (path.startsWith("lib/node_modules/") && path.contains("/bin/"))
}
