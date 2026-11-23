package com.lemon7679.note.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Same scenarios as note-ipad's SyncPlannerTests: the two apps must reconcile identically. */
class SyncPlannerTest {
    private val t0 = Instant.ofEpochSecond(1_790_000_000)

    private fun l(p: String, size: Long = 10, dir: Boolean = false, at: Long = 0) = p to LocalEntry(p, dir, size, t0.plusSeconds(at))
    private fun r(p: String, size: Long = 10, dir: Boolean = false, etag: String? = null) = p to RemoteItem.of(p, dir, size, t0, etag)
    private fun synced(p: String, size: Long = 10, dir: Boolean = false, etag: String? = null): Pair<String, SyncRecord> {
        val le = LocalEntry(p, dir, size, t0)
        val re = RemoteItem.of(p, dir, size, t0, etag)
        return p to SyncRecord(dir, le.signature, re.signature, size)
    }

    private fun plan(
        local: List<Pair<String, LocalEntry>> = emptyList(),
        remote: List<Pair<String, RemoteItem>> = emptyList(),
        base: List<Pair<String, SyncRecord>> = emptyList(),
        busy: Set<String> = emptySet(),
        resolutions: Map<String, ConflictResolution> = emptyMap(),
        onDemand: Boolean = true,
    ) = SyncPlanner.plan(local.toMap(), remote.toMap(), base.toMap(), busy, resolutions, onDemand, t0)

    @Test fun firstSyncUploadsLocalAndCreatesFoldersFirst() {
        val p = plan(local = listOf(l("/강의", dir = true), l("/강의/a.pdf")))
        assertEquals(listOf(SyncAction.MakeRemoteDirectory("/강의"), SyncAction.Upload("/강의/a.pdf")), p.actions)
        assertTrue(p.conflicts.isEmpty())
    }

    @Test fun newRemoteFilesArePlaceholdersButNotebooksDownload() {
        val p = plan(remote = listOf(r("/a.pdf"), r("/n.inknote", dir = true), r("/n.inknote/document.json")))
        assertEquals(listOf(SyncAction.MakeLocalDirectory("/n.inknote"), SyncAction.Download("/n.inknote/document.json"),
            SyncAction.Placeholder("/a.pdf")), p.actions)
        assertEquals(listOf(SyncAction.Download("/a.pdf")), plan(remote = listOf(r("/a.pdf")), onDemand = false).actions)
    }

    @Test fun oneSidedChanges() {
        assertEquals(listOf(SyncAction.Upload("/a")), plan(listOf(l("/a", 11, at = 5)), listOf(r("/a")), listOf(synced("/a"))).actions)
        assertEquals(listOf(SyncAction.Download("/a")), plan(listOf(l("/a")), listOf(r("/a", 12)), listOf(synced("/a"))).actions)
        assertEquals(emptyList<SyncAction>(), plan(listOf(l("/a")), listOf(r("/a")), listOf(synced("/a"))).actions)
        assertEquals(listOf(SyncAction.DeleteRemote("/a")), plan(remote = listOf(r("/a")), base = listOf(synced("/a"))).actions)
        assertEquals(listOf(SyncAction.DeleteLocal("/a")), plan(local = listOf(l("/a")), base = listOf(synced("/a"))).actions)
        assertEquals(listOf(SyncAction.Forget("/a")), plan(base = listOf(synced("/a"))).actions)
    }

    @Test fun deleteVersusEditKeepsTheEdit() {
        assertEquals(listOf(SyncAction.Upload("/a")), plan(local = listOf(l("/a", 20, at = 9)), base = listOf(synced("/a"))).actions)
        assertEquals(listOf(SyncAction.Download("/a")), plan(remote = listOf(r("/a", 20)), base = listOf(synced("/a"))).actions)
    }

    @Test fun etagDrivesRemoteChange() {
        val base = listOf(synced("/a", etag = "v1"))
        assertEquals(emptyList<SyncAction>(), plan(listOf(l("/a")), listOf(r("/a", etag = "v1")), base).actions)
        assertEquals(listOf(SyncAction.Download("/a")), plan(listOf(l("/a")), listOf(r("/a", etag = "v2")), base).actions)
    }

