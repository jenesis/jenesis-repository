package build.jenesis.repository.ui.store;

import build.jenesis.repository.ui.CurrentTenant;

/**
 * Who a privileged console mutation is attributed to in the audit trail. Resolved from the calling surface - the
 * web console names the signed-in member, another surface names its own actor - so {@link CredentialService} and
 * its peers record an actor without reaching into the web security context. A Spring-free seam, like
 * {@link CurrentTenant}: the console's implementation reads the current authentication in the web layer.
 */
@FunctionalInterface
public interface ConsoleActor {

    /** The acting member's name, or a neutral placeholder (e.g. {@code "console"}) when none is bound. */
    String name();
}
