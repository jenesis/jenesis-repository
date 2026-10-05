package build.jenesis.repository.server.kernel;

/**
 * The tenant of the publish in progress on this thread, so the discovered compliance gate can resolve a tenant's own
 * policy without the publication-interceptor chain carrying a tenant name. The {@code Publication} and
 * {@code PublishInterceptor} hand an interceptor only the already-scoped {@link build.jenesis.repository.store.ArtifactStore}
 * and the {@code ArtifactDescriptor} - never the tenant string - and threading one through them would be a
 * change (a republish). Instead the write path opens this scope around the request through the
 * {@link PublishTenantFilter} (on {@code /repository/**} and {@code /v2/**}), and the gate wiring resolves
 * {@link LiveConfig#publishGate(String)} from {@link #current()}; the screening and the publish run on the one thread
 * within that request.
 *
 * <p>Unset (an off-request publish - staging or batch - the filter never wraps) {@link #current()} is
 * {@code null}, and the gate falls back to the deployment-wide policy. Under fixed tenancy the filter binds the one
 * default tenant, whose policy is the deployment-wide one anyway. The scope restores the previous value on close, so a
 * reused request thread never leaks a tenant into the next publish.
 */
public final class PublishTenant {

    /** The tenant and, where the request named one, the repository. */
    private record Bound(String tenant, String repository) {
    }

    private static final ThreadLocal<Bound> CURRENT = new ThreadLocal<>();

    private PublishTenant() {
    }

    /** The tenant of the publish running on this thread, or {@code null} when none is in scope. */
    public static String current() {
        Bound bound = CURRENT.get();
        return bound == null ? null : bound.tenant();
    }

    /** The repository the request on this thread addresses, or {@code null} where it names none - an operation, or a
     *  publish off any request - so a repository's own setting is read only where the repository is known, and the
     *  tenant's answers otherwise. */
    public static String repository() {
        Bound bound = CURRENT.get();
        return bound == null ? null : bound.repository();
    }

    /** Bind {@code tenant} to this thread for the duration of the returned scope, restoring the previous binding on
     *  close - so a nested publish or a reused thread is left as it was found. */
    public static Scope open(String tenant) {
        return open(tenant, null);
    }

    /** As {@link #open(String)}, with the repository the request addresses. */
    public static Scope open(String tenant, String repository) {
        Bound previous = CURRENT.get();
        CURRENT.set(new Bound(tenant, repository));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** The binding held open by {@link #open(String)}; closing it restores the previous tenant. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
