package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every format's served artifact is the one the publish screen assessed, through every request shape its protocol
 * publishes in.
 *
 * <p>{@link RepositoryFormat#screened()} is a declaration about the protocol, and this census holds each format to it
 * by what a publish does rather than by what the format says of itself:
 * <ul>
 *   <li><b>{@code true}</b> means the ingress edge stores and screens the request body before the format lays it out,
 *       so the claim is only sound when the request body <em>is</em> the artifact. The census records every write
 *       the publish sent and requires the bytes the published path then serves to be one of those bodies, byte for
 *       byte. A format whose request wraps its artifact - a multipart form, a JSON document carrying it base64'd, a
 *       length-prefixed frame - fails here, because the edge would assess the envelope while clients download a
 *       second object no screen ever saw.</li>
 *   <li><b>{@code false}</b> means the format unwraps its request and drives the shared commit with the discovered
 *       screen itself. The census records every content hash the discovered chain assessed during the publish and
 *       requires the served bytes to be among them - so a format that opts out and then screens nothing, or screens
 *       something other than what it serves, fails too.</li>
 * </ul>
 * One publish per shape: {@link EcosystemFormatFixture#publishPackage} or the kit's opaque publish, and every
 * {@link EcosystemFormatFixture#otherShapes() further shape} the fixture declares, since a protocol that accepts the
 * bare artifact and a form around it can screen one and not the other.
 */
class ScreenedArtifactCensusTest {

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
    }

    static Stream<Arguments> shapes() {
        return EcosystemFormatFixture.all().stream().flatMap(fixture -> Stream.concat(
                Stream.of(Arguments.of(fixture.format(), fixture, (EcosystemFormatFixture.Publisher) store ->
                        ScreenedArtifactCensusTest.primary(fixture, store))),
                fixture.otherShapes().stream().map(shape -> Arguments.of(fixture.format() + " (" + shape.name()
                        + ")", fixture, shape.publisher()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void the_bytes_a_publish_serves_are_the_bytes_the_screen_assessed(String shape, EcosystemFormatFixture fixture,
                                                                       EcosystemFormatFixture.Publisher publisher)
            throws Exception {
        Path directory = Files.createDirectories(root.resolve(shape.replaceAll("[^A-Za-z0-9]", "_")));
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? directory.toString() : null);

        ContractExchange.recordWrites();
        ContractHoldInterceptor.recordAssessed();
        EcosystemFormatFixture.Packaged published;
        List<ContractExchange.Write> writes;
        List<String> assessed;
        try {
            published = publisher.publish(store);
        } finally {
            writes = ContractExchange.recordedWriteRequests();
            assessed = ContractHoldInterceptor.recordedAssessed();
        }
        ContractExchange get = ContractExchange.of("GET", published.servedPath()).settings(fixture::setting);
        fixture.serving().handle(get, store);
        assertThat(get.status()).as("%s serves what it published at %s", shape, published.servedPath())
                .isEqualTo(200);
        String served = get.responseSha256();

        if (fixture.serving().screened()) {
            List<String> bodies = new ArrayList<>();
            for (ContractExchange.Write write : writes) {
                bodies.add(write.sha256());
            }
            assertThat(bodies).as("%s declares screened() true, so the ingress edge screens the request body it is "
                    + "sent - and that is only the artifact when the body IS the artifact. No request this publish "
                    + "sent (%s) carried the bytes %s serves, so the edge would have assessed an envelope while "
                    + "clients download an object no screen saw. Unwrap the request and drive the shared commit "
                    + "with the discovered screen over the artifact itself, declaring screened() false, as PyPI does",
                    shape, writes.stream().map(ContractExchange.Write::path).toList(), published.servedPath())
                    .contains(served);
        } else {
            assertThat(assessed).as("%s declares screened() false, so it screens at its own choke point - and the "
                    + "bytes %s serves must be among those the discovered screen assessed during the publish",
                    shape, published.servedPath())
                    .contains(served);
        }
    }

    /** The release the fixture's own publish makes: a real package where the protocol parses one, else the kit's
     *  opaque body. */
    private static EcosystemFormatFixture.Packaged primary(EcosystemFormatFixture fixture, ArtifactStore store)
            throws IOException {
        Optional<EcosystemFormatFixture.Packaged> packaged = fixture.publishPackage(store);
        if (packaged.isPresent()) {
            return packaged.get();
        }
        byte[] body = "the artifact the census publishes".getBytes(StandardCharsets.UTF_8);
        FormatFixture.Published published = fixture.publish(store, body);
        return new EcosystemFormatFixture.Packaged(body, published.servedPath(), published.contentHash());
    }
}
