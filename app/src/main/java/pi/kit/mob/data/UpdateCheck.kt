package pi.kit.mob.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** A published PiKit, in the two facts the About page needs. */
data class Release(val version: String, val pageUrl: String)

/**
 * Asks GitHub whether a newer release exists.
 *
 * ## Why this is a row the user taps and not a background check
 *
 * The app makes exactly one kind of network request of its own, and it makes it when
 * something the user asked for needs the network. A release check on launch would
 * break that: it would be traffic nobody asked for, on every cold start, leaking an
 * install timestamp to GitHub — which is the same objection that keeps telemetry out
 * of this project. Tapping a row is one request, at a time the user chose, and the
 * answer is shown where it was asked for. It is also the reason there is no update
 * *notification*: that needs a check nobody asked for.
 *
 * ## Why this asks `github.com` and not `api.github.com`
 *
 * The same fact is available from both, and the API is the one that fails for a reader
 * behind a VPN. `GET api.github.com/repos/…/releases/latest` without a token is limited
 * to **60 requests per hour per address** (`X-RateLimit-Limit: 60`, resource `core`), and
 * a VPN exit is an address many people share, so the budget is normally already spent:
 * measured on such an address, `HTTP/1.1 403 rate limit exceeded` with
 * `X-RateLimit-Remaining: 0` and `X-RateLimit-Used: 60`, which the row could only report
 * as "GitHub answered 403" — the report "开了vpn后就一直403".
 *
 * `https://github.com/<owner>/<repo>/releases/latest` is the page a browser goes to, and
 * it answers with the same fact as a **redirect**: `302` to `…/releases/tag/v0.2.0`. No
 * token, no API budget, and the redirect's target is both the tag to compare and the page
 * to open. A repository with nothing published answers `404` (measured on this project
 * while it had no release) or redirects to the releases *list* (measured on
 * `octocat/Hello-World`); both mean the same thing to this row and are read as
 * [Outcome.NoReleases].
 *
 * ## What it compares
 *
 * The release's tag (`v0.2.0`, as `.github/workflows/release.yml` names it) against
 * `BuildConfig.VERSION_NAME`. The tag is the only version GitHub publishes, and it is
 * derived from `pikit.versionName`, so the two cannot drift; `versionCode` is
 * deliberately not compared, because Android's integer ordering says nothing about
 * which release is newer to a person.
 *
 * ## Nothing here installs anything
 *
 * The row opens the release page in a browser. Downloading and installing an APK from
 * inside the app needs `REQUEST_INSTALL_PACKAGES`, a `FileProvider` root and a
 * `PackageInstaller` session, and it would have to be signed by the same key as the
 * installed build — three ways to fail that are worse than one tap in a browser, for
 * an app whose whole premise is that the user is in charge of what runs.
 */
object UpdateCheck {

    /** `%s` is `<owner>/<repo>`, from `pikit.repository` via `BuildConfig`. */
    private const val ENDPOINT = "https://github.com/%s/releases/latest"

    /** Where the redirect lands, and the part of it that carries the tag. */
    private const val RELEASES_PATH = "/releases"
    private const val TAG_MARKER = "/releases/tag/"

    /**
     * Short on purpose. A row that spins for thirty seconds reads as broken, and the
     * failure it produces ("could not reach GitHub") is the same one the user can
     * act on by trying later.
     */
    private const val TIMEOUT_MILLIS = 6_000

    /** Why a check produced no answer. The row shows this through `failedWith`. */
    sealed interface Outcome {
        data class Found(val release: Release) : Outcome

        /** The repository exists but has published nothing: true of a new project. */
        data object NoReleases : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /**
     * The newest release, or why there is no answer.
     *
     * Never throws: every failure mode here is one the About page has to render
     * anyway, and a `Result` at the call site would be unwrapped in the same three
     * lines that `Outcome` names.
     */
    suspend fun latest(repository: String, userAgent: String): Outcome =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(endpointFor(repository)).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.requestMethod = "GET"
                // Explicit rather than relied on: the answer *is* the redirect, and a
                // connection that stopped following them would report the `302`'s own
                // status with no tag anywhere in it.
                connection.instanceFollowRedirects = true
                // GitHub asks that a client identify itself, and naming the app and its
                // version is also what makes this traffic legible in GitHub's own logs.
                connection.setRequestProperty("User-Agent", userAgent)

