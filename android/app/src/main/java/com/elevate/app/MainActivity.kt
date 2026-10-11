package com.elevate.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Intent
import androidx.activity.viewModels
import com.elevate.app.domain.link.DeepLink
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.community.CommunityViewModel
import com.elevate.app.ui.theme.ElevateTheme

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val vm: ReplayViewModel by viewModels()
    private val community: CommunityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light surfaces: dark system-bar icons on transparent bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ElevateTheme { ElevateApp(vm) }
        }
        if (savedInstanceState == null) openLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
    }

    /** An `elevate://m/...` link opens that match (and moment) from this phone's history. */
    private fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        // An `elevate://c/...` link is a friend's Reflex challenge.
        DeepLink.parseChallenge(intent.dataString)?.let { id ->
            intent.data = null
            community.openLink(id)
            return
        }
        val target = DeepLink.parse(intent.dataString) ?: return
        intent.data = null // handled once, not again on a later configuration change
        vm.openDeepLink(target)
    }
}
