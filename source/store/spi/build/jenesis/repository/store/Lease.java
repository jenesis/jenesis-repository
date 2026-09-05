package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;

/**
 * A best-effort distributed lock over the artifact store: one small object per name holding the holder's id and an
 * expiry, taken by conditional write and reclaimed by its ttl, so a job that must run on one node at a time - a
 * maintenance sweep across a replicated server, a staging promotion driven from request threads - does, with no
 * coordinator. On an object store the token behind {@link ArtifactStore#writeVersioned} is the backend's ETag, so
 * the conditional write is a true cross-node compare-and-set.
 *
 * <p>Two classes used to implement this protocol, character for character apart from where their objects lived:
 * the maintenance scheduler's lease at {@code .system/locks/<name>} in the enterprise server module, and the staging
 * store's per-id lock at {@code staging-lock/<id>}, re-written inside that plugin because a plugin may not depend on
 * the server. The mechanism is the store's compare-and-set, so it lives beside {@link Retries}, the policy it retries
 * under; a caller names the space its locks live in and the ttl, and nothing else differs.
 *
 * <p>The protocol. {@link #acquire} takes a free or lapsed lease with a conditional write - an absent-create when
 * there is no object, a steal against the read token when the stored expiry has passed - and refuses while another
 * holder's lease is live, so two acquirers racing one expiry never both win. {@link #renew} pushes a live lease's
 * expiry out, so a slow-but-alive holder keeps single-writer status across a long pass instead of letting the lease
 * lapse and handing a rival a second, concurrent pass; it never steals, and a {@code false} tells the holder it has
 * already lost and must stop. {@link #release} expires the object back to {@code now} by compare-and-set so the next
 * acquirer takes it at once instead of waiting out the ttl; it never releases a rival's lease, and a release that
 * cannot land is abandoned - the lease then lapses on its own ttl, which is what happened before there was a
 * release. A crashed holder's lease lapses the same way, and {@link #reapExpired} clears the objects it leaves.
 *
 * <p>The stored body is two lines, holder then expiry, and a body whose expiry does not parse reads as lapsed: a
 * corrupt lock object is stealable rather than a wedge nothing can clear.
 */
public final class Lease {

    /** A store mutation of shared state on a leased path, run only while the lease is provably still held
     *  ({@link #guarded}). Declares {@link IOException} so a genuine store failure reaches the caller unchanged. */
    @FunctionalInterface
    public interface Action {

        void run() throws IOException;
    }

    private final ArtifactStore store;
    private final String root;
    private final Duration ttl;

    /** Leases in the product's own {@code .system/locks} space - the maintenance leases' home, at the deployment
     *  root, one object per named pass. */
    public Lease(ArtifactStore store, Duration ttl) {
        this(store, Scopes.space(Scopes.LOCKS), ttl);
    }

    /** Leases under {@code <root>/<name>} in the given store - for a lock that belongs inside a scope, such as a
     *  repository's own staging ids. */
    public Lease(ArtifactStore store, String root, Duration ttl) {
        this.store = store;
        this.root = root;
        this.ttl = ttl;
    }

    /** Try to hold {@code name} for {@code holder} until {@code now} plus the ttl; true if this node took it, false
     *  while a live lease of another holder is still held. */
    public boolean acquire(String name, String holder, Instant now) throws IOException {
        String key = key(name);
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        Object expected;
        if (current.isEmpty()) {
            expected = null;
        } else {
            Instant expiry = expiry(current.get());
            if (expiry != null && expiry.isAfter(now)) {
                return false;                                   // a live lease of another holder - refuse
            }
            expected = current.get().token();                  // free or expired - steal against its token
        }
        return store.writeVersioned(key, body(holder, now.plus(ttl)), expected);
    }

    /**
     * Extend {@code holder}'s live lease on {@code name} to {@code now} plus the ttl. Returns {@code true} when this
     * node still held the lease and pushed the expiry out; {@code false} - without writing anything - when the lock
     * is gone (never held, or expired and reclaimed) or a different holder now owns it (this node's lease lapsed and
     * was stolen): the caller has lost single-writer status and must stop. The extension is a compare-and-set
     * against the read token, so a renewal that races a concurrent takeover also answers {@code false} rather than
     * overwriting the winner.
     */
    public boolean renew(String name, String holder, Instant now) throws IOException {
        String key = key(name);
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        if (current.isEmpty() || !holder.equals(owner(current.get()))) {
            return false;
        }
        return store.writeVersioned(key, body(holder, now.plus(ttl)), current.get().token());
    }

