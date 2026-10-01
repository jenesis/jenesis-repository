package build.jenesis.repository.maintenance;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A module's declaration of the store key-spaces it owns, discovered with {@link ServiceLoader}. A module may own
 * several prefixes at repository or tenant scope, so each module that persists anything beyond the artifacts
 * {@code provides} this interface naming them. The manifest feeds the orphaned-data diagnostic and the explicit
 * operator purge ({@link StorageNamespaces}), and each declaration is attributed to its JPMS module, so its keys
 * align with the modules console and the per-module settings documents.
 *
 * <p><strong>Absence never deletes.</strong> A module missing from the module path makes its manifest entry an orphan
 * <em>candidate</em> only: an incomplete image or a rolling deploy looks the same as a removal, so only an operator's
 * explicit, named purge removes data.
 *
 * <p>Every key-space is per-tenant ({@link #repositoryPrefixes} under {@code <tenant>/<repository>/},
 * {@link #tenantPrefixes} under {@code <tenant>/}) except a sub-space of the {@link #SHARED_ROOTS}; a
 * {@link #sharedPrefixes shared} declaration anywhere else is refused at construction.
 *
 * <p>Per-coordinate facts kept as sections of the consolidated metadata document need no root: the document's space
 * ({@code meta}) is the metadata store's own declaration.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An implementation is a constant: the accessors are pure, read no store and hold no
 *       state, so they may be called concurrently on a shared instance and answer while the store is unreachable.</li>
 *   <li><b>Idempotency / replay.</b> Every call returns the same prefixes for the life of the JVM, since
 *       {@link StorageNamespaces#register()} persists them at every boot and a re-run must be a byte-identical
 *       no-op.</li>
 *   <li><b>Absence sentinel.</b> Each accessor returns an empty {@link Set}, never {@code null}. A module that persists
 *       nothing may still provide three empty sets; the declaration keeps it <em>installed</em> for the orphan
 *       diagnostic, so a key-space it has stopped using surfaces as unowned.</li>
 *   <li><b>Tenant scoping.</b> {@link #repositoryPrefixes} resolve under every {@code <tenant>/<repository>/} and
 *       {@link #tenantPrefixes} under every {@code <tenant>/}; the purge composes the scope itself, so a
 *       cross-tenant reclamation is unrepresentable. Only {@link #sharedPrefixes} is deployment-global, and only as a
 *       proper sub-space of a {@link #SHARED_ROOTS} root. A tenant prefix must be a dot-space ({@code .scans},
 *       {@code .vex}), because the purge walks a tenant's non-dot children as repositories.</li>
 *   <li><b>Prefix shape.</b> A prefix is a relative key of one or more {@link ArtifactStore#segment}-valid segments -
 *       no leading, trailing or repeated {@code /}, no {@code .} or {@code ..}, no backslash - naming one object or the
 *       root of a subtree. {@link Declared} enforces this for a declaration made in code and for one read back from
 *       the store.</li>
 *   <li><b>Ownership is for reclamation, not exclusive writes.</b> A prefix says "purging this module reclaims this
 *       space"; other modules may write into it (the {@code meta} document, the gate's {@code overrides} markers).
 *       Two <em>different</em> modules may not declare equal or nested prefixes, because purging either would delete
 *       the other's data unannounced in the dry run. One module's prefixes under one root merge.</li>
 *   <li><b>Absence is never a reason to delete, and a shared space has one key owner.</b> A declared space's rows
 *       outlive the module that wrote them; the only ways out are the operator purge and a sweep that positively
 *       proves the row's subject gone. A sweep judging through a discovered provider separates "gone" from "cannot
 *       tell" and no-ops on the second, or an uninstalled format would take every one of its {@code pinned/} and
 *       {@code overrides/} rows with it. A space written per kind and reaped kind-neutrally has exactly one owner of
 *       its key spelling ({@code HoldRecords} for {@code holds/}, {@code OverrideRecords} for {@code overrides/}),
 *       since a reaper composing its own keys reaps keys nobody wrote.
 *
 *       <p>For the artifact spaces the answer is refusal: a blobs-namespace format's serving pointers live under roots
 *       only it declares, so with it absent every blob it serves reads as unreferenced. The root set a reclaiming
 *       pass is handed ({@code StoreRepositoryInventory.pointerRoots(store)}) therefore says whether every recorded
 *       ecosystem is placeable by an installed format, and the collector refuses the pass, reported with its cause,
 *       when it is not.</li>
 *   <li><b>Ordering / concurrency.</b> {@link #declared()} is ordered by module name, merges several declarations of
 *       one module, sorts every prefix set, and does not depend on discovery order.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link #declared()} instantiates afresh per call and keeps nothing, so an
 *       implementation has a no-argument constructor, owns no thread, client or store handle, and needs no
 *       closing.</li>
 *   <li><b>Bounded work.</b> An accessor performs no IO and returns a constant-sized set; the traversal lives in
 *       {@link StorageNamespaces}.</li>
 *   <li><b>What the manifest does not cover.</b> The core's own key-spaces ({@code publish/}, {@code blobs/},
 *       {@code withheld/}, {@code walks/}, {@code gc/}, {@code imports/}, {@code quota/}) carry no entry, since the
 *       core cannot be removed, and a reserved root outside {@link #SHARED_ROOTS} cannot be declared. An absent entry
 *       means "not plug-in data", never "no data".</li>
 *   <li><b>The unreachable spaces are deliberate.</b> {@link StorageNamespaces#UNREACHABLE} ({@link Scopes#SPACES}
 *       minus {@link #SHARED_ROOTS}) holds persisted data the purge never reaches; widening {@link #SHARED_ROOTS}
 *       would widen what every plug-in may claim. The purge and orphan surfaces tell the operator so.</li>
 * </ol>
 */
public interface StorageNamespace {

    /** The only root spaces a {@link #sharedPrefixes shared} declaration may sit under: the {@code auth/}
     *  credential and membership map, since a user spans tenants, and the superadmin {@code config/} space. */
    Set<String> SHARED_ROOTS = Set.of(Scopes.AUTH, Scopes.CONFIG);

    /** The key prefixes this module owns under each {@code <tenant>/<repository>/} scope (e.g.
     *  {@code index/search}); empty when the module keeps no per-repository state. */
    default Set<String> repositoryPrefixes() {
        return Set.of();
    }

    /** The key prefixes this module owns directly under each {@code <tenant>/} scope - the reserved dot-spaces
     *  beside the repositories (e.g. {@code .scans}); empty when the module keeps no tenant-wide state. */
    default Set<String> tenantPrefixes() {
        return Set.of();
    }

    /** The deployment-global key prefixes this module owns, each a sub-space of a {@link #SHARED_ROOTS shared root};
     *  empty for a module whose data is per-tenant. */
    default Set<String> sharedPrefixes() {
        return Set.of();
    }

    /**
     * Further manifest entries this provider declares <em>on behalf of other modules</em>, beside its own; empty for
     * an ordinary declaration. It serves a registrar over a plug-in family whose members cannot declare for
     * themselves, such as the inventory's format registrar, which emits one entry per installed format attributed to
     * that format's module, so a dropped format leaves an orphan entry like any other module.
     */
    default List<Declared> declarations() {
        return List.of();
    }

    /**
     * One module's manifest entry: the owning JPMS module name and its prefixes. Every segment of every prefix passes
     * {@link ArtifactStore#segment}, so no entry names a key-space outside its scope, and a shared prefix must sit
     * under a {@link #SHARED_ROOTS shared root}.
     */
    record Declared(String module, Set<String> repositoryPrefixes, Set<String> tenantPrefixes,
                    Set<String> sharedPrefixes) {

        public Declared {
            ArtifactStore.segment(module);
            repositoryPrefixes = validated(repositoryPrefixes);
            tenantPrefixes = validated(tenantPrefixes);
            sharedPrefixes = validated(sharedPrefixes);
            for (String prefix : sharedPrefixes) {
                // .system/<root>/<sub-space>: a bare root would let one entry purge every tenant's credentials.
                String[] parts = prefix.split("/", 3);
                if (parts.length < 3 || !Scopes.SYSTEM.equals(parts[0]) || !SHARED_ROOTS.contains(parts[1])
                        || parts[2].isEmpty()) {
                    throw new IllegalArgumentException("Shared key-space '" + prefix + "' of module '" + module
                            + "' is mis-scoped: a module's data is per-tenant; only a proper sub-space of the "
                            + "deployment-global " + SHARED_ROOTS + " roots, under " + Scopes.SYSTEM
                            + ", may carry a shared declaration.");
                }
            }
        }

        private static Set<String> validated(Set<String> prefixes) {
            Set<String> result = new TreeSet<>();
            for (String prefix : prefixes) {
                // -1 keeps a trailing empty segment so "forwarding/" is refused; accepted, the purge would compose a
                // key with "//" under which nothing is listed and silently reclaim nothing.
                for (String segment : prefix.split("/", -1)) {
                    ArtifactStore.segment(segment);
                }
                result.add(ArtifactStore.key(prefix));
            }
            return Collections.unmodifiableSet(result);
        }
    }

    /** Every installed declaration, attributed to its providing module, merged per module and ordered by module
     *  name. */
    static List<Declared> declared() {
        Map<String, Declared> declared = new TreeMap<>();
        for (ServiceLoader.Provider<StorageNamespace> provider : ServiceLoader.load(StorageNamespace.class)
                .stream().toList()) {
            StorageNamespace namespace;
            Set<String> repositoryPrefixes;
            Set<String> tenantPrefixes;
            Set<String> sharedPrefixes;
            List<Declared> onBehalf;
            try {
                namespace = provider.get();
                repositoryPrefixes = namespace.repositoryPrefixes();
                tenantPrefixes = namespace.tenantPrefixes();
                sharedPrefixes = namespace.sharedPrefixes();
                onBehalf = namespace.declarations();
            } catch (RuntimeException broken) {
                // Contained per module: one failing declaration must not blank the whole diagnostic. Its space reads
                // as unowned, which points at the fault.
                System.getLogger(StorageNamespace.class.getName()).log(System.Logger.Level.WARNING,
                        "storage namespace: " + provider.type().getModule().getName() + " could not declare its "
                                + "prefixes, so its space is reported as unowned rather than costing the whole "
                                + "manifest", broken);
                continue;
            }
            List<Declared> entries = new ArrayList<>(onBehalf);
            entries.add(new Declared(provider.type().getModule().getName(),
                    repositoryPrefixes, tenantPrefixes, sharedPrefixes));
            for (Declared entry : entries) {
                declared.merge(entry.module(), entry,
                    (left, right) -> {
                        Set<String> repositories = new TreeSet<>(left.repositoryPrefixes());
                        repositories.addAll(right.repositoryPrefixes());
                        Set<String> tenants = new TreeSet<>(left.tenantPrefixes());
                        tenants.addAll(right.tenantPrefixes());
                        Set<String> shared = new TreeSet<>(left.sharedPrefixes());
                        shared.addAll(right.sharedPrefixes());
                        return new Declared(left.module(), repositories, tenants, shared);
                    });
            }
        }
        return List.copyOf(declared.values());
    }
}
