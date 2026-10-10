package com.elevate.app.data.auth

import com.elevate.app.data.upload.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File

/** Where [SupabaseAuth] keeps the session between launches. */
interface TokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

/**
 * Tokens in one app-private JSON file (`filesDir/auth/session.json`), written atomically.
 *
 * App-private storage is readable only by this app's UID; it is not encrypted at rest beyond the
 * device's file-based encryption. `androidx.security:security-crypto` (EncryptedFile) would add a
 * dependency; not used (android/UPLOAD_CLIENT.md). Backups exclude `file`
 * (res/xml/data_extraction_rules.xml, allowBackup=false), so the file never leaves the phone.
 */
class FileTokenStore(dir: File) : TokenStore {
    private val file = AtomicFile(File(dir, "session.json"))
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): AuthTokens? =
        file.readText()?.let { runCatching { json.decodeFromString(AuthTokens.serializer(), it) }.getOrNull() }

    override fun save(tokens: AuthTokens) = file.writeText(json.encodeToString(AuthTokens.serializer(), tokens))

    override fun clear() = file.delete()
}

class InMemoryTokenStore(private var tokens: AuthTokens? = null) : TokenStore {
    override fun load() = tokens
    override fun save(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}
