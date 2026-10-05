package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.FeedChanges;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.store.ArtifactStore;
import java.time.format.DateTimeParseException;

/**
 * OSV's change lists drawn into a log: each ecosystem's export publishes {@code <ecosystem>/modified_id.csv}, every
 * record's last modification and id, newest first. A draw reads each list from its head down to the position the last
 * draw reached, fetches each record named since from {@code /v1/vulns/<id>}, and commits the packages those records
 * affect as one entry of the feed's {@link FeedChanges change log}, which every node's scan reads from its own position;
 * each list's position is the instant of the last line it was read to.
 *
 * <p>Bounded: a draw reads at most {@link #WINDOW} bytes of each list and fetches at most {@link #RECORDS} records,
 * oldest first, resuming where it stopped; a list whose position lies past the window is recorded as a gap, never as a
 * shorter list. The first draw of an ecosystem only records where its list stands.
 */
final class OsvChanges {

    /** The most of one change list a draw reads. */
    static final int WINDOW = 1 << 20;

    /** The most records one draw fetches. */
    static final int RECORDS = 500;

    private final FeedClient client;
    private final URI export;
    private final URI vulns;
    private final Supplier<ArtifactStore> space;
    private final Clock clock;

    OsvChanges(FeedClient client, URI export, URI vulns, Supplier<ArtifactStore> space, Clock clock) {
        this.client = client;
        this.export = export.toString().endsWith("/") ? export : URI.create(export + "/");
        this.vulns = vulns;
        this.space = space;
        this.clock = clock;
    }

    /** One line of a change list. */
    private record Line(Instant modified, String id) {
    }

    int draw() throws IOException {
        ArtifactStore store = space.get();
        FeedChanges.Opened log = FeedChanges.open(store);
        Map<String, String> positions = new TreeMap<>(log.positions());
        Set<AdvisorySource.Package> named = new LinkedHashSet<>();
        boolean gap = false;
        int budget = RECORDS;
        for (String ecosystem : OsvQuery.osvEcosystems()) {
            Optional<Instant> position = Optional.ofNullable(positions.get(ecosystem)).map(Instant::parse);
            Listed listed = list(ecosystem, position.orElse(null));
            if (listed.lines().isEmpty()) {
                continue;
            }
            if (position.isEmpty() || listed.exhausted()) {
                // A first draw records where the list stands; a list whose position lies past the window cannot say
                // what it skipped, so it records that it skipped.
                gap |= position.isPresent();
                positions.put(ecosystem, listed.lines().getFirst().modified().toString());
                continue;
            }
            // Oldest first, so a draw that runs out of budget resumes after what it named; a boundary instant is
            // named whole, since the position is exclusive.
            Instant reached = position.get();
            for (Line line : listed.lines().reversed()) {
                if (budget <= 0 && !line.modified().equals(reached)) {
                    break;
                }
                named.addAll(affected(line.id()));
                budget--;
                reached = line.modified();
            }
            positions.put(ecosystem, reached.toString());
        }
        return FeedChanges.commit(store, log, new FeedChanges.Draw(positions, named, gap), clock.instant());
    }

    AdvisorySource.ChangeLog changes(long after) throws IOException {
        return FeedChanges.read(space.get(), after);
    }

    /** What one list says since {@code position} - every line after it, newest first - and whether the window ran out
     *  before reaching it. Without a position, its head alone. */
    private record Listed(List<Line> lines, boolean exhausted) {
    }

    private Listed list(String ecosystem, Instant position) throws IOException {
        FeedRequest request = FeedRequest.get(export.resolve(URLEncoder.encode(ecosystem, StandardCharsets.UTF_8)
                .replace("+", "%20") + "/modified_id.csv"));
        try {
            return client.fetch(request, FeedClient.Reader.document(body -> {
                List<Line> lines = new ArrayList<>();
                BoundedInput window = new BoundedInput(body, WINDOW);
                BufferedReader reader = new BufferedReader(new InputStreamReader(window, StandardCharsets.UTF_8));
                String text;
                while ((text = reader.readLine()) != null) {
                    int comma = text.indexOf(',');
                    if (comma < 0) {
                        continue;
                    }
                    Instant modified;
                    try {
                        modified = Instant.parse(text.substring(0, comma).strip());
                    } catch (DateTimeParseException unreadable) {
                        continue;
                    }
                    String id = text.substring(comma + 1).strip();
                    int slash = id.lastIndexOf('/');
                    id = slash < 0 ? id : id.substring(slash + 1);
                    if (position == null) {
                        return new Listed(List.of(new Line(modified, id)), false);
                    }
                    if (!modified.isAfter(position)) {
                        return new Listed(List.copyOf(lines), false);
                    }
                    lines.add(new Line(modified, id));
                }
                // The list ended, or the window did: only a window that ran out leaves the position unreached.
                return new Listed(List.copyOf(lines), window.exhausted());
            })).value().orElseThrow();
        } catch (FeedException e) {
            throw new IOException("Could not read OSV's " + ecosystem + " change list (" + OsvQuery.reason(e) + ")",
                    e);
        }
    }

    /** The packages a record affects, in the product's ecosystem names; none for a record OSV no longer serves. */
    private Set<AdvisorySource.Package> affected(String id) throws IOException {
        JsonNode record;
        try {
            record = OsvQuery.fetch(client, vulns, id);
        } catch (IOException e) {
            if (e.getCause() instanceof FeedException failed && failed.status() == 404) {
                return Set.of();
            }
            throw e;
        }
        Set<AdvisorySource.Package> packages = new LinkedHashSet<>();
        for (JsonNode affected : record.path("affected")) {
            JsonNode named = affected.path("package");
            String ecosystem = named.path("ecosystem").asString("");
            String name = named.path("name").asString("");
            int release = ecosystem.indexOf(':');
            OsvQuery.ecosystem(release < 0 ? ecosystem : ecosystem.substring(0, release))
                    .filter(_ -> !name.isBlank())
                    .ifPresent(product -> packages.add(new AdvisorySource.Package(product, name)));
        }
        return packages;
    }

    /** At most {@code limit} bytes of {@code in}, remembering whether the limit, not the stream, ended the read. */
    private static final class BoundedInput extends FilterInputStream {

        private long remaining;
        private boolean exhausted;

        BoundedInput(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        boolean exhausted() {
            return exhausted;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                exhausted = true;
                return -1;
            }
            int read = super.read();
            if (read >= 0) {
                remaining--;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                exhausted = true;
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
