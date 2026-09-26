package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every format's exporter, in process: a version published through the format into one store is exported into a
 * second through a target that hands each request to the same format, as a second deployment would, and the second
 * store then serves the same bytes at the same path.
 *
 * <p>The real clients prove the protocols against a second live deployment in the ecosystem matrix; this holds each
 * exporter's mapping - which requests it sends and where - in the hermetic lane, where a change to it is seen first.
 */
class ExportRoundTripTest {

    @TempDir
    Path root;

    @TestFactory
    Stream<DynamicTest> every_exporter_publishes_what_its_format_serves_into_a_second_repository() {
        return EcosystemFormatFixture.all().stream()
                .filter(fixture -> fixture.serving() instanceof RepositoryExporter)
                .map(fixture -> DynamicTest.dynamicTest(fixture.format(), () -> roundTrip(fixture)));
    }

    private void roundTrip(EcosystemFormatFixture fixture) throws Exception {
        RepositoryFormat format = fixture.serving();
        ArtifactStore source = store("source-" + fixture.format()), destination = store("target-" + fixture.format());
        EcosystemFormatFixture.Seeded seeded = fixture.seed(source);

        RepositoryExporter.Exported exported = ((RepositoryExporter) format).export(source, seeded.coordinate(),
                seeded.version(), new InProcessTarget(format, ((RepositoryExporter) format).clientPath(), destination));

        assertThat(exported).as("%s: the export of %s %s", fixture.format(), seeded.coordinate(), seeded.version())
                .isNotEqualTo(RepositoryExporter.Exported.WITHHELD);
        ContractExchange original = get(format, source, seeded.servedPath());
        ContractExchange copy = get(format, destination, seeded.servedPath());
        assertThat(original.status()).as("%s serves %s", fixture.format(), seeded.servedPath()).isEqualTo(200);
        assertThat(copy.status()).as("%s: the second repository serves %s", fixture.format(), seeded.servedPath())
                .isEqualTo(200);
        assertThat(copy.responseSha256()).as("%s: the bytes at %s", fixture.format(), seeded.servedPath())
                .isEqualTo(original.responseSha256());
    }

    /** A second repository of the same format, reached in process: each request is the format's own. */
    private record InProcessTarget(RepositoryFormat format, String clientPath, ArtifactStore store)
            implements ExportTarget {

        @Override
        public Response send(Request request) throws IOException {
            String path = request.path(), query = null;
            int question = path.indexOf('?');
            if (question >= 0) {
                query = path.substring(question + 1);
                path = path.substring(0, question);
            }
            byte[] body;
            try (InputStream in = request.body().open()) {
                body = in.readAllBytes();
            }
            ContractExchange exchange = ContractExchange.of(request.method(), formatPath(path), body);
            for (Map.Entry<String, String> header : request.headers().entrySet()) {
                exchange = exchange.header(header.getKey(), header.getValue());
            }
            if (query != null) {
                for (String parameter : query.split("&")) {
                    int equals = parameter.indexOf('=');
                    exchange = exchange.query(URLDecoder.decode(equals < 0 ? parameter : parameter.substring(0, equals),
                            StandardCharsets.UTF_8), equals < 0 ? "" : URLDecoder.decode(
                            parameter.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
            format.handle(exchange, store);
            return new Response(exchange.status(), exchange.responseText());
        }

        @Override
        public Optional<String> sha256(String path) throws IOException {
            ContractExchange exchange = ContractExchange.of("GET", formatPath(path));
            format.handle(exchange, store);
            return exchange.status() == 200 ? Optional.of(exchange.responseSha256()) : Optional.empty();
        }

        @Override
        public Optional<Credential> credential() {
            return Optional.empty();
        }

        /** Where a path a client names under the repository URL plus the client path lands, as the format sees it. */
        private String formatPath(String path) {
            String relative = (clientPath.endsWith("/") ? clientPath : clientPath + "/") + path;
            return RepositoryType.of(format.name(), List.of(format)).orElseThrow().formatPath(relative);
        }
    }

    private static ContractExchange get(RepositoryFormat format, ArtifactStore store, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        format.handle(exchange, store);
        return exchange;
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
