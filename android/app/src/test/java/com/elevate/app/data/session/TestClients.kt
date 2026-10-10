package com.elevate.app.data.session

import com.elevate.app.data.auth.InMemoryTokenStore
import com.elevate.app.data.auth.SupabaseAuth
import kotlinx.coroutines.Dispatchers

/** A [SessionClient] + [SupabaseAuth] wired to a [ContractFakeServer] (test fake). */
fun clientFor(
    fake: ContractFakeServer,
    sleeps: MutableList<Long> = mutableListOf(),
    nowS: () -> Long = { System.currentTimeMillis() / 1000 },
    nowMs: () -> Long = System::currentTimeMillis,
): Pair<SessionClient, SupabaseAuth> {
    val auth = SupabaseAuth(ContractFakeServer.SUPABASE, ContractFakeServer.KEY, fake, InMemoryTokenStore(), nowS, Dispatchers.Unconfined)
    val http = ElevateHttp(ContractFakeServer.API, ContractFakeServer.KEY, auth, fake, Dispatchers.Unconfined, sleep = { sleeps += it })
    return SessionClient(http, nowMs) to auth
}
