package build.jenesis.repository.cache.protocol.gradle;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * Gradle's HTTP build cache, adapted onto this cache.
 *
 * <p>Gradle publishes no formal specification; what it documents is a behavioural expectation, and it is short.
 * {@code GET <url><key>} answers 2xx with the body or 404, {@code PUT <url><key>} answers any 2xx, an entry past
 * the server's cap is a 413, and the client can be told to authenticate preemptively. The shared serving already
 * answers 413, 507 and 411, and 201/204 are 2xx, so all this protocol contributes is the shape of the key and
 * where the credential sits.
 *
 * <p><b>The identity rides in Basic, and the fit is exact rather than a squeeze.</b> Gradle's cache client can be
 * configured with a user name and a password and nothing else, and Basic is structurally two slots - RFC 7617
 * encodes {@code userid ":" password} with a mandatory colon that a userid may not contain. This protocol carries
 * exactly two identity values, so the user name is the project and the password is the key. It type-checks rather
 * than merely fitting: a project is {@code [A-Za-z0-9_]+}, which is colon-free by construction and a valid userid
 * needing no escaping, and the charset question RFC 7617 leaves open cannot arise because both halves are ASCII
 * by that same rule. The tenant needs no slot - it already rides inside the key - so the grant model is
 * untouched. A build configured with no {@code credentials} block sends nothing and lands on the same anonymous
 * path an unauthenticated native client does.
 *
 * <p><b>The key is hashed rather than used as sent</b>, for the reason the Maven layout hashes: the shared
 * predicate every storage backend applies to a segment admits hex only, and hashing is what makes the mapping
 * total instead of refusing whatever a client puts on that path. Gradle's own keys are hex today, but that is its
 * choice to change and not a property this protocol can rest on.
 *
 * <p><b>The step is a pure shard.</b> Gradle's key is one opaque hash over a task's inputs with no step identity
 * anywhere in it, so unlike the Maven layout there is nothing to group by. Four hex characters spread entries
 * over 65,536 shards, which is what keeps a filesystem backend from holding every entry a build farm ever wrote
 * in one directory; the inputs carry the whole digest, so the mapping stays injective and the shard costs no
 * collisions.
 */
public final class GradleCacheProtocol implements CacheProtocol {

    private static final String PREFIX = "/gradle/";

    @Override
    public String name() {
        return "gradle";
    }

    @Override
    public String endpoint() {
        return PREFIX;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX) && path.length() > PREFIX.length()
                && path.indexOf('/', PREFIX.length()) < 0;
    }

    @Override
    public Optional<Address> address(Request request) {
        if (!handles(request.path())) {
            return Optional.empty();
        }
        String digest = digest(request.path().substring(PREFIX.length()));
        // The project is the Basic user name and the key its password, both already read off the request by the
        // caller - this protocol names which of the two shared presentations its wire format specifies, and
        // derives neither itself.
        return Optional.of(new Address(digest.substring(0, 4), digest,
                request.project(), request.presentedKey(), Existing.DEDUPE));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
