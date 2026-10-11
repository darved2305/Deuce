package com.elevate.app.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elevate.app.domain.community.Challenges
import com.elevate.app.domain.community.FriendsResponse
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ExplainBox
import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.theme.Elevate

private sealed interface FriendsLoad {
    data object Loading : FriendsLoad
    data class Loaded(val friends: FriendsResponse) : FriendsLoad
    data object Failed : FriendsLoad
}

/** The "Friends" card on the profile: people you have exchanged challenges with, from the server. */
@Composable
fun FriendsSection() {
    val community: CommunityViewModel = viewModel()
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<FriendsLoad>(FriendsLoad.Loading, attempt) {
        value = FriendsLoad.Loading
        value = community.friends().fold({ FriendsLoad.Loaded(it) }, { FriendsLoad.Failed })
    }
    val c = Elevate.colors
    ElevateCard {
        Text("Friends", style = MaterialTheme.typography.titleMedium, color = c.ink)
        when (val l = load) {
            FriendsLoad.Loading -> Text("Loading your friends", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            FriendsLoad.Failed -> {
                Text("Friends could not be loaded. Check your network and try again.", style = MaterialTheme.typography.bodyMedium, color = c.error)
                SecondaryButton("Try again", { attempt++ }, Modifier.fillMaxWidth())
            }
            is FriendsLoad.Loaded -> if (l.friends.items.isEmpty()) {
                Text("Challenge a friend after a Reflex run", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    l.friends.items.forEach { f ->
                        ExplainBox(Explanations.HeadToHead, Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.displayName, style = MaterialTheme.typography.titleMedium, color = c.ink)
                                    Text(Challenges.friendDenominator(f), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                                }
                                Column {
                                    Text(Challenges.friendRecord(f), style = MaterialTheme.typography.titleMedium, color = c.ink)
                                    Text("W-L-T", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
