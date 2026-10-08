package build.jenesis.repository.compliance.web.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.compliance.web.ProvenanceAttestationSweepProvider;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provenance-attestation sweep reclaims every attestation whose content is gone and keeps every one whose blob
 * lives, whichever path it was attested for - the key names a hash and a digest of the path, so a living blob is all
 * the sweep can see, and over-reclaiming would destroy a live artifact's provenance. It is off unless switched on.
 */
class ProvenanceAttestationSweepTest {

    private static final String LIVE = "a".repeat(64);
    private static final String GONE = "b".repeat(64);

    @TempDir
    Path root;

    @Test
    void attestations_of_gone_content_are_reclaimed_and_those_of_living_content_kept() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme").scope("releases");
        store.write("blobs/" + LIVE, new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        for (String key : List.of("provenance-attestation/" + LIVE + "/p1", "provenance-attestation/" + LIVE + "/p2",
                "provenance-attestation/" + GONE + "/p1", "provenance-attestation/" + GONE + "/p2")) {
            store.write(key, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        }
        Map<String, Double> gauges = new HashMap<>();

        sweep().repository(context(store, gauges));

        assertThat(store.list("provenance-attestation/" + LIVE)).as("a living blob's attestations, every path's")
                .containsExactlyInAnyOrder("p1", "p2");
        assertThat(store.list("provenance-attestation/" + GONE)).as("a gone blob's attestations").isEmpty();
        assertThat(gauges).containsEntry("jenrepo.provenance.attestations.reclaimed", 2.0)
                .containsEntry("jenrepo.provenance.attestations.unreadable", 0.0);

        sweep().repository(context(store, gauges));
        assertThat(gauges).as("a second pass over a swept store reclaims nothing")
                .containsEntry("jenrepo.provenance.attestations.reclaimed", 0.0);
        assertThat(store.list("provenance-attestation/" + LIVE)).hasSize(2);
    }

    @Test
    void the_sweep_is_off_unless_switched_on() {
        assertThat(new ProvenanceAttestationSweepProvider().create(key -> null)).isEmpty();
    }

    private static MaintenanceTask sweep() {
        return new ProvenanceAttestationSweepProvider()
                .create(Map.of("provenance-attestation-sweep", "true")::get).orElseThrow();
    }

    private static RepositoryContext context(ArtifactStore store, Map<String, Double> gauges) {
        return new RepositoryContext() {
            @Override
            public TenantView tenantView() {
                return TenantView.NONE;
            }

            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "acme";
            }

            @Override
            public String repository() {
                return "releases";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                return key -> null;
            }

            @Override
            public Instant now() {
                return Instant.now();
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
                gauges.put(name, value);
            }
        };
    }
}
