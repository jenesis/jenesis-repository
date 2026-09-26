package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.CrossSiteWriteFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A write a browser sends from another site is refused, and nothing a build tool sends is: the tool carries neither
 * {@code Sec-Fetch-Site} nor {@code Origin}, so it passes whatever it presents.
 */
class CrossSiteWriteFilterTest {

    @Test
    void a_browser_write_from_another_site_is_refused() throws Exception {
        assertThat(refused("POST", Map.of("Sec-Fetch-Site", "cross-site"))).isTrue();
        assertThat(refused("POST", Map.of("Origin", "https://evil.example", "Host", "repo.example")))
                .as("an older browser, known by its Origin").isTrue();
        assertThat(refused("PUT", Map.of("Origin", "null", "Host", "repo.example")))
                .as("an opaque origin is not this host").isTrue();
    }

    @Test
    void a_write_from_this_site_or_from_a_client_passes() throws Exception {
        assertThat(refused("POST", Map.of())).as("a build tool sends neither header").isFalse();
        assertThat(refused("POST", Map.of("Sec-Fetch-Site", "same-origin"))).isFalse();
        assertThat(refused("POST", Map.of("Origin", "https://repo.example", "Host", "repo.example"))).isFalse();
        assertThat(refused("POST", Map.of("Origin", "https://repo.example", "Host", "10.0.0.5:8080",
                "X-Forwarded-Host", "repo.example"))).as("the host a proxy in front addressed").isFalse();
        assertThat(refused("POST", Map.of("Sec-Fetch-Site", "same-site", "Origin", "https://other.example")))
                .as("where Sec-Fetch-Site is present it decides").isFalse();
    }

    @Test
    void a_read_passes_from_anywhere() throws Exception {
        assertThat(refused("GET", Map.of("Sec-Fetch-Site", "cross-site"))).isFalse();
        assertThat(refused("HEAD", Map.of("Origin", "https://evil.example", "Host", "repo.example"))).isFalse();
    }

    /** Whether the filter answered 403 rather than continuing the chain. */
    private static boolean refused(String method, Map<String, String> headers) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        headers.forEach((name, value) -> when(request.getHeader(name)).thenReturn(value));
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicBoolean passed = new AtomicBoolean();
        new CrossSiteWriteFilter().doFilter(request, response, (_, _) -> passed.set(true));
        if (passed.get()) {
            verify(response, never()).sendError(403, "A write from another site is refused");
            return false;
        }
        verify(response).sendError(403, "A write from another site is refused");
        return true;
    }
}
