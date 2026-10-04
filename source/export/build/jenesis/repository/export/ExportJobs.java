package build.jenesis.repository.export;

import build.jenesis.repository.store.BackgroundJobs;
import module java.base;
import build.jenesis.repository.format.EcosystemLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.JobRecords;
import build.jenesis.repository.store.JobState;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an export as a background job, so the starting request answers at once and the caller polls.
 *
 * <p><b>What it walks.</b> Every exporting format the repository holds, and its versions - the inventory's versions of
 * the format's ecosystem a coordinate at a time, or every published path for a format without coordinates. A
 * coordinate's versions go in publish order, so a target that marks the last version received as latest ends where this
 * repository does; then the format is asked for what describes the coordinate as a whole.
 *
 * <p><b>What it records.</b> The job's state - {@code running}, {@code completed} or {@code failed}, the counts, the
 * version reached, and on a failure the version and the target's answer - is a small JSON document at
 * {@code exports/<id>} in the repository's store, so status needs no in-memory registry and survives a restart. A
 * cursor is written after each coordinate; a resumed job skips to it and redoes at most the coordinate it stopped in,
 * which the target answers as present. The target's credential is never written.
 *
 * <p>A job runs as a {@link JobState.Run}, holding its record while it runs and writing it only while it holds it, so a
 * job whose node died reads {@code interrupted} and resumes, and a run that lost its job to a resume stops.
 */
public final class ExportJobs {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Where a job's record lives, {@code exports/<id>}. */
    private static final String RECORDS = "exports";

    /** A separator no coordinate or path can carry. */
    private static final String SEPARATOR = "\u0001";

    /** How often, in units, a published-paths walk checkpoints. */
    private static final int PATH_CHECKPOINT = 100;

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * The formats of {@code store}'s repository that export: every one it holds that implements
     * {@link RepositoryExporter}.
     *
     * @throws IllegalArgumentException when the repository holds no format, or none of its formats exports
     */
    static List<RepositoryFormat> exporting(ArtifactStore store) throws IOException {
        List<RepositoryFormat> formats = RepositoryDocument.read(store)
                .flatMap(document -> RepositoryType.installed(document.format()))
                .map(RepositoryType::formats)
                .orElseThrow(() -> new IllegalArgumentException("The repository holds no format this deployment "
                        + "serves, so there is nothing to export."));
        List<RepositoryFormat> exporting = formats.stream().filter(RepositoryExporter.class::isInstance).toList();
        if (exporting.isEmpty()) {
            throw new IllegalArgumentException("No format this repository holds can export yet: "
                    + formats.stream().map(RepositoryFormat::name).toList());
        }
        return exporting;
    }

    /** Start an export in the background, from {@code prior}'s cursor and counts when it resumes one. */
    public void submit(ArtifactStore store, ExportTarget target, String url, String jobId, Snapshot prior)
            throws IOException {
        List<RepositoryFormat> formats = exporting(store);
        Counts counts = prior == null ? new Counts() : new Counts(prior);
        String cursor = prior == null ? null : prior.cursor();
        // The claim: a new job's record is created, a resumed one's replaced only while it is the record the resume
        // read and no run holds it - a reap that dismissed it since wins, and so does a run still working on it.
        JobState.Run job = JobState.Run.claim(store, RECORDS, jobId,
                body(JobState.RUNNING, url, counts, cursor, null, null), prior == null ? null : prior.token());
        BackgroundJobs.start(store, "export-" + jobId, () -> run(job, store, target, url, formats, counts, cursor));
    }

