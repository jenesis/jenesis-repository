package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.feed.FeedClient;
import build.jenesis.repository.feed.FeedException;
import build.jenesis.repository.feed.FeedRequest;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Checksums;

/**
 * The durable half of the OSV mirror: a copy of OSV's records for each ecosystem it is asked to keep, in the mirror's
 * own signal space, partitioned by package so a lookup reads one document.
 *
 * <p>An ecosystem's copy is a generation, {@code <ecosystem>/<generation>/}: one document per package,
 * {@code packages/<sha-256 of the package>}, holding every record affecting it - each cut to the fields a lookup reads
 * and to its entries for that package - and one per record, {@code records/<sha-256 of its id>}, naming the packages
 * it was filed under, which is how an update takes a record out of a package it no longer names. The ecosystem's
 * {@code <ecosystem>/current} document names the generation a lookup reads, when it was built, when it was last drawn,
 * and the position in OSV's change list it was drawn to. JSON documents throughout, written by the one node holding the
 * refresh pass's lease.
 *
 * <p>A generation is {@link #build built} from the ecosystem's whole export, {@code <ecosystem>/all.zip}, streamed and
 * filed a bounded batch of records at a time, and only then named current, so a lookup never reads a copy being built;
 * the generation it replaces is deleted after. Between builds it is {@link #update updated} from the ecosystem's change
 * list, each record named since its position fetched from the export, {@code <ecosystem>/<id>.json}. The position a
 * build records is the list's head as it stood before the archive was drawn, so a record changed while it was drawn is
 * drawn again rather than missed.
 */
final class OsvMirror {

    /** The records one flush files: the batch a build holds in memory. */
    static final int BATCH = 500;

    /** The most one record's entry in the archive may hold: a record is a few kilobytes. */
    static final int RECORD_BYTES = 8 << 20;

    /** The bound on a record's long-form text the mirror keeps, beyond what an advisory carries. */
    private static final int TEXT = 8 << 10;

    /** The document naming the ecosystems the mirror is asked to keep. */
    private static final String WANTED = "wanted";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Supplier<ArtifactStore> space;
    private final FeedClient client;
    private final URI export;
    private final Clock clock;

    OsvMirror(Supplier<ArtifactStore> space, FeedClient client, URI export, Clock clock) {
        this.space = space;
        this.client = client;
        this.export = export.toString().endsWith("/") ? export : URI.create(export + "/");
        this.clock = clock;
    }

    /** What an ecosystem's copy is: the generation a lookup reads, when it was built, when it was last drawn, the
     *  position in the change list it was drawn to, and the generation it replaced - kept until the next build, since
     *  a node may still read it through a state it holds - or {@code null} for the first. */
    record State(String generation, Instant built, Instant drawn, Instant position, String previous) {
    }

    /** The ecosystem's current copy, OSV's name of it, or empty before its first build lands. */
    Optional<State> state(String osvName) throws IOException {
        return readJson(current(osvName)).map(node -> new State(node.path("generation").asString(),
                Instant.parse(node.path("built").asString()), Instant.parse(node.path("drawn").asString()),
                Instant.parse(node.path("position").asString()), node.path("previous").asString(null)));
    }

    /** The ecosystems, the product's names, the mirror is asked to keep. */
    Set<String> wanted() throws IOException {
        Set<String> wanted = new TreeSet<>();
        readJson(WANTED).ifPresent(node -> node.path("ecosystems").forEach(name -> wanted.add(name.asString())));
        return wanted;
    }

    /** Ask the mirror to keep {@code ecosystems}, the product's names; written only where it changed. */
    void want(Set<String> ecosystems) throws IOException {
        Set<String> wanted = new TreeSet<>(ecosystems);
        if (!wanted.equals(wanted())) {
            ObjectNode node = JSON.createObjectNode();
            ArrayNode names = node.putArray("ecosystems");
            wanted.forEach(names::add);
            writeJson(WANTED, node);
        }
    }

    /** The records the copy {@code state} of {@code osvName} holds for the package {@code key}, as {@link OsvRanges#key}
     *  spells it. */
    List<JsonNode> records(String osvName, State state, String key) throws IOException {
        List<JsonNode> records = new ArrayList<>();
        readJson(packageKey(osvName, state.generation(), key))
                .ifPresent(node -> node.path("records").forEach(records::add));
        return records;
    }

