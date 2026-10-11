package com.elevate.app.ui.community

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elevate.app.BuildConfig
import com.elevate.app.data.session.ApiErrorKind
import com.elevate.app.data.session.ApiException
import com.elevate.app.data.session.ElevateHttp
import com.elevate.app.data.session.UrlConnectionTransport
import com.elevate.app.data.upload.UploadRuntime
import com.elevate.app.domain.community.Challenge
import com.elevate.app.domain.community.ChallengeApi
import com.elevate.app.domain.community.ChallengeClient
import com.elevate.app.domain.community.Challenges
import com.elevate.app.domain.community.FriendsResponse
import com.elevate.app.ui.profile.ProfileName
import com.elevate.app.ui.readlab.ReflexSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/** How the player plays the challenge: answering a friend's, or sending a rematch of the same shots. */
enum class PlayMode { Answer, Rematch }

/** What the community overlay shows. Hidden means the rest of the app is on screen. */
sealed interface CommunityScreen {
    data object Hidden : CommunityScreen
    data class Loading(val id: String) : CommunityScreen
    data class Failed(val id: String, val message: String) : CommunityScreen
    data class Invite(val challenge: Challenge) : CommunityScreen
    data class Play(val challenge: Challenge, val mode: PlayMode) : CommunityScreen
    data class Result(val challenge: Challenge) : CommunityScreen
}

/** One network step (post a score, create a challenge): never twice for the same finished run. */
sealed interface Step<out T> {
    data object Idle : Step<Nothing>
    data object Working : Step<Nothing>
    data class Done<T>(val value: T) : Step<T>
    data class Failed(val message: String) : Step<Nothing>
}

/** Plain words for an API failure. */
fun failureMessage(e: Throwable): String = when {
    e is ApiException && e.kind == ApiErrorKind.NotFound -> "This challenge was not found. Ask your friend to send it again."
    e is ApiException && e.kind == ApiErrorKind.Conflict -> "This challenge already has a different result."
    e is ApiException && e.kind == ApiErrorKind.Invalid -> "The server did not accept this run."
    e is ApiException && e.kind == ApiErrorKind.Unauthorized -> "Sign-in did not work. Try again in a moment."
    e is ApiException && e.kind == ApiErrorKind.Server -> "The Elevate service is busy. Try again in a moment."
    e is IOException -> "No connection. Check your network and try again."
    else -> "Something went wrong. Try again."
}

/** Holds the challenge flow across rotation; one per activity. */
class CommunityViewModel(app: Application) : AndroidViewModel(app) {
    private val _screen = MutableStateFlow<CommunityScreen>(CommunityScreen.Hidden)
    val screen: StateFlow<CommunityScreen> = _screen.asStateFlow()

    /** Posting the friend's score for the run being played. */
    private val _entry = MutableStateFlow<Step<Challenge>>(Step.Idle)
    val entry: StateFlow<Step<Challenge>> = _entry.asStateFlow()

    /** Creating a challenge from a finished run (Reflex end card, or a rematch). */
    private val _publish = MutableStateFlow<Step<Challenge>>(Step.Idle)
    val publish: StateFlow<Step<Challenge>> = _publish.asStateFlow()

    private var publishSig: String? = null
    private var publishKey: String? = null
    private var entrySig: String? = null

    /** Null on a build without cloud config. */
    private val api: ChallengeApi? by lazy {
        if (!UploadRuntime.isConfigured) null
        else ChallengeClient(
            ElevateHttp(BuildConfig.API_BASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, UploadRuntime.get(getApplication()).auth, UrlConnectionTransport()),
        )
    }

    private fun name(): String? = Challenges.nameToSend(ProfileName.get(getApplication()), ProfileName.DEFAULT)

