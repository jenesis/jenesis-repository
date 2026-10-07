package build.jenesis.repository.compliance.scan;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * What the signal-refresh pass found the last time it ran, kept so the API's {@code /api/admin/signals}, the console's
 * signal-sources screen and, through the API, the CLI can say what each refreshable source holds without resolving a
 * source on a request: each source's freshness, why its last refresh failed if it did, and for a feed that keeps a copy
 * of its vendor's records ({@link AdvisorySource.Mirror}) each ecosystem's copy.
 *
 * <p>One JSON document, {@value #DOCUMENT}, in the signal space under {@value #NAME}, written whole by the one node
 * holding the pass's lease at the end of each pass and read with one point read. It is a report rather than a record a
 * decision rests on: a lost write leaves the previous one standing, as of the instant it says.
 */
public final class SignalStatus {

    /** The signal space's sub-space the document lives in. */
    public static final String NAME = "signal-refresh";

    /** The document's name. */
    static final String DOCUMENT = "status.json";

    /** The document's version; one that does not match is not read. */
    private static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SignalStatus() {
    }

    /** What the pass found: when it {@code recorded} it, and each source by name. */
    public record Status(Instant recorded, List<Source> sources) {

        public Status {
            sources = List.copyOf(sources);
        }
    }

    /**
     * One refreshable source: its signal {@code name}, when its data was last drawn ({@code null} for never), whether
     * that answer is {@code authoritative}, why its last refresh failed ({@code null} unless it did) and, for a mirror,
     * its {@code copies} - empty for a source that keeps none.
     */
    public record Source(String name, Instant refreshed, boolean authoritative, String failure,
                         List<AdvisorySource.Mirror.Copy> copies) {

        public Source {
            copies = List.copyOf(copies);
        }
    }

    /** The document's space within a deployment's {@code root} store - where the pass writes it and a surface reads it
     *  back. */
    public static ArtifactStore space(ArtifactStore root) {
        ArtifactStore store = root;
        for (String segment : SignalContext.SNAPSHOT_ROOT.split("/")) {
            store = store.scope(segment);
        }
        return store.scope(NAME);
    }

    /** The last status recorded in {@code space}, or empty before a pass has recorded one. */
    public static Optional<Status> read(ArtifactStore space) throws IOException {
        Optional<ArtifactStore.Versioned> stored = space.readVersioned(DOCUMENT);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        JsonNode document = JSON.readTree(stored.get().content());
        if (document.path("version").asInt(0) != VERSION) {
            return Optional.empty();
        }
        List<Source> sources = new ArrayList<>();
        for (JsonNode source : document.path("sources")) {
            List<AdvisorySource.Mirror.Copy> copies = new ArrayList<>();
            for (JsonNode copy : source.path("copies")) {
                copies.add(new AdvisorySource.Mirror.Copy(copy.path("ecosystem").asString(),
                        instant(copy.path("built")), instant(copy.path("drawn"))));
            }
            sources.add(new Source(source.path("name").asString(), instant(source.path("refreshed")),
                    source.path("authoritative").asBoolean(false), source.path("failure").asString(null), copies));
        }
        return Optional.of(new Status(instant(document.path("recorded")), sources));
    }

    /** Record {@code status} in {@code space}, replacing what was there. */
    static void write(ArtifactStore space, Status status) throws IOException {
        ObjectNode document = JSON.createObjectNode();
        document.put("version", VERSION);
        document.put("recorded", status.recorded().toString());
        ArrayNode sources = document.putArray("sources");
        for (Source source : status.sources()) {
            ObjectNode node = sources.addObject();
            node.put("name", source.name());
            put(node, "refreshed", source.refreshed());
            node.put("authoritative", source.authoritative());
            if (source.failure() != null) {
                node.put("failure", source.failure());
            }
            ArrayNode copies = node.putArray("copies");
            for (AdvisorySource.Mirror.Copy copy : source.copies()) {
                ObjectNode entry = copies.addObject();
                entry.put("ecosystem", copy.ecosystem());
                put(entry, "built", copy.built());
                put(entry, "drawn", copy.drawn());
            }
        }
        space.write(DOCUMENT, new ByteArrayInputStream(JSON.writeValueAsBytes(document)));
    }

    private static void put(ObjectNode node, String field, Instant value) {
        if (value != null) {
            node.put(field, value.toString());
        }
    }

    private static Instant instant(JsonNode node) {
        return node.isString() ? Instant.parse(node.asString()) : null;
    }
}
