package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two request-path reads over the quarantine log that must not scan the whole (unrotated) log: {@code events(int)}
 * pages the newest decisions by the epoch-millis in each object's name, reading only the page, and {@code latest} is a
 * point lookup of the latest-verdict-by-path index. Both are asserted against a counting store so the bodies read stay
 * bounded to the page / the single path.
 */
class QuarantineLogPagingTest {

    private static final Instant BASE = Instant.parse("2026-06-27T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
    }

    @Test
    void events_pages_the_newest_decisions_without_reading_the_whole_log() throws IOException {
        ArtifactStore store = store();
        QuarantineLog log = new QuarantineLog(store);
        for (int event = 0; event < 200; event++) {
            log.record(BASE.plusSeconds(event), "/maven/org/pkg" + event + "/1.0/a.jar",
                    "org:pkg" + event, Verdict.REJECT, List.of("blocked"));
        }

        CountingStore counting = new CountingStore(store);
        List<QuarantineLog.Event> page = new QuarantineLog(counting).events(5);

        assertThat(page).extracting(QuarantineLog.Event::when)
                .as("newest first").containsExactly(
                        BASE.plusSeconds(199), BASE.plusSeconds(198), BASE.plusSeconds(197),
                        BASE.plusSeconds(196), BASE.plusSeconds(195));
        assertThat(counting.objectReads())
                .as("only the page's five objects are read, not all 200 log entries").isLessThanOrEqualTo(5);
    }

    @Test
    void latest_point_looks_up_the_newest_verdict_for_a_path() throws IOException {
        ArtifactStore store = store();
        QuarantineLog log = new QuarantineLog(store);
        String path = "/maven/org/pkg/1.0/a.jar";
        log.record(BASE, path, "org:pkg", Verdict.QUARANTINE, List.of("first"));
        log.record(BASE.plusSeconds(60), path, "org:pkg", Verdict.REJECT, List.of("later"));
        log.record(BASE.minusSeconds(60), path, "org:pkg", Verdict.QUARANTINE, List.of("stale"));  // out of order

        CountingStore counting = new CountingStore(store);
        Optional<QuarantineLog.Event> latest = new QuarantineLog(counting).latest(path);

        assertThat(latest).isPresent();
        assertThat(latest.get().verdict()).as("the newest verdict, never regressed by an out-of-order record")
                .isEqualTo(Verdict.REJECT);
        assertThat(latest.get().reasons()).containsExactly("later");
        assertThat(counting.objectReads()).as("a single point lookup reads no log object").isZero();
        assertThat(counting.lists()).as("and lists no folder").isZero();
        assertThat(counting.versionedReads()).as("just the one index read").isEqualTo(1);
    }
}
