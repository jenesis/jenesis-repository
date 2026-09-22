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
 * <p>Unset (an off-request publish - staging, batch or demo seeding - the filter never wraps) {@link #current()} is
 * {@code null}, and the gate falls back to the deployment-wide policy. Under fixed tenancy the filter binds the one
 * default tenant, whose policy is the deployment-wide one anyway. The scope restores the previous value on close, so a
 * reused request thread never leaks a tenant into the next publish.
 */
public final class PublishTenant {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private PublishTenant() {
    }

    /** The tenant of the publish running on this thread, or {@code null} when none is in scope. */
    public static String current() {
        return CURRENT.get();
    }

    /** Bind {@code tenant} to this thread for the duration of the returned scope, restoring the previous binding on
     *  close - so a nested publish or a reused thread is left as it was found. */
    public static Scope open(String tenant) {
        String previous = CURRENT.get();
        CURRENT.set(tenant);
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
