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

    /** The generator, here rather than at a calling surface so every surface mints the same shape of token. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** How much entropy a token carries. 24 bytes is 192 bits, well past guessing for a long-lived bearer token. */
    private static final int SECRET_BYTES = 24;

    /**
     * Mint this tenant's token, store its hash and return the secret - the only moment it exists in readable form,
     * since {@link #set} keeps the hash alone.
     *
     * <p>It lives here because more than one surface issues one. The console minted it inline in its own
     * controller, so an API twin would have had to generate a second token the same way and the two would have been
     * one secret's shape written in two places, free to drift in length, alphabet or prefix - and a token that
     * differs by surface is one an identity provider accepts from one and rejects from the other.
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
