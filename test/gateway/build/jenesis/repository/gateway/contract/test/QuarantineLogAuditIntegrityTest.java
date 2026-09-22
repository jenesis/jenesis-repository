package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.store.ArtifactStore;

import static build.jenesis.repository.gateway.testkit.FormatDrive.MemStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two audit-integrity fixes over the quarantine log, driven on the flat {@link MemStore} (an object-store shape,
 * where one held key can be a proper prefix of another and where a same-millisecond write collision is observable):
 * a distinct-path collision no longer overwrites another path's audit row, and a held path that prefixes another held
 * path still surfaces in the review queue.
 */
class QuarantineLogAuditIntegrityTest {

    private static final Instant WHEN = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void two_paths_that_collide_on_hashcode_each_keep_their_audit_row() throws IOException {
        // "/Aa" and "/BB" share a String.hashCode() (2112). Keyed by hashCode, both event objects landed at the same
        // <millis>-<hex> name in the same millisecond, so one silently overwrote the other's audit row. A path digest
        // keeps them distinct.
        assertThat("/Aa".hashCode()).isEqualTo("/BB".hashCode());
        ArtifactStore store = new MemStore();
        QuarantineLog log = new QuarantineLog(store);
        log.record(WHEN, "/Aa", "eco:Aa", Verdict.REJECT, List.of("blocked"));
        log.record(WHEN, "/BB", "eco:BB", Verdict.QUARANTINE, List.of("held"));

        assertThat(log.events()).extracting(QuarantineLog.Event::path)
                .as("both audit rows survive - neither collided over the other")
                .containsExactlyInAnyOrder("/Aa", "/BB");
    }

    @Test
    void discarding_one_collision_path_leaves_the_other_intact() throws IOException {
        ArtifactStore store = new MemStore();
        QuarantineLog log = new QuarantineLog(store);
        log.record(WHEN, "/Aa", "eco:Aa", Verdict.REJECT, List.of("blocked"));
        log.record(WHEN, "/BB", "eco:BB", Verdict.QUARANTINE, List.of("held"));

        log.discarded("/Aa");

        assertThat(log.events()).extracting(QuarantineLog.Event::path)
                .as("only the discarded path's row is removed - the digest suffix targets exactly it")
                .containsExactly("/BB");
    }

    @Test
    void a_held_path_that_prefixes_another_held_path_still_surfaces() throws IOException {
        ArtifactStore store = new MemStore();
        // Two live hold pointers where one path is a proper prefix of the other - only representable on a flat store.
        store.writeVersioned("publish/quarantine/npm/pkg", "hold".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("publish/quarantine/npm/pkg/nested", "hold".getBytes(StandardCharsets.UTF_8), null);

        List<String> held = new QuarantineLog(store).reviewQueue().stream()
                .map(QuarantineLog.Held::path).toList();

        assertThat(held)
                .as("the held prefix is not hidden by the deeper hold under it")
                .containsExactlyInAnyOrder("/npm/pkg", "/npm/pkg/nested");
    }
}
