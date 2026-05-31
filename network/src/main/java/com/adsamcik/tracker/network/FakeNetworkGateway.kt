package com.adsamcik.tracker.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test fake for [NetworkGateway]. NOT for production use.
 *
 * Records every [request] call into [recordedRequests] in arrival order.
 * Returns the response set up via [respondWith] (matched by URL prefix in
 * order of registration; first match wins). Falls back to
 * [defaultResponse] when no stub matches.
 *
 * Honors the kill switch and the policy allowlist so tests can verify
 * consumer code's reaction to those signals. Rate limiting is a no-op.
 */
class FakeNetworkGateway(
	initialEnabled: Boolean = true,
	initialPolicy: NetworkPolicy = NetworkPolicy(allowedHosts = setOf("example.com")),
	private val defaultResponse: (NetworkRequest) -> NetworkResponse = { req ->
		NetworkResponse.Failure(req, NetworkError.Transport("no stub registered"))
	},
) : NetworkGateway {

	private val _isEnabled = MutableStateFlow(initialEnabled)
	override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

	private val _policy = MutableStateFlow(initialPolicy)
	override val policy: StateFlow<NetworkPolicy> = _policy.asStateFlow()

	private val stubs = mutableListOf<Pair<String, (NetworkRequest) -> NetworkResponse>>()
	private val _recordedRequests = mutableListOf<NetworkRequest>()
	val recordedRequests: List<NetworkRequest> get() = _recordedRequests.toList()

	/** Register a stub: requests whose URL starts with [urlPrefix] get [respond]'s value. */
	fun respondWith(urlPrefix: String, respond: (NetworkRequest) -> NetworkResponse) {
		stubs += urlPrefix to respond
	}

	/** Convenience: 200 OK with given body for any URL starting with [urlPrefix]. */
	fun respondOk(urlPrefix: String, body: ByteArray = ByteArray(0)) {
		respondWith(urlPrefix) { req ->
			NetworkResponse.Success(
				request = req,
				statusCode = 200,
				body = body,
				headers = emptyMap(),
				latencyMs = 0L,
			)
		}
	}

	override suspend fun request(req: NetworkRequest): NetworkResponse {
		_recordedRequests += req
		if (!_isEnabled.value) {
			return NetworkResponse.Failure(req, NetworkError.GatewayDisabled)
		}
		val host = runCatching { java.net.URI.create(req.url).host }.getOrNull()
			?: return NetworkResponse.Failure(req, NetworkError.InvalidUrl(req.url, "unparseable"))
		if (!_policy.value.allows(host)) {
			return NetworkResponse.Failure(req, NetworkError.HostNotAllowed(host))
		}
		val stub = stubs.firstOrNull { req.url.startsWith(it.first) }
		return stub?.second?.invoke(req) ?: defaultResponse(req)
	}

	override fun setEnabled(enabled: Boolean) {
		_isEnabled.value = enabled
	}

	override fun setPolicy(policy: NetworkPolicy) {
		_policy.value = policy
	}

	/** Clear recorded requests and stubs. Useful between test cases. */
	fun reset() {
		_recordedRequests.clear()
		stubs.clear()
	}
}
