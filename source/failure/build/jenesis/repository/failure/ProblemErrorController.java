package build.jenesis.repository.failure;

import module java.base;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Spring's error controller, answering a program with an RFC 9457 problem document ({@code application/problem+json})
 * - {@code type}, {@code title}, {@code status}, {@code instance} and, for a failure, the {@code reference} - where
 * Boot's own answers its default map. A browser still gets the console's error page, rendered over the same attributes.
 */
public class ProblemErrorController extends BasicErrorController {

    public ProblemErrorController(ErrorAttributes attributes, List<ErrorViewResolver> resolvers) {
        super(attributes, new ErrorProperties(), resolvers);
    }

    @Override
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        HttpStatus status = getStatus(request);
        if (status == HttpStatus.NO_CONTENT) {
            return new ResponseEntity<>(status);
        }
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        Map<String, Object> attributes = getErrorAttributes(request, getErrorAttributeOptions(request, MediaType.ALL));
        attributes.forEach((name, value) -> {
            if (!name.equals("error")) {
                problem.put(name, value);
            }
        });
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
