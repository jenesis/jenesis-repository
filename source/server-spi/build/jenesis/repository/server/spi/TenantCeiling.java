package build.jenesis.repository.server.spi;

import module java.base;

/**
 * One per-tenant ceiling an operator may set: a single non-negative number in a document of the credential space,
 * where {@code 0} - or no document - means none is set and the deployment's own answer applies.
 *
 * <p>Two exist, the stored-content quota ({@link Authorization#quotas()}, in bytes, where none means unlimited) and
 * the request rate ceiling ({@link Authorization#rateLimits()}, in permits per minute, where none means the
 * deployment default). They are one mechanism under two names: a ceiling is read on the request path through the
 * credential space's cache, and a change reaches every node the way a revocation does.
 */
public final class TenantCeiling {

    private final CredentialSpace space;
    private final String document;
    private final String property;
    private final String noun;

    private TenantCeiling(CredentialSpace space, String document, String property, String noun) {
        this.space = space;
        this.document = document;
        this.property = property;
        this.noun = noun;
    }

    /** A tenant's stored-content quota in bytes. */
    static TenantCeiling quota(CredentialSpace space) {
        return new TenantCeiling(space, "quota", "max-bytes", "A storage quota");
    }

    /** A tenant's request rate ceiling in permits per minute. */
    static TenantCeiling rateLimit(CredentialSpace space) {
        return new TenantCeiling(space, "ratelimit", "permits-per-minute", "A rate limit");
    }

    /** The tenant's ceiling, or {@code 0} when none is set. */
    public long of(String tenant) throws IOException {
        Properties stored = space.enforcing() ? space.read(path(tenant)) : null;
        String value = stored == null ? null : stored.getProperty(property);
        return value == null ? 0L : Long.parseLong(value);
    }

    /** Set ({@code > 0}) or clear ({@code 0}) the tenant's ceiling; a negative value is rejected. */
    public void set(String tenant, long value) throws IOException {
        space.require();
        if (value < 0) {
            throw new IllegalArgumentException(noun + " must not be negative");
        }
        String path = path(tenant);
        if (value == 0) {
            if (space.present(path)) {
                space.remove(path);
            }
            return;
        }
        Properties properties = new Properties();
        properties.setProperty(property, Long.toString(value));
        space.write(path, properties);
    }

    private String path(String tenant) {
        return CredentialSpace.tenantDocument(tenant, document);
    }
}
