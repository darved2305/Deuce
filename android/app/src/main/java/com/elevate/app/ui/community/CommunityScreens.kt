package com.elevate.app.ui.community

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.domain.community.Challenge
import com.elevate.app.domain.community.ChallengeEntry
import com.elevate.app.domain.community.ChallengeLink
import com.elevate.app.domain.community.Challenges
import com.elevate.app.domain.community.ShotComparison
import com.elevate.app.domain.media.PreflightState
import com.elevate.app.domain.model.AnnotationState
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.ExplainBox
import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.ScreenSpacing
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.components.StateMessage
import com.elevate.app.ui.readlab.ReadLabDrills
import com.elevate.app.ui.readlab.ReflexRoute
import com.elevate.app.ui.readlab.ReflexRounds
import com.elevate.app.ui.readlab.ReflexSession
import com.elevate.app.ui.theme.Elevate
import kotlinx.coroutines.CancellationException

/** Opens the share sheet with the challenge link and a plain sentence. */
fun shareChallenge(context: Context, challenge: Challenge) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, ChallengeLink.shareText(challenge.id, challenge.creator.score))
    }
    context.startActivity(Intent.createChooser(send, "Challenge a friend"))
}

/** The community overlay: invite, video check, play, result. Shown above everything while it is not Hidden. */
@UnstableApi
@Composable
fun CommunityRoute(vm: ReplayViewModel, community: CommunityViewModel, pickVideo: () -> Unit) {
    val screen by community.screen.collectAsStateWithLifecycle()
    val reset = { vm.reflex.restart() }
    when (val s = screen) {
        CommunityScreen.Hidden -> Unit
        is CommunityScreen.Loading -> Page("Challenge", { community.close() }) {
            Text("Opening the challenge", style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted)
        }
        is CommunityScreen.Failed -> Page("Challenge", { community.close() }) {
            StateMessage(ElevateIcons.Warning, s.message, isError = true, actionLabel = "Try again", onAction = community::retry)
            SecondaryButton("Close", { community.close() }, Modifier.fillMaxWidth())
        }
        is CommunityScreen.Invite -> Invite(s.challenge, onPlay = { community.startPlay(s.challenge, PlayMode.Answer, reset) }, onClose = { community.close() })
        is CommunityScreen.Play -> PlayRoute(vm, community, s.challenge, s.mode, pickVideo)
        is CommunityScreen.Result -> ResultCard(
            s.challenge,
            onRematch = { community.startPlay(s.challenge, PlayMode.Rematch, reset) },
            onRefresh = { community.showResult(s.challenge.id) },
            onClose = { community.close() },
        )
    }
}

@Composable
private fun Page(title: String, onBack: () -> Unit, subtitle: String? = null, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    PeachPage {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ElevateHeader(title, onBack = onBack, subtitle = subtitle)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
                verticalArrangement = ScreenSpacing,
            ) { content() }
        }
    }
}

@Composable
private fun Invite(c: Challenge, onPlay: () -> Unit, onClose: () -> Unit) {
    val ink = Elevate.colors
    Page("Challenge", onClose) {
        ExplainBox(Explanations.Challenge, Modifier.fillMaxWidth()) {
            ElevateCard(tint = ink.lavender) {
                Text(Challenges.inviteTitle(c), style = MaterialTheme.typography.titleLarge, color = ink.ink)
                Spacer(Modifier.height(4.dp))
                Text(Challenges.inviteLine(c), style = MaterialTheme.typography.displaySmall, color = ink.ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Read the same real shots before they land. Your score goes to this challenge when you finish.",
                    style = MaterialTheme.typography.bodyMedium, color = ink.ink,
                )
            }
        }
        PrimaryButton("Play the challenge", onPlay, Modifier.fillMaxWidth())
        SecondaryButton("Not now", onClose, Modifier.fillMaxWidth())
    }
}

/** What the video check found. */
private sealed interface MatchLoad {
    data object Loading : MatchLoad
    data class Loaded(val match: MatchRecord) : MatchLoad
    data object Failed : MatchLoad
}

/**
 * Plays the challenge's shots with Reflex on the open video. The video must be the same file as the
 * creator's (same sha256): the shots are frozen on exact frames. Nothing is uploaded or downloaded.
 */
