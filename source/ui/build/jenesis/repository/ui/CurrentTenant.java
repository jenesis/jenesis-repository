package build.jenesis.repository.ui;

/**
 * Which tenant this request is acting in: the one configured tenant on a single-tenant deployment, the session's
 * selection on a multi-tenant one. Screens and services are written once against the answer.
 */
@FunctionalInterface
public interface CurrentTenant {

    /** The selected tenant, or {@code null} when no tenant is bound to the current call. */
    String name();
}