                val code = connection.responseCode
                // After the redirects: `/releases/tag/v0.2.0` for a published release,
                // `/releases` for a repository that has none. The page's body is never
                // read — every fact needed here is in the URL it landed on.
                val landedOn = connection.url.toString()
                connection.disconnect()

                when {
                    code == HttpURLConnection.HTTP_NOT_FOUND -> Outcome.NoReleases
                    // 403 and 429 are what an address GitHub will not serve gets, and
                    // naming it is more use than the number: it is a proxy, a shared
                    // address or an abuse rule, not anything the reader did.
                    code == HttpURLConnection.HTTP_FORBIDDEN || code == 429 ->
                        Outcome.Failed("GitHub refused the request ($code)")

                    code !in 200..299 -> Outcome.Failed("GitHub answered $code")
                    else -> releaseFrom(landedOn)
                        ?.let { Outcome.Found(it) }
                        ?: if (landedOn.contains(RELEASES_PATH)) {
                            Outcome.NoReleases
                        } else {
                            Outcome.Failed("GitHub's answer had no release in it")
                        }
                }
            } catch (failure: Exception) {
                // A phone with no route to the host throws from `openConnection`,
                // from `responseCode` or from the read, and the messages differ
                // ("Unable to resolve host", "Failed to connect"). The row says the
                // message rather than guessing at a cause.
                Outcome.Failed(failure.message ?: failure.javaClass.simpleName)
            }
        }

    /**
     * The URL a check asks, assembled from `pikit.repository` at build time.
     *
     * A function rather than an inline `format` so the assembly is testable: a wrong
     * owner or repo produces a check that always fails, and the failure would look
     * like the network's fault rather than the build's.
     */
    internal fun endpointFor(repository: String): String = ENDPOINT.format(repository)

    /**
     * The release a check landed on, read out of the URL it was redirected to.
     *
     * Hand-read rather than deserialised, for the reason the redirect is used at all: the
     * only fact needed is the tag, and it is a path segment. Requires `https://github.com/`,
     * so that an answer from anywhere else — a captive portal, a proxy's own page — is read
     * as no answer rather than as a release with a URL this row would then offer to open.
     */
    internal fun releaseFrom(url: String): Release? {
        if (!url.startsWith("https://github.com/")) return null
        val marker = url.indexOf(TAG_MARKER)
        if (marker < 0) return null
        val tag = url.substring(marker + TAG_MARKER.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        if (tag.isBlank()) return null
        return Release(versionOf(tag), url)
    }

    /** `v0.2.0` (what the release workflow tags) to `0.2.0` (what the app reports). */
    internal fun versionOf(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /**
     * True when [candidate] is a later version than [current].
     *
     * Component-wise and numeric, so `0.10.0` is newer than `0.9.0` — a string
     * comparison gets that one backwards, which is the whole reason this is not a
     * string comparison. Missing components count as zero (`1.0` equals `1.0.0`).
     *
     * A component that is not a number is compared as text, and that is a deliberate
     * stopping point rather than a semver implementation: `pikit.versionName` is
     * checked at build time to be a bare `major.minor.patch`, `.github/workflows/
     * release.yml` tags exactly that, and GitHub's `releases/latest` never returns a
     * prerelease — so a suffix cannot arrive here from a build of this repository. It
     * is not refused either, because a check that throws on an unexpected tag is worse
     * than one that answers with a rule it can state.
     */
    internal fun isNewer(candidate: String, current: String): Boolean =
        compareVersions(candidate, current) > 0

    internal fun compareVersions(left: String, right: String): Int {
        val leftParts = versionOf(left).split('.')
        val rightParts = versionOf(right).split('.')
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val a = leftParts.getOrElse(index) { "0" }
            val b = rightParts.getOrElse(index) { "0" }
            val aNumber = a.toIntOrNull()
            val bNumber = b.toIntOrNull()
            val difference = if (aNumber != null && bNumber != null) {
                aNumber.compareTo(bNumber)
            } else {
                a.compareTo(b)
            }
            if (difference != 0) return difference
        }
        return 0
    }
}
