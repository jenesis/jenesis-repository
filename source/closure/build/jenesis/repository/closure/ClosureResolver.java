package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.closure.spi.ClosureWalk;
import build.jenesis.repository.closure.spi.RequirementGrammar;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.HeldVersions;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;

/**
 * Resolves a version's transitive closure from what the repositories of a {@link ClosureWalk} hold: breadth-first from
 * the version's declared dependencies, each requirement taking the newest held version its ecosystem's
 * {@link RequirementGrammar} admits and the repository holding it serves - the earlier repository of the walk where
 * two hold the same version, as a request walks them - and that version's own declarations read next. The nearest
 * declaration of a coordinate wins, as the build tools that mediate do, and a coordinate is visited once.
 *
 * <p>A held version's declarations are the ones its document records where its publish recorded them, and otherwise
 * the ones an installed inspector reads off a file it claims, a descriptor first - a cached copy records none, so its
 * manifest is read. A file past {@link #MANIFEST_LIMIT} is not read: a manifest is small, and an archive that large is
 * not one. A manifest an inspector fails on, or reads only in part, is a cut on the version that carries it rather than
 * a leaf declaring nothing.
 *
 * <p>Bounded: at most {@link ClosureSource#MAX_COMPONENTS} components, and {@link #MAX_VERSIONS} versions of one coordinate
 * examined; a closure stopped by either says so ({@link ClosureSection.Closure#truncated}). Nothing is fetched.
 */
public final class ClosureResolver {

    /** The most components one closure records before it stops and says so. */

    /** The most versions of one coordinate examined for the newest a requirement admits. */
    static final int MAX_VERSIONS = 2_000;

    /** The largest file read for its declarations. */
    static final int MANIFEST_LIMIT = 4 * 1024 * 1024;

    private static final int PAGE = 200;

    private final List<Reader> readers;
    private final List<QualityInspector> inspectors;

    /** One repository of the walk, as the resolver reads it. */
    private record Reader(String repository, ArtifactStore store, StoreRepositoryInventory inventory,
                          Publication publication) {

        Reader(ClosureWalk.Member member, boolean own) {
            this(own ? "" : member.repository(), member.store(), new StoreRepositoryInventory(member.store()),
                    new Publication(member.store()));
        }
    }

    public ClosureResolver(ArtifactStore store, List<QualityInspector> inspectors) {
        this(ClosureWalk.of(store), inspectors);
    }

    public ClosureResolver(ClosureWalk walk, List<QualityInspector> inspectors) {
        List<Reader> readers = new ArrayList<>();
        for (ClosureWalk.Member member : walk.members()) {
            readers.add(new Reader(member, readers.isEmpty()));
        }
        this.readers = List.copyOf(readers);
        this.inspectors = List.copyOf(inspectors);
    }

