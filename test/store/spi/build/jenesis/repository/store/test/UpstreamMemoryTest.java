package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.UpstreamMemory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node's memory of relayed upstream documents: a document is answered from memory until its ttl passes, for the
 * repository that relayed it and no other, a document past the entry cap is never kept, and a zero ttl keeps nothing.
 */
class UpstreamMemoryTest {

    private static final URI DOCUMENT = URI.create("https://upstream.example/maven2/org/acme/lib/maven-metadata.xml");

    private static final byte[] BODY = "<metadata/>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private ArtifactStore repository(String name) {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default").scope(name);
    }

    @Test
    void a_document_is_answered_from_memory_until_its_ttl_passes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-03T00:00:00Z"));
        UpstreamMemory memory = new UpstreamMemory(Duration.ofHours(6), clock);
        ArtifactStore releases = repository("releases");

        assertThat(memory.get(releases, DOCUMENT)).as("nothing remembered yet").isEmpty();
        memory.put(releases, DOCUMENT, BODY);
        clock.advance(Duration.ofHours(5));
        assertThat(memory.get(releases, DOCUMENT)).hasValue(BODY);
        clock.advance(Duration.ofHours(1));
        assertThat(memory.get(releases, DOCUMENT)).as("six hours on, the upstream is asked again").isEmpty();
        assertThat(memory.hits()).isEqualTo(1);
        assertThat(memory.misses()).isEqualTo(2);
    }

    @Test
    void a_document_relayed_for_one_repository_never_answers_another() {
        UpstreamMemory memory = new UpstreamMemory(Duration.ofHours(6));
        memory.put(repository("releases"), DOCUMENT, BODY);

        assertThat(memory.get(repository("private"), DOCUMENT))
                .as("another repository may reach the same upstream with other credentials").isEmpty();
    }

    @Test
    void a_document_past_the_cap_or_a_zero_ttl_keeps_nothing() {
        ArtifactStore releases = repository("releases");
        UpstreamMemory memory = new UpstreamMemory(Duration.ofHours(6));
        memory.put(releases, DOCUMENT, new byte[(1 << 20) + 1]);
        assertThat(memory.get(releases, DOCUMENT)).as("a document past the entry cap is relayed uncached").isEmpty();

        UpstreamMemory off = new UpstreamMemory(Duration.ZERO);
        off.put(releases, DOCUMENT, BODY);
        assertThat(off.get(releases, DOCUMENT)).as("a zero ttl switches the memory off").isEmpty();
        assertThat(off.bytes()).isZero();
    }

    @Test
    void the_ttl_defaults_to_six_hours() {
        assertThat(UpstreamMemory.ttl(null)).isEqualTo(Duration.ofHours(6));
        assertThat(UpstreamMemory.ttl("")).isEqualTo(Duration.ofHours(6));
        assertThat(UpstreamMemory.ttl("PT1H")).isEqualTo(Duration.ofHours(1));
    }

    /** A clock a test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
