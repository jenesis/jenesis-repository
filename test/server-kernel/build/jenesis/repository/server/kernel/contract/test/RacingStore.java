package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A reusable delegating {@link ArtifactStore} that injects a rival concurrent writer's mutations <em>between</em> a
 * target's read and its act - the deterministic form of the read-then-act (TOCTOU) race the withhold/staging gates
 * defend against. It is the extraction of the hand-rolled racing doubles that four audits' worth of tests each
 * re-wrote by hand: {@code RacingEnforceStore} / {@code EvictingClaimantStore} in the #207/#214 reconcile test
 * ({@code WithheldReconcileTaskTest}) and {@code StealLeaseOnFirstRelease} / {@code FailSecondHandle} in the #212
 * promote-rollback test ({@code StoreStagingTest}). Every one had the identical shape - "wrap the store, watch for a
 * named op on a matching key, run the rival's durable writes at that instant, then proceed (or abort)" - reimplementing
 * all eleven {@code ArtifactStore} methods around a one-line injection. This harness names that shape once so a new
 * release/rollback site's race test declares only the interleaving, not the plumbing.
 *
 * <p>Register interceptors fluently over a real backing store; each fires <b>once</b> (the first matching call) and
 * then goes quiet, exactly as the hand-rolled {@code injected} / {@code fired} one-shot flags did:
 *
 * <ul>
 *   <li>{@link #before(String, String, RivalWrites) before} - run the rival's writes, then delegate the op. This is the
 *       canonical form the plan names ({@code RacingStore.before("delete", key, rivalWrites)}): the reconcile clears a
 *       marker while a concurrent enforce has just written its hold; the rival's {@code holds/}+{@code /quarantine}
 *       writes land in the delegate an instant before the marker delete does, so the target's post-act re-verify reads
 *       a world where the hold now exists.</li>
 *   <li>{@link #after(String, Predicate, RivalWrites) after} - delegate the op, and only if it <em>succeeded</em> (a
 *       {@code void} op that did not throw, or a {@code writeVersioned} that returned {@code true}) run the rival. This
 *       is the lease-steal timing: the release pointer must land <em>first</em>, then the rival grabs the lapsed lease,
 *       so the promoting node's next ownership re-check sees a rival owner.</li>
 *   <li>{@link #insteadFail(String, Predicate, RivalWrites, String) insteadFail} - run the rival, then throw an
 *       {@link IOException} <em>instead of</em> delegating. This is the slow-handle-failure timing: mid-op the lease
 *       lapses, a rival acquires it and seals the work, and only then does this node's op fail - so its rollback, if it
 *       ran unfenced, would retract the rival's committed release.</li>
 * </ul>
 *
 * <p>The {@link RivalWrites} lambda receives the <b>delegate</b> store (never {@code this}, so a rival write cannot
 * re-trigger an interceptor and recurse) plus the matched key, and does whatever a concurrent writer would: mark, clear,
 * link, steal a lease, seal a state marker. Only {@code delete}, {@code write} and {@code writeVersioned} are
 * interceptable - the write verbs a race turns on; every other method delegates untouched. A test double, never a
 * production backend.
 */
public final class RacingStore implements ArtifactStore {
    @Override
    public Object identity() {
        return delegate.identity();   // a decorator answers its delegate's subspace
    }

    /** A concurrent rival's durable writes, run at an injection point against the delegate store. Given the matched key
     *  so an interceptor keyed on a predicate can react to exactly which key fired (the lease-steal records the released
     *  path it saw). Declares {@code IOException} so a rival mutation can propagate a genuine store failure unchanged. */
    @FunctionalInterface
    public interface RivalWrites {
        void run(ArtifactStore store, String key) throws IOException;
    }

    private enum When { BEFORE, AFTER, INSTEAD_FAIL }

    private static final class Interceptor {
        private final String op;
        private final Predicate<String> keyMatch;
        private final When when;
        private final RivalWrites rival;
        private final String failMessage;
        private boolean fired;

        private Interceptor(String op, Predicate<String> keyMatch, When when, RivalWrites rival, String failMessage) {
            this.op = op;
            this.keyMatch = keyMatch;
            this.when = when;
            this.rival = rival;
            this.failMessage = failMessage;
        }
    }

    private final ArtifactStore delegate;
    private final List<Interceptor> interceptors = new ArrayList<>();

    private RacingStore(ArtifactStore delegate) {
        this.delegate = delegate;
    }

    /** Wrap {@code delegate} so interceptors can be registered over it. */
    public static RacingStore over(ArtifactStore delegate) {
        return new RacingStore(delegate);
    }

    /** Before the first {@code op} on the exact key {@code key}, run {@code rival}, then delegate. */
    public RacingStore before(String op, String key, RivalWrites rival) {
        return before(op, key::equals, rival);
    }

    /** Before the first {@code op} on a key matching {@code keyMatch}, run {@code rival}, then delegate. */
    public RacingStore before(String op, Predicate<String> keyMatch, RivalWrites rival) {
        interceptors.add(new Interceptor(op, keyMatch, When.BEFORE, rival, null));
        return this;
    }

    /** After the first <em>successful</em> {@code op} on a key matching {@code keyMatch}, run {@code rival}. */
    public RacingStore after(String op, Predicate<String> keyMatch, RivalWrites rival) {
        interceptors.add(new Interceptor(op, keyMatch, When.AFTER, rival, null));
        return this;
    }

    /** On the first {@code op} on a key matching {@code keyMatch}, run {@code rival} and then throw an
     *  {@link IOException} carrying {@code failMessage} <em>instead of</em> delegating the op. */
    public RacingStore insteadFail(String op, Predicate<String> keyMatch, RivalWrites rival, String failMessage) {
        interceptors.add(new Interceptor(op, keyMatch, When.INSTEAD_FAIL, rival, failMessage));
        return this;
    }

    // --- the interceptable write verbs -------------------------------------------------------------------------------

    @Override
    public void delete(String key) throws IOException {
        runInsteadFail("delete", key);
        runBefore("delete", key);
        delegate.delete(key);
        runAfter("delete", key, true);
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        runInsteadFail("write", key);
        runBefore("write", key);
        delegate.write(key, in);
        runAfter("write", key, true);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        runInsteadFail("writeVersioned", key);
        runBefore("writeVersioned", key);
        boolean ok = delegate.writeVersioned(key, content, expected);
        runAfter("writeVersioned", key, ok);
        return ok;
    }

    private void runInsteadFail(String op, String key) throws IOException {
        Interceptor hit = match(op, key, When.INSTEAD_FAIL);
        if (hit != null) {
            hit.fired = true;
            hit.rival.run(delegate, key);
            throw new IOException(hit.failMessage);
        }
    }

    private void runBefore(String op, String key) throws IOException {
        Interceptor hit = match(op, key, When.BEFORE);
        if (hit != null) {
            hit.fired = true;
            hit.rival.run(delegate, key);
        }
    }

    private void runAfter(String op, String key, boolean succeeded) throws IOException {
        if (!succeeded) {
            return;
        }
        Interceptor hit = match(op, key, When.AFTER);
        if (hit != null) {
            hit.fired = true;
            hit.rival.run(delegate, key);
        }
    }

    private Interceptor match(String op, String key, When when) {
        for (Interceptor interceptor : interceptors) {
            if (!interceptor.fired && interceptor.when == when && interceptor.op.equals(op)
                    && interceptor.keyMatch.test(key)) {
                return interceptor;
            }
        }
        return null;
    }

    // --- everything else delegates untouched -------------------------------------------------------------------------

    @Override
    public ArtifactStore scope(String tenant) {
        return delegate.scope(tenant);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        delegate.read(key, out);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return delegate.open(key);
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        return delegate.writeBlob(in);
    }

    @Override
    public long size(String key) throws IOException {
        return delegate.size(key);
    }

    @Override
    public List<String> list(String prefix) {
        return delegate.list(prefix);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        delegate.page(prefix, startAfter, limit, consumer);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(key);
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