    @Test fun conflictInPackageBlocksWholeNotebook() {
        val pkg = "/보고서.inknote"
        val p = plan(
            listOf(l(pkg, dir = true), l("$pkg/document.json", 30, at = 3), l("$pkg/pages/1.ink", 50, at = 3), l("$pkg/pages", dir = true)),
            listOf(r(pkg, dir = true), r("$pkg/document.json", 31), r("$pkg/pages/1.ink"), r("$pkg/pages", dir = true)),
            listOf(synced(pkg, dir = true), synced("$pkg/document.json"), synced("$pkg/pages/1.ink"), synced("$pkg/pages", dir = true)),
        )
        assertEquals(listOf(pkg), p.conflicts.map { it.path })
        assertTrue(p.actions.toString(), p.actions.isEmpty())
    }

    @Test fun plainFileConflict() {
        val p = plan(listOf(l("/a.pdf", 11, at = 2)), listOf(r("/a.pdf", 12)), listOf(synced("/a.pdf")))
        assertEquals(listOf("/a.pdf"), p.conflicts.map { it.path })
        assertTrue(p.actions.isEmpty())
    }

    @Test fun firstSyncWithBothSidesAdoptsEqualSizes() {
        assertEquals(listOf(SyncAction.Adopt("/a")), plan(listOf(l("/a", 10)), listOf(r("/a", 10))).actions)
        assertEquals(listOf("/a"), plan(listOf(l("/a", 10)), listOf(r("/a", 11))).conflicts.map { it.path })
    }

    @Test fun deletedFolderIsOneActionUnlessOtherSideChanged() {
        val base = listOf(synced("/d", dir = true), synced("/d/x"), synced("/d/y"))
        assertEquals(listOf(SyncAction.DeleteRemote("/d")),
            plan(remote = listOf(r("/d", dir = true), r("/d/x"), r("/d/y")), base = base).actions)
        val kept = plan(remote = listOf(r("/d", dir = true), r("/d/x"), r("/d/y", 99)), base = base)
        assertEquals(listOf(SyncAction.MakeLocalDirectory("/d"), SyncAction.Download("/d/y"), SyncAction.DeleteRemote("/d/x")), kept.actions)
        assertEquals(listOf(SyncAction.DeleteLocal("/d")),
            plan(local = listOf(l("/d", dir = true), l("/d/x"), l("/d/y")), base = base).actions)
    }

    @Test fun busyNotebooksWait() {
        val p = plan(local = listOf(l("/n.inknote", dir = true), l("/n.inknote/document.json")), busy = setOf("/n.inknote"))
        assertTrue(p.actions.isEmpty())
    }

    @Test fun placeholdersStayOnlineAndFollowRemoteChanges() {
        val rec = synced("/a.pdf").let { it.first to it.second.copy(localSig = null) }
        assertTrue(plan(remote = listOf(r("/a.pdf")), base = listOf(rec)).actions.isEmpty())
        assertEquals(listOf(SyncAction.Placeholder("/a.pdf")), plan(remote = listOf(r("/a.pdf", 50)), base = listOf(rec)).actions)
        assertEquals(listOf(SyncAction.Forget("/a.pdf")), plan(base = listOf(rec)).actions)
    }

    @Test fun resolutions() {
        val local = listOf(l("/a.pdf", 11, at = 2))
        val remote = listOf(r("/a.pdf", 12))
        val base = listOf(synced("/a.pdf"))
        assertEquals(listOf(SyncAction.Upload("/a.pdf")),
            plan(local, remote, base, resolutions = mapOf("/a.pdf" to ConflictResolution.KEEP_LOCAL)).actions)
        assertEquals(listOf(SyncAction.Download("/a.pdf")),
            plan(local, remote, base, resolutions = mapOf("/a.pdf" to ConflictResolution.KEEP_REMOTE)).actions)
        assertEquals(listOf(SyncAction.ConflictCopy("/a.pdf"), SyncAction.Download("/a.pdf")),
            plan(local, remote, base, resolutions = mapOf("/a.pdf" to ConflictResolution.KEEP_BOTH)).actions)
    }
}
