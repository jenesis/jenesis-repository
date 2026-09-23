package build.jenesis.repository.application;

import module java.base;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.store.ReadOnlyException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a write refused because the deployment is read-only to HTTP {@code 403 Forbidden}, globally: the
 * {@link ReadOnlyArtifactStore} choke point raises {@link ReadOnlyException} from whatever endpoint attempted the
 * mutation - a publish on {@code DeployController}, an import, a promotion on the staging controller, a credential or
 * settings edit on a management / config controller in another module - and this one advice answers them all with a
 * clear {@code 403}, so no per-controller guard has to remember the mode (the quota {@code 507} handler stays
 * per-controller because only the publish path meters bytes; read-only cuts across every write). The store refuses
 * the write before any bytes are stored, so the response is never committed when this runs.
 */
@RestControllerAdvice
public class ReadOnlyAdvice {

    @ExceptionHandler(ReadOnlyException.class)
    public void readOnly(ReadOnlyException exception, HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            response.sendError(403, exception.getMessage());
        }
    }
}
