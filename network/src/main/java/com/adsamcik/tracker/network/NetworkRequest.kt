package com.adsamcik.tracker.network

/**
 * A single outbound HTTP request submitted to [NetworkGateway.request].
 *
 * @property url Absolute URL. Must use `https://` — `http://` is rejected by
 *   the default gateway implementation to prevent accidental cleartext leaks.
 * @property method HTTP verb. Defaults to GET; consumers wanting POST/PUT/etc.
 *   pass it explicitly.
 * @property headers Extra request headers. The gateway does NOT inject any
 *   identifying headers — what you pass here is what gets sent.
 * @property body Request body bytes. Empty for GET; consumers building
 *   non-GET requests pass the encoded payload here. Mime type goes in
 *   [headers] under `Content-Type`.
 * @property timeoutMs Per-request hard timeout. Default 30s.
 */
data class NetworkRequest(
	val url: String,
	val method: HttpMethod = HttpMethod.GET,
	val headers: Map<String, String> = emptyMap(),
	val body: ByteArray = EMPTY_BYTES,
	val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is NetworkRequest) return false
		return url == other.url &&
			method == other.method &&
			headers == other.headers &&
			body.contentEquals(other.body) &&
			timeoutMs == other.timeoutMs
	}

	override fun hashCode(): Int {
		var result = url.hashCode()
		result = 31 * result + method.hashCode()
		result = 31 * result + headers.hashCode()
		result = 31 * result + body.contentHashCode()
		result = 31 * result + timeoutMs.hashCode()
		return result
	}

	companion object {
		const val DEFAULT_TIMEOUT_MS: Long = 30_000L
		private val EMPTY_BYTES = ByteArray(0)
	}
}

enum class HttpMethod { GET, POST, PUT, DELETE, HEAD, PATCH }

/** Sealed result of a [NetworkGateway.request] call. */
sealed interface NetworkResponse {
	val request: NetworkRequest

	/** 2xx HTTP response with body bytes + headers + latency. */
	data class Success(
		override val request: NetworkRequest,
		val statusCode: Int,
		val body: ByteArray,
		val headers: Map<String, String>,
		val latencyMs: Long,
	) : NetworkResponse {
		init {
			require(statusCode in 200..299) {
				"NetworkResponse.Success only models 2xx; got $statusCode — use Failure with NetworkError.HttpStatus"
			}
		}

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Success) return false
			return request == other.request &&
				statusCode == other.statusCode &&
				body.contentEquals(other.body) &&
				headers == other.headers &&
				latencyMs == other.latencyMs
		}

		override fun hashCode(): Int {
			var result = request.hashCode()
			result = 31 * result + statusCode
			result = 31 * result + body.contentHashCode()
			result = 31 * result + headers.hashCode()
			result = 31 * result + latencyMs.hashCode()
			return result
		}
	}

	/** Any non-success outcome, discriminated by [error]. */
	data class Failure(
		override val request: NetworkRequest,
		val error: NetworkError,
	) : NetworkResponse
}

/** Categorical reason a request did not produce a [NetworkResponse.Success]. */
sealed interface NetworkError {
	/** Gateway kill-switch is off; no traffic permitted. */
	data object GatewayDisabled : NetworkError

	/** Host is not in [NetworkPolicy.allowedHosts]. */
	data class HostNotAllowed(val host: String) : NetworkError

	/** Per-host rate limit exceeded; caller should back off. */
	data class RateLimited(val host: String, val retryAfterMs: Long) : NetworkError

	/** Non-https scheme rejected at the URL parsing step. */
	data class InsecureScheme(val scheme: String) : NetworkError

	/** Malformed URL or unparseable host. */
	data class InvalidUrl(val url: String, val reason: String) : NetworkError

	/** Network I/O error (no route, DNS, connection refused, socket reset, …). */
	data class Transport(val message: String, val cause: Throwable? = null) : NetworkError

	/** Request exceeded [NetworkRequest.timeoutMs]. */
	data object Timeout : NetworkError

	/** Non-2xx HTTP status with body bytes preserved for diagnostic use. */
	data class HttpStatus(
		val statusCode: Int,
		val body: ByteArray,
		val headers: Map<String, String>,
	) : NetworkError {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is HttpStatus) return false
			return statusCode == other.statusCode &&
				body.contentEquals(other.body) &&
				headers == other.headers
		}

		override fun hashCode(): Int {
			var result = statusCode
			result = 31 * result + body.contentHashCode()
			result = 31 * result + headers.hashCode()
			return result
		}
	}
}
