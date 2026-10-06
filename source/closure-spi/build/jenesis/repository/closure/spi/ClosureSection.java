package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.inventory.CachedSection;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code closure} section codec of the consolidated metadata document: a published version's transitive closure as
 * the repository could resolve it from what it and the repositories its fallbacks name hold, when it was resolved, and
 * every subtree that could not be. Absent for a version not yet resolved. The {@code data} payload is
 * {@code {"status":<RESOLVED|PARTIAL|UNDECLARED>,
 * "components":[{"coordinate","version","cached","depth","repository","via":{"coordinate","version"}}],
 * "cuts":[{"coordinate","requirement","reason"}], "truncated":<bool>, "kind":<a {@link ClosureSource.Kind}>,
 * "source":<the producing source's name>, "foreign":[{"ecosystem","coordinate","version","depth","via"}]}}, every
 * component in the version's own ecosystem; a component's {@code repository} is present only where a fallback's
 * repository holds it, and its {@code via} only where it was reached through another dependency rather than named by
 * the version. {@code foreign} is present only where a carried bill names packages of other ecosystems.
 */
public final class ClosureSection {

    /** The section tag. */
    public static final String TAG = "closure";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ClosureSection() {
    }

    /** Whether every subtree resolved, some were cut, or the version declares nothing this repository can read - a
     *  recipe that is a program, a format whose artifacts declare no dependencies - which is not an empty closure. */
    public enum Status {
        RESOLVED, PARTIAL, UNDECLARED
    }

    /** One held version the closure reaches: a cached copy where {@code cached}, a release otherwise, {@code depth}
     *  edges from the version resolved, held by the repository the version was published to where {@code repository}
     *  is empty and by the fallback's repository it names otherwise, and reached through the dependency
     *  {@code viaCoordinate} at {@code viaVersion} - the one whose declarations named it first - or directly where
     *  those are empty. */
    public record Component(String coordinate, String version, boolean cached, int depth, String repository,
                            String viaCoordinate, String viaVersion) {

        public Component {
            repository = repository == null ? "" : repository;
            viaCoordinate = viaCoordinate == null ? "" : viaCoordinate;
            viaVersion = viaVersion == null ? "" : viaVersion;
        }

        /** A dependency the version resolved names itself. */
        public Component(String coordinate, String version, boolean cached, int depth, String repository) {
            this(coordinate, version, cached, depth, repository, "", "");
        }

        /** Whether a fallback's repository, not the version's own, holds this component. */
        public boolean elsewhere() {
            return !repository.isEmpty();
        }

        /** Whether the version resolved names this dependency itself. */
        public boolean direct() {
            return viaCoordinate.isEmpty();
        }
    }

    /** One step of a path through a closure: a dependency at the version the closure holds. */
    public record Hop(String coordinate, String version) {
    }

    /**
     * How {@code closure} reaches {@code coordinate} at {@code version}: from the dependency the resolved version names
     * itself down to it, each step the component the next was first named through. Empty where the closure does not
     * reach it. A step whose own component the closure does not hold - a dependency that was cut - ends the path there,
     * named, since nothing records what named it.
     */
    public static List<Hop> path(Closure closure, String coordinate, String version) {
        Map<String, Component> byName = new HashMap<>();
        for (Component component : closure.components()) {
            byName.putIfAbsent(component.coordinate() + "@" + component.version(), component);
        }
        Component at = byName.get(coordinate + "@" + version);
        if (at == null) {
            return List.of();
        }
        Deque<Hop> hops = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        while (at != null && visited.add(at.coordinate() + "@" + at.version())) {
            hops.addFirst(new Hop(at.coordinate(), at.version()));
            if (at.direct()) {
                break;
            }
            Component parent = byName.get(at.viaCoordinate() + "@" + at.viaVersion());
            if (parent == null) {
                hops.addFirst(new Hop(at.viaCoordinate(), at.viaVersion()));
            }
            at = parent;
        }
        return List.copyOf(hops);
    }

    /** A dependency whose subtree did not resolve: what was asked for and why it ended there. */
    public record Cut(String coordinate, String requirement, String reason) {
    }

    /**
     * A package the version's carried bill names in another ecosystem than the version's own - a jar or a distribution
     * package inside an image - at {@code depth}, reached through {@code viaCoordinate} at {@code viaVersion} as a
     * {@link Component} is. No repository of the walk is asked to hold it, since the build that installed it resolved
     * it elsewhere: it is indexed by its coordinate across the tenant by the relied-on index, so a finding or a hold on any
     * copy of it the tenant holds reaches the version.
     */
    public record Foreign(String ecosystem, String coordinate, String version, int depth, String viaCoordinate,
                          String viaVersion) {

        public Foreign {
            viaCoordinate = viaCoordinate == null ? "" : viaCoordinate;
            viaVersion = viaVersion == null ? "" : viaVersion;
        }

        /** Whether the version resolved names this package itself. */
        public boolean direct() {
            return viaCoordinate.isEmpty();
        }
    }

    /**
     * How {@code closure} reaches the package {@code coordinate} at {@code version} of {@code ecosystem}, another
     * ecosystem than its own: from the package the version names itself down to it, as {@link #path} answers a
     * component. Empty where the closure does not name it.
     */
    public static List<Hop> foreignPath(Closure closure, String ecosystem, String coordinate, String version) {
        Map<String, Foreign> byName = new HashMap<>();
        for (Foreign foreign : closure.foreign()) {
            byName.putIfAbsent(foreign.coordinate() + "@" + foreign.version(), foreign);
        }
        Foreign at = null;
        for (Foreign foreign : closure.foreign()) {
            if (foreign.ecosystem().equals(ecosystem) && foreign.coordinate().equals(coordinate)
                    && foreign.version().equals(version)) {
                at = foreign;
                break;
            }
        }
        Deque<Hop> hops = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        while (at != null && visited.add(at.coordinate() + "@" + at.version())) {
            hops.addFirst(new Hop(at.coordinate(), at.version()));
            if (at.direct()) {
                break;
            }
            Foreign parent = byName.get(at.viaCoordinate() + "@" + at.viaVersion());
            if (parent == null) {
                hops.addFirst(new Hop(at.viaCoordinate(), at.viaVersion()));
            }
            at = parent;
        }
        return List.copyOf(hops);
    }

    /** A version's closure: the components it reaches, the cuts, whether a bound stopped it, when it was read, the
     *  {@link ClosureSource} that produced it - its kind and its name - and the packages of other ecosystems its
     *  carried bill names. */
    public record Closure(Status status, List<Component> components, List<Cut> cuts, boolean truncated,
                          Instant resolved, ClosureSource.Kind kind, String source, List<Foreign> foreign) {

        public Closure {
            components = List.copyOf(components);
            cuts = List.copyOf(cuts);
            foreign = List.copyOf(foreign);
        }

        /** A closure naming nothing in another ecosystem. */
        public Closure(Status status, List<Component> components, List<Cut> cuts, boolean truncated,
                       Instant resolved, ClosureSource.Kind kind, String source) {
            this(status, components, cuts, truncated, resolved, kind, source, List.of());
        }
    }

    /** What a version's document says of its closure, as every surface answers it. */
    public enum State {
        /** Every subtree resolved. */
        RESOLVED,
        /** Some subtree was cut, or a bound stopped the walk. */
        PARTIAL,
        /** The version declares nothing this repository can read. */
        UNDECLARED,
        /** A release whose closure the pass has not resolved yet - or never will, while the setting is off. */
        PENDING,
        /** A cached copy: it has no closure of its own, and is screened by its own coordinate. */
        CACHED
    }

    /** A version's closure state and, where one is resolved, the closure; {@code closure} is {@code null} for a
     *  {@link State#PENDING} release and a {@link State#CACHED} copy. */
    public record Answer(State state, Closure closure) {
    }

    /** What {@code document} says of the version's closure, or empty where it records neither a publish nor a cached
     *  copy - a version this repository does not hold. */
    public static Optional<Answer> answer(MetadataDocument document) {
        if (document.section(CachedSection.TAG).isPresent()) {
            return Optional.of(new Answer(State.CACHED, null));
        }
        if (document.section(PublishedSection.TAG).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(closure(document.section(TAG)).map(closure -> new Answer(switch (closure.status()) {
            case RESOLVED -> State.RESOLVED;
            case PARTIAL -> State.PARTIAL;
            case UNDECLARED -> State.UNDECLARED;
        }, closure)).orElse(new Answer(State.PENDING, null)));
    }

    /** What a section records, or empty for a version not yet resolved. */
    public static Optional<Closure> closure(Optional<Section> section) {
        if (section.isEmpty()) {
            return Optional.empty();
        }
        Instant updated = section.get().updated();
        return section.get().payload().map(data -> closure(data, updated));
    }

    private static Closure closure(JsonNode data, Instant updated) {
        List<Component> components = new ArrayList<>();
        for (JsonNode entry : data.path("components")) {
            components.add(new Component(entry.path("coordinate").asString(""), entry.path("version").asString(""),
                    entry.path("cached").asBoolean(false), entry.path("depth").asInt(0),
                    entry.path("repository").asString(""), entry.path("via").path("coordinate").asString(""),
                    entry.path("via").path("version").asString("")));
        }
        List<Cut> cuts = new ArrayList<>();
        for (JsonNode entry : data.path("cuts")) {
            cuts.add(new Cut(entry.path("coordinate").asString(""), entry.path("requirement").asString(""),
                    entry.path("reason").asString("")));
        }
        Status status = switch (data.path("status").asString("")) {
            case "PARTIAL" -> Status.PARTIAL;
            case "UNDECLARED" -> Status.UNDECLARED;
            default -> Status.RESOLVED;
        };
        List<Foreign> foreign = new ArrayList<>();
        for (JsonNode entry : data.path("foreign")) {
            foreign.add(new Foreign(entry.path("ecosystem").asString(""), entry.path("coordinate").asString(""),
                    entry.path("version").asString(""), entry.path("depth").asInt(0),
                    entry.path("via").path("coordinate").asString(""), entry.path("via").path("version").asString("")));
        }
        ClosureSource.Kind kind;
        try {
            kind = ClosureSource.Kind.valueOf(data.path("kind").asString(""));
        } catch (IllegalArgumentException unrecorded) {
            kind = ClosureSource.Kind.DECLARATIONS;
        }
        return new Closure(status, components, cuts, data.path("truncated").asBoolean(false), updated, kind,
                data.path("source").asString(""), foreign);
    }

    /** Record {@code closure} as the version's, replacing what it had; re-derivable each compare-and-set attempt. */
    public static SectionMutation record(Closure closure) {
        return _ -> {
            ObjectNode data = JSON.createObjectNode();
            data.put("status", closure.status().name());
            ArrayNode components = data.putArray("components");
            for (Component component : closure.components()) {
                ObjectNode entry = components.addObject().put("coordinate", component.coordinate())
                        .put("version", component.version()).put("cached", component.cached())
                        .put("depth", component.depth());
                if (component.elsewhere()) {
                    entry.put("repository", component.repository());
                }
                if (!component.direct()) {
                    entry.putObject("via").put("coordinate", component.viaCoordinate())
                            .put("version", component.viaVersion());
                }
            }
            ArrayNode cuts = data.putArray("cuts");
            for (Cut cut : closure.cuts()) {
                cuts.addObject().put("coordinate", cut.coordinate()).put("requirement", cut.requirement())
                        .put("reason", cut.reason());
            }
            data.put("truncated", closure.truncated());
            data.put("kind", closure.kind().name());
            data.put("source", closure.source());
            if (!closure.foreign().isEmpty()) {
                ArrayNode foreign = data.putArray("foreign");
                for (Foreign entry : closure.foreign()) {
                    ObjectNode written = foreign.addObject().put("ecosystem", entry.ecosystem())
                            .put("coordinate", entry.coordinate()).put("version", entry.version())
                            .put("depth", entry.depth());
                    if (!entry.direct()) {
                        written.putObject("via").put("coordinate", entry.viaCoordinate())
                                .put("version", entry.viaVersion());
                    }
                }
            }
            return Section.derived(TAG, SCHEMA, closure.resolved(), Signal.NEUTRAL, data);
        };
    }
}
