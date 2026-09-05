package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Stamp;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Stamp} and {@link Epoch}: the two small markers a ledger keeps beside its rows. A stamp is the instant
 * something last completed - absent or unparseable reads as "never", a newer mark replaces an older one, and a mark
 * that loses its one compare-and-set leaves the earlier stamp rather than throwing or overwriting. An epoch is a token
 * that changes on every bump and reads as the empty string until the first.
 */
public class StampTest {

    private static final Instant FIRST = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-01T11:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    @Test
    void a_stamp_is_absent_until_marked_and_then_reads_its_newest_mark() throws IOException {
        Stamp stamp = new Stamp(store, "findings/scanned");
        assertThat(stamp.read()).as("never completed reads as absent, not as an instant").isEmpty();
        stamp.mark(FIRST);
        assertThat(stamp.read()).contains(FIRST);
        stamp.mark(LATER);
        assertThat(stamp.read()).as("the newest completion is the stamp").contains(LATER);
        assertThat(stamp.key()).isEqualTo("findings/scanned");
    }

    @Test
    void a_stamp_that_does_not_parse_reads_as_never() throws IOException {
        store.writeVersioned("findings/scanned", "not an instant".getBytes(StandardCharsets.UTF_8), null);
        assertThat(new Stamp(store, "findings/scanned").read())
                .as("a corrupt stamp degrades to never, the honest direction").isEmpty();
    }

    @Test
    void a_mark_that_loses_its_race_leaves_the_earlier_stamp_standing() throws IOException {
        new Stamp(store, "findings/scanned").mark(FIRST);
        // A peer's completion lands between this mark's read and its write - the one compare-and-set loses. The
        // stamp is not retried and not overwritten: the earlier instant stands, older but never wrong, and the next
        // completion moves it.
        FaultInjectingStore racing = FaultInjectingStore.wrap(store);
        racing.conflictNext(FaultInjectingStore.keyContaining("findings/scanned"));
        new Stamp(racing, "findings/scanned").mark(LATER);
        assertThat(new Stamp(store, "findings/scanned").read()).as("the lost mark left the earlier stamp")
                .contains(FIRST);
    }

    @Test
    void an_epoch_is_empty_until_bumped_and_every_bump_is_a_new_token() throws IOException {
        Epoch epoch = new Epoch(store, "findings/evicted");
        assertThat(epoch.current()).as("nothing ever evicted reads as the empty token").isEmpty();
        epoch.bump();
        String first = epoch.current();
        assertThat(first).isNotEmpty();
        epoch.bump();
        assertThat(epoch.current()).as("a bump moves the epoch").isNotEqualTo(first).isNotEmpty();
        assertThat(store.readVersioned("findings/evicted")).as("the token is what the store holds").isPresent();
    }
}