    private void run(JobState.Run job, ArtifactStore store, ExportTarget target, String url,
                     List<RepositoryFormat> formats, Counts counts, String resume) {
        Walk walk = new Walk(job, store, target, url, counts, resume);
        try {
            for (RepositoryFormat format : formats) {
                RepositoryExporter exporter = (RepositoryExporter) format;
                if (exporter.units() == RepositoryExporter.Units.PUBLISHED_PATHS) {
                    walk.paths(format, exporter);
                } else {
                    walk.coordinates(format, exporter);
                }
            }
            job.write(body(JobState.COMPLETED, url, counts, null, walk.reached, null));
        } catch (JobState.Lost lost) {
            // Another run has the job and writes its record; this one only stops.
        } catch (Exception e) {
            try {
                job.write(body(JobState.FAILED, url, counts, walk.cursor, walk.reached,
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

    /** One job's walk: where it resumes from, where it has got to, and what it has counted. */
    private final class Walk {

        private final JobState.Run job;
        private final ArtifactStore store;
        private final ExportTarget target;
        private final String url;
        private final Counts counts;
        private final String resume;
        private boolean resumed;
        private String cursor;
        private String reached;

        Walk(JobState.Run job, ArtifactStore store, ExportTarget target, String url, Counts counts, String resume) {
            this.job = job;
            this.store = store;
            this.target = target;
            this.url = url;
            this.counts = counts;
            this.resume = resume;
            this.resumed = resume == null;
            this.cursor = resume;
        }

        /** The inventory's versions of the format's ecosystem, one coordinate at a time, in publish order. */
        void coordinates(RepositoryFormat format, RepositoryExporter exporter) throws IOException {
            String ecosystem = exporter.inventory().orElseGet(() -> ((EcosystemLayout) format).ecosystem());
            StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
            String[] current = {null};
            List<String> versions = new ArrayList<>();
            inventory.coordinates(coordinate -> {
                if (!coordinate.ecosystem().equals(ecosystem)) {
                    return;
                }
                if (!coordinate.coordinate().equals(current[0])) {
                    if (current[0] != null) {
                        coordinate(format, exporter, inventory, ecosystem, current[0], versions);
                    }
                    current[0] = coordinate.coordinate();
                    versions.clear();
                }
                versions.add(coordinate.version());
            });
            if (current[0] != null) {
                coordinate(format, exporter, inventory, ecosystem, current[0], versions);
            }
        }

        private void coordinate(RepositoryFormat format, RepositoryExporter exporter,
                                StoreRepositoryInventory inventory, String ecosystem, String coordinate,
                                List<String> versions) throws IOException {
            String key = format.name() + SEPARATOR + coordinate;
            if (!resumed) {
                resumed = key.equals(resume);
                return;
            }
            List<String> ordered = new ArrayList<>(versions);
            Map<String, Instant> published = new HashMap<>();
            for (String version : ordered) {
                published.put(version, inventory.publishedAt(ecosystem, coordinate, version).orElse(Instant.EPOCH));
            }
            ordered.sort(Comparator.comparing((String version) -> published.get(version))
                    .thenComparing(Comparator.naturalOrder()));
            for (String version : ordered) {
                reached = coordinate + " " + version;
                count(export(exporter, coordinate, version));
            }
            exporter.exported(store, coordinate, target);
            cursor = key;
            job.write(body(JobState.RUNNING, url, counts, cursor, reached, null));
        }

        /** Every path the format has published, each a unit, checkpointed every {@value #PATH_CHECKPOINT}. */
        void paths(RepositoryFormat format, RepositoryExporter exporter) throws IOException {
            String prefix = "publish" + format.mount() + "/";
            String after = "";
            if (!resumed) {
                if (!resume.startsWith(format.name() + SEPARATOR)) {
                    return;
                }
                after = prefix + resume.substring(format.name().length() + SEPARATOR.length());
                resumed = true;
            }
            int sinceCheckpoint = 0;
            while (true) {
                List<String> keys = new ArrayList<>();
                ArtifactStore.Scan scan = store.scan(prefix, after, 1_000, listed -> keys.add(listed.key()));
                for (String key : keys) {
                    String path = key.substring("publish".length());
                    reached = path;
                    count(export(exporter, path, ""));
                    if (++sinceCheckpoint >= PATH_CHECKPOINT) {
                        cursor = format.name() + SEPARATOR + path.substring(format.mount().length() + 1);
                        job.write(body(JobState.RUNNING, url, counts, cursor, reached, null));
                        sinceCheckpoint = 0;
                    }
                }
                if (!scan.truncated()) {
                    return;
                }
                after = scan.cursor().orElseThrow();
            }
        }

        private RepositoryExporter.Exported export(RepositoryExporter exporter, String coordinate, String version)
                throws IOException {
            try {
                return exporter.export(store, coordinate, version, target);
            } catch (IOException | RuntimeException failed) {
                throw new IOException((version.isEmpty() ? coordinate : coordinate + " " + version) + ": "
                        + (failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage()),
                        failed);
            }
        }

        private void count(RepositoryExporter.Exported exported) {
            switch (exported) {
                case PUBLISHED -> counts.published++;
                case ALREADY_PRESENT -> counts.present++;
                case WITHHELD -> counts.withheld++;
            }
        }
    }

    /** A job's persisted state as raw JSON bytes, or empty when there is no such job. */
    public Optional<byte[]> status(ArtifactStore store, String jobId) throws IOException {
        return JobRecords.status(store, RECORDS, jobId);
    }

    /** A job's state parsed, for a status answer or to seed a resume. */
    public Optional<Snapshot> snapshot(ArtifactStore store, String jobId) throws IOException {
        Optional<JobRecords.Record> record = JobRecords.read(store, RECORDS, jobId);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        JsonNode state = record.get().fields();
        return Optional.of(new Snapshot(record.get().state(), state.path("target").asString(null),
                state.path("published").asInt(0), state.path("present").asInt(0), state.path("withheld").asInt(0),
                state.path("cursor").asString(null), state.path("reached").asString(null),
                state.path("error").asString(null), record.get().token()));
    }

    private static byte[] body(String state, String url, Counts counts, String cursor, String reached, String error)
            throws IOException {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("state", state);
        job.put("target", url);
        job.put("published", counts.published);
        job.put("present", counts.present);
        job.put("withheld", counts.withheld);
        job.put("cursor", cursor);
        job.put("reached", reached);
        job.put("error", error);
        return JSON.writeValueAsBytes(job);
    }

    /** The running counts: versions sent, versions the target already held, versions with nothing servable. */
    private static final class Counts {

        int published;
        int present;
        int withheld;

        Counts() {
        }

        Counts(Snapshot prior) {
            published = prior.published();
            present = prior.present();
            withheld = prior.withheld();
        }
    }

    /**
     * A job's persisted state. {@code target} is the URL exported to; {@code reached} the version (or path) it last
     * handled; {@code cursor} what a resume continues after; {@code error} the version and the target's answer on a
     * failure.
     */
    public record Snapshot(String state, String target, int published, int present, int withheld, String cursor,
                           String reached, String error, Object token) {
    }
}
