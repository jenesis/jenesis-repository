package build.jenesis.repository.closure.lock;

import module java.base;
import build.jenesis.repository.closure.CarriedClosure;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.closure.spi.ClosureWalk;
import build.jenesis.repository.compliance.Ecosystems;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An npm release's closure from the {@code npm-shrinkwrap.json} its tarball carries, which npm installs exactly as
 * written when the package is installed as a dependency - unlike a {@code package-lock.json}, which a published
 * package never carries. Each package the lock installs is reached from the root along the dependencies each one
 * declares, resolved the way npm resolves a name - the nearest {@code node_modules} up the installing package's path -
 * at the distance it is reached. A development dependency is not installed for a consumer and is not reached; a
 * dependency bundled inside the tarball is vendored code, and is left out; a link to a folder names nothing a
 * repository holds, and is a cut. A lock that does not parse, or names no root, is no closure, and the resolvers
 * answer instead.
 */
public final class NpmShrinkwrap implements ClosureSource {

    /** The source's name. */
    public static final String NAME = "carried-lock-npm";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String MODULES = "node_modules/";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> ecosystems() {
        return Set.of(Ecosystems.NPM);
    }

    @Override
    public Kind kind() {
        return Kind.LOCK;
    }

    @Override
    public Optional<ClosureSection.Closure> resolve(ClosureWalk walk, String ecosystem, String coordinate,
                                                    String version, Instant now) throws IOException {
        Optional<byte[]> lock = CarriedLock.read(walk, ecosystem, coordinate, version, "npm-shrinkwrap.json");
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<CarriedClosure.Entry>> entries = entries(lock.get());
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(CarriedClosure.place(walk, ecosystem, coordinate, version, entries.get(),
                "the version's npm-shrinkwrap.json", this, now));
    }

    /** One installed package: its name, version, what it declares it needs, and how the lock marks it. */
    private record Installed(String name, String version, List<String> needs, boolean dev, boolean bundled,
                             boolean link) {
    }

    /** The packages {@code lock} installs for a consumer, each at its distance from the root, or empty where it does
     *  not parse. */
    static Optional<List<CarriedClosure.Entry>> entries(byte[] lock) {
        JsonNode root;
        try {
            root = JSON.readTree(lock);
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
        Map<String, Installed> installed = new LinkedHashMap<>();
        List<String> roots;
        if (root.path("packages").isObject()) {
            root.path("packages").properties().forEach(entry -> installed.put(entry.getKey(),
                    installed(entry.getKey(), entry.getValue())));
            Installed top = installed.remove("");
            if (top == null) {
                return Optional.empty();
            }
            roots = top.needs();
        } else if (root.path("dependencies").isObject()) {
            flatten(root.path("dependencies"), "", installed);
            roots = installed.entrySet().stream()
                    .filter(entry -> entry.getKey().lastIndexOf(MODULES) == 0 && !entry.getValue().dev())
                    .map(entry -> entry.getValue().name()).toList();
        } else {
            return Optional.empty();
        }
        List<CarriedClosure.Entry> entries = new ArrayList<>();
        Map<String, Integer> depths = new HashMap<>();
        // The install path whose dependencies first named each one reached, "" for the root's own.
        Map<String, String> parents = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String name : roots) {
            reach(installed, "", name, 1, depths, parents, queue);
        }
        while (!queue.isEmpty()) {
            String path = queue.poll();
            Installed at = installed.get(path);
            int depth = depths.get(path);
            if (at.link()) {
                entries.add(CarriedClosure.Entry.unplaced(at.name(), at.version(),
                        "linked by the version's npm-shrinkwrap.json to a folder, which names nothing a repository "
                                + "holds"));
                continue;
            }
            if (at.version() == null || at.version().isBlank()) {
                entries.add(CarriedClosure.Entry.unplaced(at.name(), "",
                        "named by the version's npm-shrinkwrap.json with no version"));
                continue;
            }
            Installed via = installed.get(parents.getOrDefault(path, ""));
            entries.add(via == null
                    ? CarriedClosure.Entry.placed(Ecosystems.NPM, at.name(), at.version(), depth)
                    : CarriedClosure.Entry.placed(Ecosystems.NPM, at.name(), at.version(), depth, via.name(),
                            via.version()));
            for (String need : at.needs()) {
                reach(installed, path, need, depth + 1, depths, parents, queue);
            }
        }
        return Optional.of(entries);
    }

    /** Queue the package {@code name} as the one installed at {@code from} resolves it, where it is installed, not yet
     *  reached, and installed for a consumer. */
    private static void reach(Map<String, Installed> installed, String from, String name, int depth,
                              Map<String, Integer> depths, Map<String, String> parents, Deque<String> queue) {
        String path = resolve(installed, from, name);
        if (path == null || depths.containsKey(path)) {
            return;
        }
        Installed at = installed.get(path);
        if (at.dev() || at.bundled()) {
            return;
        }
        depths.put(path, depth);
        parents.put(path, from);
        queue.add(path);
    }

    /** Where the package installed at {@code from} finds {@code name}: its own {@code node_modules}, then each one up
     *  its path to the root's, as npm resolves it. */
    private static String resolve(Map<String, Installed> installed, String from, String name) {
        String base = from;
        while (true) {
            String candidate = base.isEmpty() ? MODULES + name : base + "/" + MODULES + name;
            if (installed.containsKey(candidate)) {
                return candidate;
            }
            if (base.isEmpty()) {
                return null;
            }
            int up = base.lastIndexOf("/" + MODULES);
            base = up < 0 ? "" : base.substring(0, up);
        }
    }

    /** A {@code packages} entry: its name is its own where it records one - an alias installs a package under another
     *  name - else the path's last {@code node_modules} segment. */
    private static Installed installed(String path, JsonNode entry) {
        int last = path.lastIndexOf(MODULES);
        String name = entry.path("name").asString(last < 0 ? path : path.substring(last + MODULES.length()));
        List<String> needs = new ArrayList<>();
        for (String field : List.of("dependencies", "optionalDependencies", "peerDependencies")) {
            entry.path(field).propertyNames().forEach(needs::add);
        }
        return new Installed(name, entry.path("version").asString(null), needs, entry.path("dev").asBoolean(false),
                entry.path("inBundle").asBoolean(false), entry.path("link").asBoolean(false));
    }

    /** A lock of the first form's nested {@code dependencies}, as the paths the later forms key {@code packages}
     *  by. */
    private static void flatten(JsonNode dependencies, String under, Map<String, Installed> installed) {
        dependencies.properties().forEach(entry -> {
            String path = (under.isEmpty() ? "" : under + "/") + MODULES + entry.getKey();
            JsonNode node = entry.getValue();
            List<String> needs = new ArrayList<>();
            node.path("requires").propertyNames().forEach(needs::add);
            String version = node.path("version").asString(null);
            boolean link = version != null && version.startsWith("file:");
            installed.put(path, new Installed(entry.getKey(), version, needs, node.path("dev").asBoolean(false),
                    node.path("bundled").asBoolean(false), link));
            flatten(node.path("dependencies"), path, installed);
        });
    }
}
