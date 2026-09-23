package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.CurrentTenant;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The web console's {@link CurrentTenant}: the tenant the current session has selected, held as an HTTP-session
 * attribute. A session with exactly one accessible tenant has it selected automatically and never sees the
 * concept; a user with several, or an env super-admin, picks one on the instances page. Selecting and clearing are
 * session mutations, so they live here in the web layer rather than on the Spring-free domain interface.
 */
@Component
public class SessionCurrentTenant implements CurrentTenant {

    public static final String ATTRIBUTE = "jenreg.console.selected-tenant";

    @Override
    public String name() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object tenant = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_SESSION);
        return tenant == null ? null : tenant.toString();
    }

    public void select(String tenant) {
        RequestContextHolder.currentRequestAttributes().setAttribute(ATTRIBUTE, tenant, RequestAttributes.SCOPE_SESSION);
    }

    public void clear() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.removeAttribute(ATTRIBUTE, RequestAttributes.SCOPE_SESSION);
        }
    }
}
