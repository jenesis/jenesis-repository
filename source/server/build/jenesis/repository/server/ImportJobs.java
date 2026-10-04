package build.jenesis.repository.server;

import build.jenesis.repository.store.BackgroundJobs;
import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.store.JobState;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a migration as a background job so the trigger can return at once and the caller polls for progress. A
 * submitted import runs on a virtual thread and records its state - {@code running}, {@code completed} or
 * {@code failed}, with the running counts and the resume cursor - as a small JSON object in the store under
 * {@code imports/<id>}, which is what a status read returns. Persisting the cursor after each batch makes the
 * migration resumable: a re-submit naming a prior job continues its walk from the recorded cursor and counts, and
 * the content-addressed store dedupes anything a resumed run repeats. The
 * store is the only state, so progress survives a restart and a status read needs no in-memory registry.
 *
 * <p>A job runs as a {@link JobState.Run}, holding its record while it runs and writing it only while it holds it, so a
 * job whose node died reads {@code interrupted} and resumes, rather than reading {@code running} for good, and a run
 * that lost its job to a resume stops rather than writing over the run that took it over.
 */
public final class ImportJobs {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Where a job's record lives, {@code imports/<id>}. */
    private static final String RECORDS = "imports";

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Start an import in the background, seeded with the given counts (non-zero for a resume), and return at once.
     *  The convenience arm: no edition listener and no job-scope decorator, so the job runs exactly as the free
     *  import walk does. */
    public void submit(ArtifactStore store, ImportSource source, String jobId, Snapshot prior) throws IOException {
        submit(store, source, jobId, prior, RepositoryImport.Listener.NONE, UnaryOperator.identity());
    }

    /** As above, with two seams an edition binds around the background job. {@code listener} rides every imported,
     *  held, rejected and skipped asset (an edition's controller records a held asset's replay context here);
     *  {@code jobScope} decorates the job body before it is started on the virtual thread - the seam an edition's
     *  controller uses to bind its {@code PublishTenant} around the run, since the import job runs on a fresh
     *  {@link Thread#ofVirtual() virtual thread} where no tenant is bound and the screen would otherwise resolve the
     *  deployment-wide policy rather than the tenant's. The default {@link UnaryOperator#identity() identity} leaves
     *  the behaviour unchanged. This job's own progress accounting (counts, cursor, status JSON) always runs;
     *  {@code listener} is notified in addition to it. */
    public void submit(ArtifactStore store, ImportSource source, String jobId, Snapshot prior,
                       RepositoryImport.Listener listener, UnaryOperator<Runnable> jobScope) throws IOException {
        List<RepositoryFormat> formats = formats(store);
        int baseImported = prior == null ? 0 : prior.imported();
        int baseSkipped = prior == null ? 0 : prior.skipped();
        // The claim: a new job's record is created, a resumed one's replaced only while it is the record the resume
        // read and no run holds it - a reap that dismissed it since wins, and so does a run still working on it.
        JobState.Run claimed = JobState.Run.claim(store, RECORDS, jobId, body(JobState.RUNNING, baseImported,
                baseSkipped, 0, 0, new LinkedHashSet<>(), Map.of(), null, null, null), prior == null ? null
                : prior.token());
        Runnable body = () -> run(claimed, source, jobId, baseImported, baseSkipped, listener, formats, store);
        BackgroundJobs.start(store, "import-" + jobId, jobScope.apply(body));
    }

    /**
     * The formats an import into {@code store} may lay out: the ones its repository holds. A migration source may
     * carry several formats, and a repository holds one - or one combined type - so the assets of any other format
     * are skipped and reported as such, rather than laid out where no request will ever reach them. An import into a
     * repository that holds no format is refused before it starts, for the same reason.
     *
     * @throws IllegalArgumentException when the repository holds no format this deployment serves.
     */
    static List<RepositoryFormat> formats(ArtifactStore store) throws IOException {
        return RepositoryDocument.read(store)
                .flatMap(document -> RepositoryType.installed(document.format()))
                .map(RepositoryType::formats)
                .orElseThrow(() -> new IllegalArgumentException("The repository holds no format this deployment "
                        + "serves, so nothing imported into it would answer. Create it with the format it holds "
                        + "first."));
    }

