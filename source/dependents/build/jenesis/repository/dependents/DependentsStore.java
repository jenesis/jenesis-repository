package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The layout, keys and codec of the declared-dependencies index, shared by the pass that writes it
 * ({@link DeclaredDependents}) and the read path ({@link DependentsQueryReader}) so the two cannot drift.
 *
 * <p>A package name maps to one shard object {@code dependents/declared/<hh>} (the first byte of the SHA-256 of the name
 * as spelled); a shard lists, one line per package, the versions declaring a dependency on it, every token URL-encoded
 * so no name holding a space or newline can split a line - a byte-stable, cacheable object.
 */
final class DependentsStore {

    private DependentsStore() {
    }

    /** The store prefix the index lives under, a sibling of {@code blobs/} and the version documents. */
    static final String PREFIX = "dependents";

    /**
     * The declared dependencies ({@link DeclaredDependents}): {@code <hh>} shards keyed by the package name as the
     * manifest spelled it, each dependent a {@link #declaration} entry; {@link #DECLARED_BY} records; and the pass's
     * counter and full-pass stamp.
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

    /** A per-thread digest, since a pass derives a shard key per declared package. */
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                 // SHA-256 is a required JDK algorithm
        }
    });

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
                return List.of();                               // a torn line for our key; the next full pass rewrites it
            }
        }
        return List.of();
    }

    /**
     * One compare-and-set of the shard at {@code key}: the removals out, the additions in, a key left with no entries
     * dropping its line. Idempotent (set semantics), so a replayed batch never duplicates, and retried on a lost race,
     * so a concurrent pass never loses an entry.
     *
     * <p>An emptied shard is written back empty, not deleted: replicas run this concurrently and a delete is not
     * token-fenced, so it could wipe an entry a racing rewrite just committed.
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
                // a torn line is skipped; the next full pass rewrites the shard
            }
        }
        return shard;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