    /**
     * Release {@code holder}'s lease on {@code name} by compare-and-setting the stored expiry back to {@code now}, so
     * the next acquirer takes the lock at once. Never releases a rival's lease: once the owner differs the object is
     * left untouched - a read-then-delete would delete a rival's freshly stolen lock in the window between the check
     * and the delete. A lost compare-and-set (a concurrent renewal by this same holder can race the release) is
     * retried under {@link Retries}; a release that still cannot land answers {@code false} and is abandoned, and the
     * lease lapses on its own ttl.
     */
    public boolean release(String name, String holder, Instant now) throws IOException {
        return Retries.tryUpdate(store, key(name), current ->
                current.isEmpty() || !holder.equals(owner(current.get())) ? null : body(holder, now));
    }

    /**
     * Whether this holder still provably owns {@code name}'s lease right now - a fresh compare-and-set renew against
     * the holder token. This is the fence: any unconditional mutation of shared state on a leased path - an
     * unpublish, a delete another node could race - gates on it, because the lease may have lapsed mid-pass and been
     * legitimately taken over by a rival that has already redone and sealed the same work. Prefer {@link #guarded},
     * which runs the mutation only when this is true; call this directly only to branch on ownership for more than
     * one action.
     */
    public boolean stillHeld(String name, String holder, Instant now) throws IOException {
        return renew(name, holder, now);
    }

    /**
     * Run {@code action} - an unconditional mutation of shared state on {@code name}'s leased path - only while this
     * holder still provably owns the lease ({@link #stillHeld}). Returns {@code true} if the action ran; {@code false},
     * touching nothing, when the lease was lost or ownership could not even be probed: a node that cannot prove it
     * holds the lease has no authority to mutate the leased path, so it skips rather than risk clobbering the rival,
     * and the caller surfaces its own original failure.
     */
    public boolean guarded(String name, String holder, Instant now, Action action) throws IOException {
        boolean owned;
        try {
            owned = stillHeld(name, holder, now);
        } catch (IOException | RuntimeException probe) {
            return false;
        }
        if (owned) {
            action.run();
            return true;
        }
        return false;
    }

    /** The holder whose lease on {@code name} is live at {@code now}, or empty when the lock is free, expired or was
     *  never taken - what an operator's or a test's view of the fleet reads to say which node owns a pass right now. */
    public Optional<String> holder(String name, Instant now) throws IOException {
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key(name));
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Instant expiry = expiry(current.get());
        return expiry != null && expiry.isAfter(now) ? Optional.of(owner(current.get())) : Optional.empty();
    }

    /**
     * Delete the lock objects whose lease has lapsed - what a crashed holder leaves behind on a name no later pass
     * revisits, and the expired objects {@link #release} leaves - so an orphaned lock never accumulates. A lock with
     * a live lease is left held; one whose body carries no parseable expiry is treated as lapsed. Best-effort: a live
     * lock deleted in the read-then-delete window only makes its holder's next {@link #renew} answer {@code false},
     * so it stops and retries - never a lost or double-applied mutation.
     */
    public void reapExpired(Instant now) throws IOException {
        Names names = Names.over(store, root);
        for (String name = names.next(); name != null; name = names.next()) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key(name));
            if (current.isPresent()) {
                Instant expiry = expiry(current.get());
                if (expiry == null || !expiry.isAfter(now)) {
                    store.delete(key(name));
                }
            }
        }
    }

    private String key(String name) {
        return root + "/" + name;
    }

    private static byte[] body(String holder, Instant expiry) {
        return (holder + "\n" + expiry).getBytes(StandardCharsets.UTF_8);
    }

    private static String owner(ArtifactStore.Versioned versioned) {
        String stored = new String(versioned.content(), StandardCharsets.UTF_8);
        int newline = stored.indexOf('\n');
        return newline < 0 ? stored : stored.substring(0, newline);
    }

    private static Instant expiry(ArtifactStore.Versioned versioned) {
        String stored = new String(versioned.content(), StandardCharsets.UTF_8);
        int newline = stored.indexOf('\n');
        try {
            return Instant.parse((newline < 0 ? stored : stored.substring(newline + 1)).trim());
        } catch (DateTimeParseException _) {
            return null;
        }
    }
}
