package com.flowmap.nexcore;

import com.flowmap.nexcore.impact.GitHub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Open-PR plumbing: {@code gh pr list --state open} JSON → {@link GitHub.Pr}, and status carry-through. */
class GitHubOpenPullsTest {

    @Test
    void parseOpenMapsHeadOidStatusAndUpdatedAt() {
        String json = "[{\"number\":42,\"title\":\"wip: feature\",\"author\":{\"login\":\"alice\"},"
                + "\"headRefOid\":\"abc123\",\"createdAt\":\"2026-06-01T00:00:00Z\","
                + "\"updatedAt\":\"2026-06-10T09:00:00Z\",\"isDraft\":false},"
                + "{\"number\":7,\"title\":\"draft one\",\"author\":{\"login\":\"bob\"},"
                + "\"headRefOid\":\"def456\",\"createdAt\":\"2026-05-01T00:00:00Z\","
                + "\"updatedAt\":\"2026-05-02T00:00:00Z\",\"isDraft\":true}]";

        List<GitHub.Pr> prs = GitHub.parseOpen(json);
        assertEquals(2, prs.size());

        GitHub.Pr open = prs.get(0);
        assertEquals(42, open.number);
        assertEquals("open", open.status);
        assertEquals("abc123", open.headOid);
        assertEquals("abc123", open.analyzedCommit());   // no mergeCommit → head is the analyzed revision
        assertNull(open.mergeCommit);
        assertNull(open.mergedAt);                        // not merged yet
        assertEquals("2026-06-10T09:00:00Z", open.updatedAt);
        assertTrue(open.isOpen());

        GitHub.Pr draft = prs.get(1);
        assertEquals("draft", draft.status);
        assertTrue(draft.isOpen());
    }

    @Test
    void parseOpenFallsBackToCreatedAtWhenUpdatedMissing() {
        String json = "[{\"number\":1,\"title\":\"t\",\"headRefOid\":\"h\",\"createdAt\":\"2026-01-01T00:00:00Z\"}]";
        List<GitHub.Pr> prs = GitHub.parseOpen(json);
        assertEquals("2026-01-01T00:00:00Z", prs.get(0).updatedAt);
        assertEquals("open", prs.get(0).status);   // isDraft absent → open
    }

    @Test
    void parseOpenHandlesEmptyAndGarbage() {
        assertTrue(GitHub.parseOpen("[]").isEmpty());
        assertTrue(GitHub.parseOpen("not json").isEmpty());
    }

    @Test
    void mergedPrDefaultsToMergedStatusAndIsNotOpen() {
        GitHub.Pr merged = new GitHub.Pr(5, "fix", "carol", "2026-02-02T00:00:00Z", "mergeSha");
        assertEquals("merged", merged.status);
        assertEquals("mergeSha", merged.analyzedCommit());
        assertNull(merged.headOid);
        assertTrue(!merged.isOpen());
    }

    @Test
    void shardAndIndexCarryStatusAndUpdatedAt() {
        GitHub.Pr open = new GitHub.Pr(9, "wip", "dan", null, "2026-03-03T00:00:00Z", null, "head9", "open");
        Map<String, Object> shard = GitHub.buildShard(open, List.of(), "https://github.com/o/r");
        assertEquals("open", shard.get("status"));
        assertEquals("2026-03-03T00:00:00Z", shard.get("updatedAt"));
        assertEquals("head9", shard.get("mergeCommit"));   // analyzed commit = open head
        assertEquals("https://github.com/o/r/pull/9", shard.get("url"));

        Map<String, Object> entry = GitHub.indexEntry(shard, "r.pulls");
        assertEquals("open", entry.get("status"));
        assertEquals("2026-03-03T00:00:00Z", entry.get("updatedAt"));
    }
}
