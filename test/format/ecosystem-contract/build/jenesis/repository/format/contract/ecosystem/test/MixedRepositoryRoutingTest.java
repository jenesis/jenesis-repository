package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repository that both hosts and proxies, routed as the server routes it: local first, then its upstream. For every
 * format, the listings of a name published here answer from what the repository holds and never ask the upstream - so
 * a version only the upstream has is not listed beside the ones published here - while an artifact only the upstream
 * has still comes through the fallback. That is what makes each format's mixing, which a client then reads its own
 * way, the same decision on every format: the published document answers ahead of the upstream's.
 */
class MixedRepositoryRoutingTest {

    private static final String TENANT = "acme";

    private static final String REPOSITORY = "mixed";

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
        StoredCounter.settle();
    }

    static Stream<Named<EcosystemFormatFixture>> fixtures() {
        return EcosystemFormatFixture.all().stream().map(fixture -> Named.of(fixture.format(), fixture));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void a_listing_published_here_answers_ahead_of_the_upstream_and_an_upstream_artifact_still_comes_through(
            EcosystemFormatFixture fixture) throws Exception {
        ArtifactStore stores = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        ArtifactStore repository = stores.scope(TENANT).scope(REPOSITORY);
        Optional<FormatFixture.Enumerated> enumerated = fixture.enumerated(repository);
        Optional<FormatFixture.Upstream> upstream = fixture.upstream(GeneratedBody.of(4096));
        Assumptions.assumeTrue(enumerated.isPresent() || upstream.isPresent(),
                fixture.format() + " seeds no listing and proxies nothing, so it has no mixed repository");

        Recording fetcher = new Recording(upstream.map(FormatFixture.Upstream::fetcher).orElse(ProxyFormat.Fetcher.NONE),
                new CopyOnWriteArrayList<>());
        URI fallback = upstream.map(FormatFixture.Upstream::root).orElse(URI.create("https://upstream.invalid/"));
        RepositoryRouter router = new RepositoryRouter(
                name -> REPOSITORY.equals(name) ? RepositoryDefinition.parse("writable fallback " + fallback) : null,
                (tenant, name) -> stores.scope(tenant).scope(name), fetcher);

        for (FormatFixture.Probe probe : enumerated.map(FormatFixture.Enumerated::probes).orElse(List.of())) {
            ContractExchange listing = ContractExchange.of("GET", probe.path());
            router.serve(TENANT, REPOSITORY, fixture.serving(), listing);
            assertThat(listing.status()).as("%s: %s answers from what the repository holds", fixture.format(),
                    probe.path()).isEqualTo(200);
            assertThat(new String(listing.responseBytes(), StandardCharsets.UTF_8))
                    .as("%s: and lists what was published here", fixture.format()).contains(probe.token());
        }
        assertThat(fetcher.asked).as("%s: a listing the repository holds is answered ahead of the upstream, which is "
                + "never asked for it", fixture.format()).isEmpty();

        if (upstream.isPresent()) {
            ContractExchange artifact = ContractExchange.of("GET", upstream.get().requestPath());
            router.serve(TENANT, REPOSITORY, fixture.serving(), artifact);
            assertThat(artifact.status()).as("%s: %s, which only the upstream has, comes through the fallback",
                    fixture.format(), upstream.get().requestPath()).isEqualTo(200);
            assertThat(fetcher.asked).as("%s: by asking the upstream", fixture.format()).isNotEmpty();
        }
    }

    /** The upstream, recording every address it is asked for - the artifact and anything read beside it. */
    private record Recording(ProxyFormat.Fetcher delegate, List<URI> asked) implements ProxyFormat.Fetcher {

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
            asked.add(url);
            return delegate.fetch(url, requestHeaders);
        }

        @Override
        public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders)
                throws IOException {
            asked.add(url);
            return delegate.download(url, requestHeaders);
        }

        @Override
        public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
            asked.add(url);
            return delegate.head(url, requestHeaders);
        }

        @Override
        public ProxyFormat.Fetcher beside() {
            return new Recording(delegate.beside(), asked);
        }

        @Override
        public String toString() {
            return "recording " + delegate;
        }
    }
}
