package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.kernel.RequestBodyLimitFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The streaming cut-off of {@link RequestBodyLimitFilter}: a declared {@code Content-Length} over the cap is rejected
 * {@code 413} before the body is read (proven end-to-end by {@code RequestBodyLimitE2ETest}), but a chunked request
 * (no {@code Content-Length}) or a lying, understated {@code Content-Length} slips past that early check - so the
 * byte-counting wrapper on the request input stream is the <em>only</em> remaining defense. It must throw the moment
 * the body runs past the cap, so nothing beyond the cap is ever buffered; without it an anonymous caller could stream
 * an unbounded body into memory through {@code POST /api/leaked} or {@code /api/token}. Driven over reflective servlet
 * stubs (mirroring the sibling filter tests), no server.
 */
class RequestBodyLimitFilterTest {

    @Test
    void a_chunked_over_cap_body_is_cut_off_mid_stream() throws Exception {
        // Content-Length -1 (a chunked request) is never > cap, so it passes the declared-length 413 check untouched.
        assertCutOff(-1L);
    }

    @Test
    void an_understated_content_length_over_cap_body_is_cut_off_mid_stream() throws Exception {
        // A body that lies about its length (declares 4, sends 64) also passes the declared-length check, so only the
        // streaming counter stops it.
        assertCutOff(4L);
    }

    private static void assertCutOff(long declaredLength) throws Exception {
        long cap = 8;
        byte[] oversized = new byte[64];   // a real body far over the 8-byte cap
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        FilterChain chain = (req, resp) -> {
            try {
                ((HttpServletRequest) req).getInputStream().readAllBytes();
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        };

        new RequestBodyLimitFilter(cap).doFilter(
                request("POST", "/api/leaked", declaredLength, oversized), response(), chain);

        assertThat(thrown.get())
                .as("the wrapped stream cuts the over-cap body off mid-read rather than buffering it whole")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds");
    }

    private static HttpServletRequest request(String method, String uri, long contentLength, byte[] body) {
        Map<String, Object> attributes = new HashMap<>();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn(method);
        when(request.getContentLengthLong()).thenReturn(contentLength);
        try {
            when(request.getInputStream()).thenReturn(servletInputStream(body));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        when(request.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        doAnswer(invocation -> {
            attributes.remove(invocation.getArgument(0));
            return null;
        }).when(request).removeAttribute(anyString());
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.isAsyncStarted()).thenReturn(false);
        return request;
    }

    private static ServletInputStream servletInputStream(byte[] body) {
        InputStream backing = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return backing.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return backing.read(buffer, offset, length);
            }

            @Override
            public boolean isFinished() {
                try {
                    return backing.available() == 0;
                } catch (IOException absent) {
                    return true;
                }
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
            }
        };
    }

    private static HttpServletResponse response() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);
        when(response.getStatus()).thenReturn(200);
        return response;
    }
}
