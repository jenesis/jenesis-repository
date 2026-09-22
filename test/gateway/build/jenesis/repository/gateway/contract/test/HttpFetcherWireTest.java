package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.HttpFetcher;
import build.jenesis.repository.proxy.HttpFetcherProvider;
import build.jenesis.repository.gateway.testkit.LoopbackUpstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The upstream-wire misbehaviour matrix, driving the real {@link HttpFetcher} (and the caching stack it composes into
 * through {@link HttpFetcherProvider}) against the {@link LoopbackUpstream} on a real socket - the one wire the proxy
 * tests otherwise faked with an in-memory {@code Fetcher} map. It pins the fetcher's behaviour on the misbehaviours a
 * pull-through proxy has to survive: conditional revalidation ({@code ETag}/{@code If-None-Match} → {@code 304}),
 * range requests ({@code 206}/{@code 416}), redirects and a bounded redirect loop, negative caching of a definite
 * {@code 404} but not a transient {@code 5xx} (which recovers), a truncated body that must not be accepted, and a
 * stalled upstream clipped by the per-request timeout into the contract's transport failure. Always runs (loopback,
 * no tool, no registry).
 */
public class HttpFetcherWireTest {

    private static final byte[] BLOB = blob(4096);
    private static final byte[] INDEX = "{\"name\":\"acme\",\"versions\":[\"1.0.0\"]}".getBytes(StandardCharsets.UTF_8);

    @Test
    public void a_conditional_revalidation_serves_304_as_the_remembered_body() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            upstream.serveEtag("/index.json", "\"v1\"", "application/json", INDEX);
            ProxyFormat.Fetcher fetcher = new HttpFetcherProvider().create(key -> null).orElseThrow();
            URI url = upstream.base().resolve("/index.json");

