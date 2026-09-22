package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.WitnessStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Withheld;

/**
 * The two serve-side properties the free kit states over an <em>arbitrary</em> artifact body, restated over a
 * <em>real package</em> for the seven ecosystems whose publish protocol parses the artifact.
 *
 * <p>The kit's {@link FormatContract.Property#PUBLISH_SERVES_EXACT_BYTES} and
 * {@link FormatContract.Property#HEAD_ANSWERS_FROM_METADATA} hand a fixture a generated ramp and require that exact
 * body back. That is right for every ecosystem whose artifact is opaque to its publish protocol (Maven, OCI, raw, and
 * the npm / PyPI / Go / Cargo / Conan / Hugging Face six here), and it is <em>unrepresentable</em> for the seven whose
 * publish reads the artifact: a NuGet push resolves its coordinate from the {@code .nuspec} inside the {@code .nupkg},
 * a gem push from the gzipped YAML gemspec, a Debian push from the {@code control} inside the {@code ar}/tar, an RPM
 * push from the binary header, a conda push from the {@code info/index.json} inside the archive, a Composer push from
 * the {@code composer.json} and a CocoaPods push from the {@code .podspec.json} - so a ramp is not a publishable
 * artifact there and no path serves one back. Those fixtures exclude the two kit properties with that protocol reason
 * and supply {@link EcosystemFormatFixture#publishPackage} instead.
 *
 * <p>Nothing about the properties is weakened here, and that is the point of stating them separately rather than
 * loosening the kit: the same {@link WitnessStore#seal sealed blob} makes a {@code HEAD} that opens the artifact fail
 * loudly, the same non-vacuity check watches a {@code GET} trip that seal, and the served bytes are still compared in
 * full. Only the body changes shape - from a ramp the kit generated to a package the ecosystem would actually accept.
 */
final class PackagedArtifactContract {

    /** One documented clause, named so a failure reads as its own property rather than as the kit's. */
    enum Property {
        /** A published package serves back byte for byte from its content-addressed blob, and an unpublished path
         *  this format claims is a client error rather than an empty {@code 200}. */
        PACKAGE_SERVES_EXACT_BYTES,
        /** A package whose blob is gone answers a clean {@code 404} with no body: the serve opens the blob before it
         *  commits, so the open is the existence check. */
        PACKAGE_GONE_BLOB_IS_A_CLEAN_404,
        /** A published package held by both halves of the hold convention answers {@code 404} with no byte
         *  disclosed, and serves the original package again once both are lifted. */
        PACKAGE_HELD_THEN_RELEASED_SERVES_AGAIN,
        /** A {@code HEAD} of a published package answers {@code 200} with its length from the store's metadata and
         *  without opening the blob. */
        PACKAGE_HEAD_ANSWERS_FROM_METADATA,
        /** The kit's {@code PUBLISH_PATHS_ARE_DESCRIBED} over a real package: every path the package publish wrote
         *  at is one the format's describe places. */
        PACKAGE_PUBLISH_PATHS_ARE_DESCRIBED
    }

    record Check(Property property, String name, Body body) {

        Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    @FunctionalInterface
    interface Body {
        void run(EcosystemFormatFixture fixture, ArtifactStore store) throws Exception;
    }

    /** The two kit properties this contract stands in for. A fixture that excludes either of them must supply a
     *  packaged leg; a fixture that excludes neither runs the kit's own legs and needs nothing here. */
    static final Set<FormatContract.Property> STANDS_IN_FOR = Set.of(
            FormatContract.Property.PUBLISH_SERVES_EXACT_BYTES,
            FormatContract.Property.GONE_BLOB_IS_A_CLEAN_404,
            FormatContract.Property.HELD_THEN_RELEASED_SERVES_AGAIN,
            FormatContract.Property.HEAD_ANSWERS_FROM_METADATA);

    private PackagedArtifactContract() {
    }

    /** Whether this fixture's format needs the packaged restatement - exactly when it excluded one of the kit legs. */
    static boolean stands(EcosystemFormatFixture fixture) {
        return fixture.unsupported().keySet().stream().anyMatch(STANDS_IN_FOR::contains);
    }

