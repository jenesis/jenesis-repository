package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the store an object-store backend was pointed at honours the two write preconditions every compare-and-set
 * in this product rests on. A backend expresses "create only if absent" and "replace only if unchanged" in its
 * protocol's terms - {@code If-None-Match: *} and {@code If-Match} on S3, a generation on GCS, an ETag on Azure - and
 * the whole multi-node story (leases, listings, counters, the identity fold) assumes the endpoint refuses a write
 * whose precondition fails. Not every S3-compatible endpoint does, and not every one that honours the first honours
 * the second: OVHcloud Object Storage accepts both headers and ignores them (its roadmap issue #671 has been open
 * since December 2024), and Exoscale's SOS honours {@code If-None-Match: *} but takes no ETag on {@code If-Match}.
 * Over either, two nodes would each believe they won every compare-and-set and overwrite one another without a
 * trace. Nothing at request time can tell a precondition that was honoured from one that was dropped - the write
 * succeeds either way - which is why this is asked once, at boot, and answered by refusing to start.
 *
 * <p>The probe is four writes of one fresh key under {@link Scopes#SYSTEM}: a create that must land, the same
 * create again that must be refused, a replace under the token the create left that must land, and the same replace
 * under that now-stale token that must be refused. Any other answer names the endpoint and stops the node. The key is
 * deleted afterwards whatever happened; the cost is four writes, one version read and a delete per boot.
 *
 * <p>A deployment may switch the probe off - {@code jenreg.<backend>.conditional-write-probe=false}, one key per
 * object-store backend - for an endpoint it has satisfied itself about by other means, or one that refuses writes
 * under the system space; it then boots with a warning that says what it has given up.
 */
public final class ConditionalWrites {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConditionalWrites.class);

    private ConditionalWrites() {
    }

    /**
     * Probe {@code store} for honoured write preconditions unless {@code setting} - the backend's
     * {@code conditional-write-probe} value - is {@code false}, naming {@code endpoint} in the refusal or the warning.
     *
     * @throws IllegalStateException when the endpoint accepts a create of a key that already exists, refuses to
     *                               create one that does not, accepts a replace under a stale token, or refuses one
     *                               under the current token
     * @throws IOException           when the probe itself could not be written, read or deleted - a bucket the node
     *                               cannot write is a different failure from one it must not trust
     */
    public static void probe(ArtifactStore store, String endpoint, String setting) throws IOException {
        if ("false".equalsIgnoreCase(setting)) {
            LOGGER.warn("The conditional-write probe of {} is switched off: if this endpoint ignores If-None-Match or "
                    + "If-Match, two nodes over it will overwrite each other's compare-and-set writes without a trace, "
                    + "and nothing at request time can tell.", endpoint);
            return;
        }
        String key = Scopes.SYSTEM + "/probe/conditional-writes-" + UUID.randomUUID();
        byte[] first = "conditional-writes probe".getBytes(StandardCharsets.UTF_8);
        byte[] second = "conditional-writes probe, replaced".getBytes(StandardCharsets.UTF_8);
        try {
            if (!store.writeVersioned(key, first, null)) {
                throw new IllegalStateException(endpoint + " refused to create " + key + ", which did not exist: a "
                        + "compare-and-set that cannot create an absent key can never land a first write, so this node "
                        + "will not start over it.");
            }
            if (store.writeVersioned(key, first, null)) {
                throw new IllegalStateException(endpoint + " accepted a second create of " + key + " under an "
                        + "only-if-absent precondition (If-None-Match: *) that had to fail: this endpoint ignores write "
                        + "preconditions, so two nodes over it would overwrite each other's compare-and-set writes "
                        + "without a trace. OVHcloud Object Storage is one such endpoint. This node refuses to start on "
                        + "it rather than lose data quietly; point it at a store that honours conditional writes, or "
                        + "switch the probe off with conditional-write-probe=false if you know better.");
            }
            Object token = store.version(key).orElseThrow(() -> new IllegalStateException(endpoint
                    + " reports no version token for " + key + ", which it has just stored: a compare-and-set has "
                    + "nothing to compare against on this endpoint, so this node will not start over it."));
            if (!store.writeVersioned(key, second, token)) {
                throw new IllegalStateException(endpoint + " refused to replace " + key + " under the token it had "
                        + "itself reported for it (" + token + "): a replace-if-unchanged that fails while nothing "
                        + "changed can never land, so this node will not start over it.");
            }
            if (store.writeVersioned(key, first, token)) {
                throw new IllegalStateException(endpoint + " accepted a replace of " + key + " under a stale token ("
                        + token + ") that had to fail: this endpoint honours only-if-absent but not replace-if-unchanged "
                        + "(Exoscale's SOS takes no ETag on If-Match), so two nodes over it would overwrite each other's "
                        + "updates without a trace. This node refuses to start on it rather than lose data quietly; point "
                        + "it at a store that honours conditional writes, or switch the probe off with "
                        + "conditional-write-probe=false if you know better.");
            }
        } finally {
            store.delete(key);
        }
    }
}
