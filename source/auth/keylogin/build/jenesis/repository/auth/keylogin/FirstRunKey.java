package build.jenesis.repository.auth.keylogin;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;

/**
 * The one-time key a fresh deployment prints so that somebody can sign in at all.
 *
 * <p><b>When there is one.</b> A deployment nobody can sign in to yet - no console admin key in the environment, no
 * deployment administrator granted, no login key issued - mints one on every start, valid for {@link #VALIDITY}, and
 * the start announces it. A start that finds any of the three mints nothing and clears what earlier starts left, so a
 * configured deployment carries no first-run key at all. Nothing else decides it: the store is fresh or it is not,
 * and "fresh" here means "nobody can get in", which is the only reason the key exists.
 *
 * <p><b>What it grants.</b> The same session the environment's admin key yields: super-admin, marked as the
 * starter credential, so the console sends it to the setup guide. It stops being accepted the moment a deployment
 * administrator exists - the guide's first step is to grant one - and when its hour is up, whichever comes first. A
 * restart of a still-fresh deployment mints a new one, so an expired key is never a lockout.
 *
 * <p><b>What is stored.</b> Only the key's one-way hash, as the name of a document holding its expiry, under the
 * key-login module's space; the plaintext exists in the start's log and nowhere else. One document per key rather
 * than one shared document, so two nodes starting fresh over one store each keep the key they printed without a
 * compare-and-set between them.
 */
public final class FirstRunKey {

    /** How long a printed key is accepted: short, because it sits in a log, and harmless to outlive, because a restart
     *  of a deployment that is still fresh prints a new one. */
    public static final Duration VALIDITY = Duration.ofHours(1);

    /** The store prefix the keys' documents live under, one per key, named by the key's hash. */
    static final String SPACE = Scopes.space(Scopes.AUTH) + "/keylogin/first-run";

    private static final String PREFIX = "jfr_";
    private static final int SECRET_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String EXPIRES = "expires";

    private final Documents root;
    private final KeyLoginKeys keys;
    private final boolean adminKeySet;
    private final BooleanSupplier administered;
    private final Clock clock;

    /**
     * @param root         the deployment-wide documents the key-login module stores under
     * @param keys         the issued login keys, whose existence means somebody can sign in
     * @param adminKeySet  whether the environment names a console admin key
     * @param administered whether the deployment has an administrator - a point read, asked at every decision
     * @param clock        the clock a key's hour is measured on
     */
    public FirstRunKey(Documents root, KeyLoginKeys keys, boolean adminKeySet, BooleanSupplier administered,
                       Clock clock) {
        this.root = root;
        this.keys = keys;
        this.adminKeySet = adminKeySet;
        this.administered = administered;
        this.clock = clock;
    }

    /** A minted key and the instant it stops being accepted; the plaintext is only ever here. */
    public record Issued(String key, Instant expires) {
    }

    /** Whether nobody can sign in: no admin key in the environment, no deployment administrator, no issued key. */
    public boolean nobodyCanSignIn() {
        return !adminKeySet && !administered.getAsBoolean() && keys.list().isEmpty();
    }

    /**
     * Mint a key when nobody can sign in, storing only its hash and expiry; otherwise mint nothing and remove what
     * earlier starts left behind.
     */
    public Optional<Issued> issueIfNeeded() throws IOException {
        if (!nobodyCanSignIn()) {
            root.deleteAll(SPACE);
            return Optional.empty();
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expires = clock.instant().plus(VALIDITY);
        Properties document = new Properties();
        document.setProperty(EXPIRES, expires.toString());
        root.write(SPACE + "/" + Authorization.hash(key), document);
        return Optional.of(new Issued(key, expires));
    }

    /** Whether a presented key is a first-run key still in its hour, on a deployment that still has no administrator. */
    public boolean accepts(String presented) {
        if (presented == null || presented.isBlank() || !presented.trim().startsWith(PREFIX)) {
            return false;
        }
        String expires = root.read(SPACE + "/" + Authorization.hash(presented.trim())).getProperty(EXPIRES);
        if (expires == null) {
            return false;
        }
        Instant until;
        try {
            until = Instant.parse(expires);
        } catch (DateTimeParseException unreadable) {
            return false;
        }
        return clock.instant().isBefore(until) && !administered.getAsBoolean();
    }
}
