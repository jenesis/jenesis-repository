package build.jenesis.repository.staging.store.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A reusable delegating {@link ArtifactStore} that injects a rival concurrent writer's mutations <em>between</em> a
 * target's read and its act - the deterministic form of the read-then-act (TOCTOU) race the staging promote/rollback
 * gate defends against (#212). It is the extraction of the hand-rolled racing doubles this test file used to re-write by
 * hand ({@code StealLeaseOnFirstRelease}, {@code FailSecondHandle}), each reimplementing all eleven {@code ArtifactStore}
 * methods around a one-line injection.
 *
 * <p><b>Colocation note.</b> This is the same extraction as the server test module's {@code RacingStore}
 * ({@code build.jenesis.repository.server.kernel.test.RacingStore}); it is duplicated here rather than shared because the
 * two white-box test roots reach no common source-level test-support module - {@code StoreStaging}
 * are module-private to {@code build.jenesis.repository.staging.store} and can only be exercised from beside them, while
 * the gate-owned races run only in the server test root. The shape is identical so a reader who knows one knows both.
 *
 * <p>Register interceptors fluently over a real backing store; each fires <b>once</b> (the first matching call) and then
 * goes quiet, exactly as the hand-rolled {@code injected} / {@code fired} one-shot flags did:
 *
 * <ul>
 *   <li>{@link #before(String, String, RivalWrites) before} - run the rival's writes, then delegate the op.</li>
 *   <li>{@link #after(String, Predicate, RivalWrites) after} - delegate the op, and only if it <em>succeeded</em> (a
 *       {@code void} op that did not throw, or a {@code writeVersioned} that returned {@code true}) run the rival - the
 *       lease-steal timing (the release must land first, then the rival grabs the lapsed lease).</li>
 *   <li>{@link #insteadFail(String, Predicate, RivalWrites, String) insteadFail} - run the rival, then throw an
 *       {@link IOException} <em>instead of</em> delegating - the slow-handle-failure timing (mid-op the lease lapses, a
 *       rival acquires and seals it, then this node's op fails).</li>
 * </ul>
 *
 * <p>The {@link RivalWrites} lambda receives the <b>delegate</b> store (never {@code this}, so a rival write cannot
 * re-trigger an interceptor and recurse) plus the matched key. Only {@code delete}, {@code write} and
 * {@code writeVersioned} are interceptable; every other method delegates untouched. A test double, never a backend.
 */
public final class RacingStore implements ArtifactStore {
    @Override
    public Object identity() {
        return delegate.identity();   // a decorator answers its delegate's subspace
    }

    /** A concurrent rival's durable writes, run at an injection point against the delegate store, given the matched
     *  key. Declares {@code IOException} so a rival mutation can propagate a genuine store failure unchanged. */
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
