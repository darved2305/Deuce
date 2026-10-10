package com.elevate.app.data.auth

import com.elevate.app.data.session.ContractFakeServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Anonymous sign-in, persisted tokens, refresh 60 s before expiry, single-flight. */
class SupabaseAuthTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val fake = ContractFakeServer(nowMs = { now * 1000 })

    private fun auth(store: TokenStore = InMemoryTokenStore()) =
        SupabaseAuth(ContractFakeServer.SUPABASE, ContractFakeServer.KEY, fake, store, { now }, Dispatchers.IO)

    @Test fun `signs in anonymously once with the publishable key and body {}`() = runBlocking<Unit> {
        val a = auth()
        val t1 = a.accessToken()
        val t2 = a.accessToken()
        assertThat(t1).isEqualTo(t2)
        assertThat(fake.signUps.get()).isEqualTo(1)
        val req = fake.requests.single()
        assertThat(req.url).isEqualTo("${ContractFakeServer.SUPABASE}/auth/v1/signup")
        assertThat(req.headers["apikey"]).isEqualTo(ContractFakeServer.KEY)
        assertThat(req.headers.keys).doesNotContain("Authorization")
        assertThat(a.currentUser()!!.isAnonymous).isTrue()
    }

    @Test fun `refreshes when 60 s or less remain, not before`() = runBlocking<Unit> {
        val a = auth()
        val t1 = a.accessToken()
        now += 3600 - 61
        assertThat(a.accessToken()).isEqualTo(t1)
        now += 1
        val t2 = a.accessToken()
        assertThat(t2).isNotEqualTo(t1)
        assertThat(fake.refreshes.get()).isEqualTo(1)
        assertThat(fake.requests.last().url).endsWith("/auth/v1/token?grant_type=refresh_token")
    }

    @Test fun `concurrent callers share one sign-in and one refresh (single-flight)`() = runBlocking<Unit> {
        val a = auth()
        val tokens = (1..8).map { async(Dispatchers.IO) { a.accessToken() } }.awaitAll()
        assertThat(tokens.toSet()).hasSize(1)
        assertThat(fake.signUps.get()).isEqualTo(1)
        val rejected = tokens.first()
        val refreshed = (1..8).map { async(Dispatchers.IO) { a.refreshAfterUnauthorized(rejected) } }.awaitAll()
        assertThat(refreshed.toSet()).hasSize(1)
        assertThat(fake.refreshes.get()).isEqualTo(1)
    }

    @Test fun `tokens persist across instances (process restart) in an app-private file`() = runBlocking<Unit> {
        val store = FileTokenStore(tmp.root)
        val t = auth(store).accessToken()
        val again = auth(FileTokenStore(tmp.root)).accessToken()
        assertThat(again).isEqualTo(t)
        assertThat(fake.signUps.get()).isEqualTo(1)
        auth(store).signOut()
        assertThat(FileTokenStore(tmp.root).load()).isNull()
    }

    @Test fun `a rejected refresh token throws AuthException without signing in a new user`() = runBlocking<Unit> {
        val store = InMemoryTokenStore(AuthTokens("at_old", "bogus", expiresAt = now - 10, userId = "u", isAnonymous = true))
        val e = runCatching { auth(store).accessToken() }.exceptionOrNull()
        assertThat(e).isInstanceOf(AuthException::class.java)
        assertThat((e as AuthException).status).isEqualTo(400)
        assertThat(e.message).contains("refresh_token_not_found")
        assertThat(fake.signUps.get()).isEqualTo(0)
    }

    @Test fun `tokens never appear in toString`() {
        val t = AuthTokens("secret-access", "secret-refresh", 1, "u", true)
        assertThat(t.toString()).doesNotContain("secret")
    }
}
