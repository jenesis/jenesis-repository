package build.jenesis.repository.format.lifecycle.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.ServableNames;

/**
 * A repository's lifecycle marks as an operator reads and changes them, behind the API and the console's Lifecycle
 * page: what a listing discloses, which repositories refuse a mark, and the audit name of each change are stated here
 * once.
 *
 * <p>A listing discloses only what a pull would: a withheld version's mark is left out, judged by the servable-name
 * seam under {@code HIDE_WITHHELD}. A repository-wide listing is paged by cursor; one coordinate's marks are answered
 * whole.
 */
public final class LifecycleMarks {

    /** What a caller that names no limit gets. */
    public static final int DEFAULT_LIMIT = 200;

    /** The most marks one page answers with, however large a limit is asked for. */
    public static final int MAX_LIMIT = 1_000;

    private final ArtifactStore root;
    private final AuditTrail audit;

    /** Marks over the store every tenant's repositories are scoped from, recorded on {@code audit}. */
    public LifecycleMarks(ArtifactStore root, AuditTrail audit) {
        this.root = root;
        this.audit = audit;
    }

    private ArtifactStore store(String tenant, String repository) {
        return root.scope(tenant).scope(repository);
    }

    private Optional<RepositoryType> type(String tenant, String repository) throws IOException {
        return RepositoryDocument.cached(root, tenant, repository)
                .flatMap(document -> RepositoryType.installed(document.format()));
    }

    /** One marked version. */
    public record Mark(String coordinate, String version, Lifecycle.State state, String message) {
    }

    /** A page of marks and the cursor that continues it, {@code null} at the end. */
    public record Page(List<Mark> marks, String next) {
    }

