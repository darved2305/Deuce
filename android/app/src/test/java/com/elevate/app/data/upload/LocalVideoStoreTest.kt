package com.elevate.app.data.upload

import com.elevate.app.data.session.TestRequests
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** Kept history copies: the pure eviction/capacity plan, and the file operations on a temp folder. */
class LocalVideoStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mb = 1_000_000L
    private fun item(name: String, mbs: Long, used: Long) = LocalVideoStore.Item(name, mbs * mb, used)
    private fun plan(existing: List<LocalVideoStore.Item>, incoming: Long, added: Long, free: Long) =
        LocalVideoStore.plan(existing, incoming * mb, added * mb, free * mb, minFreeBytes = 500 * mb, capBytes = 1500 * mb)

    @Test fun `fits - nothing evicted`() {
        assertThat(plan(listOf(item("a", 300, 1)), incoming = 300, added = 300, free = 5_000)).isEqualTo(LocalVideoStore.Plan.Keep(emptyList()))
    }

    @Test fun `over the 1_5 GB cap - least recently used evicted first, only as many as needed`() {
        val existing = listOf(item("new", 300, 30), item("old", 300, 10), item("mid", 300, 20), item("mid2", 300, 25), item("x", 200, 40))
        // 1400 + 300 = 1700 > 1500: dropping "old" (300) is enough.
        assertThat(plan(existing, incoming = 300, added = 0, free = 5_000)).isEqualTo(LocalVideoStore.Plan.Keep(listOf("old")))
    }

    @Test fun `less than 500 MB would stay free - evicts until it does`() {
        val existing = listOf(item("b", 100, 2), item("a", 100, 1))
        // free 700 - 300 copied = 400 < 500: one 100 MB eviction brings it to 500.
        assertThat(plan(existing, incoming = 300, added = 300, free = 700)).isEqualTo(LocalVideoStore.Plan.Keep(listOf("a")))
    }

    @Test fun `refuses rather than evicting in vain`() {
        val existing = listOf(item("a", 100, 1))
        assertThat(plan(existing, incoming = 300, added = 300, free = 600)).isInstanceOf(LocalVideoStore.Plan.Refuse::class.java)
        assertThat(plan(emptyList(), incoming = 1600, added = 0, free = 50_000)).isInstanceOf(LocalVideoStore.Plan.Refuse::class.java)
    }

    private val bytes = ByteArray(4096) { (it * 13 % 256).toByte() }
    private val sha = TestRequests.sha256(bytes)

    @Test fun `adopt moves the staging copy in as sha256 dot ext and find returns it`() {
        val store = LocalVideoStore(tmp.newFolder("videos"), freeBytes = { 10_000 * mb })
        val staged = tmp.newFile("source-1.mp4").apply { writeBytes(bytes) }
        val kept = store.adopt(staged, sha, "mp4")!!
        assertThat(kept.name).isEqualTo("$sha.mp4")
        assertThat(staged.exists()).isFalse()
        assertThat(store.find(sha)).isEqualTo(kept)
        assertThat(store.find("not-a-sha")).isNull()
    }

    @Test fun `adopt refused when the phone is nearly full - the staging copy is left to the caller`() {
        val store = LocalVideoStore(tmp.newFolder("videos"), freeBytes = { 100 * mb })
        val staged = tmp.newFile("source-1.mp4").apply { writeBytes(bytes) }
        assertThat(store.adopt(staged, sha, "mp4")).isNull()
        assertThat(staged.exists()).isTrue()
    }

    @Test fun `adopt evicts the least recently used copy over the cap`() {
        val dir = tmp.newFolder("videos")
        val old = File(dir, "a".repeat(64) + ".mp4").apply { writeBytes(ByteArray(3000)); setLastModified(1_000) }
        val store = LocalVideoStore(dir, freeBytes = { 10_000 * mb }, capBytes = 5000)
        val staged = tmp.newFile("source-1.mp4").apply { writeBytes(bytes) }
        assertThat(store.adopt(staged, sha, "mp4")).isNotNull()
        assertThat(old.exists()).isFalse()
    }

    @Test fun `copy verifies the sha256 and never keeps changed bytes`() {
        val store = LocalVideoStore(tmp.newFolder("videos"), freeBytes = { 10_000 * mb })
        assertThat(store.copy(ByteArrayInputStream(bytes), bytes.size.toLong(), sha, "mp4")!!.readBytes()).isEqualTo(bytes)
        val other = "b".repeat(64)
        assertThrows(IOException::class.java) { store.copy(ByteArrayInputStream(bytes), bytes.size.toLong(), other, "mp4") }
        assertThat(store.find(other)).isNull()
        assertThat(store.dir.listFiles()!!.map { it.name }).containsExactly("$sha.mp4")
    }

    @Test fun `prune drops copies no history refers to once unused for the grace period`() {
        val dir = tmp.newFolder("videos")
        val now = 10L * 24 * 60 * 60 * 1000
        val kept = File(dir, sha + ".mp4").apply { writeBytes(bytes); setLastModified(1_000) }
        val orphanOld = File(dir, "c".repeat(64) + ".mp4").apply { writeBytes(bytes); setLastModified(1_000) }
        val orphanNew = File(dir, "d".repeat(64) + ".mp4").apply { writeBytes(bytes); setLastModified(now - 1_000) }
        LocalVideoStore(dir, clock = { now }).prune(setOf(sha))
        assertThat(kept.exists()).isTrue()
        assertThat(orphanOld.exists()).isFalse()
        assertThat(orphanNew.exists()).isTrue()
    }
}
