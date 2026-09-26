package build.jenesis.repository.maintenance;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A module's declaration of the store key-spaces it owns - the per-module storage manifest, discovered with
 * {@link ServiceLoader} like every other contribution. The store's key-spaces are not a 1:1 module-to-folder map
 * (a module may own several prefixes, at repository or at tenant scope), so each module that persists anything
 * beyond the artifacts themselves {@code provides} this interface naming its prefixes. The manifest feeds two
 * consumers that must never guess from a hardcoded table: the orphaned-data diagnostic (data whose declaring module
 * is no longer installed) and the explicit operator purge ({@link StorageNamespaces}) that reaps a <em>named</em>
 * module's key-space. The declaration is attributed to its JPMS module automatically, so the manifest keys align
 * with the modules console and the per-module settings documents.
 *
 * <p><strong>Absence never deletes.</strong> A module missing from the module path only makes its persisted
 * manifest entry an orphan <em>candidate</em> the diagnostic may report - an incomplete image or a mid-rolling
 * deploy is indistinguishable from an intentional removal, so nothing acts on absence alone; only an operator's
 * explicit, named purge command removes data.
 *
 * <p><strong>Per-tenant by default, shared only under {@code .system/auth} and {@code .system/config}.</strong> Every
 * key-space a module declares is tenant-scoped ({@link #repositoryPrefixes} under {@code <tenant>/<repository>/},
 * {@link #tenantPrefixes} under {@code <tenant>/}) - two tenants never share a module's data. The one
 * deployment-global exception is the pair of product-owned spaces in {@link #SHARED_ROOTS}: {@code auth} (a user
 * spans tenants, so the credential/membership map is root-level by design) and the superadmin {@code config} space,
 * both under the product's own {@code .system} root. A {@link #sharedPrefixes shared} declaration outside those two
 * is refused at construction, so a new module defaults to per-tenant and a mis-scoped declaration fails instead of
 * silently claiming deployment-global storage.
 *
 * <p>A module whose per-coordinate facts are sections of the consolidated metadata document claims no root for them:
 * the document's space ({@code meta}) is the metadata store's own declaration, and every composition carries that
 * store. What a module declares is what lives outside the document - its repo-level singletons and derived indexes.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An implementation is a constant: the three accessors are pure, take no argument, read
 *       no store and hold no state, so the server may call them concurrently on a shared instance. An implementation
 *       that computed its prefixes would also have to be correct while the store is unreachable, which is why the
 *       shape is a returned constant set rather than a lookup.</li>
 *   <li><b>Idempotency / replay.</b> Every call returns the same prefixes for the life of the JVM. The manifest is
 *       persisted from them repeatedly ({@link StorageNamespaces#register()} runs at every boot) and a re-run must
 *       be a byte-identical no-op, so a set whose iteration order or membership varied would rewrite the stored
 *       document on every start.</li>
 *   <li><b>Absence sentinel.</b> Each accessor returns an empty {@link Set}, never {@code null}; the defaults supply
 *       exactly that. A module that persists nothing still legitimately {@code provides} this interface with three
 *       empty sets - that declaration is not decoration, it is what keeps the module <em>installed</em> in the eyes
 *       of the orphan diagnostic, so a key-space it has finished migrating away from surfaces as unowned data rather
 *       than hiding under a live manifest entry.</li>
 *   <li><b>Tenant scoping.</b> {@link #repositoryPrefixes} resolve under every {@code <tenant>/<repository>/} and
 *       {@link #tenantPrefixes} under every {@code <tenant>/}; both are per-tenant, and cross-tenant reclamation is
 *       unrepresentable because the purge composes the scope itself. Only {@link #sharedPrefixes} is
 *       deployment-global, and only as a proper sub-space of a {@link #SHARED_ROOTS} root. A tenant prefix must
 *       further be a reserved dot-space ({@code .scans}, {@code .vex}): the purge enumerates a tenant's non-dot
 *       children as repositories, so a tenant prefix without the leading dot would be walked as a repository and its
 *       data missed.</li>
 *   <li><b>Prefix shape.</b> A prefix is a relative store key of one or more {@link ArtifactStore#segment}-valid
 *       segments - no leading, trailing or repeated {@code /}, no {@code .} or {@code ..} segment, no backslash. It
 *       may be a whole key (the key-login index is one object) or the root of a subtree; both are walked the same
 *       way. {@link Declared} enforces this at construction for a declaration made in code and for one read back
 *       out of the store, so a malformed prefix can never reach the purge's key composition.</li>
 *   <li><b>Ownership is for reclamation, not exclusive writes.</b> Declaring a prefix says "purging this module
 *       reclaims this space", not "only this module writes here" - several modules legitimately write into another's
 *       declared space (the consolidated {@code meta} document, the gate's {@code overrides} markers an eviction
 *       clears). What the declaration must be exclusive in is the other direction: two <em>different</em> modules
 *       may not declare equal or nested prefixes, because purging either would then delete the other's data with no
 *       warning in the dry run. One module may freely declare several prefixes under one root; they merge.</li>
 *   <li><b>Absence is never a reason to delete, and a shared space has one key owner.</b> A
 *       declared space's rows outlive the module that wrote them. Uninstalling a module changes what can be
 *       <em>explained</em> and <em>acted on</em>, never what exists, so the only ways out of a declared space are the
 *       explicit dry-run-guarded operator purge above and a sweep that can positively prove the row's subject gone. A
 *       sweep that judges through a discovered provider must therefore separate "gone" from "I cannot tell" and
 *       no-op on the second: the reconcile pass's derived-row sweep did not, so an uninstalled <em>format</em> module
 *       made every one of its versions read as dead and took their {@code pinned/} and {@code overrides/} rows with
 *       it - a human's force-keep and a human's clearance of a hold, deleted because a module was absent. And where a
 *       space is written per kind but reaped kind-neutrally - as clause 6's {@code overrides} markers are - its key
 *       spelling has exactly ONE owner ({@code HoldRecords} for {@code holds/}, {@code OverrideRecords} for
 *       {@code overrides/}): a reaper that composes its own reaps a key no writer wrote, which strands the row rather
 *       than reclaiming it, and is the mirror failure of deleting one it should have kept.
 *
 *       <p><b>The artifact spaces are held to this rule too, and there the answer is refusal.</b> A blobs-
 *       namespace format's serving pointers live under roots only that format declares, so with its module absent the
 *       garbage collector's reference scan cannot see them, every blob it serves reads as unreferenced and the sweep
 *       deletes the artifact itself - the one loss nothing re-derives. The collector is handed a flat list of pointer
 *       roots and judges the whole content namespace against it: there is no way to spare one root's subtree, so an
 *       incomplete root set is not a degraded scan but a licence to delete. The root set a reclaiming pass is handed
 *       ({@code StoreRepositoryInventory.pointerRoots(store)}) therefore says whether every ecosystem the durable
 *       published set records is one an installed format can place, and a set that cannot say so refuses the pass at
 *       the collector - loudly, reported as an incomplete judgment carrying its cause. Refusing is not a refusal to
 *       reclaim: with every recorded format installed the collector runs unchanged, and a blob no live pointer names
 *       is still condemned and collected.</li>
 *   <li><b>Ordering / concurrency.</b> {@link #declared()} is ordered by module name and its result must not depend
 *       on {@code ServiceLoader} discovery order: several declarations from one module merge into one entry, and
 *       every prefix set is sorted. A consumer may rely on the order; an implementation may not rely on being
 *       discovered before or after any other.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link #declared()} constructs a fresh instance per call through
 *       {@code ServiceLoader} and keeps none, so an implementation must have a no-argument constructor, own no
 *       thread, client or store handle, and need no closing.</li>
 *   <li><b>Bounded work.</b> An accessor performs no IO and returns a constant-sized set. All bounded traversal
 *       lives in {@link StorageNamespaces}, which walks the declared prefixes for the dry run, the purge and the
 *       orphan count.</li>
 *   <li><b>What the manifest does not cover.</b> This is the <em>plug-in</em> manifest. The core's
 *       own key-spaces ({@code publish/}, {@code blobs/}, {@code withheld/}, {@code walks/}, {@code gc/},
 *       {@code imports/}, {@code quota/}) carry no entry by construction - the core is never a module that can be
 *       removed, so it can leave no orphan - and the reserved root spaces outside {@link #SHARED_ROOTS} cannot be
 *       declared at all, since a {@link Declared} naming them is refused. An absent entry therefore means "not
 *       plug-in data", never "no data".</li>
 *   <li><b>The unreachable spaces are deliberate, and stated.</b> Those undeclarable roots -
 *       {@link StorageNamespaces#UNREACHABLE}, derived as {@link Scopes#SPACES} minus {@link #SHARED_ROOTS}, today
 *       {@code audit}, {@code locks}, {@code cache} and {@code quota} - hold real persisted data that the purge can
 *       therefore never reach, and that is the intended trade rather than a gap to close by widening
 *       {@link #SHARED_ROOTS}: widening it would widen what <em>any</em> plug-in may claim, permanently, to make one
 *       rare operation reach the one ledger it should reach least. Because it is a decision, it is told to the
 *       operator on the purge and orphan surfaces themselves rather than only here, and it is asserted in both
 *       directions - a reserved root that quietly became purgeable, and a purge that quietly began skipping a space
 *       a module <em>can</em> declare, both fail the build.</li>
 * </ol>
 */
public interface StorageNamespace {

    /** The only root spaces a {@link #sharedPrefixes shared} (deployment-global) declaration may sit under: the
     *  {@code auth/} credential/membership map (a user spans tenants - root-level by design) and the superadmin
     *  {@code config/} space. Everything else a module persists is per-tenant. */
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

    /** The deployment-global key prefixes this module owns at the store root, beside the tenant scopes - the rare
     *  exception, allowed only under the {@link #SHARED_ROOTS auth/config roots}; empty (the default) for every
     *  module whose data is per-tenant, which is all of them but the auth/config owners. */
    default Set<String> sharedPrefixes() {
        return Set.of();
    }

    /**
     * Further manifest entries this provider declares <em>on behalf of other modules</em>, beside its own
     * self-attributed entry - empty for every ordinary declaration. The one sanctioned use is a registrar over a
     * discovered plug-in family whose members cannot carry a declaration of their own without each requiring this
     * module: the inventory's format registrar emits one entry per installed format, attributed to the format's own
     * JPMS module, so a format dropped from an image (or toggled off) leaves a manifest entry behind exactly as any
     * other module does - which is what lets the orphan diagnostic name its artifacts and the explicit purge reap
     * them. Every entry obeys the same rules as a self-attributed one; the {@link Declared} constructor enforces the
     * prefix shape either way.
     */
    default List<Declared> declarations() {
        return List.of();
    }

    /**
     * One module's manifest entry: the owning JPMS module name and the prefixes it declares. Every prefix is
     * validated to be a traversal-free relative path (each {@code /}-separated segment passes
     * {@link ArtifactStore#segment}), so a manifest entry - declared in code or read back from the store - can
     * never name a key-space outside its scope; a shared prefix must additionally sit under one of the
     * {@link #SHARED_ROOTS}, so no entry can claim a deployment-global space outside auth/config.
     */
    record Declared(String module, Set<String> repositoryPrefixes, Set<String> tenantPrefixes,
                    Set<String> sharedPrefixes) {

        public Declared {
            ArtifactStore.segment(module);
            repositoryPrefixes = validated(repositoryPrefixes);
            tenantPrefixes = validated(tenantPrefixes);
            sharedPrefixes = validated(sharedPrefixes);
            for (String prefix : sharedPrefixes) {
                // A module owns a space UNDER a shared root, never the root itself: a bare auth or config
                // declaration would authorize a purge of the entire deployment-global tree (every tenant's
                // credentials, every superadmin config) through one manifest entry. The roots live in the
                // product's own space, so a shared prefix reads .system/<root>/<something> and all three parts
                // are required - the last of them is what stops the bare-root claim.
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
                // -1, so a trailing separator is kept as an empty segment and refused. Without it Java drops trailing
                // empties and "forwarding/" validates, is stored verbatim, and the purge composes "<tenant>/<repo>//
                // forwarding/" - a key the walk lists nothing under, so the dry run reports an empty blast radius and
                // the purge silently reclaims nothing at all.
                for (String segment : prefix.split("/", -1)) {
                    ArtifactStore.segment(segment);
                }
                result.add(ArtifactStore.key(prefix));
            }
            return Collections.unmodifiableSet(result);
        }
    }

    /** Every storage declaration installed on this deployment, each attributed to the JPMS module that provides
     *  it (several declarations from one module merge into one entry), ordered by module name - the manifest of
     *  the modules that are actually present. */
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
                // Contained per module, because the manifest this feeds is a DIAGNOSTIC: it tells an operator what
                // is in the store that nothing owns. Uncontained, one module whose declaration throws cost the
                // whole manifest - and a blank manifest is the worst shape a diagnostic can have here, because the
                // standing ruling that orphan purge is never automatic makes it perfectly safe and completely
                // useless. The module that could not declare is named at WARN and contributes no prefixes, so its
                // own space reads as unowned - which is the honest answer and the one that points at the fault.
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
