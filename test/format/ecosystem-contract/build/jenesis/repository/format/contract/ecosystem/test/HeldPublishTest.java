package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Withheld;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a format that screens its own publish does with an upload the screen holds: it lays the release out behind the
 * withhold marker on the artifact's hash, answers {@code 202}, and serves nothing of it until the hold is lifted -
 * so a review release is the marker clear every retroactive hold's release is, rather than a replay of a request
 * whose envelope no longer exists.
 *
 * <p>The review handle is the artifact's own served path, never the endpoint the request was sent to: a push
 * endpoint every upload shares would let a second held upload overwrite the first one's handle, and a marker whose
 * handle is gone is one the reconcile lifts as holderless.
 */
class HeldPublishTest {

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
    }

    static List<ReleaseImmutabilityTest.Format> screening() {
        return ReleaseImmutabilityTest.screening();
    }

    @ParameterizedTest
    @MethodSource("screening")
    void a_held_upload_is_laid_out_withheld_and_serves_once_released(ReleaseImmutabilityTest.Format format)
            throws IOException {
        Path directory = Files.createDirectories(root.resolve(format.name()));
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? directory.toString() : null);
        format.arrangement().arrange(store);

        ReleaseImmutabilityTest.Upload held;
        ContractHoldInterceptor.QUARANTINE_UPLOADS.set(true);
        try {
            held = format.upload(store, "");
        } finally {
            ContractHoldInterceptor.QUARANTINE_UPLOADS.set(false);
        }
        String hash = Packages.sha256(held.artifact());

        assertThat(held.exchange().status()).as("%s answers a held upload 202", format).isEqualTo(202);
        assertThat(get(format, store).status()).as("a held %s serves nothing", format).isEqualTo(404);
        assertThat(Withheld.is(store, hash)).as("the artifact's own hash is withheld").isTrue();
        Publication publication = new Publication(store, List.of(), List.of());
        assertThat(publication.blob("/quarantine" + format.served()))
                .as("the review handle of a held %s is its own served path", format).contains(hash);

        Withheld.clear(store, hash, Known.absent());
        publication.unpublish("/quarantine" + format.served());

        ContractExchange released = get(format, store);
        assertThat(released.status()).as("released, the %s serves", format).isEqualTo(200);
        assertThat(released.responseBytes()).as("the bytes that were held").isEqualTo(held.artifact());
    }

    private static ContractExchange get(ReleaseImmutabilityTest.Format format, ArtifactStore store)
            throws IOException {
        ContractExchange get = ContractExchange.of("GET", format.served());
        format.fixture().serving().handle(get, store);
        return get;
    }
}
