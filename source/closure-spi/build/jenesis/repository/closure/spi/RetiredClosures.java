package build.jenesis.repository.closure.spi;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Checksums;

/**
 * The closures a version gave up - its document evicted, or its closure cleared to be resolved again - kept in the
 * version's own repository until the closure pass there takes back the rows they wrote in the repositories they
 * reached, which a write in this one cannot reach. One JSON document per retired closure, at
 * {@code closure/retired/<sha-256 of the version and the closure's instant>}, holding the version and its closure and
 * exposure sections as they stood; a closure retired twice is two documents, so neither is lost to the other.
 *
 * <p>A record is an instruction to the pass, never a fact a reader relies on: the pass deletes the rows the retired
 * closure placed less those the version's closure now places, and the reconcile removes any it could not reach.
 */
public final class RetiredClosures {

    /** Where the retired closures are kept, per repository. */
    public static final String ROOT = "closure/retired";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RetiredClosures() {
    }

    /** A closure a version gave up, and the exposure derived from it - empty where the version had none. */
    public record Retired(String ecosystem, String coordinate, String version, Optional<Section> closure,
                          Optional<Section> exposure) {
    }

    /** What one retired closure is done with. */
    @FunctionalInterface
    public interface Visitor {

        void accept(Retired retired) throws IOException;
    }

    /** Record that {@code version} of {@code coordinate} gives up the closure {@code document} records, in
     *  {@code store}, the repository holding it; nothing where it records none. */
    public static void retire(ArtifactStore store, String ecosystem, String coordinate, String version,
                              MetadataDocument document) throws IOException {
        Optional<Section> closure = document.section(ClosureSection.TAG);
        if (closure.isEmpty()) {
            return;
        }
        ObjectNode node = JSON.createObjectNode().put("ecosystem", ecosystem).put("coordinate", coordinate)
                .put("version", version);
        node.set("closure", section(closure.get()));
        document.section(ExposureSection.TAG).ifPresent(exposure -> node.set("exposure", section(exposure)));
        store.write(ROOT + "/" + Checksums.sha256(ecosystem + "\n" + coordinate + "\n" + version + "\n"
                + closure.get().updated()), new ByteArrayInputStream(JSON.writeValueAsBytes(node)));
    }

    /** Hand at most {@code limit} retired closures of {@code store} to {@code visitor}, deleting each once it is
     *  done with; a record that does not parse is deleted unread, and one the visitor fails on is left for the next
     *  pass. */
    public static void drain(ArtifactStore store, int limit, Visitor visitor) throws IOException {
        List<String> keys = new ArrayList<>();
        store.scan(ROOT, "", limit, listed -> keys.add(listed.key()));
        for (String key : keys) {
            Optional<Retired> retired = read(store, key);
            if (retired.isPresent()) {
                visitor.accept(retired.get());
            }
            store.delete(key);
        }
    }

    private static Optional<Retired> read(ArtifactStore store, String key) throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(stored.get().content());
            String ecosystem = node.path("ecosystem").asString("");
            String coordinate = node.path("coordinate").asString("");
            String version = node.path("version").asString("");
            if (ecosystem.isEmpty() || coordinate.isEmpty() || version.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Retired(ecosystem, coordinate, version, section(node.path("closure")),
                    section(node.path("exposure"))));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static ObjectNode section(Section section) {
        ObjectNode node = JSON.createObjectNode().put("tag", section.tag()).put("schema", section.schema())
                .put("updated", section.updated().toString());
        section.payload().ifPresent(data -> node.set("data", data));
        return node;
    }

    private static Optional<Section> section(JsonNode node) {
        if (!node.isObject() || !node.has("data")) {
            return Optional.empty();
        }
        return Optional.of(Section.derived(node.path("tag").asString(), node.path("schema").asInt(),
                Instant.parse(node.path("updated").asString()), null, node.get("data")));
    }
}
