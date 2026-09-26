package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.Retries;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A write whose compare-and-set lost every try to peers on the same document is answered {@code 503} with a
 * {@code Retry-After}, never a {@code 500}: nothing is wrong with the write or the store, and sent again a moment later
 * it lands. The controller is driven directly, as {@link RouteWritableTest} drives it, over a format whose write meets
 * the exhaustion - so what is held is that the exhaustion reaches the controller's edge as itself, and that the edge
 * answers it as the transient refusal it is.
 */
class ContendedWriteTest {

    @TempDir
    Path root;

    /** A format whose every write loses its compare-and-set as a burst of peers would make it. */
    private static final class Contended implements RepositoryFormat {

        @Override
        public String name() {
            return "contended";
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
            throw new Retries.Contended("meta/contended/document");
        }
    }

    private record FixedRoute(RepositoryRouting.Route route) implements RepositoryRouting {
        @Override
        public Route route(HttpServletRequest request) {
            return route;
        }
    }

    @Test
    void a_write_that_loses_every_compare_and_set_is_answered_503_with_a_retry_after() throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("default");
        new RepositoryDocument("contended", Instant.now()).create(store);
        RepositoryController controller = new RepositoryController(
                new FixedRoute(new RepositoryRouting.Route("default", "default", store, "/x", true)),
                new FormatDispatcher(List.of(new Contended()), Map.of(), ProxyFormat.Fetcher.NONE),
                List.of(), ProxyFormat.Fetcher.NONE);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("PUT");

        Retries.Contended thrown = catchThrowableOfType(Retries.Contended.class,
                () -> controller.handle(request, mock(HttpServletResponse.class)));
        assertThat(thrown).as("the exhaustion reaches the edge as itself, not wrapped into a server error")
                .isNotNull();

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
        Method handler = RepositoryController.class.getMethod("contended", Retries.Contended.class,
                HttpServletResponse.class);
        assertThat(handler.getAnnotation(ExceptionHandler.class).value())
                .as("the edge's handler for it is registered").containsExactly(Retries.Contended.class);
        controller.contended(thrown, response);

        verify(response).setStatus(503);
        verify(response).setHeader("Retry-After", "1");
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("send it again");
    }
}