    /** The checks {@code fixture} runs: both when it excluded a kit publish leg, none otherwise. */
    static List<Check> checks(EcosystemFormatFixture fixture) {
        if (!stands(fixture)) {
            return List.of();
        }
        return List.of(
                new Check(Property.PACKAGE_SERVES_EXACT_BYTES,
                        "a published package serves back byte for byte from its content-addressed blob",
                        PackagedArtifactContract::servesExactBytes),
                new Check(Property.PACKAGE_GONE_BLOB_IS_A_CLEAN_404,
                        "a package whose blob is gone answers a clean 404, never a truncated 200",
                        PackagedArtifactContract::goneBlobIsACleanNotFound),
                new Check(Property.PACKAGE_HELD_THEN_RELEASED_SERVES_AGAIN,
                        "a held package answers 404, and serves the original package again once released",
                        PackagedArtifactContract::heldThenReleasedServesAgain),
                new Check(Property.PACKAGE_HEAD_ANSWERS_FROM_METADATA,
                        "a HEAD of a published package answers from the store's metadata without opening it",
                        PackagedArtifactContract::headAnswersFromMetadata),
                new Check(Property.PACKAGE_PUBLISH_PATHS_ARE_DESCRIBED,
                        "every path a package publish writes at is one the format's describe places",
                        PackagedArtifactContract::publishPathsAreDescribed));
    }

    private static void publishPathsAreDescribed(EcosystemFormatFixture fixture, ArtifactStore store)
            throws Exception {
        ContractExchange.recordWrites();
        List<String> written;
        try {
            fixture.publishPackage(store).orElseThrow(() -> new AssertionError(fixture.format()
                    + ": stands in for the kit's publish properties but publishes no package"));
        } finally {
            written = ContractExchange.recordedWrites();
        }
        FormatContract.describesAll(fixture, written);
    }

    private static void servesExactBytes(EcosystemFormatFixture fixture, ArtifactStore store) throws Exception {
        EcosystemFormatFixture.Packaged published = published(fixture, store);

        isTrue(fixture.serving().handles(published.servedPath()), fixture,
                "the format claims the path its own publish laid the package out at (" + published.servedPath() + ")");
        isTrue(store.exists("blobs/" + published.contentHash()), fixture,
                "the package is stored content-addressed at blobs/" + published.contentHash());

        ContractExchange get = ContractExchange.of("GET", published.servedPath()).settings(fixture::setting);
        fixture.serving().handle(get, store);
        equal(get.status(), 200, fixture, "a GET of the published path serves it");
        equal(get.responseBytes(), published.artifact(), fixture,
                "the served bytes are the published bytes, byte for byte");

        // ... and again: a read renders stored state, so a second GET of unchanged state is identical.
        ContractExchange again = ContractExchange.of("GET", published.servedPath()).settings(fixture::setting);
        fixture.serving().handle(again, store);
        equal(again.status(), 200, fixture, "a repeated GET still serves");
        equal(again.responseBytes(), published.artifact(), fixture, "a repeated GET serves identical bytes");

        ContractExchange missing = ContractExchange.of("GET", fixture.probe("t202b-never-published/absent.bin"))
                .settings(fixture::setting);
        fixture.serving().handle(missing, store);
        isTrue(missing.status() >= 400, fixture,
                "an unpublished path this format claims answers a client error, never an empty 200 (was "
                        + missing.status() + ")");
    }

    /** The kit's gone-blob leg over a real package: published, its blob deleted out from under the pointer, and a
     *  GET answering a clean 404 with no body - the serve opened the blob before it committed. */
    private static void goneBlobIsACleanNotFound(EcosystemFormatFixture fixture, ArtifactStore store)
            throws Exception {
        EcosystemFormatFixture.Packaged published = published(fixture, store);
        String blob = "blobs/" + published.contentHash();
        isTrue(store.exists(blob), fixture, "the package is stored content-addressed at " + blob);

        store.delete(blob);
        ContractExchange get = ContractExchange.of("GET", published.servedPath()).settings(fixture::setting);
        fixture.serving().handle(get, store);
        equal(get.status(), 404, fixture, "a GET of a package whose blob is gone answers 404 - the open before the "
                + "commit is the existence check, and a 200 here would be a truncated body a client caches as empty");
        equal(get.responseLength(), 0L, fixture, "and writes no body");
    }

