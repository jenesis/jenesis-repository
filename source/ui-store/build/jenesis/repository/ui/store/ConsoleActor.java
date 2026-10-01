package build.jenesis.repository.ui.store;

import build.jenesis.repository.ui.CurrentTenant;

/**
 * Who a privileged console mutation is attributed to in the audit trail, resolved by the calling surface, so the
 * services need no web security context.
 */
@FunctionalInterface
public interface ConsoleActor {

    /** The acting member's name, or a neutral placeholder (e.g. {@code "console"}) when none is bound. */
    String name();
}
