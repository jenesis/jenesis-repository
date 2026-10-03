package build.jenesis.repository.store;

import module java.base;

/**
 * The writes this node holds in memory before it lands them in the store - download counts, a credential's last use,
 * the deferred counters - as an operator sees them: what each holds, when it writes on its own, and the one act that
 * asks every one of them to write now. The API, the console and the CLI reach this one implementation, beside the
 * read caches' clear.
 *
 * <p><strong>Node-local.</strong> What is held is held by one process, so a write-now lands what the node that served
 * the request holds, and every other node writes its own on its cadence. Every holder also writes what it holds when
 * the node stops cleanly, so a write-now is for reading a figure before its cadence comes round, never for keeping it.
 *
 * <p>A holder registers while it can hold something - a tracker from its start to its close - and is held weakly, so
 * one a test drops is forgotten without unregistering.
 */
public final class HeldWrites {

    private static final Set<Holder> HOLDERS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private HeldWrites() {
    }

    /** One kind of write a node holds in memory. */
    public interface Holder {

        /** What is held, as an operator reads it: {@code download counts}. */
        String what();

        /** When it is written without being asked, as an operator reads it: {@code every PT6H per version}. */
        String cadence();

        /** How many entries are held unwritten now - versions, credentials, counters. */
        long pending();

        /** Ask for everything held to be written now. It may land on the holder's own thread a moment later, which
         *  is the thread that owns what is held; a write that fails stays held for the next. */
        void writeNow();
    }

    /** What one holder holds, as {@link #held()} reports it. */
    public record Held(String what, String cadence, long pending) {
    }

    /** Register {@code holder} until {@link #release}. */
    public static void hold(Holder holder) {
        HOLDERS.add(Objects.requireNonNull(holder, "holder"));
    }

    /** Forget {@code holder}: it holds nothing any more. */
    public static void release(Holder holder) {
        HOLDERS.remove(holder);
    }

    /** What every holder on this node holds, by what it holds. */
    public static List<Held> held() {
        return holders().stream().map(holder -> new Held(holder.what(), holder.cadence(), holder.pending()))
                .sorted(Comparator.comparing(Held::what)).toList();
    }

    /** Ask every holder on this node to write what it holds now, and answer what each held when asked. */
    public static List<Held> writeNow() {
        List<Held> asked = held();
        for (Holder holder : holders()) {
            holder.writeNow();
        }
        return asked;
    }

    private static List<Holder> holders() {
        synchronized (HOLDERS) {
            return List.copyOf(HOLDERS);
        }
    }
}
