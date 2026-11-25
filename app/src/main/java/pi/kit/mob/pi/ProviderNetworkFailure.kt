package pi.kit.mob.pi

import pi.kit.mob.locales.ProviderNetworkFailure
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

internal fun endpointHost(baseUrl: String): String? = runCatching {
    val uri = URI(baseUrl)
    uri.host?.takeIf { uri.rawUserInfo == null && uri.scheme.lowercase() in setOf("http", "https") }
}.getOrNull()

internal fun providerNetworkFailure(error: Throwable): ProviderNetworkFailure {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return causes.firstNotNullOfOrNull { providerNetworkFailure(it.message.orEmpty()) }
        ?: when {
            causes.any { it is CertificateException || it is CertPathValidatorException } -> ProviderNetworkFailure.Certificate
            causes.any { it is SSLPeerUnverifiedException } -> ProviderNetworkFailure.Certificate
            causes.any { it is SSLException } -> ProviderNetworkFailure.Tls
            causes.any { it is UnknownHostException } -> ProviderNetworkFailure.Dns
            causes.any { it is SocketTimeoutException } -> ProviderNetworkFailure.Timeout
            else -> ProviderNetworkFailure.Connection
        }
}

internal fun providerNetworkFailure(message: String): ProviderNetworkFailure? {
    val text = message.lowercase()
    return when {
        "err_tls_cert_altname_invalid" in text || "hostname mismatch" in text ||
            "doesn't match the certificate" in text || "no subject alternative" in text -> ProviderNetworkFailure.Hostname
        "cert_has_expired" in text || "certificate has expired" in text ||
            "self signed certificate" in text || "self-signed certificate" in text ||
            "unable to verify the first certificate" in text || "unable to get local issuer" in text ||
            "trust anchor for certification path" in text || "pkix path" in text -> ProviderNetworkFailure.Certificate
        "tls handshake" in text || "ssl handshake" in text || "ssl routines:" in text ||
            "before secure tls connection" in text || "err_ssl_" in text -> ProviderNetworkFailure.Tls
        "enotfound" in text || "eai_again" in text || "unable to resolve host" in text -> ProviderNetworkFailure.Dns
        "etimedout" in text || "und_err_connect_timeout" in text || "request timed out" in text -> ProviderNetworkFailure.Timeout
        "econnrefused" in text || "econnreset" in text || text.trim() in setOf("connection error.", "fetch failed") -> ProviderNetworkFailure.Connection
        else -> null
    }
}

private val AUTH_STATUS = Regex("""^(?:HTTP\s+)?(401|403)(?:\s|:|$)""", RegexOption.IGNORE_CASE)

internal fun providerAuthStatus(message: String): Int? =
    AUTH_STATUS.find(message.trimStart())?.groupValues?.get(1)?.toIntOrNull()
