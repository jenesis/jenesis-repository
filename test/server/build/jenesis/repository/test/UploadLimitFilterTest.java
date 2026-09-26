package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.UploadLimitFilter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One request's body is bounded: a declared length past the bound is refused before it is read, a body streaming
 * past it without declaring one is refused at the byte that crosses it, whatever the handler made of the failed
 * read, and a request under it - or with the bound lifted - passes untouched.
 */
class UploadLimitFilterTest {

    private static final long BOUND = 1024;

    @Test
    void a_declared_length_past_the_bound_is_refused_before_anything_is_read() throws Exception {
        Outcome outcome = upload("PUT", 4096, 4096, BOUND);

        assertThat(outcome.reached).as("the handler never ran").isFalse();
        verify(outcome.response).sendError(eq(413), anyString());
    }

    @Test
    void a_body_streaming_past_the_bound_is_refused_at_the_byte_that_crosses_it() throws Exception {
        Outcome outcome = upload("POST", -1, 4096, BOUND);

        assertThat(outcome.reached).isTrue();
        assertThat(outcome.read).as("the handler read no further than the bound").isLessThanOrEqualTo(BOUND);
        verify(outcome.response).sendError(eq(413), anyString());
    }

    @Test
    void a_body_under_the_bound_and_one_with_the_bound_lifted_pass() throws Exception {
        Outcome under = upload("PUT", 512, 512, BOUND);
        assertThat(under.read).isEqualTo(512);
        verify(under.response, never()).sendError(anyInt(), anyString());

        Outcome lifted = upload("PUT", 4096, 4096, 0);
        assertThat(lifted.read).isEqualTo(4096);
        verify(lifted.response, never()).sendError(anyInt(), anyString());
    }

    @Test
    void the_setting_reads_as_the_default_when_unset_or_unreadable() {
        assertThat(UploadLimitFilter.live(_ -> null).getAsLong())
                .isEqualTo(Long.parseLong(UploadLimitFilter.DEFAULT_TEXT));
        assertThat(UploadLimitFilter.live(_ -> "not a number").getAsLong())
                .isEqualTo(Long.parseLong(UploadLimitFilter.DEFAULT_TEXT));
        assertThat(UploadLimitFilter.live(_ -> "0").getAsLong()).as("0 lifts the bound").isZero();
    }

    private record Outcome(HttpServletResponse response, boolean reached, long read) {
    }

    /** Send {@code sent} bytes declaring {@code declared} (or none, when negative) through the filter to a handler
     *  that reads the whole body and, like most, answers a failed read by failing. */
    private static Outcome upload(String method, long declared, int sent, long bound) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getContentLengthLong()).thenReturn(declared);
        ByteArrayInputStream bytes = new ByteArrayInputStream(new byte[sent]);
        when(request.getInputStream()).thenReturn(new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return bytes.read();
            }

            @Override
            public boolean isFinished() {
                return bytes.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
            }
        });
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicBoolean reached = new AtomicBoolean();
        AtomicLong read = new AtomicLong();
        new UploadLimitFilter(() -> bound).doFilter(request, response, (servletRequest, _) -> {
            reached.set(true);
            InputStream body = servletRequest.getInputStream();
            byte[] buffer = new byte[100];
            for (int count; (count = body.read(buffer)) > 0; ) {
                read.addAndGet(count);
            }
        });
        return new Outcome(response, reached.get(), read.get());
    }
}