    /**
     * Build a new copy of {@code osvName}, the product's {@code product}, from its whole export, name it current, and
     * delete the one before the copy it replaces. The copy it replaces stays one build longer: a node holds the state
     * it read for {@link OsvMirrorSource#STATE_TTL}, and deleting what that state names would answer its lookups from
     * missing package documents - no records, which reads as clean.
     *
     * @throws FeedException the export could not be drawn; nothing is named current and the copy before keeps serving
     * @throws IOException   the mirror's own space could not be read or written
     */
    void build(String osvName, String product) throws IOException, FeedException {
        Instant started = clock.instant();
        Optional<State> before = state(osvName);
        // The list's head before the archive is drawn: a record changed while it is drawn lies above it.
        Instant position = OsvChangeList.read(client, export, osvName, null)
                .flatMap(listed -> listed.lines().stream().findFirst()).map(OsvChangeList.Line::modified)
                .orElse(Instant.EPOCH);
        String generation = "g" + started.toEpochMilli();
        try {
            client.fetch(FeedRequest.get(export.resolve(OsvChangeList.path(osvName) + "/all.zip")),
                    FeedClient.Reader.document(body -> {
                        file(osvName, product, generation, body);
                        return Boolean.TRUE;
                    }));
        } catch (FeedException e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof StoreFailure failure) {
                    throw failure.getCause();
                }
                cause = cause.getCause();
            }
            throw e;
        }
        Instant now = clock.instant();
        String replaced = before.map(State::generation).filter(named -> !named.equals(generation)).orElse(null);
        writeState(osvName, new State(generation, now, now, position, replaced));
        String retired = before.map(State::previous).orElse(null);
        if (retired != null && !retired.equals(generation) && !retired.equals(replaced)) {
            delete(osvName + "/" + retired);
        }
    }

    /** What one {@link #update} did: the packages whose records changed, and how many records it fetched. */
    record Update(Set<AdvisorySource.Package> packages, int fetched) {
    }

    /**
     * Bring the copy {@code state} of {@code osvName} up to date from its change list: at most {@code budget} records
     * named since its position, oldest first, each replaced in the packages it names and taken out of those it no
     * longer does. Empty where the list's window ran out before reaching the position, which a {@link #build} has to
     * settle.
     *
     * @throws FeedException the change list or a record could not be drawn; the position is left where it was
     */
    Optional<Update> update(String osvName, String product, State state, int budget)
            throws IOException, FeedException {
        Optional<OsvChangeList.Listed> answered = OsvChangeList.read(client, export, osvName, state.position());
        Set<AdvisorySource.Package> changed = new LinkedHashSet<>();
        if (answered.isEmpty()) {
            writeState(osvName, new State(state.generation(), state.built(), clock.instant(), state.position(),
                    state.previous()));
            return Optional.of(new Update(changed, 0));
        }
        if (answered.get().exhausted()) {
            return Optional.empty();
        }
        Instant reached = state.position();
        int left = budget;
        for (OsvChangeList.Line line : answered.get().lines().reversed()) {
            if (left <= 0 && !line.modified().equals(reached)) {
                break;
            }
            Optional<JsonNode> record = fetch(osvName, line.id());
            changed.addAll(refile(osvName, product, state.generation(), line.id(), record));
            left--;
            reached = line.modified();
        }
        writeState(osvName, new State(state.generation(), state.built(), clock.instant(), reached, state.previous()));
        return Optional.of(new Update(changed, budget - left));
    }

    /** Have the next refresh build {@code osvName} again, its copy {@code state} serving until it does. */
    void markForRebuild(String osvName, State state) throws IOException {
        writeState(osvName, new State(state.generation(), Instant.EPOCH, state.drawn(), state.position(),
                state.previous()));
    }

    /** File every record of the archive {@code body} into {@code generation}, a batch at a time. */
    private void file(String osvName, String product, String generation, InputStream body) throws IOException {
        List<JsonNode> batch = new ArrayList<>(BATCH);
        ZipInputStream zip = new ZipInputStream(body);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory() || !entry.getName().endsWith(".json")) {
                continue;
            }
            byte[] content = zip.readNBytes(RECORD_BYTES + 1);
            if (content.length > RECORD_BYTES) {
                throw new IOException("The OSV " + osvName + " export holds a record, " + entry.getName()
                        + ", larger than " + RECORD_BYTES + " bytes");
            }
            batch.add(JSON.readTree(content));
            if (batch.size() >= BATCH) {
                flush(osvName, product, generation, batch);
                batch.clear();
            }
        }
        flush(osvName, product, generation, batch);
    }

    /** File {@code batch} into {@code generation}: each package's document read once and written once. */
    private void flush(String osvName, String product, String generation, List<JsonNode> batch) {
        try {
            Map<String, Map<String, JsonNode>> byPackage = new LinkedHashMap<>();
            for (JsonNode record : batch) {
                String id = record.path("id").asString("");
                if (id.isBlank()) {
                    continue;
                }
                Set<String> packages = OsvRanges.packages(record, osvName, product);
                for (String key : packages) {
                    byPackage.computeIfAbsent(key, _ -> new LinkedHashMap<>()).put(id, kept(record, osvName, product,
                            key));
                }
                writeIndex(osvName, generation, id, packages);
            }
            for (Map.Entry<String, Map<String, JsonNode>> named : byPackage.entrySet()) {
                Map<String, JsonNode> records = held(osvName, generation, named.getKey());
                records.putAll(named.getValue());
                writePackage(osvName, generation, named.getKey(), records);
            }
        } catch (IOException e) {
            throw new StoreFailure(e);
        }
    }

    /** Replace {@code id} in the packages {@code record} names and take it out of those it was filed under before;
     *  answers the packages either names. An absent record is taken out of every package. */
    private Set<AdvisorySource.Package> refile(String osvName, String product, String generation, String id,
                                               Optional<JsonNode> record) throws IOException {
        Set<String> before = readJson(indexKey(osvName, generation, id))
                .map(node -> {
                    Set<String> names = new LinkedHashSet<>();
                    node.path("packages").forEach(name -> names.add(name.asString()));
                    return names;
                }).orElse(Set.of());
        Set<String> after = record.map(found -> OsvRanges.packages(found, osvName, product)).orElse(Set.of());
        Set<AdvisorySource.Package> changed = new LinkedHashSet<>();
        for (String key : before) {
            if (!after.contains(key)) {
                Map<String, JsonNode> records = held(osvName, generation, key);
                records.remove(id);
                writePackage(osvName, generation, key, records);
                changed.add(new AdvisorySource.Package(product, key));
            }
        }
        for (String key : after) {
            Map<String, JsonNode> records = held(osvName, generation, key);
            records.put(id, kept(record.get(), osvName, product, key));
            writePackage(osvName, generation, key, records);
            changed.add(new AdvisorySource.Package(product, key));
        }
        if (after.isEmpty()) {
            space.get().delete(indexKey(osvName, generation, id));
        } else {
            writeIndex(osvName, generation, id, after);
        }
        return changed;
    }

    /** One record from the export, or empty where it no longer holds it. */
    private Optional<JsonNode> fetch(String osvName, String id) throws IOException, FeedException {
        FeedRequest request = FeedRequest.get(export.resolve(OsvChangeList.path(osvName) + "/"
                + URLEncoder.encode(id, StandardCharsets.UTF_8) + ".json"));
        try {
            JsonNode record = client.fetch(request, FeedClient.Reader.document(JSON::readTree)).value().orElseThrow();
            if (!id.equals(record.path("id").asString(null))) {
                throw new IOException("OSV's export answered a request for record " + id + " with another record");
            }
            return Optional.of(record);
        } catch (FeedException e) {
            if (e.reason() == FeedException.Reason.STATUS && e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The records a package's document in {@code generation} holds, by id; none where it has none. */
    private Map<String, JsonNode> held(String osvName, String generation, String key) throws IOException {
        Map<String, JsonNode> records = new LinkedHashMap<>();
        readJson(packageKey(osvName, generation, key)).ifPresent(node -> node.path("records")
                .forEach(record -> records.put(record.path("id").asString(), record)));
        return records;
    }

    /** {@code record} cut to what a lookup reads of it for the package {@code key}: its identity, withdrawal,
     *  severity, aliases and bounded text, and its entries for that package alone. */
    private static JsonNode kept(JsonNode record, String osvName, String product, String key) {
        ObjectNode kept = JSON.createObjectNode();
        for (String field : List.of("id", "modified", "withdrawn")) {
            if (record.has(field)) {
                kept.set(field, record.get(field));
            }
        }
        for (String field : List.of("summary", "details")) {
            String text = record.path(field).asString(null);
            if (text != null) {
                kept.put(field, text.length() > TEXT ? text.substring(0, TEXT) : text);
            }
        }
        for (String field : List.of("aliases", "severity")) {
            if (record.has(field)) {
                kept.set(field, record.get(field));
            }
        }
        String word = record.path("database_specific").path("severity").asString(null);
        if (word != null) {
            kept.putObject("database_specific").put("severity", word);
        }
        ArrayNode affected = kept.putArray("affected");
        for (JsonNode entry : record.path("affected")) {
            JsonNode named = entry.path("package");
            String ecosystem = named.path("ecosystem").asString("");
            int release = ecosystem.indexOf(':');
            if ((release < 0 ? ecosystem : ecosystem.substring(0, release)).equals(osvName)
                    && key.equals(OsvRanges.key(product, named.path("name").asString("")))) {
                ObjectNode copy = affected.addObject();
                copy.set("package", named);
                if (entry.has("ranges")) {
                    copy.set("ranges", entry.get("ranges"));
                }
                if (entry.has("versions")) {
                    copy.set("versions", entry.get("versions"));
                }
            }
        }
        return kept;
    }

    private void writePackage(String osvName, String generation, String key, Map<String, JsonNode> records)
            throws IOException {
        String at = packageKey(osvName, generation, key);
        if (records.isEmpty()) {
            space.get().delete(at);
            return;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("name", key);
        ArrayNode array = node.putArray("records");
        records.values().forEach(array::add);
        writeJson(at, node);
    }

    private void writeIndex(String osvName, String generation, String id, Set<String> packages) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id);
        ArrayNode names = node.putArray("packages");
        packages.forEach(names::add);
        writeJson(indexKey(osvName, generation, id), node);
    }

    private void writeState(String osvName, State state) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("generation", state.generation());
        node.put("built", state.built().toString());
        node.put("drawn", state.drawn().toString());
        node.put("position", state.position().toString());
        if (state.previous() != null) {
            node.put("previous", state.previous());
        }
        writeJson(current(osvName), node);
    }

    /** Delete every object under {@code prefix}, a page at a time. */
    private void delete(String prefix) throws IOException {
        ArtifactStore store = space.get();
        String resume = "";
        while (resume != null) {
            List<String> keys = new ArrayList<>();
            ArtifactStore.Scan scan = store.scan(prefix, resume, BATCH, listed -> keys.add(listed.key()));
            for (String key : keys) {
                store.delete(key);
            }
            resume = scan.cursor().orElse(null);
        }
    }

    /** The JSON document at {@code key}, empty where none is: one read, so a document deleted between a probe and
     *  an open cannot fail the read. */
    private Optional<JsonNode> readJson(String key) throws IOException {
        return space.get().readVersioned(key).map(stored -> JSON.readTree(stored.content()));
    }

    private void writeJson(String key, JsonNode node) throws IOException {
        space.get().write(key, new ByteArrayInputStream(JSON.writeValueAsBytes(node)));
    }

    private static String current(String osvName) {
        return osvName + "/current";
    }

    private static String packageKey(String osvName, String generation, String key) {
        return osvName + "/" + generation + "/packages/" + Checksums.sha256(key);
    }

    private static String indexKey(String osvName, String generation, String id) {
        return osvName + "/" + generation + "/records/" + Checksums.sha256(id);
    }

    /** A failure of the mirror's own space while a record is filed inside the archive's read, carried out through the
     *  feed client so it is reported as the store's and never as the export's. */
    private static final class StoreFailure extends UncheckedIOException {

        StoreFailure(IOException cause) {
            super(cause);
        }
    }
}