@UnstableApi
@Composable
private fun PlayRoute(vm: ReplayViewModel, community: CommunityViewModel, c: Challenge, mode: PlayMode, pickVideo: () -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val replay = screen as? Screen.Replay
    val sha = replay?.sha256
    val facts = (replay?.preflight as? PreflightState.Done)?.facts
    val onBack = { community.close { vm.reflex.restart() } }
    LaunchedEffect(sha) { vm.controller.pause() }

    val load by produceState<MatchLoad>(MatchLoad.Loading, c.matchId) {
        value = try {
            MatchLoad.Loaded(vm.analysisClient.getMatch(c.matchId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            MatchLoad.Failed
        }
    }

    when {
        screen is Screen.Import && (screen as Screen.Import).opening ->
            Page("Same video", onBack) { Text("Opening the video", style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted) }
        replay == null -> NeedVideo(null, pickVideo, onBack)
        replay.annotations is AnnotationState.UnsupportedHdr ->
            NeedVideo("This is an HDR video, which Elevate cannot read yet. Pick the original file.", pickVideo, onBack)
        sha == null -> Page("Same video", onBack) { Text("Checking the video", style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted) }
        !sha.equals(c.videoSha256, ignoreCase = true) ->
            NeedVideo("This is a different video file, so the frozen frames would not match. Pick the exact file your friend used.", pickVideo, onBack)
        load is MatchLoad.Loading || facts == null ->
            Page("Challenge", onBack) { Text("Getting the shots ready", style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted) }
        load is MatchLoad.Failed -> Page("Challenge", onBack) {
            StateMessage(ElevateIcons.Warning, "The match could not be loaded. Check your network and try again.", isError = true)
            SecondaryButton("Back", onBack, Modifier.fillMaxWidth())
        }
        else -> {
            val match = (load as MatchLoad.Loaded).match
            val selection = remember(c, match, facts) {
                Challenges.select(c, ReadLabDrills.fromMatch(match, facts.frameTimesUs, facts.displayWidth, facts.displayHeight))
            }
            if (selection.rounds.size < ReflexRounds.MIN_ROUNDS) {
                Page("Challenge", onBack) {
                    StateMessage(
                        ElevateIcons.Empty,
                        "Only ${selection.rounds.size} of ${selection.total} shots can be played on this phone, which is too few for a fair challenge.",
                        isError = true,
                    )
                    SecondaryButton("Back", onBack, Modifier.fillMaxWidth())
                }
            } else {
                ReflexRoute(
                    vm, selection.rounds.map { it.drill }, c.matchId, null, onBack,
                    endExtra = { s ->
                        if (selection.missing > 0) {
                            Text(
                                "You played ${selection.rounds.size} of ${selection.total} shots; the rest could not be rebuilt on this phone.",
                                style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted,
                            )
                        }
                        if (mode == PlayMode.Answer) SendScoreCard(community, c, s) else RematchCard(community, c, s)
                    },
                )
            }
        }
    }
}

@Composable
private fun NeedVideo(problem: String?, pickVideo: () -> Unit, onBack: () -> Unit) {
    val c = Elevate.colors
    Page("Same video", onBack, subtitle = "The shots are frozen on exact frames") {
        ExplainBox(Explanations.SameVideo, Modifier.fillMaxWidth()) {
            ElevateCard(tint = c.lavender) {
                Text("Ask your friend to send the video", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    problem ?: "To play the same shots you need the same video file. Elevate never uploads extra video, so your friend sends the file and you pick it here. It is checked with a fingerprint of the file.",
                    style = MaterialTheme.typography.bodyMedium, color = c.ink,
                )
            }
        }
        PrimaryButton("Pick the video", pickVideo, Modifier.fillMaxWidth())
        SecondaryButton("Back", onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SendScoreCard(community: CommunityViewModel, c: Challenge, s: ReflexSession) {
    val step by community.entry.collectAsStateWithLifecycle()
    LaunchedEffect(s.results.size) { community.submitEntry(c, s) }
    StatusCard(
        step = step,
        working = "Sending your score to ${c.creator.displayName}",
        retryLabel = "Send score again",
        onRetry = { community.submitEntry(c, s) },
    )
}

@Composable
private fun RematchCard(community: CommunityViewModel, c: Challenge, s: ReflexSession) {
    val step by community.publish.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(s.results.size) { community.publish(c.matchId, s, rematchOf = c.id) }
    StatusCard(step = step, working = "Creating your rematch", retryLabel = "Try again", onRetry = { community.publish(c.matchId, s, rematchOf = c.id) })
    (step as? Step.Done)?.let { done ->
        PrimaryButton("Share the rematch link", { shareChallenge(context, done.value) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun <T> StatusCard(step: Step<T>, working: String, retryLabel: String, onRetry: () -> Unit) {
    when (step) {
        Step.Idle -> Unit
        Step.Working -> ElevateCard { Text(working, style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.ink) }
        is Step.Done -> ElevateCard(tint = Elevate.colors.mint) { Text("Done", style = MaterialTheme.typography.titleMedium, color = Elevate.colors.ink) }
        is Step.Failed -> StateMessage(ElevateIcons.Warning, step.message, isError = true, actionLabel = retryLabel, onAction = onRetry)
    }
}

/** "Challenge a friend" under a finished Reflex run: creates the challenge on the server, then opens the share sheet. */
@Composable
fun ChallengeFriendCard(matchId: String?, session: ReflexSession) {
    if (matchId.isNullOrBlank()) return
    val community: CommunityViewModel = viewModel()
    val step by community.publish.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var wantShare by remember { mutableStateOf(false) }
    val done = (step as? Step.Done)?.value
    LaunchedEffect(done, wantShare) {
        if (wantShare && done != null) { shareChallenge(context, done); wantShare = false }
    }
    val c = Elevate.colors
    ElevateCard(tint = c.lavender) {
        Text("Challenge a friend", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(
            "Send ${session.rounds.size} real shots and your score of ${Challenges.points(session.score)}. Your friend plays them on the same video.",
            style = MaterialTheme.typography.bodyMedium, color = c.ink,
        )
        Spacer(Modifier.height(8.dp))
        ExplainBox(Explanations.Challenge, Modifier.fillMaxWidth()) {
            PrimaryButton(
                if (done != null) "Share the link again" else "Challenge a friend",
                { wantShare = true; community.publish(matchId, session) },
                Modifier.fillMaxWidth(),
                busy = step is Step.Working && wantShare,
            )
        }
        (step as? Step.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = c.error) }
    }
}

@Composable
private fun ResultCard(c: Challenge, onRematch: () -> Unit, onRefresh: () -> Unit, onClose: () -> Unit) {
    val ink = Elevate.colors
    val context = LocalContext.current
    val entries = if (c.isCreator) c.entries else listOfNotNull(c.myEntry)
    Page("Challenge result", onClose) {
        if (entries.isEmpty()) {
            ElevateCard {
                Text("No one has played yet", style = MaterialTheme.typography.titleMedium, color = ink.ink)
                Text("Your score: ${Challenges.points(c.creator.score)} on ${c.rounds.size} shots", style = MaterialTheme.typography.bodyMedium, color = ink.inkMuted)
            }
        }
        entries.forEach { EntryResult(c, it) }
        PrimaryButton("Rematch", onRematch, Modifier.fillMaxWidth())
        if (c.isCreator) SecondaryButton("Share the link", { shareChallenge(context, c) }, Modifier.fillMaxWidth())
        SecondaryButton("Refresh", onRefresh, Modifier.fillMaxWidth())
        SecondaryButton("Done", onClose, Modifier.fillMaxWidth())
    }
}

@Composable
private fun EntryResult(c: Challenge, e: ChallengeEntry) {
    val ink = Elevate.colors
    val creatorName = if (c.isCreator) "You" else c.creator.displayName
    val otherName = if (c.isCreator) e.displayName else "You"
    val winner = Challenges.winnerText(c, e)
    ExplainBox(Explanations.HeadToHead, Modifier.fillMaxWidth()) {
        ElevateCard(tint = if (winner == "You won") ink.mint else Color.White) {
            Text(winner ?: "${e.displayName} has not played yet", style = MaterialTheme.typography.titleLarge, color = ink.ink)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$creatorName ${Challenges.points(c.creator.score)}", style = MaterialTheme.typography.titleMedium, color = ink.ink)
                Text("$otherName ${e.score?.let(Challenges::points) ?: "none yet"}", style = MaterialTheme.typography.titleMedium, color = ink.ink)
            }
        }
    }
    if (e.roundResults != null) {
        ElevateCard {
            Text("Shot by shot", style = MaterialTheme.typography.titleMedium, color = ink.ink)
            Challenges.compare(c, e.roundResults).forEach { ShotRow(it, creatorName, otherName) }
        }
    }
}

@Composable
private fun ShotRow(s: ShotComparison, creatorName: String, otherName: String) {
    val ink = Elevate.colors
    fun side(name: String, r: com.elevate.app.domain.community.ChallengeRoundResult?) =
        if (r == null) "$name: not played"
        else "$name: ${Challenges.verdictLabel(r.verdict)}, ${r.points} pts" + (r.reactionMs?.let { ", $it ms" } ?: "")
    Column(Modifier.padding(top = 8.dp)) {
        Text("Shot ${s.index}", style = MaterialTheme.typography.labelMedium, color = ink.inkMuted)
        Text(side(creatorName, s.creator), style = MaterialTheme.typography.bodyMedium, color = ink.ink)
        Text(side(otherName, s.player), style = MaterialTheme.typography.bodyMedium, color = ink.ink)
    }
}
