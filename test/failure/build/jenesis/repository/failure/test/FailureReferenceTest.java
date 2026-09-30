package build.jenesis.repository.failure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.failure.Failures;
import build.jenesis.repository.failure.ProblemErrorController;
import build.jenesis.repository.failure.ReferencedErrorAttributes;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A failure the server did not mean is logged once, whole, under a reference, and the answer carries the reference
 * and nothing else of it: not the message a planted exception carries, not its class, not a path it names.
 */
class FailureReferenceTest {

    /** What the planted failure says, which no answer may repeat. */
    private static final String INSIDES = "cannot read /data/default/secret-repo/publish/internal.key";

    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void capture() {
        log = new ListAppender<>();
        log.start();
        ((Logger) LoggerFactory.getLogger(Failures.class)).addAppender(log);
    }

    @AfterEach
    void release() {
        ((Logger) LoggerFactory.getLogger(Failures.class)).detachAppender(log);
    }

    @Test
    void a_failure_is_logged_once_with_its_trace_under_the_reference_it_answers() {
        String reference = Failures.record("GET /api/example", new IOException(INSIDES));

        assertThat(reference).matches("[0-9a-hjkmnp-tv-z]{12}");
        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains(reference).contains("GET /api/example");
            assertThat(event.getThrowableProxy()).as("the whole failure, trace included, is in the log")
                    .isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo(INSIDES);
        });
        assertThat(Failures.record("GET /api/example", new IOException(INSIDES)))
                .as("a reference is random, never derived from the request").isNotEqualTo(reference);
    }

    @Test
    void the_problem_document_carries_the_reference_and_none_of_the_failure() {
        HttpServletRequest request = failedRequest(500, new IllegalStateException(INSIDES));
        ProblemErrorController controller = new ProblemErrorController(new ReferencedErrorAttributes(), List.of());

        ResponseEntity<Map<String, Object>> answer = controller.error(request);

        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        assertThat(answer.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(answer.getBody()).containsEntry("title", Failures.MESSAGE).containsEntry("status", 500)
                .containsKey("reference");
        assertThat(answer.getBody().toString()).doesNotContain(INSIDES).doesNotContain("IllegalStateException")
                .doesNotContain("secret-repo");
        assertThat(log.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains((String) answer.getBody().get("reference"));
    }

    @Test
    void a_refusal_the_product_meant_carries_no_reference_and_logs_nothing() {
        HttpServletRequest request = failedRequest(404, null);

        ResponseEntity<Map<String, Object>> answer = new ProblemErrorController(new ReferencedErrorAttributes(),
                List.of()).error(request);

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(answer.getBody()).containsEntry("title", "Not Found").doesNotContainKey("reference");
        assertThat(log.list).isEmpty();
    }

    /** A request as Spring's error dispatch sees it: the status, the exception and the path the container recorded,
     *  and request attributes that hold what is set on them. */
    private static HttpServletRequest failedRequest(int status, Throwable failure) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RequestDispatcher.ERROR_STATUS_CODE, status);
        attributes.put(RequestDispatcher.ERROR_REQUEST_URI, "/api/example");
        if (failure != null) {
            attributes.put(RequestDispatcher.ERROR_EXCEPTION, failure);
        }
        when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.<String>getArgument(0)));
        doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1))).when(request)
                .setAttribute(anyString(), org.mockito.ArgumentMatchers.any());
        when(request.getMethod()).thenReturn("GET");
        return request;
    }
}
