package build.jenesis.repository.auth.keylogin;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Retries;

/**
 * The issued console login keys, stored deployment-wide at {@code .system/auth/keylogin/keys.properties}, one line
 * {@code <sha-256 hash> = <principal-id> <tenant> [login]}. Only the one-way hash is written
 * ({@link Authorization#hash}, the hash repository credentials use), so a leaked store yields no usable key; the
 * plaintext is shown once at issue. A presented key is hashed and looked up, and every sign-in re-reads the file, so
 * revocation is immediate. Writes use the store's compare-and-set, so concurrent issues and revokes never lose one
 * another. The space is deployment-global because a login key spans tenants. The bound tenant is stored so a revoke can
 * reverse the membership grant the issue wrote.
 */
public final class KeyLoginKeys {

    /** Store path of the issued-key index. */
    static final String FILE = Scopes.space(Scopes.AUTH) + "/keylogin/keys.properties";

    private static final String PREFIX = "jkl_";
    private static final int SECRET_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Documents root;

    public KeyLoginKeys(Documents root) {
        this.root = root;
    }

    /** One issued key as an admin sees it: its stable id (the hash, the revoke handle), who it signs in as, and the
     *  tenant its membership was written to - never the key, which exists only in the {@link Issued} returned at
     *  issue. */
    public record Entry(String id, String principal, String tenant, String login) {
    }

    /** The outcome of issuing a key: the plaintext {@code key}, shown once, with its stored id and tenant. */
    public record Issued(String id, String key, String principal, String tenant, String login) {
    }

    /** The principal a presented key resolves to. */
    public record Resolved(String principal, String login) {
    }

    /** Mint a login key for {@code principal} bound to {@code tenant}, store only its hash, and return the plaintext
     *  once. The tenant is stored so {@link #revoke} can reverse the membership grant rather than leave it for a
     *  same-name reissue to inherit. */
    public Issued issue(String principal, String tenant, String login) throws IOException {
        String id = require(principal);
        String scope = tenant == null ? "" : tenant.trim();
        String key = mint();
        String hash = Authorization.hash(key);
        String trimmedLogin = login == null ? "" : login.trim();
        String value = trimmedLogin.isBlank() ? id + " " + scope : id + " " + scope + " " + trimmedLogin;
        mutate(properties -> properties.setProperty(hash, value));
        return new Issued(hash, key, id, scope, trimmedLogin);
    }

    /** Resolve a presented key to its principal, or empty when no issued key hashes to it. */
    public Optional<Resolved> resolve(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        String value = root.read(FILE).getProperty(Authorization.hash(presented.trim()));
        if (value == null) {
            return Optional.empty();
        }
        Entry entry = parse("", value);
        return Optional.of(new Resolved(entry.principal(), entry.login()));
    }

    /** Every issued key, by stable id, for an admin listing - never exposing any key material. */
    public List<Entry> list() {
        Properties properties = root.read(FILE);
        List<Entry> entries = new ArrayList<>();
        for (String id : new TreeSet<>(properties.stringPropertyNames())) {
            entries.add(parse(id, properties.getProperty(id)));
        }
        return entries;
    }

    /** The stored entry for one key id, or empty when none - the revoke reads it to reverse the membership grant. */
    public Optional<Entry> find(String id) {
        String value = root.read(FILE).getProperty(id);
        return value == null ? Optional.empty() : Optional.of(parse(id, value));
    }

    /** Parse a stored value {@code <principal> <tenant> [login]}: principal and tenant are single tokens, the login the
     *  remainder, which may contain spaces. */
    private static Entry parse(String id, String value) {
        String[] parts = value.trim().split("\\s+", 3);
        String principal = parts[0];
        String tenant = parts.length > 1 ? parts[1] : "";
        String login = parts.length > 2 ? parts[2] : "";
        return new Entry(id, principal, tenant, login);
    }

    /** Revoke one issued key by its stable id; a no-op when it is already gone. */
    public void revoke(String id) throws IOException {
        mutate(properties -> properties.remove(id));
    }

    private static String mint() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static String require(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Principal id must not be blank.");
        }
        String trimmed = principal.trim();
        // Any whitespace, not just a space: parse() splits on \s+, so a tab or newline inside a principal would split
        // at read time and forge the entry's tenant field.
        if (trimmed.codePoints().anyMatch(Character::isWhitespace)
                || trimmed.contains("=") || trimmed.contains(":")) {
            throw new IllegalArgumentException("Principal id must not contain '=', ':' or whitespace.");
        }
        return trimmed;
    }

    /** Apply one edit under the store's compare-and-set, so concurrent issues and revokes never clobber each other. */
    private void mutate(Consumer<Properties> edit) throws IOException {
        Retries.compareAndSet(FILE, () -> {
            Object token = root.version(FILE);
            Properties properties = root.read(FILE);
            edit.accept(properties);
            return root.writeVersioned(FILE, properties, token);
        });
    }
}
