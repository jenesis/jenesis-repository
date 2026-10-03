package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.JobState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * How a background job's stored record is read back - an import's or an export's, each a small JSON document under its
 * repository's records level. A record says {@code running} for as long as its run holds it; one whose run no longer
 * holds it is told as {@link JobState#INTERRUPTED interrupted}, whatever it last wrote.
 */
public final class JobRecords {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JobRecords() {
    }

    /** A record as read: its fields, the state it is told as, and the token a resume claims it against. */
    public record Record(JsonNode fields, String state, Object token) {
    }

    /** The record of {@code jobId} as a status answer's bytes - as its run wrote it, with the state it is told as -
     *  or empty when there is no such job. One store read when the record's state stands as written. */
    public static Optional<byte[]> status(ArtifactStore store, String records, String jobId) throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(records + "/" + jobId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        JsonNode fields = JSON.readTree(stored.get().content());
        String written = fields.path("state").asString(null);
        String effective = JobState.effective(store, records, jobId, written);
        if (Objects.equals(effective, written)) {
            return Optional.of(stored.get().content());
        }
        ((ObjectNode) fields).put("state", effective);
        return Optional.of(JSON.writeValueAsBytes(fields));
    }

    /** The record of {@code jobId} parsed, for a status answer or to seed a resume; empty for a job there is none of,
     *  or one a reap has {@linkplain JobState#DISMISSED dismissed}. */
    public static Optional<Record> read(ArtifactStore store, String records, String jobId) throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(records + "/" + jobId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        JsonNode fields = JSON.readTree(stored.get().content());
        String written = fields.path("state").asString(null);
        if (JobState.DISMISSED.equals(written)) {
            return Optional.empty();
        }
        return Optional.of(new Record(fields, JobState.effective(store, records, jobId, written),
                stored.get().token()));
    }
}
