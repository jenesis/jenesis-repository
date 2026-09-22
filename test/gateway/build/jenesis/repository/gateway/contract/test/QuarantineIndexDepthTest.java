package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The latest-verdict-by-path index has to be writable for <em>every</em> path the gate can hold, including a deep one
 *. It used to URL-encode the request path into one key segment, which triples every separator: a real deep
 * pool path produced a name past a filesystem store's 255-byte limit, the store refused it, and the write is
 * best-effort - so the decision was appended to the trail and the path's {@code latest} read empty for ever, which is
 * exactly the artifact-detail view that exists to explain a hold.
 *
 * <p>Driven against the filesystem backend on purpose: it is the backend that maps one key segment to one file name
 * and enforces the limit, so an in-memory or object-store stand-in would accept the old key and prove nothing.
 */
class QuarantineIndexDepthTest {

    private static final Instant WHEN = Instant.parse("2026-08-18T09:15:00Z");

    /** A pool path whose URL-encoded form overruns a filesystem name: 24 separators at three bytes each, plus the
     *  segments themselves, is comfortably past 255 while every individual segment is an ordinary name. */
    private static final String DEEP_PATH = "/debian/pool/main/" + String.join("/",
            Collections.nCopies(20, "libexample-extra-runtime")) + "/libexample_1.0-1_amd64.deb";

    @TempDir
    Path root;

    /** The Debian Release family and the .gz twins are derived off the request; they are finished before the
     *  store goes. */
    @AfterEach
    void settleDerivations() {
        StoredListing.settle();
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
    }

    @Test
    void the_probe_path_really_is_one_the_old_encoded_key_could_not_hold() {
        assertThat(URLEncoder.encode(DEEP_PATH, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8).length)
                .as("if this ever drops under a filesystem name's 255-byte ceiling the test below proves nothing")
                .isGreaterThan(255);
    }

    @Test
    void a_deep_path_gets_its_latest_verdict_row_and_reads_back_with_its_path() throws IOException {
        ArtifactStore store = store();
        QuarantineLog log = new QuarantineLog(store);

        log.record(WHEN, DEEP_PATH, "libexample:1.0-1", Verdict.QUARANTINE, List.of("kev"));

        Optional<QuarantineLog.Event> latest = log.latest(DEEP_PATH);
        assertThat(latest).as("the derived index row must exist for a deep path, not only for a shallow one")
                .isPresent();
        assertThat(latest.get().verdict()).isEqualTo(Verdict.QUARANTINE);
        assertThat(latest.get().path())
                .as("the key is only a digest, so the path has to come back off the row's body")
                .isEqualTo(DEEP_PATH);
        assertThat(latest.get().reasons()).containsExactly("kev");

        // A newer decision still wins on the same path, so the digest key did not turn the index into an append.
        log.record(WHEN.plusSeconds(60), DEEP_PATH, "libexample:1.0-1", Verdict.REJECT, List.of("blocked"));
        assertThat(log.latest(DEEP_PATH)).get().extracting(QuarantineLog.Event::verdict).isEqualTo(Verdict.REJECT);
    }

    @Test
    void the_index_row_is_a_fixed_width_name_whatever_the_path_length() throws IOException {
        ArtifactStore store = store();
        QuarantineLog log = new QuarantineLog(store);
        log.record(WHEN, "/maven/org/example/lib/1.0/lib-1.0.jar", "org.example:lib", Verdict.QUARANTINE,
                List.of("licence"));
        log.record(WHEN, DEEP_PATH, "libexample:1.0-1", Verdict.QUARANTINE, List.of("kev"));

        List<String> names = store.list("audit/quarantine-index");
        assertThat(names).as("one row per held path").hasSize(2);
        assertThat(names).allSatisfy(name -> assertThat(name)
                .as("a hex SHA-256, so no path can overrun the segment and no two paths can fuse into one row")
                .matches("[0-9a-f]{64}"));
    }

    @Test
    void a_discard_removes_the_deep_paths_row_too() throws IOException {
        ArtifactStore store = store();
        QuarantineLog log = new QuarantineLog(store);
        log.record(WHEN, DEEP_PATH, "libexample:1.0-1", Verdict.QUARANTINE, List.of("kev"));

        log.discarded(DEEP_PATH);

        assertThat(log.latest(DEEP_PATH))
                .as("the write composer and the reap composer must name the same key, or a discard leaks a row")
                .isEmpty();
        assertThat(store.list("audit/quarantine-index")).isEmpty();
    }
}
