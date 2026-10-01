package build.jenesis.repository.maintenance;

import module java.base;

import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * The persisted per-module storage manifest and the explicit purge over it. {@link #register()} writes every installed
 * {@link StorageNamespace} declaration under {@link #ROOT} at boot, so an entry outlives its module: a module dropped
 * from an image leaves its entry behind, which lets the orphan diagnostic name its data and an operator purge it. An
 * entry is written or updated here, never removed.
 *
 * <p>Nothing here runs on a schedule or on module absence. {@link #orphans} only counts, data is deleted only by
 * {@link #purge} of an explicitly named module, and {@link #plan} is its dry run, walking exactly the keys the purge
 * would delete.
 *
 * <p>The purge never reaches {@link #UNREACHABLE}: no {@link StorageNamespace.Declared} may name those roots, so no
 * manifest entry describes them. A tenant's removal reclaims {@code audit/<tenant>} explicitly and leases expire on
 * their own ttl.
 *
 * <p>The walk is the bounded tree descent ({@link PagedTreeWalk}) over {@link ArtifactStore#page} alone: each
 * repository prefix under every {@code <tenant>/<repository>/}, each tenant prefix under every {@code <tenant>/}, each
 * shared prefix once at the store root. The manifest document is a {@code key=value} text object, compare-and-set
 * through {@link Retries}.
 */
public final class StorageNamespaces {

    /** The deployment-global prefix under which the per-module manifest documents are kept. */
    public static final String ROOT = Scopes.space(Scopes.CONFIG) + "/namespaces";

    /**
     * The reserved root key spaces this purge can never reach, sorted: {@link Scopes#SPACES} minus
     * {@link StorageNamespace#SHARED_ROOTS}. It is not a skip list, since no manifest entry can name one; the purge and
     * orphan responses carry it so that "the purge found nothing here" is not read as "there is nothing here".
     */
    public static final Set<String> UNREACHABLE = Collections.unmodifiableSortedSet(Scopes.SPACES.stream()
            .filter(root -> !StorageNamespace.SHARED_ROOTS.contains(root))
            .collect(Collectors.toCollection(TreeSet::new)));

    /** The sentence the purge and orphan surfaces carry beside {@link #UNREACHABLE}. */
    public static final String UNREACHABLE_NOTE = "Reserved root key spaces no module may declare, so this purge "
            + "never reaches them - deliberately: making them declarable would widen what any plug-in may claim. "
            + "The audit trail is reclaimed only by removing a tenant, leases expire on their own ttl, and the usage "
            + "counter is core state.";

    private final ArtifactStore store;

    /** Over the deployment's unscoped root store, whose top level holds the tenant scopes. */
    public StorageNamespaces(ArtifactStore store) {
        this.store = store;
    }

    /** Persists every installed declaration. Idempotent and additive: an unchanged entry is not rewritten, a changed
     *  one is compare-and-set, none is removed. */
    public void register() throws IOException {
        for (StorageNamespace.Declared declared : StorageNamespace.declared()) {
            byte[] content = serialize(declared);
            Retries.update(store, ROOT + "/" + declared.module(), existing ->
                    existing.isPresent() && Arrays.equals(existing.get().content(), content) ? null : content);
        }
    }

    /** Every persisted entry overlaid with the installed declarations, which win, ordered by module name. A stored
     *  document that does not parse to a valid entry is skipped, never acted on. */
    public List<StorageNamespace.Declared> manifest() throws IOException {
        Map<String, StorageNamespace.Declared> manifest = new TreeMap<>();
        for (String module : store.list(ROOT)) {
            Optional<ArtifactStore.Versioned> document = store.readVersioned(ROOT + "/" + module);
            if (document.isPresent()) {
                StorageNamespace.Declared declared = parse(module, document.get().content());
                if (declared != null) {
                    manifest.put(module, declared);
                }
            }
        }
        for (StorageNamespace.Declared declared : StorageNamespace.declared()) {
            manifest.put(declared.module(), declared);
        }
        return List.copyOf(manifest.values());
    }

    /**
     * The orphaned-data diagnostic: for every manifest entry whose module is not installed, the dry-run count of what
     * its key-spaces still hold, listing only those that hold data. Deletes nothing.
     */
    public List<Report> orphans(Collection<String> tenants) throws IOException {
        Set<String> installed = new HashSet<>();
        for (StorageNamespace.Declared declared : StorageNamespace.declared()) {
            installed.add(declared.module());
        }
        List<Report> orphans = new ArrayList<>();
        for (StorageNamespace.Declared declared : manifest()) {
            if (!installed.contains(declared.module())) {
                Report report = sweep(declared, tenants, false);
                if (report.objects() > 0) {
                    orphans.add(report);
                }
            }
        }
        return List.copyOf(orphans);
    }

    /** The dry run of {@link #purge}: the per-prefix object counts and bytes of exactly the keys it would delete.
     *  Empty when no manifest entry names {@code module}. */
    public Optional<Report> plan(String module, Collection<String> tenants) throws IOException {
        return resolveAndSweep(module, tenants, false);
    }

    /** Deletes exactly what {@link #plan} lists for the named module and reports it; idempotent, so a partial purge
     *  re-runs to convergence. Empty when no manifest entry names {@code module}. The maintenance module is refused:
     *  its space is the manifest itself, and purging it would erase the record of every other module's data. */
    public Optional<Report> purge(String module, Collection<String> tenants) throws IOException {
        String manifestOwner = ManifestStorageNamespace.class.getModule().getName();
        if (module.equals(manifestOwner != null ? manifestOwner : "build.jenesis.repository.maintenance")) {
            throw new IllegalArgumentException("The storage-namespace manifest itself cannot be purged: '" + module
                    + "' declares the manifest directory every other module's entry lives in.");
        }
        return resolveAndSweep(module, tenants, true);
    }

    private Optional<Report> resolveAndSweep(String module, Collection<String> tenants, boolean delete)
            throws IOException {
        for (StorageNamespace.Declared declared : manifest()) {
            if (declared.module().equals(module)) {
                return Optional.of(sweep(declared, tenants, delete));
            }
        }
        return Optional.empty();
    }

    /** One walk serves the plan and the purge, so the dry run is exactly the delete's blast radius. A prefix that an
     *  installed module also declares at the same scope, equal or nested, is not walked, since it holds that module's
     *  data too; the report lists it as kept with its owners. */
    private Report sweep(StorageNamespace.Declared declared, Collection<String> tenants, boolean delete)
            throws IOException {
        List<StorageNamespace.Declared> others = StorageNamespace.declared().stream()
                .filter(other -> !other.module().equals(declared.module())).toList();
        List<Report.Kept> kept = new ArrayList<>();
        Set<String> sharedOwned = owned(declared.sharedPrefixes(), others, StorageNamespace.Declared::sharedPrefixes,
                "shared", kept);
        Set<String> tenantOwned = owned(declared.tenantPrefixes(), others, StorageNamespace.Declared::tenantPrefixes,
                "tenant", kept);
        Set<String> repositoryOwned = owned(declared.repositoryPrefixes(), others,
                StorageNamespace.Declared::repositoryPrefixes, "repository", kept);
        List<Report.Space> spaces = new ArrayList<>();
        for (String prefix : declared.sharedPrefixes()) {
            if (!sharedOwned.contains(prefix)) {
                space(spaces, prefix, delete);
            }
        }
        for (String tenant : tenants) {
            ArtifactStore.segment(tenant);
            for (String prefix : declared.tenantPrefixes()) {
                if (!tenantOwned.contains(prefix)) {
                    space(spaces, tenant + "/" + prefix, delete);
                }
            }
            if (declared.repositoryPrefixes().isEmpty()) {
                continue;
            }
            for (String repository : store.list(tenant)) {
                // A tenant's children include its reserved spaces, some without a leading dot; only Scopes tells
                // them from repositories.
                if (!Scopes.valid(repository)) {
                    continue;
                }
                for (String prefix : declared.repositoryPrefixes()) {
                    if (!repositoryOwned.contains(prefix)) {
                        space(spaces, tenant + "/" + repository + "/" + prefix, delete);
                    }
                }
            }
        }
        long objects = 0;
        long bytes = 0;
        for (Report.Space space : spaces) {
            objects += space.objects();
            bytes += space.bytes();
        }
        return new Report(declared.module(), !delete, List.copyOf(spaces), objects, bytes, List.copyOf(kept));
    }

    /** The prefixes of {@code prefixes} another module also owns at this scope, each added to {@code kept} with its
     *  owners. */
    private static Set<String> owned(Set<String> prefixes, List<StorageNamespace.Declared> others,
                                     Function<StorageNamespace.Declared, Set<String>> scope, String label,
                                     List<Report.Kept> kept) {
        Set<String> owned = new TreeSet<>();
        for (String prefix : prefixes) {
            List<String> owners = new ArrayList<>();
            for (StorageNamespace.Declared other : others) {
                if (scope.apply(other).stream().anyMatch(theirs -> overlap(prefix, theirs))) {
                    owners.add(other.module());
                }
            }
            if (!owners.isEmpty()) {
                owned.add(prefix);
                kept.add(new Report.Kept(label, prefix, List.copyOf(owners)));
            }
        }
        return owned;
    }

    /** Whether two prefixes name one space or one inside the other, segment by segment - {@code audit/quarantine}
     *  and {@code audit/quarantine-index} are neighbours, not one inside the other. */
    public static boolean overlap(String one, String other) {
        return one.equals(other) || one.startsWith(other + "/") || other.startsWith(one + "/");
    }

    private void space(List<Report.Space> spaces, String prefix, boolean delete) throws IOException {
        long[] totals = new long[2];
        walk(prefix, delete, totals);
        if (totals[0] > 0) {
            spaces.add(new Report.Space(prefix, totals[0], totals[1]));
        }
    }

    /** The bounds one declared prefix is descended under. A plan or purge must never answer short, so the entry cap is
     *  only the continuation {@link #walk} follows to exhaustion, and the step budget raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} instead. */
    private static final PagedTreeWalk SPACE = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Counts, and when purging deletes, every object under one prefix. Deleting behind the cursor is safe: the
     *  descent pages forward from the last key it delivered, so a removed key never displaces a later one. */
    private void walk(String key, boolean delete, long[] totals) throws IOException {
        String cursor = null;
        while (true) {
            Traversal.Result result = SPACE.walk(store, key, cursor, current -> {
                long size = store.size(current);
                if (size >= 0) {
                    totals[0]++;
                    totals[1] += size;
                    if (delete) {
                        store.delete(current);
                    }
                }
            });
            if (result.exhausted()) {
                return;
            }
            cursor = result.cursor().orElseThrow();
        }
    }

    /** What one plan, purge or orphan scan found: per fully-scoped prefix that holds data, its counts. */
    public record Report(String module, boolean dryRun, List<Space> spaces, long objects, long bytes,
                         List<Kept> kept) {

        public Report {
            spaces = List.copyOf(spaces);
            kept = List.copyOf(kept);
        }

        /** One fully-scoped prefix and what it holds. */
        public record Space(String prefix, long objects, long bytes) {
        }

        /** A declared prefix the purge leaves, at its {@code scope} ({@code repository}, {@code tenant} or
         *  {@code shared}), because the installed {@code owners} declare it too. */
        public record Kept(String scope, String prefix, List<String> owners) {
        }
    }

    /** The manifest document: {@code repository=}, {@code tenant=} and {@code shared=} lines of comma-joined sorted
     *  prefixes (a valid segment holds no comma), deterministic so an unchanged registration compares equal. */
    private static byte[] serialize(StorageNamespace.Declared declared) {
        String content = "repository=" + String.join(",", declared.repositoryPrefixes()) + "\n"
                + "tenant=" + String.join(",", declared.tenantPrefixes()) + "\n"
                + "shared=" + String.join(",", declared.sharedPrefixes()) + "\n";
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /** The stored entry for {@code module}, or {@code null} when the document does not parse or carries an unsafe or
     *  mis-scoped prefix. A missing line parses to an empty set. */
    private static StorageNamespace.Declared parse(String module, byte[] content) {
        Set<String> repositories = new TreeSet<>();
        Set<String> tenants = new TreeSet<>();
        Set<String> shared = new TreeSet<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            int separator = line.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            Set<String> target = switch (key) {
                case "repository" -> repositories;
                case "tenant" -> tenants;
                case "shared" -> shared;
                default -> null;
            };
            if (target == null) {
                continue;
            }
            for (String prefix : line.substring(separator + 1).split(",")) {
                if (!prefix.isBlank()) {
                    target.add(prefix.trim());
                }
            }
        }
        try {
            return new StorageNamespace.Declared(module, repositories, tenants, shared);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
