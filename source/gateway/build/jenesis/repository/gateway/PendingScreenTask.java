package build.jenesis.repository.gateway;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ScreeningMode;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.RetroactiveHolds;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Names;
import build.jenesis.repository.store.Publication;

/**
 * Screens again every copy served while an advisory feed could not answer, so a repository that admits through an
 * outage ({@link ScreeningMode#ADMIT}, {@link ScreeningMode#RECORD}) closes the window once the feed answers, on its
 * own cadence rather than that of the passes that read the feeds.
 *
 * <p>A fill admitted that way leaves a marker under {@value ScreeningMode#PENDING_ROOT} naming the copy's path. Per
 * repository, this pass pages the markers and re-screens each copy from its stored bytes through the same decision a
 * fill makes, under the repository's current mode and gate:
 * <ul>
 *   <li>a feed that still cannot answer leaves the marker, and the copy serving;</li>
 *   <li>a decision that serves - the gate allows it, or the repository records - clears the marker;</li>
 *   <li>a decision that withholds holds every file of the version for review through {@link RetroactiveHolds}, the
 *       hold a reviewer releases as any gate hold, and clears the marker. A refusal is held rather than discarded,
 *       since the bytes were served and a reviewer needs them to see what was.</li>
 * </ul>
 * A marker whose path serves nothing - the copy never landed, was evicted or is held already - is cleared.
 *
 * <p>Idempotent and stateless: a crash leaves the markers it had not cleared, which the next pass reads again. It is
 * lease-owned, since it places holds. The set it reads holds only what was admitted during outages and is cleared as
 * the feeds answer, so a pass over a repository that admitted nothing is one listing.
 */
public final class PendingScreenTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingScreenTask.class);

    /** The task name - also the {@code locks/pending-rescreen} object the pass locks on. */
    public static final String NAME = "pending-rescreen";

    private final Duration interval;
    private final Function<UnaryOperator<String>, ComplianceGate> gates;
    private final ToIntFunction<UnaryOperator<String>> holdDays;

    /** {@code gates} answers the gate a repository's fetches are screened through from its effective settings, and
     *  {@code holdDays} its immaturity window. */
    public PendingScreenTask(Duration interval, Function<UnaryOperator<String>, ComplianceGate> gates,
                             ToIntFunction<UnaryOperator<String>> holdDays) {
        this.interval = Objects.requireNonNull(interval, "interval");
        this.gates = Objects.requireNonNull(gates, "gates");
        this.holdDays = Objects.requireNonNull(holdDays, "holdDays");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        ArtifactStore store = context.store();
        // One page of markers held at a time, however many a long outage left: each is deleted once decided, which
        // moves nothing under the drain's cursor.
        Names markers = Names.over(store, ScreeningMode.PENDING_ROOT);
        String marker = markers.next();
        if (marker == null) {
            return;
        }
        UnaryOperator<String> config = context.config();
        ProxyScreen screen = new ProxyScreen(gates.apply(config), store, holdDays.applyAsInt(config))
                .screening(ScreeningMode.of(config));
        Publication publication = new Publication(store);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        long pending = 0;
        long held = 0;
        for (; marker != null; marker = markers.next()) {
            String key = ScreeningMode.PENDING_ROOT + "/" + marker;
            Optional<String> path = path(store, key);
            Optional<String> blob = path.isEmpty() ? Optional.empty() : publication.located(path.get());
            if (blob.isEmpty()) {
                store.delete(key);
                continue;
            }
            ProxyScreen.Screening screening;
            try (InputStream body = store.open(blob.get())) {
                screening = screen.rescreen(path.get(), body);
            }
            if (screening.pending()) {
                pending++;
                continue;
            }
            if (screening.verdict() != Verdict.ALLOW && hold(store, publication, inventory, context.now(),
                    path.get(), screening)) {
                held++;
            }
            store.delete(key);
        }
        if (held > 0) {
            LOGGER.warn("Repository {}/{} held {} cop(ies) served while a feed could not answer, now that it does",
                    context.tenant(), context.repository(), held);
        }
        context.gauge("jenrepo.screen.pending",
                "Copies a repository served while an advisory feed could not answer, still waiting for one",
                Map.of("tenant", context.tenant(), "repository", context.repository()), pending);
    }

    /** The path a marker names, empty for a marker that names none. */
    private static Optional<String> path(ArtifactStore store, String key) throws IOException {
        Optional<ArtifactStore.Versioned> marker = store.readVersioned(key);
        if (marker.isEmpty()) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        properties.load(new InputStreamReader(new ByteArrayInputStream(marker.get().content()), StandardCharsets.UTF_8));
        return Optional.ofNullable(properties.getProperty("path")).filter(path -> !path.isBlank());
    }

    /** Hold every file of the version {@code path} belongs to, or the path alone where no layout places it. */
    private static boolean hold(ArtifactStore store, Publication publication, StoreRepositoryInventory inventory,
                                Instant now, String path, ProxyScreen.Screening screening) throws IOException {
        Optional<ArtifactDescriptor> described = inventory.describe(path);
        String ecosystem = described.map(ArtifactDescriptor::ecosystem).orElse("");
        String coordinate = described.map(ArtifactDescriptor::coordinate).orElse(screening.coordinate());
        String version = described.map(ArtifactDescriptor::version).orElse("");
        List<String> paths = described.isPresent() && described.get().coordinate() != null
                && described.get().version() != null ? inventory.paths(ecosystem, coordinate, version) : List.of();
        if (paths.isEmpty()) {
            paths = List.of(path);
        }
        List<String> reasons = new ArrayList<>(screening.reasons());
        reasons.add("Held on a later screen: served while an advisory feed could not answer, and withheld now that "
                + "it does");
        return RetroactiveHolds.hold(store, publication, inventory, new QuarantineLog(store), now, ecosystem,
                coordinate, version, paths, new RetroactiveHolds.Grounds(screening.rules(), screening.coordinate(),
                        reasons), () -> {
                });
    }
}
