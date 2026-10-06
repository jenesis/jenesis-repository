package build.jenesis.repository.closure.lock;

import module java.base;
import build.jenesis.repository.closure.CarriedClosure;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.closure.ClosureSource;
import build.jenesis.repository.closure.ClosureWalk;
import build.jenesis.repository.compliance.Ecosystems;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * A crate's closure from the {@code Cargo.lock} it carries, as the build that packaged it resolved it: each package
 * the lock pins, reached from the crate's own entry along the dependencies each one lists, at the distance it is
 * reached. A lock records a build's development dependencies beside the rest, so the closure may name a package a
 * consumer's build never compiles. A package the lock names with no source is built from the crate's own sources - a
 * workspace member or a path dependency - and names nothing a repository holds, so it is a cut. A lock that does not
 * parse, or has no entry for the crate itself, is no closure, and the resolvers answer instead.
 */
public final class CargoLock implements ClosureSource {

    /** The source's name. */
    public static final String NAME = "carried-lock-cargo";

    private static final TomlMapper TOML = TomlMapper.builder().build();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> ecosystems() {
        return Set.of(Ecosystems.CRATES_IO);
    }

    @Override
    public Kind kind() {
        return Kind.BILL;
    }

    @Override
    public Optional<ClosureSection.Closure> resolve(ClosureWalk walk, String ecosystem, String coordinate,
                                                    String version, Instant now) throws IOException {
        Optional<byte[]> lock = CarriedLock.read(walk, ecosystem, coordinate, version, "Cargo.lock");
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<CarriedClosure.Entry>> entries = entries(lock.get(), coordinate, version);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(CarriedClosure.place(walk, ecosystem, coordinate, version, entries.get(),
                "the version's Cargo.lock", NAME, now));
    }

    /** One pinned package: its name, version, where it comes from, and the packages it lists. */
    private record Pinned(String name, String version, String source, List<String> dependencies) {
    }

    /** The packages {@code lock} pins below {@code name} at {@code version}, each at its distance from it, or empty
     *  where the lock does not parse or has no entry for it. */
    static Optional<List<CarriedClosure.Entry>> entries(byte[] lock, String name, String version) {
        JsonNode root;
        try {
            root = TOML.readTree(lock);
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
        List<Pinned> pinned = new ArrayList<>();
        for (JsonNode node : root.path("package")) {
            List<String> dependencies = new ArrayList<>();
            for (JsonNode dependency : node.path("dependencies")) {
                dependencies.add(dependency.asString(""));
            }
            pinned.add(new Pinned(node.path("name").asString(""), node.path("version").asString(""),
                    node.path("source").asString(null), dependencies));
        }
        Optional<Pinned> crate = pinned.stream()
                .filter(at -> at.name().equals(name) && at.version().equals(version) && at.source() == null)
                .findFirst();
        if (crate.isEmpty()) {
            return Optional.empty();
        }
        List<CarriedClosure.Entry> entries = new ArrayList<>();
        Map<Pinned, Integer> depths = new HashMap<>();
        // The crate whose dependencies first named each one reached; the crate itself names its direct ones.
        Map<Pinned, Pinned> parents = new HashMap<>();
        depths.put(crate.get(), 0);
        Deque<Pinned> queue = new ArrayDeque<>(List.of(crate.get()));
        while (!queue.isEmpty()) {
            Pinned at = queue.poll();
            int depth = depths.get(at);
            if (depth > 0) {
                entries.add(at.source() == null
                        ? CarriedClosure.Entry.unplaced(at.name(), at.version(), "built from the crate's own sources, "
                                + "as the version's Cargo.lock records it, which names nothing a repository holds")
                        : depth == 1
                                ? CarriedClosure.Entry.placed(Ecosystems.CRATES_IO, at.name(), at.version(), depth)
                                : CarriedClosure.Entry.placed(Ecosystems.CRATES_IO, at.name(), at.version(), depth,
                                        parents.get(at).name(), parents.get(at).version()));
            }
            for (String dependency : at.dependencies()) {
                Pinned next = pinned(pinned, dependency);
                if (next != null && !depths.containsKey(next)) {
                    depths.put(next, depth + 1);
                    parents.put(next, at);
                    queue.add(next);
                }
            }
        }
        return Optional.of(entries);
    }

    /** The package a lock's dependency names - {@code name}, {@code name version} or
     *  {@code name version (source)}, the version written only where two versions of the name are pinned - or
     *  {@code null} where the lock pins none. */
    private static Pinned pinned(List<Pinned> pinned, String dependency) {
        String[] parts = dependency.strip().split(" ", 3);
        List<Pinned> named = pinned.stream().filter(at -> at.name().equals(parts[0])
                && (parts.length < 2 || at.version().equals(parts[1]))).toList();
        if (named.size() > 1 && parts.length == 3) {
            String source = parts[2].replaceAll("^\\(|\\)$", "");
            named = named.stream().filter(at -> source.equals(at.source())).toList();
        }
        return named.isEmpty() ? null : named.getFirst();
    }
}