    /** The closure of {@code ecosystem}'s {@code coordinate} at {@code version}, as of {@code now}. */
    public ClosureSection.Closure resolve(String ecosystem, String coordinate, String version, Instant now)
            throws IOException {
        RequirementGrammar grammar = RequirementGrammar.of(ecosystem);
        List<ClosureSection.Component> components = new ArrayList<>();
        List<ClosureSection.Cut> cuts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(coordinate);
        // What named each dependency: the component whose declarations queued it, or none for the version's own.
        record Pending(ComplianceGate.Dependency dependency, int depth, String viaCoordinate, String viaVersion) {
        }
        Optional<Declared> roots = declarations(readers.getFirst(), ecosystem, coordinate, version);
        if (roots.isEmpty()) {
            return new ClosureSection.Closure(ClosureSection.Status.UNDECLARED, List.of(), List.of(), false, now,
                    ClosureSource.Kind.DECLARATIONS,
                DeclaredClosure.NAME);
        }
        roots.get().unread().ifPresent(reason -> cuts.add(new ClosureSection.Cut(coordinate, version, reason)));
        Deque<Pending> queue = new ArrayDeque<>();
        for (ComplianceGate.Dependency dependency : roots.get().dependencies()) {
            queue.add(new Pending(dependency, 1, "", ""));
        }
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Pending next = queue.poll();
            ComplianceGate.Dependency dependency = next.dependency();
            if (!seen.add(dependency.coordinate())) {
                continue;
            }
            if (components.size() >= ClosureSource.MAX_COMPONENTS) {
                truncated = true;
                break;
            }
            Choice choice = choose(ecosystem, grammar, dependency);
            if (choice.holding() == null) {
                cuts.add(new ClosureSection.Cut(dependency.coordinate(), dependency.requirement(), choice.reason()));
                truncated |= choice.truncated();
                continue;
            }
            StoreRepositoryInventory.Holding held = choice.holding();
            components.add(new ClosureSection.Component(held.coordinate(), held.version(), held.cached(),
                    next.depth(), choice.reader().repository(), next.viaCoordinate(), next.viaVersion()));
            Declared declared = declarations(choice.reader(), ecosystem, held.coordinate(), held.version())
                    .orElse(Declared.NOTHING);
            declared.unread().ifPresent(reason ->
                    cuts.add(new ClosureSection.Cut(held.coordinate(), held.version(), reason)));
            for (ComplianceGate.Dependency transitive : declared.dependencies()) {
                queue.add(new Pending(transitive, next.depth() + 1, held.coordinate(), held.version()));
            }
        }
        return new ClosureSection.Closure(cuts.isEmpty() && !truncated ? ClosureSection.Status.RESOLVED
                : ClosureSection.Status.PARTIAL, components, cuts, truncated, now,
                ClosureSource.Kind.DECLARATIONS,
                DeclaredClosure.NAME);
    }

    /** The held version a dependency resolves to and the repository holding it, or why none does. */
    private record Choice(StoreRepositoryInventory.Holding holding, Reader reader, String reason, boolean truncated) {

        static Choice cut(String reason, boolean truncated) {
            return new Choice(null, null, reason, truncated);
        }
    }

    /** A held version a requirement admits, and the repository of the walk holding it. */
    private record Candidate(StoreRepositoryInventory.Holding holding, Reader reader) {
    }

    /** The cut of a dependency whose every admitted version is held for review. */
    private static final String ALL_HELD = "every held version it admits is held for review";

    /**
     * Whether a version {@code dependency} admits is held for review by a repository of the walk although no holding
     * records it: a proxied copy the screen held at its fill becomes a holding only once released, and until then it
     * is found through its hold's subject, its review pointer still in place.
     */
    private boolean heldForReview(String ecosystem, RequirementGrammar grammar, ComplianceGate.Dependency dependency)
            throws IOException {
        for (Reader reader : readers) {
            for (String version : HeldVersions.of(reader.store(), ecosystem, dependency.coordinate())) {
                if (grammar.admits(dependency.requirement(), version) == RequirementGrammar.Admission.ADMITS) {
                    return true;
                }
            }
        }
        return false;
    }

    private Choice choose(String ecosystem, RequirementGrammar grammar, ComplianceGate.Dependency dependency)
            throws IOException {
        List<Candidate> admitted = new ArrayList<>();
        boolean anyHeld = false;
        boolean unknown = false;
        boolean unexamined = false;
        int examined = 0;
        for (Reader reader : readers) {
            String after = null;
            do {
                StoreRepositoryInventory.HoldingPage page = reader.inventory().holdings(ecosystem,
                        dependency.coordinate(), after, PAGE);
                for (StoreRepositoryInventory.Holding holding : page.holdings()) {
                    anyHeld = true;
                    switch (grammar.admits(dependency.requirement(), holding.version())) {
                        case ADMITS -> admitted.add(new Candidate(holding, reader));
                        case UNKNOWN -> unknown = true;
                        case EXCLUDES -> {
                        }
                    }
                }
                examined += page.holdings().size();
                after = page.next();
            } while (after != null && examined < MAX_VERSIONS);
            unexamined |= after != null;
            if (examined >= MAX_VERSIONS) {
                break;
            }
        }
        if (!anyHeld) {
            return heldForReview(ecosystem, grammar, dependency) ? Choice.cut(ALL_HELD, false)
                    : Choice.cut(ClosureSection.Cut.notHeld(readers.size() == 1), false);
        }
        // Newest first; a stable sort keeps the walk's order among repositories holding the same version.
        admitted.sort((left, right) -> grammar.compare(right.holding().version(), left.holding().version()));
        for (Candidate candidate : admitted) {
            if (candidate.reader().inventory().disclosable(ecosystem, candidate.holding().coordinate(),
                    candidate.holding().version(), ServableNames.Policy.HIDE_WITHHELD)) {
                return new Choice(candidate.holding(), candidate.reader(), null, false);
            }
        }
        if (!admitted.isEmpty() || heldForReview(ecosystem, grammar, dependency)) {
            return Choice.cut(ALL_HELD, false);
        }
        if (unknown) {
            return Choice.cut("the requirement could not be evaluated", false);
        }
        return Choice.cut(unexamined
                ? "no examined version satisfies the requirement, and more are held than are examined"
                : "no held version satisfies the requirement", unexamined);
    }

    /** What a held version declares, and why what it declares was not all read - empty where it was: a manifest an
     *  inspector failed on, or read only in part. The walk records that as a cut on the version, so a subtree
     *  nobody read is never taken for a leaf that depends on nothing. */
    private record Declared(List<ComplianceGate.Dependency> dependencies, Optional<String> unread) {

        static final Declared NOTHING = new Declared(List.of(), Optional.empty());
    }

    /** What a held version declares: its document's record where the publish made one, its manifest otherwise - the
     *  first file whose inspectors read a dependency from it, else the first one they failed on - and empty where
     *  neither says anything: no record, and no file an inspector reads a dependency from. */
    private Optional<Declared> declarations(Reader reader, String ecosystem, String coordinate, String version)
            throws IOException {
        Optional<List<DependencySection.Declared>> recorded = reader.inventory().dependencies(ecosystem, coordinate,
                version);
        if (recorded.isPresent()) {
            return Optional.of(new Declared(recorded.get().stream()
                    .map(declared -> new ComplianceGate.Dependency(declared.coordinate(), declared.requirement()))
                    .toList(), Optional.empty()));
        }
        Optional<Declared> failed = Optional.empty();
        for (String path : manifestsFirst(reader.inventory().paths(ecosystem, coordinate, version))) {
            Declared read = manifest(reader, path);
            if (!read.dependencies().isEmpty()) {
                return Optional.of(read);
            }
            if (failed.isEmpty() && read.unread().isPresent()) {
                failed = Optional.of(read);
            }
        }
        return failed;
    }

    /** What the inspectors claiming {@code path} read off its stored bytes, and why not all of it where an inspector
     *  failed on it or read its dependencies only in part; nothing where none claims it or it is too large to be a
     *  manifest. */
    private Declared manifest(Reader reader, String path) throws IOException {
        List<QualityInspector> claiming = inspectors.stream()
                .filter(inspector -> inspector.claims(path, QualityInspector.Lookup.NONE)).toList();
        if (claiming.isEmpty()) {
            return Declared.NOTHING;
        }
        Optional<byte[]> read = served(reader, path);
        if (read.isEmpty()) {
            return Declared.NOTHING;
        }
        byte[] body = read.get();
        Set<ComplianceGate.Dependency> dependencies = new LinkedHashSet<>();
        Optional<String> unread = Optional.empty();
        for (QualityInspector inspector : claiming) {
            try {
                for (ComplianceGate.Subject subject : inspector.inspect(path, body, QualityInspector.Lookup.NONE)) {
                    if (subject.dependencies() == null) {
                        unread = unread.or(() -> Optional.of("its manifest could not be read in full"));
                    } else {
                        dependencies.addAll(subject.dependencies());
                    }
                }
            } catch (IOException | RuntimeException failure) {
                unread = Optional.of("its manifest could not be read: " + Objects.requireNonNullElse(
                        failure.getMessage(), failure.getClass().getSimpleName()));
            }
        }
        return new Declared(List.copyOf(dependencies), unread);
    }

    /**
     * The bytes {@code path} serves, empty where nothing serves it, a hold withholds it or it is past
     * {@link #MANIFEST_LIMIT}: the blob its {@code publish/} pointer names, or, for a format that keeps its files in
     * the shared {@code Blobs} namespace - every format but the {@code publish/} layouts - the blob its layout serves
     * the path from. Without the second, a cached copy of such a format would declare nothing and its closure would
     * stop at it.
     */
    private static Optional<byte[]> served(Reader reader, String path) throws IOException {
        Optional<Publication.Located> published = reader.publication().locate(path);
        if (published.isPresent()) {
            if (published.get().size() > MANIFEST_LIMIT) {
                return Optional.empty();
            }
            try (InputStream in = reader.store().open(published.get().key())) {
                return Optional.of(in.readNBytes(MANIFEST_LIMIT));
            }
        }
        Blobs blobs = new Blobs(reader.store());
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            if (!(format instanceof BlobLayout layout) || !format.handles(path)) {
                continue;
            }
            Optional<String> key = layout.servingKey(path, reader.store());
            Optional<Blobs.Located> located = key.isEmpty() ? Optional.empty() : blobs.locate(key.get());
            if (located.isPresent()) {
                if (located.get().size() > MANIFEST_LIMIT) {
                    return Optional.empty();
                }
                try (InputStream in = blobs.open(located.get().hash())) {
                    byte[] body = in.readNBytes(MANIFEST_LIMIT + 1);
                    return body.length > MANIFEST_LIMIT ? Optional.empty() : Optional.of(body);
                }
            }
        }
        return Optional.empty();
    }

    /** {@code paths} ordered so a manifest is read first: a descriptor by its extension, then any other file, a jar
     *  last. */
    private static List<String> manifestsFirst(List<String> paths) {
        return paths.stream().sorted(Comparator.comparingInt(ClosureResolver::rank).thenComparing(path -> path))
                .toList();
    }

    private static int rank(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".pom") || name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".toml")
                || name.endsWith(".nuspec") || name.endsWith(".yaml") || name.endsWith(".yml")) {
            return 0;
        }
        return name.endsWith(".jar") ? 2 : 1;
    }
}
