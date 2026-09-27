package pi.kit.mob.locales

/**
 * Why a model list could not be fetched, in a shape the catalogs can word.
 *
 * [ModelDiscovery][pi.kit.mob.pi.ModelDiscovery] used to carry an English sentence
 * built at the throw site, and the model page printed it verbatim — so a Chinese or
 * Japanese interface showed `host rejected the request: HTTP 401`, and the two
 * sentences that say what to *do* about it (a wrong proxy URL answers `401` just
 * like a wrong key; type the id by hand) were unreachable to anyone who needed them.
 *
 * Each case carries the varying parts and nothing else; the wording is
 * [Strings.discoveryProblem]'s job. Numbers and identifiers stay as they are in
 * every language — an HTTP status and a host name are not translated.
 */
sealed interface DiscoveryProblem {

    /** No key filled in yet, and a built-in provider cannot list models without one. */
    data object NeedApiKey : DiscoveryProblem

    /** The endpoint field is empty on a custom endpoint. */
    data object NeedBaseUrl : DiscoveryProblem

    /**
     * The endpoint field is filled in but is not a URL this app can hand pi.
     * Same verdict as [Strings.invalidBaseUrl], said as a fetch failure.
     */
    data object InvalidBaseUrl : DiscoveryProblem

    /**
     * pi has no `/models` route registered for this provider, so the list comes
     * from pi's own catalog instead — if that answers at all.
     */
    data class NoModelsEndpoint(val provider: String) : DiscoveryProblem

    /**
     * The endpoint answered with a non-2xx status.
     *
     * @param viaOverride the URL came from the profile's endpoint field rather
     *   than from pi's own provider table. A `401` from a proxy is just as often
     *   a wrong path as a wrong key, which is why the wording differs and why
     *   this is not a verdict on the credential.
     */
    data class HttpRejected(
        val host: String,
        val code: Int,
        val detail: String,
        val viaOverride: Boolean,
    ) : DiscoveryProblem

    /** The endpoint answered 2xx with a body this app could not find model ids in. */
    data class NoModelIds(val host: String) : DiscoveryProblem

    /** The request did not complete: DNS, TLS, connection, or timeout. */
    data class Unreachable(val host: String, val cause: String) : DiscoveryProblem

    /** pi started but refused `get_available_models`. */
    data class PiRefused(val detail: String) : DiscoveryProblem

    /** pi's catalog listed nothing for this provider. */
    data class CatalogEmpty(val provider: String) : DiscoveryProblem

    /**
     * The `pi` CLI is not in the bundled runtime, so no process can be started
     * to read a catalog at all. Distinct from [PiCannotStart] because the fix is
     * reinstalling the runtime, not retrying.
     */
    data object CliMissing : DiscoveryProblem

    /** The throwaway `pi --mode rpc` would not start or load its bundle. */
    data class PiCannotStart(val cause: String) : DiscoveryProblem

    /** The scratch agent directory [catalogueProbe][pi.kit.mob.pi.ModelDiscoveryClient.catalogueProbe] needs could not be created. */
    data object ScratchDirFailed : DiscoveryProblem
}