            ProxyFormat.Fetched first = fetcher.fetch(url, Map.of()).orElseThrow();
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).isEqualTo(INDEX);

            ProxyFormat.Fetched second = fetcher.fetch(url, Map.of()).orElseThrow();
            assertThat(second.status()).as("a 304 is presented as the remembered body, not empty").isEqualTo(200);
            assertThat(second.body()).isEqualTo(INDEX);
            assertThat(upstream.total()).as("the upstream is still asked - only the transfer is saved").isEqualTo(2);
        }
    }

    @Test
    public void a_range_request_is_answered_206_and_past_the_end_416() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            upstream.serveRange("/blob.bin", "application/octet-stream", BLOB);
            HttpFetcher fetcher = new HttpFetcher();
            URI url = upstream.base().resolve("/blob.bin");

            ProxyFormat.Fetched partial = fetcher.fetch(url, Map.of("Range", "bytes=0-9")).orElseThrow();
            assertThat(partial.status()).isEqualTo(206);
            assertThat(partial.body()).isEqualTo(Arrays.copyOfRange(BLOB, 0, 10));
            assertThat(partial.header("Content-Range")).isEqualTo("bytes 0-9/" + BLOB.length);

            ProxyFormat.Fetched unsatisfiable = fetcher.fetch(url, Map.of("Range", "bytes=99999-")).orElseThrow();
            assertThat(unsatisfiable.status()).as("a range past the end is 416").isEqualTo(416);
        }
    }

    @Test
    public void a_redirect_is_followed_to_the_final_body() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            upstream.serve("/final.bin", 200, "application/octet-stream", BLOB);
            upstream.redirect("/moved.bin", "/final.bin", 302);
            // 0.7.0 HttpFetcher screens redirect targets for SSRF (loopback/private hosts refused). Drive the
            // redirect-follow behaviour against the loopback LoopbackUpstream with the permissive screen the core
            // documents for exactly this fixture (HttpFetcher(Duration, Predicate) - a screen that never blocks).
            HttpFetcher fetcher = new HttpFetcher(Duration.ofMinutes(1), host -> false);

            ProxyFormat.Fetched followed = fetcher.fetch(upstream.base().resolve("/moved.bin"), Map.of()).orElseThrow();
            assertThat(followed.status()).isEqualTo(200);
            assertThat(followed.body()).isEqualTo(BLOB);
            assertThat(upstream.hits("/final.bin")).as("the redirect target was reached").isEqualTo(1);
        }
    }

    @Test
    public void a_redirect_loop_is_bounded_not_hung() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            upstream.redirect("/a", "/b", 302);
            upstream.redirect("/b", "/a", 302);
            HttpFetcher fetcher = new HttpFetcher();

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                try {
                    ProxyFormat.Fetched result = fetcher.fetch(upstream.base().resolve("/a"), Map.of()).orElseThrow();
                    assertThat(result.status())
                            .as("the client caps the redirect chain and hands back the last hop rather than looping")
                            .isBetween(300, 399);
                } catch (IOException tooManyRedirects) {
                    // Equally acceptable: the client refuses the loop outright. Either way it terminated, not hung.
                }
            });
        }
    }

    @Test
    public void a_definite_404_is_negatively_cached() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            ProxyFormat.Fetcher fetcher = new HttpFetcherProvider().create(key -> null).orElseThrow();
            URI url = upstream.base().resolve("/never-there");

            assertThat(fetcher.fetch(url, Map.of()).orElseThrow().status()).isEqualTo(404);
            assertThat(fetcher.fetch(url, Map.of()).orElseThrow().status()).isEqualTo(404);
            assertThat(upstream.total()).as("the second 404 is answered from the negative cache, not the socket")
                    .isEqualTo(1);
        }
    }

    @Test
    public void a_transient_5xx_is_not_cached_and_recovers() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            upstream.flaky("/flaky.json", 1, 503, "application/json", INDEX);
            ProxyFormat.Fetcher fetcher = new HttpFetcherProvider().create(key -> null).orElseThrow();
            URI url = upstream.base().resolve("/flaky.json");

            assertThat(fetcher.fetch(url, Map.of()).orElseThrow().status()).as("first attempt fails").isEqualTo(503);
            ProxyFormat.Fetched recovered = fetcher.fetch(url, Map.of()).orElseThrow();
            assertThat(recovered.status()).as("a 5xx is not negatively cached - the retry reaches the recovered upstream")
                    .isEqualTo(200);
            assertThat(recovered.body()).isEqualTo(INDEX);
        }
    }

    @Test
    public void a_checksum_sibling_is_fetched_independently() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            byte[] sha1 = "e5c02c...".getBytes(StandardCharsets.UTF_8);
            upstream.serve("/lib-1.0.jar", 200, "application/java-archive", BLOB);
            upstream.serve("/lib-1.0.jar.sha1", 200, "text/plain", sha1);
            HttpFetcher fetcher = new HttpFetcher();

            assertThat(fetcher.fetch(upstream.base().resolve("/lib-1.0.jar"), Map.of()).orElseThrow().body())
                    .isEqualTo(BLOB);
            assertThat(fetcher.fetch(upstream.base().resolve("/lib-1.0.jar.sha1"), Map.of()).orElseThrow().body())
                    .isEqualTo(sha1);
            assertThat(fetcher.fetch(upstream.base().resolve("/lib-1.0.jar.md5"), Map.of()).orElseThrow().status())
                    .as("an absent checksum sibling is a plain 404 the format tolerates").isEqualTo(404);
        }
    }

    @Test
    public void a_truncated_body_is_rejected_not_returned_whole() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            upstream.truncated("/short.bin", BLOB.length, Arrays.copyOfRange(BLOB, 0, 512), "application/octet-stream");
            HttpFetcher fetcher = new HttpFetcher();
            URI url = upstream.base().resolve("/short.bin");

            assertThatThrownBy(() -> fetcher.fetch(url, Map.of()))
                    .as("a buffered fetch rejects a truncated upstream body").isInstanceOf(IOException.class);

            assertThatThrownBy(() -> {
                try (ProxyFormat.Download download = fetcher.download(url, Map.of()).orElseThrow()) {
                    download.body().readAllBytes();
                }
            }).as("a streamed body that ends abruptly throws before it can be linked as a whole artifact")
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    public void a_stalled_upstream_times_out_as_a_transport_failure() throws IOException {
        try (LoopbackUpstream upstream = LoopbackUpstream.start()) {
            upstream.stall("/hang");
            HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(1));
            URI url = upstream.base().resolve("/hang");

            assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
                    assertThat(fetcher.fetch(url, Map.of()))
                            .as("a stalled upstream is clipped by the request timeout and reported as a transport failure")
                            .isEmpty());
        }
    }

    private static byte[] blob(int length) {
        byte[] blob = new byte[length];
        for (int i = 0; i < length; i++) {
            blob[i] = (byte) (i * 17 + 3);
        }
        return blob;
    }
}
