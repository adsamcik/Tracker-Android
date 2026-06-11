package com.adsamcik.tracker.network.internal

import com.adsamcik.tracker.network.NetworkError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HttpsOnlyInterceptor")
class HttpsOnlyInterceptorTest {

	private fun fakeOkResponse(req: Request): Response = Response.Builder()
		.request(req)
		.protocol(Protocol.HTTP_2)
		.code(200)
		.message("OK")
		.build()

	@Test
	fun `https request is allowed through`() {
		val req = Request.Builder().url("https://example.com/x".toHttpUrl()).build()
		val chain = mockk<Interceptor.Chain>(relaxed = true)
		every { chain.request() } returns req
		every { chain.proceed(req) } returns fakeOkResponse(req)

		val interceptor = HttpsOnlyInterceptor()
		val response = interceptor.intercept(chain)

		response.code shouldBe 200
		verify(exactly = 1) { chain.proceed(req) }
	}

	@Test
	fun `http request is rejected with InsecureScheme(http)`() {
		val req = Request.Builder().url("http://example.com/x".toHttpUrl()).build()
		val chain = mockk<Interceptor.Chain>(relaxed = true)
		every { chain.request() } returns req

		val interceptor = HttpsOnlyInterceptor()
		val ex = runCatching { interceptor.intercept(chain) }.exceptionOrNull()

		ex.shouldBeInstanceOf<GatewayInterceptorException>()
		val err = (ex as GatewayInterceptorException).networkError
		err.shouldBeInstanceOf<NetworkError.InsecureScheme>()
		(err as NetworkError.InsecureScheme).scheme shouldBe "http"
		// Chain MUST NOT proceed when rejecting.
		verify(exactly = 0) { chain.proceed(any()) }
	}

	@Test
	fun `case-insensitive HTTPS scheme is allowed`() {
		// OkHttp's HttpUrl normalises scheme to lowercase, so this proves the
		// guard doesn't false-reject if some upstream constructs a URL via the
		// raw `Request.Builder().url(String)` overload. The check is
		// `equals("https", ignoreCase = true)` for defensive parity with the
		// suspend wrapper's scheme guard.
		val req = Request.Builder().url("HTTPS://example.com/x".toHttpUrl()).build()
		val chain = mockk<Interceptor.Chain>(relaxed = true)
		every { chain.request() } returns req
		every { chain.proceed(req) } returns fakeOkResponse(req)

		val interceptor = HttpsOnlyInterceptor()
		val response = interceptor.intercept(chain)

		response.code shouldBe 200
	}
}
