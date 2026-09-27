package build.jenesis.repository.server.spi;

import module java.base;

/**
 * A tenant's OIDC trusts, by name: which identity providers' id-tokens are exchanged for a short-lived credential, and
 * what that credential carries. Stored as one document per tenant in the credential space, {@code <name>.<field>}
 * per trust.
 */
public final class OidcTrusts {

    /** The token lifetime a trust stored without one is given. */
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final CredentialSpace space;

    OidcTrusts(CredentialSpace space) {
        this.space = space;
    }

    /** A named OIDC trust: an id-token from {@code issuer} (signed by its JWKS) whose {@code audience} and
     *  {@code subject} (a glob, blank for any) match is exchanged for a short-lived credential of {@code ttl} carrying
     *  {@code rights} on {@code scope}. The {@code audience} is required and must be explicit: a blank audience would
     *  match a token bearing <em>any</em> {@code aud} - including one minted for a foreign relying party - so it is
     *  rejected at construction (fail-fast) rather than silently trusting every audience. Every Trust, whether built
     *  in code or parsed from stored config ({@link #of}), flows through this canonical constructor, so the check
     *  covers every creation and parse path. */
    public record Trust(String name, String issuer, String audience, String subject, String scope, String rights,
                        Duration ttl) {
        public Trust {
            if (audience == null || audience.isBlank()) {
                throw new IllegalArgumentException("OIDC trust '" + name + "' (issuer " + issuer
                        + ") requires an explicit audience: set its audience to the value your tokens carry in the "
                        + "aud claim; a blank audience would accept a token minted for any relying party");
            }
        }
    }

    /** A tenant's OIDC trusts, in name order. An id-token matching a trust is exchanged for a short-lived
     *  credential. */
    public List<Trust> of(String tenant) throws IOException {
        Properties stored = space.enforcing() ? space.read(path(tenant)) : null;
        if (stored == null) {
            return List.of();
        }
        Set<String> names = new TreeSet<>();
        for (String key : stored.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                names.add(key.substring(0, dot));
            }
        }
        List<Trust> trusts = new ArrayList<>();
        for (String name : names) {
            String ttl = stored.getProperty(name + ".ttl");
            trusts.add(new Trust(name, stored.getProperty(name + ".issuer"),
                    stored.getProperty(name + ".audience"), stored.getProperty(name + ".subject"),
                    stored.getProperty(name + ".scope"), stored.getProperty(name + ".rights"),
                    ttl == null || ttl.isBlank() ? null : Duration.parse(ttl)));
        }
        return trusts;
    }

    /** Add or replace an OIDC trust by name; {@code issuer}, {@code scope} and {@code rights} are required here, and
     *  an explicit {@code audience} is enforced earlier at {@link Trust} construction. */
    public void set(String tenant, Trust trust) throws IOException {
        space.require();
        if (trust.name() == null || trust.name().isBlank() || trust.issuer() == null || trust.issuer().isBlank()
                || trust.scope() == null || trust.scope().isBlank() || trust.rights() == null || trust.rights().isBlank()) {
            throw new IllegalArgumentException("An OIDC trust needs a name, an issuer, a scope and rights");
        }
        Properties stored = space.read(path(tenant));
        if (stored == null) {
            stored = new Properties();
        }
        String name = trust.name();
        stored.setProperty(name + ".issuer", trust.issuer());
        stored.setProperty(name + ".audience", trust.audience() == null ? "" : trust.audience());
        stored.setProperty(name + ".subject", trust.subject() == null ? "" : trust.subject());
        stored.setProperty(name + ".scope", trust.scope());
        stored.setProperty(name + ".rights", trust.rights());
        stored.setProperty(name + ".ttl", (trust.ttl() == null ? DEFAULT_TTL : trust.ttl()).toString());
        space.write(path(tenant), stored);
    }

    /** Remove a tenant's OIDC trust by name. */
    public void remove(String tenant, String name) throws IOException {
        space.require();
        Properties stored = space.read(path(tenant));
        if (stored == null) {
            return;
        }
        for (String key : new ArrayList<>(stored.stringPropertyNames())) {
            if (key.startsWith(name + ".")) {
                stored.remove(key);
            }
        }
        space.write(path(tenant), stored);
    }

    private static String path(String tenant) {
        return CredentialSpace.tenantDocument(tenant, "oidc");
    }
}