    private void run(JobState.Run job, ImportSource source, String jobId, int baseImported, int baseSkipped,
                     RepositoryImport.Listener delegate, List<RepositoryFormat> formats, ArtifactStore store) {
        AtomicInteger imported = new AtomicInteger(baseImported);
        AtomicInteger skipped = new AtomicInteger(baseSkipped);
        AtomicInteger held = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> skippedFormats = new LinkedHashSet<>();
        Map<ImportSource.Reason, Integer> dropped = new EnumMap<>(ImportSource.Reason.class);
        String[] cursor = {null};
        AtomicReference<String> asset = new AtomicReference<>();
        try {
            new RepositoryImport(formats).run(source, store, new RepositoryImport.Listener() {
                @Override
                public void imported(String path) {
                    imported.incrementAndGet();
                    asset.set(path);
                    delegate.imported(path);
                }

                @Override
                public void held(String path, ArtifactDescriptor descriptor, String hash) {
                    held.incrementAndGet();
                    delegate.held(path, descriptor, hash);
                }

                @Override
                public void rejected(String path, ArtifactDescriptor descriptor) {
                    rejected.incrementAndGet();
                    delegate.rejected(path, descriptor);
                }

                @Override
                public void skipped(String format) {
                    skipped.incrementAndGet();
                    skippedFormats.add(format);
                    delegate.skipped(format);
                }

                @Override
                public void dropped(String path, ImportSource.Reason reason) {
                    dropped.merge(reason, 1, Integer::sum);
                    delegate.dropped(path, reason);
                }

                @Override
                public void checkpoint(String reached) throws IOException {
                    cursor[0] = reached;
                    job.write(body(JobState.RUNNING, imported.get(), skipped.get(), held.get(), rejected.get(),
                            skippedFormats, dropped, reached, asset.get(), null));
                    delegate.checkpoint(reached);
                }
            });
            job.write(body(JobState.COMPLETED, imported.get(), skipped.get(), held.get(), rejected.get(),
                    skippedFormats, dropped, null, asset.get(), null));
        } catch (JobState.Lost lost) {
            // Another run has the job and writes its record; this one only stops.
        } catch (Exception e) {
            try {
                job.write(body(JobState.FAILED, imported.get(), skipped.get(), held.get(), rejected.get(),
                        skippedFormats, dropped, cursor[0], asset.get(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } catch (JobState.Lost lost) {
                // As above: the record is the other run's.
            } catch (IOException suppressed) {
                throw new UncheckedIOException(suppressed);
            }
        } finally {
            try {
                job.close();
            } catch (IOException unreleased) {
                // The hold lapses on its own; until then a reader sees the job as its record says.
            }
        }
    }

    /** The persisted state of a job as raw JSON bytes, or empty if there is no such job. */
    public Optional<byte[]> status(ArtifactStore store, String jobId) throws IOException {
        return JobRecords.status(store, RECORDS, jobId);
    }

    /** A job's state parsed for a status response or to seed a resume; empty for a job there is none of, or one a
     *  reap has dismissed. */
    public Optional<Snapshot> snapshot(ArtifactStore store, String jobId) throws IOException {
        Optional<JobRecords.Record> record = JobRecords.read(store, RECORDS, jobId);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        JsonNode state = record.get().fields();
        List<String> formats = new ArrayList<>();
        for (JsonNode format : state.path("skippedFormats")) {
            formats.add(format.asString(null));
        }
        Map<String, Integer> drops = new LinkedHashMap<>();
        JsonNode dropped = state.path("dropped");
        dropped.propertyNames().forEach(name -> drops.put(name, dropped.path(name).asInt(0)));
        return Optional.of(new Snapshot(record.get().state(), state.path("imported").asInt(0),
                state.path("skipped").asInt(0), state.path("held").asInt(0), state.path("rejected").asInt(0),
                formats, Map.copyOf(drops), state.path("cursor").asString(null),
                state.path("asset").asString(null), state.path("error").asString(null), record.get().token()));
    }

    private static byte[] body(String state, int imported, int skipped, int held, int rejected,
                               Set<String> skippedFormats, Map<ImportSource.Reason, Integer> dropped, String cursor,
                               String asset, String error) throws IOException {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("state", state);
        job.put("imported", imported);
        job.put("skipped", skipped);
        job.put("held", held);
        job.put("rejected", rejected);
        job.put("skippedFormats", new ArrayList<>(skippedFormats));
        // Persisted per reason rather than as one total: an operator polling a job needs to see that the refusals
        // were UNSAFE_PATH - the hostile-source indicator - and not merely that some rows did not arrive.
        Map<String, Integer> drops = new LinkedHashMap<>();
        dropped.forEach((reason, count) -> drops.put(reason.name(), count));
        job.put("dropped", drops);
        job.put("cursor", cursor);
        job.put("asset", asset);
        job.put("error", error);
        return JSON.writeValueAsBytes(job);
    }

    /** A parsed view of a job's persisted state; {@code held} and {@code rejected} are the assets the import edge
     *  screened to quarantine and rejection, {@code asset} is the source path of the most recently imported asset
     *  (which one the walk has reached), {@code null} before the first asset. */
    public record Snapshot(String state, int imported, int skipped, int held, int rejected,
                           List<String> skippedFormats, Map<String, Integer> dropped, String cursor, String asset,
                           String error, Object token) {

        /** Every row the source offered that no connector would carry. A completed job with zero imported and a
         *  non-zero count here is a refused source, not an empty one. */
        public int droppedTotal() {
            return dropped.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
