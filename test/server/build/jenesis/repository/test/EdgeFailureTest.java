package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.failure.Failures;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RepositoryDocument;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A format that fails on a request the server did not mean to refuse is answered by the edge with a sentence and a
 * reference, in the format's own error dialect, and nothing of the failure: the planted exception's message, a store
 * path it names and its class stay in the log, under the reference the answer carries.
 */
class EdgeFailureTest {

    /** What the planted failure says, which the answer may not repeat. */
    private static final String INSIDES = "cannot open /data/default/secret-repo/publish/internal.key";

    @TempDir
    Path root;

    /** A format whose every request fails the way a broken store makes it, answering a failure in its own dialect. */
    private static final class Failing implements RepositoryFormat {

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public boolean handles(String path) {
            return true;
        }

        @Override
        public boolean screened() {
            return false;
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
            throw new IOException(INSIDES);
        }

        @Override
        public void failed(FormatExchange exchange, String sentence) throws IOException {
            exchange.respond(500, ("{\"error\":\"" + sentence + "\"}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private record FixedRoute(RepositoryRouting.Route route) implements RepositoryRouting {
        @Override
        public Route route(HttpServletRequest request) {
            return route;
        }
    }

    @Test
    void a_format_failure_is_answered_with_a_reference_in_the_formats_dialect_and_logged_whole() throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("default");
        new RepositoryDocument("failing", Instant.now()).create(store);
        RepositoryController controller = new RepositoryController(
                new FixedRoute(new RepositoryRouting.Route("default", "default", store, "/x", false)),
                new FormatDispatcher(List.of(new Failing()), Map.of(), ProxyFormat.Fetcher.NONE),
                List.of(), ProxyFormat.Fetcher.NONE);
        HttpServletRequest request = request();
        IOException thrown = catchThrowableOfType(IOException.class,
                () -> controller.handle(request, mock(HttpServletResponse.class)));
        assertThat(thrown).hasMessage(INSIDES);
        assertThat(RepositoryController.class.getMethod("failed", Exception.class, HttpServletRequest.class,
                HttpServletResponse.class).getAnnotation(ExceptionHandler.class).value())
                .as("the edge's catch-all is registered for whatever no narrower handler answers")
                .containsExactly(Exception.class);

        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        ((Logger) LoggerFactory.getLogger(Failures.class)).addAppender(log);
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }

            @Override
            public void write(int one) {
                body.write(one);
            }
        });
        try {
            controller.failed(thrown, request, response);
        } finally {
            ((Logger) LoggerFactory.getLogger(Failures.class)).detachAppender(log);
        }

        verify(response).setStatus(500);
        String answer = body.toString(StandardCharsets.UTF_8);
        assertThat(answer).startsWith("{\"error\":\"" + Failures.MESSAGE).contains("Reference: ")
                .doesNotContain("secret-repo").doesNotContain("IOException").doesNotContain("internal.key");
        String reference = answer.substring(answer.indexOf("Reference: ") + "Reference: ".length(),
                answer.lastIndexOf('"'));
        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains(reference);
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo(INSIDES);
        });
    }

    /** A GET as the edge sees it, whose request attributes hold what is set on them. */
    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> attributes = new HashMap<>();
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/repository/default/default/x");
        when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.<String>getArgument(0)));
        doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1))).when(request)
                .setAttribute(anyString(), any());
        return request;
    }
}
