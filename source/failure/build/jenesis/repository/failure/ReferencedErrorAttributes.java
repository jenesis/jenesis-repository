package build.jenesis.repository.failure;

import module java.base;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * What Spring's error dispatch renders, for every surface: the status, a title a person can read and, for a failure
 * the server did not mean ({@code 5xx}), the reference {@link Failures} logged the whole failure under - never the
 * exception's message, its class or a stack trace, whatever the error properties would otherwise allow.
 *
 * <p>The failure is recorded once per request: the attributes may be asked for more than once while a response is
 * rendered, and a second reference for one failure would send an operator looking for a line that is not there.
 */
public class ReferencedErrorAttributes extends DefaultErrorAttributes {

    /** Where a request's reference is kept once minted, so the attributes asked for twice answer one reference. */
    private static final String REFERENCE = ReferencedErrorAttributes.class.getName() + ".reference";

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        Map<String, Object> defaults = super.getErrorAttributes(request, ErrorAttributeOptions.of(
                ErrorAttributeOptions.Include.STATUS, ErrorAttributeOptions.Include.PATH));
        int status = defaults.get("status") instanceof Integer code ? code : 500;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("status", status);
        if (status >= 500) {
            attributes.put("title", Failures.MESSAGE);
            attributes.put("error", Failures.MESSAGE);
            attributes.put("reference", reference(request, defaults));
        } else {
            HttpStatus known = HttpStatus.resolve(status);
            String title = known == null ? "Error" : known.getReasonPhrase();
            attributes.put("title", title);
            attributes.put("error", title);
        }
        if (defaults.get("path") != null) {
            attributes.put("instance", defaults.get("path"));
        }
        return attributes;
    }

    private String reference(WebRequest request, Map<String, Object> defaults) {
        Object minted = request.getAttribute(REFERENCE, RequestAttributes.SCOPE_REQUEST);
        if (minted instanceof String reference) {
            return reference;
        }
        String method = request instanceof NativeWebRequest web
                && web.getNativeRequest() instanceof HttpServletRequest servlet ? servlet.getMethod() : "?";
        String reference = Failures.record(method + " " + defaults.get("path"), getError(request));
        request.setAttribute(REFERENCE, reference, RequestAttributes.SCOPE_REQUEST);
        return reference;
    }
}
