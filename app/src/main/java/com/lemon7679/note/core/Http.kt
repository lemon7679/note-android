package com.lemon7679.note.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TLS rules for one NAS. Home NAS boxes very often use self-signed
 * certificates on a LAN IP; with `allowSelfSigned` the first certificate seen
 * is pinned (trust on first use) and any later change is refused.
 */
data class TlsPolicy(val allowSelfSigned: Boolean = false, val pinnedSha256: String? = null)

class HttpResult(val status: Int, val body: ByteArray, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
    val isSuccess get() = status in 200..299
}

/** OkHttp wrapper with suspending calls, cancellation and the TLS policy above. */
class HttpTransport(policy: TlsPolicy = TlsPolicy(), timeoutSeconds: Long = 30) {
    @Volatile var tls: TlsPolicy = policy
        private set

    /** Called with the fingerprint of an accepted self-signed certificate (to pin it). */
    var onCertificatePinned: ((String) -> Unit)? = null

    /** Fingerprint of the last certificate that was rejected (for "trust this certificate?" UI). */
    @Volatile var lastRejectedFingerprint: String? = null
        private set

    private val systemTrust: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            systemTrust.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val fingerprint = chain.firstOrNull()?.let { fingerprint(it) }
            val pinned = tls.pinnedSha256
            if (pinned != null) {
                // A pinned certificate is accepted only while it stays the same.
                if (fingerprint == pinned) return
                lastRejectedFingerprint = fingerprint
                throw CertificateException("pinned certificate changed")
            }
            try {
                systemTrust.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                if (tls.allowSelfSigned && fingerprint != null) {
                    tls = tls.copy(pinnedSha256 = fingerprint)
                    onCertificatePinned?.invoke(fingerprint)
                } else {
                    lastRejectedFingerprint = fingerprint
                    throw e
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
    }

    private val hostnameVerifier = HostnameVerifier { host, session ->
        val pinned = tls.pinnedSha256
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        if (pinned != null && leaf != null && fingerprint(leaf) == pinned) true
        else OkHostnameVerifier.verify(host, session)
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory,
            trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build()

    /** Runs a request and reads the whole body. */
    suspend fun execute(request: Request): HttpResult = call(request) { response ->
        HttpResult(response.code, response.body?.bytes() ?: ByteArray(0), headersOf(response))
    }

    /** Streams a 2xx body into `destination` (replacing it); other responses return without writing. */
    suspend fun download(request: Request, destination: File): HttpResult = call(request) { response ->
        if (response.isSuccessful) {
            destination.parentFile?.mkdirs()
            val tmp = File(destination.parentFile, ".${destination.name}.part")
            response.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            destination.delete()
            if (!tmp.renameTo(destination)) throw IOException("다운로드한 파일을 저장하지 못했습니다.")
            HttpResult(response.code, ByteArray(0), headersOf(response))
        } else {
            HttpResult(response.code, response.body?.bytes() ?: ByteArray(0), headersOf(response))
        }
    }

    private fun headersOf(r: Response): Map<String, String> = r.headers.associate { it.first.lowercase() to it.second }

    private suspend fun <T> call(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    cont.resumeWithException(map(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use(read)
                        cont.resume(value)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(map(e))
                    }
                }
            })
        }

    private fun map(e: IOException): IOException {
        if (e is RemoteException) return e
        if (e is SSLException || e.cause is CertificateException) {
            val fp = lastRejectedFingerprint
            val pinned = tls.pinnedSha256
            return if (pinned != null && fp != null && fp != pinned) RemoteException.CertificateChanged(fp)
            else RemoteException.UntrustedCertificate(fp)
        }
        return RemoteException.Network(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

        /** "Basic dXNlcjpwYXNz" */
        fun basicAuthorization(user: String, password: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    }
}
