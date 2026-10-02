package build.jenesis.repository.cache.protocol.gradle;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * Gradle's HTTP build cache, adapted onto this cache.
 *
 * <p>Gradle documents a behavioural expectation rather than a specification: {@code GET <url><key>} answers 2xx with
 * the body or 404, {@code PUT <url><key>} any 2xx, an entry past the server's cap 413. The shared serving answers all
 * of that, so this protocol contributes the shape of the key and where the credential sits.
 *
 * <p><b>The identity rides in Basic.</b> Gradle's cache client can be configured with a user name and a password and
 * nothing else, so the user name is the project and the password is the key. A project is {@code [A-Za-z0-9_]+},
 * colon-free and ASCII, so it is a valid RFC 7617 userid needing no escaping. The tenant rides in the URL. A build with
 * no {@code credentials} block sends nothing and is anonymous, as an unauthenticated native client is.
 *
 * <p><b>The key is hashed</b>, since the storage predicate admits hex segments only and Gradle's keys being hex is
 * Gradle's choice rather than a property to rest on. Gradle's key carries no step identity, so the step is a shard:
 * four hex characters spread entries over 65,536 directories on a filesystem backend, and the inputs carry the whole
 * digest, so the mapping stays injective.
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
        // The Basic user name and password, both read off the request by the caller.
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
