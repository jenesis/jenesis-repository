package build.jenesis.repository.cache.protocol.bazel;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * Bazel's native HTTP/1.1 remote cache, adapted onto this cache.
 *
 * <p>Bazel splits its cache in two and the split is the whole of the protocol: {@code /ac/<hash>} holds an action
 * RESULT - what running an action produced - and {@code /cas/<hash>} holds content addressed by the digest of its
 * own bytes. An operator points {@code --remote_cache} at {@code .../build/<tenant>/bazel} and the client appends the rest.
 *
 * <p><b>The two namespaces disagree about what a re-PUT means, which is why the write policy is per request
 * rather than per protocol.</b> A CAS entry cannot differ from itself, so a second upload is waste and
 * {@link CacheProtocol.Existing#DEDUPE} declines to read the body at all. An action result is addressed by the
 * action and not by the result, so a re-run that produces different output must replace what is recorded -
 * {@link CacheProtocol.Existing#REWRITE}. One answer for both would either pin the first result an action ever
 * produced and serve it forever, or make every repeated blob upload pay for bytes already stored.
 *
 * <p><b>The namespace is part of the key, not just of the route.</b> The two spaces are addressed by digests of
 * different things, so one hash value can legitimately appear in both meaning two unrelated entries; hashing
 * {@code <namespace>/<hash>} is what keeps them from colliding on one stored blob.
 *
 * <p><b>Operator note: Bazel carries its credential in the URL.</b> {@code --remote_cache=http://user:pass@…} is
 * the documented form, so the key lands in a command line, in {@code .bazelrc}, and in any log or CI transcript
 * that echoes the flag - unlike Gradle's, which sits in a credentials block. That is a property of the client
 * rather than of this protocol, and it is worth saying where the deployment is documented, because a key pasted
 * into a build file is a key that leaks with the build file.
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
