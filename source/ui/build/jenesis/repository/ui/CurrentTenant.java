package build.jenesis.repository.ui;

/**
 * Which tenant this request is acting in.
 *
 * <p>One seam with two implementations, and which one is installed is a deployment's choice rather than an
 * edition's: a single-tenant deployment answers its one configured tenant on every request, a multi-tenant one
 * answers the tenant the session selected. Everything above this - every screen, every service - is written once
 * against the answer and never asks which kind of deployment it is running in.
 */
@FunctionalInterface
public interface CurrentTenant {

    /** The selected tenant, or {@code null} when no tenant is bound to the current call. */
    String name();
}
