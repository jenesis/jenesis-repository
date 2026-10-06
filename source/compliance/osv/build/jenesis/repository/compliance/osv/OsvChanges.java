package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.FeedChanges;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.store.ArtifactStore;

/**
 * OSV's change lists drawn into a log: each ecosystem's export publishes {@code <ecosystem>/modified_id.csv}, every
 * record's last modification and id, newest first. A draw reads each list from its head down to the position the last
 * draw reached, fetches each record named since from {@code /v1/vulns/<id>}, and commits the packages those records
 * affect as one entry of the feed's {@link FeedChanges change log}, which every node's scan reads from its own position;
 * each list's position is the instant of the last line it was read to.
 *
 * <p>A feed that publishes a part of OSV's records - the malicious-package feed, its {@code MAL-} ones - draws the same
 * lists into a log of its own, keeping only the ids it {@linkplain #of keeps}: a line it does not keep moves the
 * position and fetches nothing.
 *
 * <p>Bounded: a draw reads at most {@link OsvChangeList#WINDOW} bytes of each list and fetches at most
 * {@link #RECORDS} records, oldest first, resuming where it stopped; a list whose position lies past the window is
 * recorded as a gap, never as a shorter list. The first draw of an ecosystem only records where its list stands. An ecosystem the export holds no
 * list for has no record in OSV yet, which is not a failure: its position stands at the epoch, so every line of the list
 * it gains is drawn as a change.
 */
public final class OsvChanges {

    /** The most records one draw fetches. */
    static final int RECORDS = 500;

    private final FeedClient client;
    private final URI export;
    private final URI vulns;
    private final Supplier<ArtifactStore> space;
    private final Clock clock;
    private final Predicate<String> kept;

    OsvChanges(FeedClient client, URI export, URI vulns, Supplier<ArtifactStore> space, Clock clock) {
        this(client, export, vulns, space, clock, _ -> true);
    }

    private OsvChanges(FeedClient client, URI export, URI vulns, Supplier<ArtifactStore> space, Clock clock,
                       Predicate<String> kept) {
        this.kept = kept;
        this.client = client;
        this.export = export.toString().endsWith("/") ? export : URI.create(export + "/");
        this.vulns = vulns;
        this.space = space;
        this.clock = clock;
    }

    /** OSV's change lists at {@code export}, each record named fetched from {@code vulns} through {@code client} and
     *  committed to the log in {@code space} on {@code clock}, keeping the records whose id {@code kept} accepts. */
    public static OsvChanges of(FeedClient client, URI export, URI vulns, Supplier<ArtifactStore> space, Clock clock,
                                Predicate<String> kept) {
        return new OsvChanges(client, export, vulns, space, clock, kept);
    }

    /** Draw what changed since the last draw into the log, answering how many packages it named. */
    public int draw() throws IOException {
        ArtifactStore store = space.get();
        FeedChanges.Opened log = FeedChanges.open(store);
        Map<String, String> positions = new TreeMap<>(log.positions());
        Set<AdvisorySource.Package> named = new LinkedHashSet<>();
        boolean gap = false;
        int budget = RECORDS;
        for (String ecosystem : OsvQuery.osvEcosystems()) {
            Optional<Instant> position = Optional.ofNullable(positions.get(ecosystem)).map(Instant::parse);
            Optional<OsvChangeList.Listed> answered = OsvChangeList.read(client, export, ecosystem,
                    position.orElse(null));
            if (answered.isEmpty()) {
                // The export holds no list for an ecosystem OSV has no record of yet. Every record it later lists is
                // a change, so the list stands at the start of time until it appears.
                if (position.isEmpty()) {
                    positions.put(ecosystem, Instant.EPOCH.toString());
                }
                continue;
            }
            OsvChangeList.Listed listed = answered.get();
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
            for (OsvChangeList.Line line : listed.lines().reversed()) {
                if (budget <= 0 && !line.modified().equals(reached)) {
                    break;
                }
                if (kept.test(line.id())) {
                    named.addAll(affected(line.id()));
                    budget--;
                }
                reached = line.modified();
            }
            positions.put(ecosystem, reached.toString());
        }
        return FeedChanges.commit(store, log, new FeedChanges.Draw(positions, named, gap), clock.instant());
    }

    /** What the log holds after sequence {@code after}. */
    public AdvisorySource.ChangeLog changes(long after) throws IOException {
        return FeedChanges.read(space.get(), after);
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
}
