package build.jenesis.repository.store;

import module java.base;

/**
 * A newest-first index over a store namespace: one small object per entry, keyed by the entry's instant
 * <em>inverted</em>, so that a plain ordered page of the namespace is the most recent entries first.
 *
 * <p><b>Why the key carries the order.</b> A roll-up index keyed by the entry's own id - a content hash, a scan id -
 * is in no useful order at all, so a screen wanting "the newest first" has to read every row and sort. That is the
 * shape &sect;10 forbids on a request path, and it is not fixable by paging the id-keyed namespace, because a page of
 * an arbitrary order is an arbitrary subset rather than the newest. Putting the order <em>in the key</em> is what
 * makes the two compatible: the store's own ordering is the answer, so one bounded page is both correct and cheap.
 *
 * <p><b>Inverted, zero-padded, fixed width.</b> The key's ordering component is
 * {@code Long.MAX_VALUE - epochMilli} rendered to {@value #ORDER_KEY_DIGITS} digits. Inverted so ascending key order
 * is descending time; zero-padded so <em>lexicographic</em> order - the only order a key-value store promises -
 * agrees with numeric order, since {@code 9} would otherwise sort after {@code 10}.
 *
 * <p>The padding is insurance rather than a live fix, and saying so is the honest form: every instant between the
 * epoch and the year 3000 inverts to a 19-digit number anyway, so the widths already agree and dropping the padding
 * would not break anything today. It is kept because the property then holds <em>by construction</em> rather than by
 * an accident of where the epoch sits, and because the failure it guards against is silent - a wrong page, not an
 * error.
 *
 * <p><b>Instants before the epoch are clamped to it.</b> A negative epoch-milli inverts to a number above
 * {@code Long.MAX_VALUE}, which overflows to a negative value, renders with a minus sign, and sorts before every
 * digit - so such a row would read as the newest thing in the index. Clamping costs one comparison and removes the
 * case; no caller here has a pre-epoch instant, and the point is that none can introduce one.
 *
 * <p><b>A discriminator, because an instant is not unique.</b> Two entries can share a millisecond, and a key
 * collision would lose one of them. The caller's identity string is digested into the key, so same-millisecond
 * entries get distinct keys and a stable order among themselves.
 *
 * <p><b>What it does not do.</b> It stores whatever bytes the caller hands it and hands them back; the payload's
 * shape is the caller's business, because the two callers here index different things. It does not reconcile: a row
 * whose subject has since been removed without the index hearing of it is the caller's problem, which is why a
 * caller filtering rows asks for more than it renders and why each has a background pass that backfills.
 */
public final class RecentIndex {

    /** Enough digits for {@link Long#MAX_VALUE}, so every key is the same width and sorts lexicographically. */
    private static final int ORDER_KEY_DIGITS = 19;

    private final ArtifactStore store;

    private final String root;

    /**
     * @param root the namespace the rows live under, relative to {@code store} - a caller scopes the store first, so
     *             two subjects indexed in one repository are two roots and never one shared namespace.
     */
    public RecentIndex(ArtifactStore store, String root) {
        this.store = Objects.requireNonNull(store, "store");
        this.root = Objects.requireNonNull(root, "root");
    }

    /** One row: the bare key name it is stored under - which is the cursor a caller resumes from - and its bytes. */
    public record Row(String name, byte[] content) {
    }

    /** A bounded page, newest first, and the name to resume after - {@code null} when the index is exhausted. */
    public record Page(List<Row> rows, String next) {
    }

    /** Write the row create-only: an entry already indexed is left exactly as it is, so recording twice is safe and
     *  a re-record never rewrites history. */
    public void record(Instant at, String identity, byte[] content) throws IOException {
        store.writeVersioned(key(at, identity), content, null);
    }

    /** Write the row only if it is absent - the idempotent backfill a reconcile pass makes, where a settled row
     *  should cost a read and no write per pass. */
    public void ensure(Instant at, String identity, byte[] content) throws IOException {
        String key = key(at, identity);
        if (store.readVersioned(key).isEmpty()) {
            store.writeVersioned(key, content, null);
        }
    }

    /** Drop the row for an entry, which needs the instant it was recorded with because that is what the key encodes.
     *  A caller that has lost the instant leaves a row for its backfill pass to reconcile rather than guessing. */
    public void forget(Instant at, String identity) throws IOException {
        store.delete(key(at, identity));
    }

    /**
     * At most {@code limit} rows after {@code after} - the bare name of the previous page's last row, or {@code null}
     * from the newest - in newest-first order.
     *
     * <p>One more than the limit is listed, so "are there more" is answered by the same read rather than by a second
     * one or by a count.
     */
    public Page page(String after, int limit) throws IOException {
        List<String> names = new ArrayList<>();
        store.page(root, after == null ? "" : after, ArtifactStore.oneMoreThan(limit), names::add);
        boolean more = names.size() > limit;
        List<String> window = more ? names.subList(0, limit) : names;
        List<Row> rows = new ArrayList<>(window.size());
        for (String name : window) {
            store.readVersioned(root + "/" + name)
                    .ifPresent(versioned -> rows.add(new Row(name, versioned.content())));
        }
        return new Page(List.copyOf(rows), more ? window.getLast() : null);
    }

    /** The key a row takes: the inverted instant, then a digest of the caller's identity to separate two entries
     *  sharing a millisecond. */
    private String key(Instant at, String identity) {
        long milli = Math.max(0L, at.toEpochMilli());
        return root + "/" + String.format("%0" + ORDER_KEY_DIGITS + "d", Long.MAX_VALUE - milli)
                + "-" + digest(identity);
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
