package build.jenesis.repository.closure.spi;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.definitions.RoutingSettingsContributor;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The repositories a closure is resolved through, in the order a request walks them: the repository the release was
 * published to, then every repository its definition names as a fallback, and theirs, each once. A fallback to an
 * upstream URL adds nothing: what a build fetched through it is already a cached copy of the repository that names
 * it, and nothing is fetched.
 */
public record ClosureWalk(List<Member> members) {

    /** The most repositories one walk follows, so a definition naming its own group back ends. */
    static final int MAX_MEMBERS = 32;

    private static final Logger LOGGER = LoggerFactory.getLogger(ClosureWalk.class);

    public ClosureWalk {
        members = List.copyOf(members);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("A closure walks at least the repository it resolves");
        }
    }

    /** One repository of the walk: its name - empty for the repository the release was published to, when the walk
     *  was built without one - and its store. */
    public record Member(String repository, ArtifactStore store) {
    }

    /** The walk of one repository alone. */
    public static ClosureWalk of(ArtifactStore store) {
        return new ClosureWalk(List.of(new Member("", store)));
    }

    /** The walk from the repository {@code context} visits, following its fallbacks to the repositories they name. */
    public static ClosureWalk of(RepositoryContext context) {
        Map<String, Member> members = new LinkedHashMap<>();
        Deque<RepositoryContext> queue = new ArrayDeque<>();
        queue.add(context);
        while (!queue.isEmpty() && members.size() < MAX_MEMBERS) {
            RepositoryContext at = queue.poll();
            if (members.containsKey(at.repository())) {
                continue;
            }
            members.put(at.repository(), new Member(at.repository(), at.store()));
            for (String named : fallbacks(at)) {
                context.tenantView().repository(named).ifPresent(queue::add);
            }
        }
        return new ClosureWalk(List.copyOf(members.values()));
    }

    /** The repositories {@code at}'s definition names as fallbacks: its own {@code routing}, else the deployment's
     *  {@code repositories.<name>}. A definition that does not parse names none. */
    private static List<String> fallbacks(RepositoryContext at) {
        UnaryOperator<String> config = at.config();
        String own = config.apply(RoutingSettingsContributor.KEY);
        String specification = own != null && !own.isBlank() ? own
                : config.apply(SettingsScopes.repositoryKey(at.repository()));
        if (specification == null || specification.isBlank()) {
            return List.of();
        }
        try {
            return RepositoryDefinition.parse(specification).fallbacks().stream()
                    .map(RepositoryDefinition.Fallback::source)
                    .filter(source -> source instanceof RepositoryDefinition.Source.Repository)
                    .map(source -> ((RepositoryDefinition.Source.Repository) source).name())
                    .toList();
        } catch (RuntimeException malformed) {
            LOGGER.warn("Resolving closures in {} through it alone: its definition '{}' did not parse",
                    at.repository(), specification, malformed);
            return List.of();
        }
    }
}
