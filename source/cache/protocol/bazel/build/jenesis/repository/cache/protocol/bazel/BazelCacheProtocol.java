package build.jenesis.repository.cache.protocol.bazel;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * Bazel's native HTTP/1.1 remote cache, adapted onto this cache.
 *
 * <p>{@code /ac/<hash>} holds an action result and {@code /cas/<hash>} content addressed by the digest of its own
 * bytes. An operator points {@code --remote_cache} at {@code .../build/<tenant>/bazel} and the client appends the rest.
 *
 * <p><b>The write policy is per request.</b> A CAS entry cannot differ from itself, so a second upload is waste and
 * {@link CacheProtocol.Existing#DEDUPE} does not read the body. An action result is addressed by the action, so a
 * re-run with different output replaces what is recorded ({@link CacheProtocol.Existing#REWRITE}).
 *
 * <p><b>The namespace is part of the key</b>: the two spaces hash different things, so one hash value can name two
 * unrelated entries, and hashing {@code <namespace>/<hash>} keeps them apart.
 *
 * <p>Bazel carries its credential in the URL ({@code --remote_cache=http://user:pass@…}), so the key lands in command
 * lines, {@code .bazelrc} and any transcript echoing the flag - a property of the client worth saying where a
 * deployment is documented.
 */
public final class BazelCacheProtocol implements CacheProtocol {

    private static final String PREFIX = "/bazel/";

    /** The action cache: addressed by the action, so a re-run's different output must replace what is recorded. */
    private static final String ACTIONS = "ac";

    /** Content-addressed storage: an entry cannot differ from itself, so a second upload is waste. */
    private static final String CONTENT = "cas";

    @Override
    public String name() {
        return "bazel";
    }

    @Override
    public String endpoint() {
        return "/bazel";
    }

    @Override
    public boolean handles(String path) {
        return namespace(path) != null;
    }

    @Override
    public Optional<Address> address(Request request) {
        String namespace = namespace(request.path());
        if (namespace == null) {
            return Optional.empty();
        }
        String hash = request.path().substring(PREFIX.length() + namespace.length() + 1);
        // The namespace rides in the hashed value, so one hash meaning two unrelated entries stays two entries.
        String digest = digest(namespace + "/" + hash);
        return Optional.of(new Address(digest.substring(0, 4), digest,
                request.project(), request.presentedKey(),
                ACTIONS.equals(namespace) ? Existing.REWRITE : Existing.DEDUPE));
    }

    /** The namespace a path names, or {@code null} when it names neither - one segment under it, and non-empty. */
    private static String namespace(String path) {
        for (String namespace : List.of(ACTIONS, CONTENT)) {
            String prefix = PREFIX + namespace + "/";
            if (path.startsWith(prefix) && path.length() > prefix.length()
                    && path.indexOf('/', prefix.length()) < 0) {
                return namespace;
            }
        }
        return null;
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