    /** One page of {@code repository}'s marks, resumed after {@code after}; {@code limit} is clamped. */
    public Page page(String tenant, String repository, String after, Integer limit) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store(tenant, repository));
        Lifecycle.Page page = Lifecycle.page(store(tenant, repository),
                (coordinate, version) -> inventory.disclosableDisplay(coordinate + ":" + version,
                        ServableNames.Policy.HIDE_WITHHELD),
                after, limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT));
        List<Mark> marks = new ArrayList<>();
        for (Lifecycle.Entry entry : page.entries()) {
            marks.add(new Mark(entry.coordinate(), entry.version(), entry.flag().state(), entry.flag().message()));
        }
        return new Page(marks, page.next());
    }

    /** Every mark of one coordinate, whole. */
    public List<Mark> coordinate(String tenant, String repository, String coordinate) throws IOException {
        RepositoryRequests.rejectTraversal(coordinate);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store(tenant, repository));
        List<Mark> marks = new ArrayList<>();
        for (var marked : Lifecycle.versions(store(tenant, repository), coordinate).entrySet()) {
            if (inventory.disclosableDisplay(coordinate + ":" + marked.getKey(), ServableNames.Policy.HIDE_WITHHELD)) {
                marks.add(new Mark(coordinate, marked.getKey(), marked.getValue().state(),
                        marked.getValue().message()));
            }
        }
        return marks;
    }

    /** The coordinate a mark names for the version the inventory records as {@code coordinate}, served at {@code path}
     *  - the repository format's own key ({@link RepositoryFormat#lifecycleCoordinate}), so a page linking a version to
     *  its mark names the mark that format's clients see. */
    public String coordinateOf(String tenant, String repository, String coordinate, String path) throws IOException {
        Optional<RepositoryType> type = type(tenant, repository);
        if (type.isEmpty() || path == null || path.isBlank()) {
            return coordinate;
        }
        return type.get().claiming(path).map(format -> format.lifecycleCoordinate(coordinate, path))
                .orElse(coordinate);
    }

    /** The marks a repository of type {@code type} shows its clients, in the order a reader is offered them: each its
     *  formats surface ({@link RepositoryFormat#surfacesDeprecation()}, {@link RepositoryFormat#surfacesYank()}), none
     *  for a type nothing installed defines. Held per type, since a type's formats are fixed for the life of the
     *  process and a page asks on every render. */
    public static Set<Lifecycle.State> states(String type) {
        return type == null ? Set.of() : STATES.computeIfAbsent(type, name -> RepositoryType.installed(name)
                .map(LifecycleMarks::states).orElse(Set.of()));
    }

    private static final Map<String, Set<Lifecycle.State>> STATES = new ConcurrentHashMap<>();

    private static Set<Lifecycle.State> states(RepositoryType type) {
        Set<Lifecycle.State> shown = EnumSet.noneOf(Lifecycle.State.class);
        for (RepositoryFormat format : type.formats()) {
            if (format.surfacesDeprecation()) {
                shown.add(Lifecycle.State.DEPRECATED);
            }
            if (format.surfacesYank()) {
                shown.add(Lifecycle.State.YANKED);
            }
        }
        return Collections.unmodifiableSet(shown);
    }

    /** The marks {@code repository} shows its clients, by its type; none for a repository of no installed type. */
    public Set<Lifecycle.State> states(String tenant, String repository) throws IOException {
        return type(tenant, repository).map(LifecycleMarks::states).orElse(Set.of());
    }

    /** Why {@code repository} takes no mark at all, or empty when it takes one: a format that shows a mark to no client
     *  refuses every one, since stored it would read as done while every client went on offering the version. */
    public Optional<String> refusal(String tenant, String repository) throws IOException {
        Optional<RepositoryType> type = type(tenant, repository);
        if (type.isPresent() && states(type.get()).isEmpty()) {
            return Optional.of("A " + type.get().name() + " repository shows a lifecycle mark to no client: its "
                    + "format has no metadata a client reads a deprecation or a yank from, so the mark is refused "
                    + "rather than stored where nobody would see it.");
        }
        return Optional.empty();
    }

    /** Why {@code repository} takes no mark of {@code state}, or empty when it does: a deprecation on a format whose
     *  clients have no deprecation signal is refused as a mark on a format that shows none is. */
    public Optional<String> refusal(String tenant, String repository, Lifecycle.State state) throws IOException {
        Optional<RepositoryType> type = type(tenant, repository);
        if (type.isEmpty() || states(type.get()).contains(state)) {
            return Optional.empty();
        }
        Set<Lifecycle.State> shown = states(type.get());
        if (shown.isEmpty()) {
            return refusal(tenant, repository);
        }
        String asked = state.name().toLowerCase(Locale.ROOT);
        return Optional.of("A " + type.get().name() + " repository shows no " + asked + " mark to a client: its "
                + "format's clients read only " + String.join(" and ", shown.stream()
                        .map(shownState -> shownState.name().toLowerCase(Locale.ROOT)).toList())
                + " marks, so a " + asked + " mark is refused rather than stored where nobody would see it.");
    }

    /** Mark a version, audited as {@code actor}: the refusal when the repository takes no mark, empty when marked. A
     *  traversal-unsafe coordinate or version is an {@link IllegalArgumentException}. */
    public Optional<String> mark(String tenant, String repository, String coordinate, String version,
                                 Lifecycle.State state, String message, String actor) throws IOException {
        RepositoryRequests.rejectTraversal(coordinate);
        RepositoryRequests.rejectTraversal(version);
        Optional<String> refused = refusal(tenant, repository, state);
        if (refused.isPresent()) {
            return refused;
        }
        Lifecycle.mark(store(tenant, repository), coordinate, version,
                new Lifecycle.Flag(state, message == null ? "" : message));
        audit.record(tenant, actor, Lifecycle.action(state), repository + "/" + coordinate + "@" + version);
        return Optional.empty();
    }

    /** Clear a version's mark, audited as {@code actor} when there was one; whether there was. */
    public boolean clear(String tenant, String repository, String coordinate, String version, String actor)
            throws IOException {
        RepositoryRequests.rejectTraversal(coordinate);
        RepositoryRequests.rejectTraversal(version);
        boolean cleared = Lifecycle.clear(store(tenant, repository), coordinate, version);
        if (cleared) {
            audit.record(tenant, actor, AuditActions.LIFECYCLE_CLEAR, repository + "/" + coordinate + "@" + version);
        }
        return cleared;
    }
}
