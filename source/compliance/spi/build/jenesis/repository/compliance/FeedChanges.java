package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The change log an {@link AdvisorySource.Changes} feed keeps in its own signal space: each draw's packages as one
 * entry with a sequence, the position each of the feed's change lists was read to, and the entries kept. One home for
 * what every change-publishing feed stores, so a reader's position means the same against any of them.
 *
 * <p>The document is a {@link Properties} under {@link #KEY}: {@code seq}, {@code position.<list>} per list,
 * {@code entry.<seq>.at}, {@code entry.<seq>.gap} and {@code entry.<seq>.packages} (one {@code <ecosystem>\t<coordinate>}
 * per line) per kept entry. Committed by compare-and-set against the version the draw opened, so a draw that raced
 * another fails rather than overwriting it; the refresh pass's lease keeps one drawer.
 *
 * <p>Bounded: {@link #KEPT} entries, oldest dropped first, and a reader whose position fell off them reads a gap. A
 * package whose name carries a line break or a tab cannot be told apart from the next and is not named.
 */
public final class FeedChanges {

    /** The log's key in the feed's signal space. */
    public static final String KEY = "changes";

    /** The most draws the log keeps: a day of draws at the refresh pass's five-minute cadence. */
    public static final int KEPT = 288;

    private FeedChanges() {
    }

    /** What one draw found: the positions to resume each list from, the packages it named, and whether it skipped
     *  changes it could not name. */
    public record Draw(Map<String, String> positions, Set<AdvisorySource.Package> packages, boolean gap) {

        public Draw {
            positions = Map.copyOf(positions);
            packages = Set.copyOf(packages);
        }
    }

    /** One entry of the log. */
    private record Entry(long seq, Instant at, boolean gap, List<AdvisorySource.Package> packages) {
    }

    /** The log as a draw opened it: its positions, and what the commit compares against. */
    public static final class Opened {

        private final long seq;
        private final Map<String, String> positions;
        private final List<Entry> entries;
        private final Object token;

        private Opened(long seq, Map<String, String> positions, List<Entry> entries, Object token) {
            this.seq = seq;
            this.positions = Map.copyOf(positions);
            this.entries = List.copyOf(entries);
            this.token = token;
        }

        /** Where each list was read to by the last committed draw; empty for a feed never drawn. */
        public Map<String, String> positions() {
            return positions;
        }
    }

    /** The log of {@code space} as it stands. */
    public static Opened open(ArtifactStore space) throws IOException {
        Optional<ArtifactStore.Versioned> stored = space.readVersioned(KEY);
        if (stored.isEmpty()) {
            return new Opened(0, Map.of(), List.of(), null);
        }
        Properties document = new Properties();
        document.load(new StringReader(new String(stored.get().content(), StandardCharsets.UTF_8)));
        Map<String, String> positions = new TreeMap<>();
        SortedSet<Long> seqs = new TreeSet<>();
        for (String key : document.stringPropertyNames()) {
            if (key.startsWith("position.")) {
                positions.put(key.substring("position.".length()), document.getProperty(key));
            } else if (key.startsWith("entry.") && key.endsWith(".at")) {
                seqs.add(Long.parseLong(key.substring("entry.".length(), key.length() - ".at".length())));
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (long seq : seqs) {
            List<AdvisorySource.Package> packages = new ArrayList<>();
            for (String line : document.getProperty("entry." + seq + ".packages", "").split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    packages.add(new AdvisorySource.Package(line.substring(0, tab), line.substring(tab + 1)));
                }
            }
            entries.add(new Entry(seq, Instant.parse(document.getProperty("entry." + seq + ".at")),
                    Boolean.parseBoolean(document.getProperty("entry." + seq + ".gap")), packages));
        }
        return new Opened(Long.parseLong(document.getProperty("seq", "0")), positions, entries,
                stored.get().token());
    }

    /**
     * Commit {@code draw} after the log {@code opened} read, as of {@code at}: a new entry where it named a package or
     * skipped any, and its positions either way. Answers how many packages it named; raises when the log moved since
     * it was opened, leaving it as the other writer left it.
     */
    public static int commit(ArtifactStore space, Opened opened, Draw draw, Instant at) throws IOException {
        Set<AdvisorySource.Package> named = new LinkedHashSet<>();
        for (AdvisorySource.Package candidate : draw.packages()) {
            if (plain(candidate.ecosystem()) && plain(candidate.coordinate())) {
                named.add(candidate);
            }
        }
        if (named.isEmpty() && !draw.gap() && draw.positions().equals(opened.positions())) {
            return 0;
        }
        List<Entry> entries = new ArrayList<>(opened.entries);
        long seq = opened.seq;
        if (!named.isEmpty() || draw.gap()) {
            seq++;
            entries.add(new Entry(seq, at, draw.gap(), List.copyOf(named)));
        }
        while (entries.size() > KEPT) {
            entries.removeFirst();
        }
        Properties document = new Properties();
        document.setProperty("seq", Long.toString(seq));
        draw.positions().forEach((list, position) -> document.setProperty("position." + list, position));
        for (Entry entry : entries) {
            document.setProperty("entry." + entry.seq() + ".at", entry.at().toString());
            document.setProperty("entry." + entry.seq() + ".gap", Boolean.toString(entry.gap()));
            StringBuilder packages = new StringBuilder();
            for (AdvisorySource.Package listed : entry.packages()) {
                packages.append(listed.ecosystem()).append('\t').append(listed.coordinate()).append('\n');
            }
            document.setProperty("entry." + entry.seq() + ".packages", packages.toString());
        }
        StringWriter text = new StringWriter();
        document.store(text, null);
        if (!space.writeVersioned(KEY, text.toString().getBytes(StandardCharsets.UTF_8), opened.token)) {
            throw new IOException("The change log moved under this draw; the next draw resumes from the log");
        }
        return named.size();
    }

    /** What the log of {@code space} holds after sequence {@code after} - see {@link AdvisorySource.ChangeLog}. */
    public static AdvisorySource.ChangeLog read(ArtifactStore space, long after) throws IOException {
        Opened log = open(space);
        if (log.token == null) {
            return new AdvisorySource.ChangeLog(0, Set.of(), after > 0);
        }
        // A position past the last sequence is one the log no longer knows: a log rewritten from empty.
        boolean gap = after > log.seq;
        long oldest = log.entries.isEmpty() ? log.seq + 1 : log.entries.getFirst().seq();
        if (after < oldest - 1) {
            gap = true;
        }
        Set<AdvisorySource.Package> packages = new LinkedHashSet<>();
        for (Entry entry : log.entries) {
            if (entry.seq() > after) {
                gap |= entry.gap();
                packages.addAll(entry.packages());
            }
        }
        return new AdvisorySource.ChangeLog(log.seq, packages, gap);
    }

    private static boolean plain(String value) {
        return value != null && !value.isBlank() && value.indexOf('\n') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\t') < 0;
    }
}
