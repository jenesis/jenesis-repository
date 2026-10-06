package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The layout, keys and codec of the reverse-dependency index, shared by the build path ({@link DependentsIndex}) and
 * the query path ({@link DependentsQueryReader}) so the two cannot drift: the key constants, the sharding function,
 * the line codec and the per-segment {@link SegmentState} the walk-riding rebuild checkpoints through.
 *
 * <p>A coordinate maps to one shard object {@code dependents/<hh>} (the first byte of the SHA-256 of its
 * <em>neutralised</em> spelling, so at most 256 shards); a shard lists, one line per coordinate, the dependent
 * coordinates that pull it in, every token URL-encoded so no coordinate holding a space or newline can split a line -
 * a byte-stable, cacheable object.
 *
 * <p>The shard byte is taken over the neutral spelling: the index records the package URL an SBOM used, while every
 * reachability question ({@link DependentsQuery#reachable(java.util.Collection)}) is phrased in the
 * {@code group:name:version} a report line keys on. {@link DependentsQuery#neutralise} leaves a neutral coordinate
 * untouched, so both land in one shard and {@code reachable} costs {@code min(k, 256)} reads rather than a scan.
 */
final class DependentsStore {

    private DependentsStore() {
    }

    /** The store prefix the sharded index lives under, a sibling of {@code blobs/} and the version documents. */
    static final String PREFIX = "dependents";

    /** The walk consumer name - the pass and its per-segment partials live under {@code walks/dependents/}. */
    static final String PASS = "dependents";

    /** Where the pass's per-segment partial inversions live, beside the walk state, keyed by segment index: a
     *  {@code <nnn>/head} object stable across generations, so a superseded pass's HEAD still donates its token, and
     *  {@code <nnn>/g<gen>/c<n>} chunks scoped by generation, so a new pass's never collide with the old. */
    static final String PASS_STATE = "walks/" + PASS + "/state";

    /** The last pass generation merged, so a merged pass is not merged again on every rebuild. Best-effort: a lost
     *  marker only re-runs the idempotent merge. */
    static final String MERGED = "walks/" + PASS + "/merged";

    /** The negative cache of blobs a complete read proved to carry no SBOM - permanent, since a blob is immutable - so
     *  a sweep does not decompress them again. A read failing part way is never cached. Not a two-hex name, so the
     *  shard walks pass over it. */
    static final String NO_SBOM = PREFIX + "/nosbom";

    /** The fixed body of a negative-cache marker - its presence under {@code dependents/nosbom/<hash>} is the signal. */
    static final byte[] NO_SBOM_MARKER = "jenesis-dependents-nosbom 1\n".getBytes(StandardCharsets.UTF_8);

    /** The marker every commit writes, even one filling no shard, so a swept-empty index is told from a never-built
     *  one. Its body, {@code <layout> <instant>} (see {@link #LAYOUT}), says how fresh the index is
     *  ({@link DependentsQueryReader#builtAt()}). Not a two-hex name, so the shard walks pass over it. */
    static final String BUILT = PREFIX + "/built";

    /**
     * The generation of the shard layout the marker names, bumped whenever {@link #shard} changes where a coordinate
     * lands. {@link DependentsQueryReader#built()} reports a marker of another generation as not built, so the next
     * sweep runs the full rebuild, which re-derives every shard and compacts away the misplaced ones.
     */
    static final int LAYOUT = 2;

    /** The built marker's body for a sweep completing now: {@code <layout> <instant>}. */
    static byte[] builtMarker(Instant instant) {
        return (LAYOUT + " " + instant + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Whether a built marker body was stamped by this shard layout; any other body, a bare instant included, reads as
     *  not built. */
    static boolean builtHere(byte[] content) {
        String first = new String(content, StandardCharsets.UTF_8).trim().split(" ", 2)[0];
        try {
            return Integer.parseInt(first) == LAYOUT;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /** The instant a built marker of this layout stamps, or empty for another layout or a torn instant. */
    static Optional<Instant> builtAt(byte[] content) {
        if (!builtHere(content)) {
            return Optional.empty();
        }
        String[] body = new String(content, StandardCharsets.UTF_8).trim().split(" ", 2);
        if (body.length < 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(body[1].trim()));
        } catch (DateTimeParseException _) {
            return Optional.empty();                             // a torn marker body: built, instant unknown
        }
    }

    /** The stable child name of a segment's HEAD under its {@code <nnn>/} subtree - not a {@code g<gen>} chunk dir. */
    static final String HEAD = "head";

    /** Each blob's contributed edges - its root and the coordinates it was recorded under - keyed by blob hash, so the
     *  incremental sweep undoes exactly a deleted blob's edges. Written by the incremental path only; not a two-hex
     *  name, so the shard walks pass over it. */
    static final String EDGES = PREFIX + "/edges";

    /** How many incremental sweeps have run since the last full rebuild. */
    static final String PASSES = PREFIX + "/passes";

    /**
     * The declared tier ({@link DeclaredDependents}): {@code <hh>} shards in the resolved tier's line format, keyed by
     * the package name as the manifest spelled it - nothing joins a declaration to a report line, so nothing is
     * neutralised - each dependent a {@link #declaration} entry; {@link #DECLARED_BY} records; and the pass's counter
     * and full-pass stamp.
     */
    static final String DECLARED = PREFIX + "/declared";

    /** One record per declaring version, keyed by the digest of its ecosystem, coordinate and version: what that
     *  version contributed to the declared shards, so a re-decision takes out exactly what it put in. */
    static final String DECLARED_BY = DECLARED + "/by";

    /** How many declared-tier passes have run since the last full one. */
    static final String DECLARED_PASSES = DECLARED + "/passes";

    /** When the last full declared-tier pass started - the tier's built stamp. */
    static final String DECLARED_FULL = DECLARED + "/full";

    /** The declared shard a package name belongs to: the first byte of the SHA-256 of the name as spelled. */
    static String declaredShardKey(String dependency) {
        byte[] digest = SHA256.get().digest(dependency.getBytes(StandardCharsets.UTF_8));
        return DECLARED + "/" + HexFormat.of().formatHex(digest, 0, 1);
    }

    /** The record key of one declaring version. */
    static String declaredRecordKey(String ecosystem, String coordinate, String version) {
        byte[] digest = SHA256.get().digest((encode(ecosystem) + ' ' + encode(coordinate) + ' ' + encode(version))
                .getBytes(StandardCharsets.UTF_8));
        return DECLARED_BY + "/" + HexFormat.of().formatHex(digest);
    }

    /** One declared shard entry: the declaring version and the requirement it states, each token URL-encoded and
     *  space-joined, so the entries of one package sort by ecosystem, then coordinate, then version. */
    static String declaration(String ecosystem, String coordinate, String version, String requirement) {
        return encode(ecosystem) + ' ' + encode(coordinate) + ' ' + encode(version) + ' ' + encode(requirement);
    }

    /** A {@link #declaration} entry read back, or {@code null} for a torn one. */
    static DependentsQuery.Declaration parseDeclaration(String entry) {
        String[] tokens = entry.split(" ", -1);
        if (tokens.length != 4) {
            return null;
        }
        try {
            return new DependentsQuery.Declaration(decode(tokens[0]), decode(tokens[1]), decode(tokens[2]),
                    decode(tokens[3]));
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** What one version declares, as its record holds it: the version, and each package it names with the
     *  requirement stated - a set, so a manifest naming one package twice with one requirement contributes once. */
    record DeclaredRecord(String ecosystem, String coordinate, String version, Set<Declares> declares) {

        DeclaredRecord {
            declares = Set.copyOf(declares);
        }

        /** The first line names the version; each further line one package and its requirement. */
        byte[] serialise() {
            StringBuilder builder = new StringBuilder()
                    .append(encode(ecosystem)).append(' ').append(encode(coordinate)).append(' ')
                    .append(encode(version)).append('\n');
            for (Declares declared : new TreeSet<>(declares)) {
                builder.append(encode(declared.dependency())).append(' ')
                        .append(encode(declared.requirement())).append('\n');
            }
            return builder.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** A record read back, or {@code null} for a torn one - which the next full pass rewrites. */
        static DeclaredRecord parse(byte[] content) {
            String[] lines = new String(content, StandardCharsets.UTF_8).split("\n");
            try {
                String[] head = lines[0].split(" ", -1);
                if (head.length != 3) {
                    return null;
                }
                Set<Declares> declares = new TreeSet<>();
                for (int line = 1; line < lines.length; line++) {
                    if (lines[line].isBlank()) {
                        continue;
                    }
                    String[] tokens = lines[line].split(" ", -1);
                    declares.add(new Declares(decode(tokens[0]), tokens.length > 1 ? decode(tokens[1]) : ""));
                }
                return new DeclaredRecord(decode(head[0]), decode(head[1]), decode(head[2]), declares);
            } catch (RuntimeException _) {
                return null;
            }
        }
    }

    /** One package a version names and the requirement it states for it. */
    record Declares(String dependency, String requirement) implements Comparable<Declares> {

        @Override
        public int compareTo(Declares other) {
            int byDependency = dependency.compareTo(other.dependency);
            return byDependency != 0 ? byDependency : requirement.compareTo(other.requirement);
        }
    }

    /** The per-blob contributed-edges record key ({@code dependents/edges/<hash>}). */
    static String edgesKey(String hash) {
        return EDGES + "/" + hash;
    }

    /** A shard object name is exactly the two lowercase-hex characters {@link #shard} produces, so the built marker
     *  and any other non-shard object under the prefix are told apart from a real shard by name alone. */
    static boolean isShard(String name) {
        if (name.length() != 2) {
            return false;
        }
        for (int index = 0; index < 2; index++) {
            char c = name.charAt(index);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /** The negative-cache key for a blob key ({@code blobs/<hash>} -> {@code dependents/nosbom/<hash>}), or
     *  {@code null} for anything that is not a blob key (the walk and the paged rebuild only ever pass blob keys). */
    static String negativeKey(String blobKey) {
        return blobKey.startsWith("blobs/") ? NO_SBOM + "/" + blobKey.substring("blobs/".length()) : null;
    }

    /** The generation a {@code g<gen>} chunk directory names, or {@code -1} for a name that is not one. */
    static long parseGeneration(String directory) {
        try {
            return Long.parseLong(directory.substring(1));
        } catch (RuntimeException _) {
            return -1L;
        }
    }

    static String shardKey(String coordinate) {
        return PREFIX + "/" + shard(coordinate);
    }

    /** A per-thread digest, since {@link #shard} runs once per coordinate in a full rebuild. */
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                 // SHA-256 is a required JDK algorithm
        }
    });

    /** The two-hex shard a coordinate belongs to: the first byte of the SHA-256 of its neutralised spelling - the one
     *  function the write path and both read paths apply. */
    static String shard(String coordinate) {
        // digest(byte[]) completes and resets the instance, so the reused per-thread digest is safe without a reset.
        byte[] digest = SHA256.get().digest(DependentsQuery.neutralise(coordinate).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, 1);
    }

    /** Serialise a shard as sorted {@code <coordinate> <dependent>...} lines, every token URL-encoded so no
     *  coordinate that itself contains a space or newline can split a line - a byte-stable, cacheable object. */
    static byte[] serialise(Map<String, Collection<String>> shard) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Collection<String>> entry : new TreeMap<>(shard).entrySet()) {
            builder.append(encode(entry.getKey()));
            for (String dependent : new TreeSet<>(entry.getValue())) {
                builder.append(' ').append(encode(dependent));
            }
            builder.append('\n');
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The dependents recorded for one {@code coordinate} in its shard, found by comparing encoded first tokens, so
     * only the matching line is decoded.
     */
    static List<String> dependentsOf(byte[] content, String coordinate) {
        String target = encode(coordinate);
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int space = line.indexOf(' ');
            String key = space < 0 ? line : line.substring(0, space);
            if (!key.equals(target)) {
                continue;                                       // not our coordinate - never split or decode this line
            }
            if (space < 0) {
                return List.of();                               // the coordinate is listed with no dependents
            }
            try {
                String[] tokens = line.substring(space + 1).split(" ");
                List<String> dependents = new ArrayList<>(tokens.length);
                for (String token : tokens) {
                    dependents.add(decode(token));
                }
                return List.copyOf(dependents);
            } catch (RuntimeException _) {
                return List.of();                               // a torn line for our key; the next sweep rewrites it
            }
        }
        return List.of();
    }

    /** Collects a shard's coordinate keys, decoding only each line's first token; a torn one is skipped. */
    static void collectKeys(byte[] content, Collection<String> into) {
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int space = line.indexOf(' ');
            try {
                into.add(decode(space < 0 ? line : line.substring(0, space)));
            } catch (RuntimeException _) {
                // a torn key token is skipped; the next sweep rewrites the shard
            }
        }
    }

    /**
     * One compare-and-set of the shard at {@code key}: the removals out, the additions in, a key left with no entries
     * dropping its line. Idempotent (set semantics), so a replayed batch never duplicates, and retried on a lost race,
     * so a concurrent pass never loses an entry.
     *
     * <p>An emptied shard is written back empty, not deleted: replicas run this concurrently and a delete is not
     * token-fenced, so it could wipe an entry a racing rewrite just committed. The reconcile compacts it.
     */
    static void rewriteShard(ArtifactStore store, String key, Map<String, Set<String>> removals,
                             Map<String, Set<String>> additions) throws IOException {
        Retries.update(store, key, stored -> {
            if (stored.isEmpty() && additions.isEmpty()) {
                return null;   // nothing recorded in this shard and nothing to add - nothing to remove either
            }
            Map<String, Collection<String>> shard =
                    stored.map(versioned -> parseShard(versioned.content())).orElseGet(TreeMap::new);
            removals.forEach((entry, values) -> {
                Collection<String> held = shard.get(entry);
                if (held != null) {
                    held.removeAll(values);
                    if (held.isEmpty()) {
                        shard.remove(entry);
                    }
                }
            });
            additions.forEach((entry, values) -> shard.computeIfAbsent(entry, _ -> new TreeSet<>()).addAll(values));
            return serialise(shard);
        });
    }

    /** A whole shard as its {@code coordinate -> dependents} map, the inverse of {@link #serialise}; a torn line is
     *  skipped. */
    static Map<String, Collection<String>> parseShard(byte[] content) {
        Map<String, Collection<String>> shard = new TreeMap<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                String[] tokens = line.split(" ");
                Collection<String> dependents = new TreeSet<>();
                for (int token = 1; token < tokens.length; token++) {
                    dependents.add(decode(tokens[token]));
                }
                shard.put(decode(tokens[0]), dependents);
            } catch (RuntimeException _) {
                // a torn line is skipped; the next reconcile rewrites the shard from truth
            }
        }
        return shard;
    }

    /** A contributed-edges record: one line, the root then every coordinate it was recorded under, URL-encoded and
     *  sorted. */
    static byte[] serialiseEdges(String root, Collection<String> dependencies) {
        StringBuilder builder = new StringBuilder(encode(root));
        for (String dependency : new TreeSet<>(dependencies)) {
            builder.append(' ').append(encode(dependency));
        }
        builder.append('\n');
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A contributed-edges record read back; {@code null} for an empty or torn one, which removes nothing. */
    record ContributedEdges(String root, List<String> dependencies) {

        static ContributedEdges parse(byte[] content) {
            String text = new String(content, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            String line = newline < 0 ? text : text.substring(0, newline);
            if (line.isBlank()) {
                return null;
            }
            try {
                String[] tokens = line.split(" ");
                List<String> dependencies = new ArrayList<>(tokens.length - 1);
                for (int token = 1; token < tokens.length; token++) {
                    dependencies.add(decode(tokens[token]));
                }
                return new ContributedEdges(decode(tokens[0]), dependencies);
            } catch (RuntimeException _) {
                return null;
            }
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** A segment owns the subtree {@code walks/dependents/state/<nnn>/}. */
    private static String segmentBase(int index) {
        return PASS_STATE + "/" + String.format(Locale.ROOT, "%03d", index);
    }

    /** The segment's stable, CAS-fenced HEAD object (generation, holder, chunk count, cursor). */
    static String headKey(int index) {
        return segmentBase(index) + "/" + HEAD;
    }

    /** The generation-scoped, immutable append chunk {@code <nnn>/g<gen>/c<n>} - the n-th delta this pass flushed. */
    static String chunkKey(int index, long generation, int chunk) {
        return segmentBase(index) + "/g" + generation + "/c" + chunk;
    }


    /** A segment's HEAD: the generation and holder that fence it, the count of committed chunks and the last inverted
     *  key. The edges live in the chunks, so the HEAD stays small whatever the segment's length. */
    record SegmentState(long generation, String holder, int chunkCount, String cursor) {

        /** The single HEAD line {@code <generation> <holder> <chunkCount> [<encoded cursor>]} - the cursor
         *  URL-encoded so no key holding a space can split it. */
        static byte[] serializeHead(long generation, String holder, int chunkCount, String cursor) {
            StringBuilder builder = new StringBuilder();
            builder.append(generation).append(' ').append(holder).append(' ').append(chunkCount);
            if (cursor != null) {
                builder.append(' ').append(encode(cursor));
            }
            builder.append('\n');
            return builder.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** {@code null} for an unparseable HEAD - treated as a superseded pass's leftover, never a failure. */
        static SegmentState parseHead(byte[] content) {
            try {
                String first = new String(content, StandardCharsets.UTF_8).split("\n", 2)[0];
                String[] header = first.split(" ");
                if (header.length < 3 || header.length > 4) {
                    return null;
                }
                long generation = Long.parseLong(header[0]);
                int chunkCount = Integer.parseInt(header[2]);
                String cursor = header.length == 4 ? decode(header[3]) : null;
                return new SegmentState(generation, header[1], chunkCount, cursor);
            } catch (RuntimeException _) {
                return null;
            }
        }

        /** An append chunk: one delta's edges in the shard line format, with no header. */
        static byte[] serializeChunk(Map<String, SortedSet<String>> edges) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, SortedSet<String>> entry : edges.entrySet()) {
                builder.append(encode(entry.getKey()));
                for (String dependent : entry.getValue()) {
                    builder.append(' ').append(encode(dependent));
                }
                builder.append('\n');
            }
            return builder.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** An append chunk's edges; a torn or blank line is skipped. */
        static Map<String, SortedSet<String>> parseChunk(byte[] content) {
            Map<String, SortedSet<String>> edges = new TreeMap<>();
            for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    String[] tokens = line.split(" ");
                    SortedSet<String> dependents = new TreeSet<>();
                    for (int token = 1; token < tokens.length; token++) {
                        dependents.add(decode(tokens[token]));
                    }
                    edges.put(decode(tokens[0]), dependents);
                } catch (RuntimeException _) {
                    // a torn line contributes nothing
                }
            }
            return edges;
        }
    }
}
