package build.jenesis.repository.compliance.web;

import module java.base;

import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.gate.RetroLicensePlanner;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The retroactive licence enforcement dry run - what enabling enforcement would newly hold in a repository - as one
 * capability behind the API's {@code /api/licenses/retro/plan}, the console's enforcement preview and, through the
 * API, the CLI. The plan assesses every release, so it never runs on a request: a start runs it off the request under
 * a stored report per mode, and every surface reads that report back, so a recompute asked for on one surface is the
 * run the others see rather than a second walk beside it.
 *
 * <p>The configuration the plan reads is the caller's - the API's pins over its environment, the console's over its
 * stored settings - because each surface resolves the deployment's dials as its node resolves them.
 */
public final class LicenseBlastRadius {

    private final Optional<RetroLicensePlanner> planner;

    public LicenseBlastRadius(Optional<RetroLicensePlanner> planner) {
        this.planner = planner;
    }

    /** Whether a planner is installed; without one there is nothing to preview. */
    public boolean installed() {
        return planner.isPresent();
    }

    /** The stored report a mode runs under: the denied mode alone, or with the unidentified licences held too. */
    static String report(boolean includeUnknown) {
        return includeUnknown ? "blast-radius-unknown" : "blast-radius";
    }

    /**
     * Start the plan for the mode off the request; {@code bind} decorates the pass before it is handed to its thread -
     * the seam a caller that resolves its tenant from the request binds it through. Answers whether this call started
     * it rather than finding one running; {@code false} too without a planner.
     */
    public boolean start(ArtifactStore store, UnaryOperator<String> config, boolean includeUnknown,
                         UnaryOperator<StoredReport.Pass> bind) throws IOException {
        if (planner.isEmpty()) {
            return false;
        }
        RetroLicensePlanner plans = planner.get();
        return StoredReport.compute(store, report(includeUnknown),
                bind.apply(() -> rows(plans.plan(config, store, includeUnknown))));
    }

    /** Run the plan now and store its report - the test seam; every surface reads the result through {@link #read}. */
    public View computeNow(ArtifactStore store, UnaryOperator<String> config, boolean includeUnknown)
            throws IOException {
        RetroLicensePlanner plans = planner.orElseThrow(() -> new IllegalStateException("no licence policy"));
        Instant started = Instant.now();
        StoredReport.write(store, report(includeUnknown), started, Instant.now(),
                rows(plans.plan(config, store, includeUnknown)));
        return read(store, includeUnknown);
    }

    /** What the last run of the mode found, or that none has run, or that one is running now - one point read. */
    public View read(ArtifactStore store, boolean includeUnknown) throws IOException {
        String mode = includeUnknown ? "denied+unknown" : "denied";
        if (planner.isEmpty()) {
            return new View(false, mode, "not-installed", 0, List.of(), null, null);
        }
        Optional<StoredReport.Report> stored = StoredReport.read(store, report(includeUnknown));
        if (stored.isEmpty()) {
            return new View(true, mode, "not-computed", 0, List.of(), null, null);
        }
        StoredReport.Report report = stored.get();
        // A running or failed run keeps the last finished answer readable beneath it.
        Optional<StoredReport.Report> finished = report.lastFinished();
        List<Held> held = new ArrayList<>();
        for (String row : finished.map(StoredReport.Report::rows).orElse(List.of())) {
            String[] parts = row.split("\t", 4);
            if (parts.length == 4) {
                held.add(new Held(parts[0], parts[1], parts[2],
                        parts[3].isEmpty() ? List.of() : List.of(parts[3].split("; "))));
            }
        }
        boolean failed = report.status() == StoredReport.Status.FAILED;
        return new View(true, mode, report.running() ? "running" : failed ? "failed" : "done",
                finished.map(StoredReport.Report::count).orElse(0), held,
                finished.map(done -> done.finishedAt() == null ? done.startedAt() : done.finishedAt()).orElse(null),
                failed && report.failure() != null && !report.failure().isBlank() ? report.failure() : null);
    }

    private static StoredReport.Rows rows(RetroLicensePlanner.Plan plan) {
        List<String> rows = new ArrayList<>();
        for (RetroLicensePlanner.Held entry : plan.held()) {
            rows.add(entry.ecosystem() + "\t" + entry.coordinate() + "\t" + entry.version() + "\t"
                    + String.join("; ", entry.reasons()));
        }
        return new StoredReport.Rows(plan.count(), List.copyOf(rows.subList(0, Math.min(rows.size(),
                StoredReport.SAMPLE))));
    }

    /**
     * The preview's state: {@code installed}, the {@code mode}, the run's {@code state} ({@code not-installed},
     * {@code not-computed}, {@code running}, {@code done} or {@code failed}), how many releases the last finished run
     * would newly hold ({@code count}) and the first of them ({@code held}), when it finished ({@code computedAt},
     * {@code null} before one has) and why the run failed ({@code failure}, {@code null} unless it did).
     */
    public record View(boolean installed, String mode, String state, int count, List<Held> held, Instant computedAt,
                       String failure) {

        /** Whether a run is going now - the console disables its button on it and a caller watching polls on it. */
        public boolean running() {
            return "running".equals(state);
        }

        /** Whether any run has finished, so {@code count} and {@code held} say something. */
        public boolean computed() {
            return computedAt != null;
        }
    }

    /** One release a retroactive-enforcement pass would newly hold, with the human-readable reasons behind the hold. */
    public record Held(String ecosystem, String coordinate, String version, List<String> reasons) {
    }
}
