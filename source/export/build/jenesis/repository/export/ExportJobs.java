package build.jenesis.repository.export;

import module java.base;
import build.jenesis.repository.format.EcosystemLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an export as a background job, so the request that starts it answers at once and the caller polls.
 *
 * <p><b>What it walks.</b> Every format the repository holds that exports, and for each, its versions - the inventory's
 * versions of the format's ecosystem, a coordinate at a time, or for a format that records no coordinates, every path
 * it has published. A coordinate's versions go in the order they were published, so a target that marks the last
 * version it received as the latest ends where this repository does; after a coordinate's last version its format is
 * asked for what describes the coordinate as a whole.
 *
 * <p><b>What it records.</b> The job's state - {@code running}, {@code completed} or {@code failed}, the counts, the
 * version it reached, and on a failure the version and the target's answer - is a small JSON document under
 * {@code exports/<id>} in the repository's own store: the store is the only state, so a status read needs no
 * in-memory registry and progress survives a restart. After each coordinate the cursor is written; a resumed job skips
 * everything up to it and redoes at most the coordinate it stopped in, which the target answers as already present.
 * The target's credential is never written.
 */
public final class ExportJobs {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        write(store, jobId, "running", url, counts, cursor, null, null);
        Thread.ofVirtual().name("export-" + jobId).start(() -> run(store, target, url, jobId, formats, counts, cursor));
    }

    private void run(ArtifactStore store, ExportTarget target, String url, String jobId,
                     List<RepositoryFormat> formats, Counts counts, String resume) {
        Walk walk = new Walk(store, target, url, jobId, counts, resume);
        try {
            for (RepositoryFormat format : formats) {
                RepositoryExporter exporter = (RepositoryExporter) format;
                if (exporter.units() == RepositoryExporter.Units.PUBLISHED_PATHS) {
                    walk.paths(format, exporter);
                } else {
                    walk.coordinates(format, exporter);
                }
            }
            write(store, jobId, "completed", url, counts, null, walk.reached, null);
        } catch (Exception e) {
            try {
                write(store, jobId, "failed", url, counts, walk.cursor, walk.reached,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (IOException suppressed) {
                throw new UncheckedIOException(suppressed);
            }
        }
    }

    /** One job's walk: where it resumes from, where it has got to, and what it has counted. */
    private final class Walk {

        private final ArtifactStore store;
        private final ExportTarget target;
        private final String url;
        private final String jobId;
        private final Counts counts;
        private final String resume;
        private boolean resumed;
        private String cursor;
        private String reached;

        Walk(ArtifactStore store, ExportTarget target, String url, String jobId, Counts counts, String resume) {
            this.store = store;
            this.target = target;
            this.url = url;
            this.jobId = jobId;
            this.counts = counts;
            this.resume = resume;
            this.resumed = resume == null;
            this.cursor = resume;
        }

        /** The inventory's versions of the format's ecosystem, one coordinate at a time, in publish order. */
        void coordinates(RepositoryFormat format, RepositoryExporter exporter) throws IOException {
            String ecosystem = ((EcosystemLayout) format).ecosystem();
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
            write(store, jobId, "running", url, counts, cursor, reached, null);
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
                        write(store, jobId, "running", url, counts, cursor, reached, null);
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
        return store.readVersioned("exports/" + jobId).map(ArtifactStore.Versioned::content);
    }

    /** A job's state parsed, for a status answer or to seed a resume. */
    public Optional<Snapshot> snapshot(ArtifactStore store, String jobId) throws IOException {
        Optional<byte[]> bytes = status(store, jobId);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        JsonNode state = JSON.readTree(bytes.get());
        return Optional.of(new Snapshot(state.path("state").asString(null), state.path("target").asString(null),
                state.path("published").asInt(0), state.path("present").asInt(0), state.path("withheld").asInt(0),
                state.path("cursor").asString(null), state.path("reached").asString(null),
                state.path("error").asString(null)));
    }

    private void write(ArtifactStore store, String jobId, String state, String url, Counts counts, String cursor,
                       String reached, String error) throws IOException {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("state", state);
        job.put("target", url);
        job.put("published", counts.published);
        job.put("present", counts.present);
        job.put("withheld", counts.withheld);
        job.put("cursor", cursor);
        job.put("reached", reached);
        job.put("error", error);
        store.write("exports/" + jobId, new ByteArrayInputStream(JSON.writeValueAsBytes(job)));
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
                           String reached, String error) {
    }
}
