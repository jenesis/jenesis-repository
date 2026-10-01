package build.jenesis.repository.application;

import module java.base;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.store.ReadOnlyException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers a write refused because the deployment is read-only with {@code 403}, whichever controller attempted it:
 * {@link ReadOnlyArtifactStore} raises {@link ReadOnlyException} before any byte is stored, so no controller has to
 * remember the mode. It covers {@code @RestController}s only; a console screen renders the refusal itself.
 */
@RestControllerAdvice(annotations = RestController.class)
public class ReadOnlyAdvice {

    @ExceptionHandler(ReadOnlyException.class)
    public void readOnly(ReadOnlyException exception, HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            response.sendError(403, exception.getMessage());
        }
    }
}