    /** The kit's hold round trip over a real package: the {@code /quarantine<path>} review pointer (copied onto a
     *  {@code publish/} serving pointer as its hold flag) and the content-addressed marker, then both lifted, and the
     *  package must serve again byte for byte - the leg that catches a hold copied onto the pointer and never
     *  restored. */
    private static void heldThenReleasedServesAgain(EcosystemFormatFixture fixture, ArtifactStore store)
            throws Exception {
        EcosystemFormatFixture.Packaged published = published(fixture, store);
        Publication publication = new Publication(store, List.of());
        String review = Publication.QUARANTINE_PATH + published.servedPath();

        publication.link(review, published.contentHash());
        Withheld.mark(store, published.contentHash());
        ContractExchange held = ContractExchange.of("GET", published.servedPath()).settings(fixture::setting);
        fixture.serving().handle(held, store);
        equal(held.status(), 404, fixture, "a held package answers 404");
        equal(held.responseLength(), 0L, fixture, "and discloses no byte of it");

        publication.unpublish(review);
        Withheld.clear(store, published.contentHash(), Known.absent());
        ContractExchange released = ContractExchange.of("GET", published.servedPath()).settings(fixture::setting);
        fixture.serving().handle(released, store);
        equal(released.status(), 200, fixture, "released, the package serves again");
        equal(released.responseBytes(), published.artifact(), fixture, "and it is the original package, byte for byte");
    }

    private static void headAnswersFromMetadata(EcosystemFormatFixture fixture, ArtifactStore store)
            throws Exception {
        EcosystemFormatFixture.Packaged published = published(fixture, store);
        String blob = "blobs/" + published.contentHash();

        // The proof, exactly as the kit makes it: the package's bytes are made unreadable, so a format that answers a
        // HEAD from the store's metadata is unaffected while one that opens the blob fails by touching a refused key.
        WitnessStore sealed = WitnessStore.over(store).seal(blob);
        ContractExchange head = ContractExchange.of("HEAD", published.servedPath()).settings(fixture::setting);
        fixture.serving().handle(head, sealed);

        equal(head.status(), 200, fixture, "a HEAD of the published path answers 200");
        equal(head.responseLength(), 0L, fixture, "a HEAD writes no body");
        equal(head.responseHeader("Content-Length"), Integer.toString(published.artifact().length), fixture,
                "a HEAD advertises the package's length, read from the store's metadata - a client sizing an artifact "
                        + "before pulling it must get the same answer a GET would give");

        boolean tripped = false;
        try {
            ContractExchange get = ContractExchange.of("GET", published.servedPath()).settings(fixture::setting);
            fixture.serving().handle(get, sealed);
        } catch (AssertionError expected) {
            tripped = expected.getMessage() != null && expected.getMessage().contains(blob);
        }
        isTrue(tripped, fixture, "the sealed blob is genuinely unreadable - a GET through the same store must trip the "
                + "witness, or the HEAD leg above proves nothing");

        ContractExchange missing = ContractExchange.of("HEAD", fixture.probe("t202b-never-published/absent.bin"))
                .settings(fixture::setting);
        fixture.serving().handle(missing, store);
        isTrue(missing.status() >= 400, fixture,
                "a HEAD of an unpublished path answers a client error (was " + missing.status() + ")");
    }

    private static EcosystemFormatFixture.Packaged published(EcosystemFormatFixture fixture, ArtifactStore store)
            throws IOException {
        return fixture.publishPackage(store).orElseThrow(() -> new AssertionError(fixture.format()
                + ": this fixture excluded a kit publish leg but supplies no packaged artifact, so the property is "
                + "asserted nowhere. Either publish a real package here, or stop excluding the kit's leg."));
    }

    private static void equal(Object actual, Object expected, EcosystemFormatFixture fixture, String what) {
        if (!Objects.deepEquals(actual, expected)) {
            throw new AssertionError(fixture.format() + ": " + what
                    + " - expected " + render(expected) + " but was " + render(actual));
        }
    }

    private static void isTrue(boolean actual, EcosystemFormatFixture fixture, String what) {
        if (!actual) {
            throw new AssertionError(fixture.format() + ": " + what);
        }
    }

    private static String render(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.length + " bytes " + HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 32));
        }
        return String.valueOf(value);
    }
}
