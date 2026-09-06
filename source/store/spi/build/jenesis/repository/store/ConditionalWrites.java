package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;

/**
 * Whether the store an object-store backend was pointed at honours the write precondition every compare-and-set in
 * this product rests on. A backend expresses "create only if absent" and "replace only if unchanged" in its
 * protocol's terms - {@code If-None-Match: *} and {@code If-Match} on S3 - and the whole multi-node story (leases,
 * listings, counters, the identity fold) assumes the endpoint refuses a write whose precondition fails. Not every
 * S3-compatible endpoint does: OVHcloud Object Storage accepts the header and ignores it (its roadmap issue #671 has
 * been open since December 2024), so two nodes over it would each believe they won every compare-and-set and
 * overwrite one another without a trace. Nothing at request time can tell a precondition that was honoured from one
 * that was dropped - the write succeeds either way - which is why this is asked once, at boot, and answered by refusing
 * to start.
 *
 * <p>The probe is two creates of one fresh key under {@link Scopes#SYSTEM}: the first must land, the second - the
 * same "only if absent" write - must be refused. Either other answer names the endpoint and stops the node. The key
 * is deleted afterwards whatever happened; the cost is two writes and a delete per boot.
 */
public final class ConditionalWrites {

    private ConditionalWrites() {
    }

    /**
     * Probe {@code store} for honoured write preconditions, naming {@code endpoint} in the refusal.
     *
     * @throws IllegalStateException when the endpoint accepts a create of a key that already exists, or refuses to
     *                               create one that does not
     * @throws IOException           when the probe itself could not be written or deleted - a bucket the node cannot
     *                               write is a different failure from one it must not trust
     */
    public static void probe(ArtifactStore store, String endpoint) throws IOException {
        String key = Scopes.SYSTEM + "/probe/conditional-writes-" + UUID.randomUUID();
        byte[] body = "conditional-writes probe".getBytes(StandardCharsets.UTF_8);
        try {
            if (!store.writeVersioned(key, body, null)) {
                throw new IllegalStateException(endpoint + " refused to create " + key + ", which did not exist: a "
                        + "compare-and-set that cannot create an absent key can never land a first write, so this node "
                        + "will not start over it.");
            }
            if (store.writeVersioned(key, body, null)) {
                throw new IllegalStateException(endpoint + " accepted a second create of " + key + " under an "
                        + "only-if-absent precondition (If-None-Match: *) that had to fail: this endpoint ignores write "
                        + "preconditions, so two nodes over it would overwrite each other's compare-and-set writes "
                        + "without a trace. OVHcloud Object Storage is one such endpoint. This node refuses to start on "
                        + "it rather than lose data quietly; point it at a store that honours conditional writes.");
            }
        } finally {
            store.delete(key);
        }
    }
}
