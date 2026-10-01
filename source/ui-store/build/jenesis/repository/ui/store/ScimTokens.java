package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Retries;

/**
 * A tenant's SCIM bearer token, stored as the SHA-256 hash of the secret (never the secret) in the tenant's
 * {@code .scim.properties}. Each tenant sets its own token so its identity provider can provision only that tenant's
 * members, rather than one deployment-wide token reaching every tenant. The match is constant-time over the hashes.
 */
public final class ScimTokens {

    private static final String FILE = ".scim.properties";

    private final Documents storage;

    public ScimTokens(Documents storage) {
        this.storage = storage;
    }

    /** The token generator. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A token's entropy: 192 bits, for a long-lived bearer token. */
    private static final int SECRET_BYTES = 24;

    /**
     * Mints this tenant's token, stores its hash and returns the secret, its only readable moment; every surface mints
     * through here, so the shape is one.
     */
    public String mint() throws IOException {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        String token = "scim_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        set(token);
        return token;
    }

    public boolean configured() {
        return storage.read(FILE).getProperty("token") != null;
    }

    /** Whether {@code presented} is this tenant's token; false when none is set. */
    public boolean matches(String presented) {
        String hash = storage.read(FILE).getProperty("token");
        return hash != null && presented != null && MessageDigest.isEqual(
                Authorization.hash(presented).getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8));
    }

    /** Set the tenant's token (only its hash is kept) or, with a blank value, clear it. Compare-and-set under
     *  {@link Retries}, so a concurrent rotation (two admins, or an admin racing a clear) never blind-reverts the
     *  other's write. */
    public void set(String token) throws IOException {
        Retries.compareAndSet(FILE, () -> {
            Object version = storage.version(FILE);
            Properties properties = storage.read(FILE);
            if (token == null || token.isBlank()) {
                properties.remove("token");
            } else {
                properties.setProperty("token", Authorization.hash(token));
            }
            return storage.writeVersioned(FILE, properties, version);
        });
    }
}