    /** A link `elevate://c/{id}` was opened: accept it (the server shares the match with this player). */
    fun openLink(id: String) {
        _screen.value = CommunityScreen.Loading(id)
        _entry.value = Step.Idle
        entrySig = null
        viewModelScope.launch {
            val a = api ?: return@launch run { _screen.value = CommunityScreen.Failed(id, "Challenges need the Elevate service, which this build is not set up for.") }
            try {
                val c = a.accept(id, name())
                _screen.value = if (c.myEntry?.score != null) CommunityScreen.Result(c) else CommunityScreen.Invite(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // The creator opening their own link reads it instead.
                if (e.kind == ApiErrorKind.Conflict) load(id) else _screen.value = CommunityScreen.Failed(id, failureMessage(e))
            } catch (e: IOException) {
                _screen.value = CommunityScreen.Failed(id, failureMessage(e))
            }
        }
    }

    /** Opens a challenge the caller already belongs to as a result card. */
    private suspend fun load(id: String) {
        try {
            _screen.value = CommunityScreen.Result(api!!.get(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            _screen.value = CommunityScreen.Failed(id, failureMessage(e))
        } catch (e: IOException) {
            _screen.value = CommunityScreen.Failed(id, failureMessage(e))
        }
    }

    fun showResult(id: String) {
        _screen.value = CommunityScreen.Loading(id)
        viewModelScope.launch { if (api == null) _screen.value = CommunityScreen.Hidden else load(id) }
    }

    fun retry() {
        (_screen.value as? CommunityScreen.Failed)?.let { openLink(it.id) }
    }

    /** Play the same shots: [onReset] clears any earlier Reflex run so this one starts fresh. */
    fun startPlay(challenge: Challenge, mode: PlayMode, onReset: () -> Unit) {
        onReset()
        _entry.value = Step.Idle
        entrySig = null
        _publish.value = Step.Idle
        publishSig = null
        _screen.value = CommunityScreen.Play(challenge, mode)
    }

    fun close(onReset: () -> Unit = {}) {
        onReset()
        _screen.value = CommunityScreen.Hidden
    }

    /** The friend's finished run goes to the challenge, once. A failure can be retried. */
    fun submitEntry(challenge: Challenge, session: ReflexSession) {
        val sig = signature(challenge.id, session)
        if (sig == entrySig && _entry.value !is Step.Failed) return
        entrySig = sig
        _entry.value = Step.Working
        viewModelScope.launch {
            val a = api ?: return@launch run { _entry.value = Step.Failed("Challenges need the Elevate service.") }
            try {
                val updated = a.submitEntry(challenge.id, Challenges.entryRequest(session, name()))
                _entry.value = Step.Done(updated)
                _screen.value = CommunityScreen.Result(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _entry.value = Step.Failed(failureMessage(e))
            } catch (e: IOException) {
                _entry.value = Step.Failed(failureMessage(e))
            }
        }
    }

    /**
     * Creates a challenge from a finished run. The Idempotency-Key belongs to the run, so a retry after
     * a lost answer returns the same challenge instead of making a second one.
     */
    fun publish(matchId: String, session: ReflexSession, rematchOf: String? = null) {
        val sig = signature("$matchId/$rematchOf", session)
        if (sig == publishSig && _publish.value !is Step.Failed) return
        if (sig != publishSig) { publishSig = sig; publishKey = UUID.randomUUID().toString() }
        val key = publishKey ?: return
        _publish.value = Step.Working
        viewModelScope.launch {
            val a = api ?: return@launch run { _publish.value = Step.Failed("Challenges need the Elevate service.") }
            try {
                _publish.value = Step.Done(a.create(Challenges.createRequest(matchId, session, name(), rematchOf), key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _publish.value = Step.Failed(failureMessage(e))
            } catch (e: IOException) {
                _publish.value = Step.Failed(failureMessage(e))
            }
        }
    }

    suspend fun friends(): Result<FriendsResponse> {
        val a = api ?: return Result.failure(IOException("not configured"))
        return try {
            Result.success(a.friends())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun signature(scope: String, s: ReflexSession) =
        scope + "|" + s.results.joinToString(",") { "${it.hitEventId}:${it.pick}:${it.reactionMs}:${it.points}" }
}
