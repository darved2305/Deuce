package com.elevate.app.data.upload

import android.content.Context
import androidx.core.net.toUri
import com.elevate.app.BuildConfig
import com.elevate.app.data.auth.FileTokenStore
import com.elevate.app.data.auth.SupabaseAuth
import com.elevate.app.data.session.ElevateHttp
import com.elevate.app.data.session.SessionClient
import com.elevate.app.data.session.UrlConnectionTransport
import java.io.File

/**
 * The process-wide upload graph: one [SupabaseAuth], one [SessionClient], one [UploadCoordinator],
 * shared by the UI and the background job so both see the same [UploadCoordinator.progress].
 *
 * Config comes from BuildConfig (android/local.properties `elevate.supabaseUrl`,
 * `elevate.supabasePublishableKey`, `elevate.apiBaseUrl`): the publishable key only, never a
 * service-role or secret key.
 */
class UploadRuntime private constructor(app: Context) {
    private val transport = UrlConnectionTransport()

    val auth = SupabaseAuth(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        transport = transport,
        store = FileTokenStore(File(app.filesDir, "auth")),
    )

    val sessions = SessionClient(ElevateHttp(BuildConfig.API_BASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, auth, transport))

    val coordinator = UploadCoordinator(
        api = sessions,
        // An 8 MiB part on a slow uplink: allow 2 min for R2's answer after the body is written.
        partTransport = UrlConnectionTransport(readTimeoutMs = 120_000),
        store = UploadStateStore(File(app.filesDir, "uploads")),
        sourceFor = { s ->
            val uri = s.stagedPath?.let { File(it).toUri() } ?: s.sourceUri.toUri()
            UriPartSource(app.contentResolver, uri)
        },
    )

    companion object {
        @Volatile private var instance: UploadRuntime? = null

        /** True when local.properties supplied all three values at build time. */
        val isConfigured: Boolean
            get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() &&
                BuildConfig.API_BASE_URL.isNotBlank()

        /** Throws [IllegalArgumentException] when not [isConfigured]. */
        fun get(context: Context): UploadRuntime =
            instance ?: synchronized(this) { instance ?: UploadRuntime(context.applicationContext).also { instance = it } }
    }
}
