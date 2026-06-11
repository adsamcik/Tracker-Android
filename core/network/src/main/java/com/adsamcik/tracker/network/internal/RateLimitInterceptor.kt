package com.adsamcik.tracker.network.internal

import com.adsamcik.tracker.network.NetworkError
import com.adsamcik.tracker.network.NetworkPolicy
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Per-host token bucket rate limiter.
 *
 * Each host gets a bucket sized to [NetworkPolicy.perHostRateLimit]; it refills
 * at `perHostRateLimit / perHostRateWindowMs` tokens per millisecond. Each
 * intercept tries to consume one token; if none available it throws with a
 * [NetworkError.RateLimited] carrying the retry-after delay (milliseconds
 * until one token would have refilled).
 *
 * Zero or negative `perHostRateLimit` disables limiting (the bucket grants
 * unlimited tokens).
 *
 * Thread-safe via `ConcurrentHashMap` + synchronized per-bucket compute.
 */
internal class RateLimitInterceptor(
	private val policySource: () -> NetworkPolicy,
	private val nowMs: () -> Long = System::currentTimeMillis,
) : Interceptor {

	private data class Bucket(var tokens: Double, var lastRefillMs: Long)

	private val buckets = ConcurrentHashMap<String, Bucket>()

	override fun intercept(chain: Interceptor.Chain): Response {
		val host = chain.request().url.host
		val policy = policySource()
		val limit = policy.perHostRateLimit
		if (limit <= 0) return chain.proceed(chain.request())

		val window = policy.perHostRateWindowMs.coerceAtLeast(1L)
		val refillPerMs = limit.toDouble() / window.toDouble()
		val now = nowMs()

		val retryAfter = synchronized(buckets) {
			val bucket = buckets.getOrPut(host) { Bucket(limit.toDouble(), now) }
			val elapsedMs = max(0L, now - bucket.lastRefillMs)
			bucket.tokens = min(limit.toDouble(), bucket.tokens + elapsedMs * refillPerMs)
			bucket.lastRefillMs = now
			if (bucket.tokens >= 1.0) {
				bucket.tokens -= 1.0
				0L
			} else {
				val deficit = 1.0 - bucket.tokens
				(deficit / refillPerMs).toLong().coerceAtLeast(1L)
			}
		}

		if (retryAfter > 0L) {
			throw GatewayInterceptorException(NetworkError.RateLimited(host, retryAfter))
		}

		return chain.proceed(chain.request())
	}
}
