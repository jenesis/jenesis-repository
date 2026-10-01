package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.CurrentTenant;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The web console's {@link CurrentTenant}: the tenant the session selected, held as a session attribute. Selecting and
 * clearing are session mutations, so they live here rather than on the Spring-free domain interface.
 */
@Component
public class SessionCurrentTenant implements CurrentTenant {

    public static final String ATTRIBUTE = "jenrepo.console.selected-tenant";

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
