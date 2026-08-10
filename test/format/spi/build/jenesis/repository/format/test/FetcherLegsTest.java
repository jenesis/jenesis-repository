package build.jenesis.repository.format.test;

import build.jenesis.repository.format.ProxyFormat;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of {@link ProxyFormat.Fetcher} (D-055): the streaming leg and the metadata leg are <b>declared</b>, never
 * inherited, and the derivations that violate the streaming clause live on a named type a class has to write down.
 *
 * <p>The defect this pins was not that the derivations existed - a scripted upstream genuinely has nothing but a small
 * in-memory document - but that they were {@code default} methods on the interface that <em>declares</em> the streaming
 * clause. A real transport received them for saying nothing, and a decorator received them for saying nothing, which
 * is worse: it discards the streaming download and the real {@code HEAD} of the fetcher it wraps and substitutes
 * buffered derivations, so a deployment's streaming path collapses without a line of its own code admitting it. The
 * decision therefore lives in the type, and this is the ratchet that keeps it there - re-adding a {@code default} to
 * {@code Fetcher} would silently restore exactly the inheritance that was removed.
 */
class FetcherLegsTest {

    private static Method leg(Class<?> type, String name) throws NoSuchMethodException {
        return type.getMethod(name, URI.class, Map.class);
    }

    @Test
    void all_three_fetcher_legs_are_abstract_so_a_transport_declares_each_of_them() throws Exception {
        for (String leg : List.of("fetch", "download", "head")) {
            assertThat(leg(ProxyFormat.Fetcher.class, leg).isDefault())
                    .as("ProxyFormat.Fetcher.%s must be abstract: a default is how a transport - or, worse, a "
                            + "decorator over one - acquires a buffered derivation by saying nothing", leg)
                    .isFalse();
        }
    }

    @Test
    void the_derivations_live_on_the_named_buffered_type_which_stays_a_one_method_interface() throws Exception {
        assertThat(ProxyFormat.Fetcher.class).isAssignableFrom(ProxyFormat.Fetcher.Buffered.class);
        assertThat(leg(ProxyFormat.Fetcher.Buffered.class, "download").isDefault()).isTrue();
        assertThat(leg(ProxyFormat.Fetcher.Buffered.class, "head").isDefault()).isTrue();
        assertThat(leg(ProxyFormat.Fetcher.Buffered.class, "fetch").isDefault())
                .as("fetch is the one leg a Buffered fetcher really answers")
                .isFalse();
    }

    @Test
    void a_buffered_fetcher_derives_both_other_legs_from_its_one_answer() throws Exception {
        byte[] document = "{\"index\":true}".getBytes(StandardCharsets.UTF_8);
        ProxyFormat.Fetcher.Buffered buffered = (url, headers) ->
                Optional.of(new ProxyFormat.Fetched(200, document, Map.of("Content-Type", "application/json")));
        URI url = URI.create("https://upstream.example/index.json");

        try (ProxyFormat.Download download = buffered.download(url, Map.of()).orElseThrow()) {
            assertThat(download.status()).isEqualTo(200);
            assertThat(download.body().readAllBytes()).isEqualTo(document);
        }
        ProxyFormat.Head head = buffered.head(url, Map.of()).orElseThrow();
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.header("content-type")).isEqualTo("application/json");
    }

    @Test
    void the_absent_fetcher_answers_every_leg_itself_rather_than_deriving_one_from_another() throws Exception {
        // NONE used to be a lambda, so its head() opened a download that materialised a fetch to discover there was no
        // upstream. It answers each leg directly now: the sentinel for "no upstream connectivity" must not have to
        // start a body transfer to say so.
        assertThat(ProxyFormat.Fetcher.NONE).isNotInstanceOf(ProxyFormat.Fetcher.Buffered.class);
        URI url = URI.create("https://upstream.example/artifact.jar");
        assertThat(ProxyFormat.Fetcher.NONE.fetch(url, Map.of())).isEmpty();
        assertThat(ProxyFormat.Fetcher.NONE.download(url, Map.of())).isEmpty();
        assertThat(ProxyFormat.Fetcher.NONE.head(url, Map.of())).isEmpty();

        for (String leg : List.of("fetch", "download", "head")) {
            assertThat(leg(ProxyFormat.Fetcher.NONE.getClass(), leg).getDeclaringClass())
                    .as("NONE declares %s itself", leg)
                    .isNotEqualTo(ProxyFormat.Fetcher.class);
        }
    }

    @Test
    void a_decorator_that_forwards_only_fetch_no_longer_compiles_into_a_fetcher() throws Exception {
        // The compile-time half of the fix cannot be asserted at runtime, so this states what it buys: a wrapper must
        // hand back the transport's own legs. A wrapper that delegates all three keeps them; the buffered derivation
        // is what it would have got for free before, and it is measurably not the same object.
        AtomicInteger streamed = new AtomicInteger();
        ProxyFormat.Fetcher transport = new ProxyFormat.Fetcher() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                throw new AssertionError("the streaming leg must not route through the buffered one");
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) {
                streamed.incrementAndGet();
                return Optional.of(new ProxyFormat.Download(200, InputStream.nullInputStream(), Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) {
                return Optional.of(new ProxyFormat.Head(200, Map.of("Content-Length", "4096")));
            }
        };
        ProxyFormat.Fetcher decorator = new ProxyFormat.Fetcher() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) throws IOException {
                return transport.fetch(url, headers);
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) throws IOException {
                return transport.download(url, headers);
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) throws IOException {
                return transport.head(url, headers);
            }
        };

        URI url = URI.create("https://upstream.example/artifact.jar");
        assertThat(decorator.head(url, Map.of()).orElseThrow().header("Content-Length")).isEqualTo("4096");
        assertThat(streamed).as("answering HEAD opened no body").hasValue(0);
        decorator.download(url, Map.of()).orElseThrow().close();
        assertThat(streamed).hasValue(1);
    }
}
