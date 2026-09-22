package build.jenesis.repository.maintenance;

import module java.base;

import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * The persisted per-module storage manifest and the explicit purge primitive over it. {@link #register()} writes
 * every installed {@link StorageNamespace} declaration under {@code config/namespaces/<module>} at boot, so the
 * manifest <em>outlives the module</em>: a module dropped from an image leaves its entry behind, which is exactly
 * what lets the orphan diagnostic name the data ("orphaned data detected for module X: N objects, M bytes") and
 * lets an operator purge a key-space whose declaring code is gone. A manifest entry is only ever written or
 * updated here, never removed - a dormant entry for long-gone data is harmless and re-registration on a module's
 * return simply overwrites it.
 *
 * <p><strong>The operator's hard rules, enforced by this shape:</strong> nothing here runs on a schedule or on
 * module absence - {@link #orphans} only <em>counts</em> (the diagnostic), and data is deleted only by
 * {@link #purge} with an explicitly named module; {@link #plan} is the mandatory dry-run twin that walks exactly
 * the keys the purge would delete, reporting per-prefix object counts and bytes, so an operator always sees the
 * blast radius first. Absence is never a trigger: an absent-but-not-purged module's data stays untouched forever,
 * because an incomplete image or a mid-rolling deploy is indistinguishable from an intentional removal.
 *
 * <p><strong>What the purge deliberately cannot reach.</strong> The deployment reserves five root key spaces
 * ({@link Scopes#SPACES}) and a module may declare a space under exactly two of them
 * ({@link StorageNamespace#SHARED_ROOTS}, {@code auth/} and {@code config/}). The difference -
 * {@link #UNREACHABLE} - is therefore outside this primitive <em>by construction</em>: a {@link
 * StorageNamespace.Declared} naming one is refused, so no manifest entry can describe it, so neither {@link #plan}
 * nor {@link #purge} nor {@link #orphans} ever walks it. That is a decision, not an oversight, and it is stated on
 * the operator's own surface rather than only here: widening {@code SHARED_ROOTS} to make the audit trail purgeable
 * would widen what <em>any</em> plug-in may claim - a permanent cost paid for a rare operation - and the audit trail
 * is the last thing a product should let a routine reclamation reach. What does reclaim it is a tenant's removal,
 * which deletes {@code audit/<tenant>} by naming it explicitly; the leases under {@code locks/} expire on their own
 * ttl; and the {@code quota/} counter is core state, which carries no manifest entry by construction
 * anyway. An operator who needs one of these gone removes it deliberately, outside the purge.
 *
 * <p>The walk is the shared bounded tree descent ({@link PagedTreeWalk}) over the doubly-scoped
 * layout - each declared repository prefix under every {@code <tenant>/<repository>/}, each tenant prefix under
 * every {@code <tenant>/}, and each (auth/config-rooted) shared prefix once at the store root - driving
 * {@link ArtifactStore#page} only, so it runs identically on filesystem and object stores, no level is materialised
 * whole, and a client-planted key depth is refused by name rather than descended. The manifest document is a tiny hand-written {@code key=value} object (like the settings documents,
 * keeping this contract module {@code java.base}-only), compare-and-set with the 3-attempt re-read idiom.
 */
public final class StorageNamespaces {

    /** The store prefix (a root-level {@code config/} space, deployment-global by design) under which the
     *  per-module manifest documents are kept. */
    public static final String ROOT = Scopes.space(Scopes.CONFIG) + "/namespaces";

    /**
     * The reserved root key spaces this purge can never reach, sorted: the deployment's {@linkplain Scopes#SPACES
     * spaces} minus the two a module may declare under ({@link StorageNamespace#SHARED_ROOTS}). Derived, not
     * listed, so the two sets can never drift into disagreement unnoticed - widening {@code SHARED_ROOTS} shrinks this
     * set and shows up on the operator surface that renders it, and a new reserved root joins it the day it is
     * reserved.
     *
     * <p>Every entry is unreachable <em>because</em> a declaration naming it is refused at construction (see the class
     * note for why that is the right trade), so this is not a skip list the walk consults: there is nothing for it to
     * skip, since no manifest entry can name one. It exists to be <em>told to the operator</em> - the purge and orphan
     * responses carry it - so "the purge found nothing here" is never mistaken for "there is nothing here".
     */
    public static final Set<String> UNREACHABLE = Collections.unmodifiableSortedSet(Scopes.SPACES.stream()
            .filter(root -> !StorageNamespace.SHARED_ROOTS.contains(root))
            .collect(Collectors.toCollection(TreeSet::new)));

    /** The one sentence the purge and orphan surfaces carry beside {@link #UNREACHABLE}, so an operator meets the
     *  exclusion where they meet the purge rather than in a javadoc they will never open. */
    public static final String UNREACHABLE_NOTE = "Reserved root key spaces no module may declare, so this purge "
            + "never reaches them - deliberately: making them declarable would widen what any plug-in may claim. "
            + "The audit trail is reclaimed only by removing a tenant, leases expire on their own ttl, and the usage "
            + "counter is core state.";

    private final ArtifactStore store;

    /** Over the deployment's root store - the unscoped store whose top level holds the tenant scopes. */
    public StorageNamespaces(ArtifactStore store) {
        this.store = store;
    }

    /** Persist every installed declaration into the manifest, so it survives the declaring module's removal.
     *  Idempotent and additive: an unchanged entry is left alone, a changed one is compare-and-set under
     *  {@link Retries}, and no entry is ever removed here. */
    public void register() throws IOException {
        for (StorageNamespace.Declared declared : StorageNamespace.declared()) {
            byte[] content = serialize(declared);
            Retries.update(store, ROOT + "/" + declared.module(), existing ->
                    existing.isPresent() && Arrays.equals(existing.get().content(), content) ? null : content);
        }
    }

    /** The full manifest: every persisted entry overlaid with the installed declarations (an installed module's
     *  own declaration wins over its stored copy, and answers even before {@link #register} ran), ordered by
     *  module name. A stored document that does not parse to a valid entry is skipped, never acted on. */
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
     * The orphaned-data diagnostic: for every manifest entry whose declaring module is <em>not</em> installed,
     * the dry-run count of what its key-spaces still hold, reporting only those that actually hold data. Purely
     * informational - nothing is deleted here, and module absence never triggers anything beyond this report;
     * removal is only ever {@link #purge} with the module named explicitly.
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

    /** The mandatory dry-run: walk exactly the keys {@link #purge} would delete - each declared prefix per
     *  doubly-scoped {@code <tenant>/<repository>} (and per {@code <tenant>} for the tenant spaces) - and report
     *  per-prefix object counts and bytes without touching anything. Empty when no manifest entry names
     *  {@code module}. */
    public Optional<Report> plan(String module, Collection<String> tenants) throws IOException {
        return resolveAndSweep(module, tenants, false);
    }

    /** The explicit purge of the named module's declared key-spaces - the operator's command, never anything
     *  automatic. Deletes exactly what {@link #plan} lists and reports what was removed; idempotent, so a
     *  partly-completed purge can be re-run to convergence. Empty when no manifest entry names {@code module}.
     *  The maintenance module itself is un-purgeable: its declared space is the manifest directory holding every
     *  module's entry, so purging it would erase the record of what every other module ever owned - the orphan
     *  diagnostic would go permanently blind. */
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

    /** One walk serves the plan and the purge, so the dry-run listing is exactly the delete's blast radius. A
     *  shared (deployment-global) prefix is walked once at the store root; the tenant-scoped prefixes per scope. */
    private Report sweep(StorageNamespace.Declared declared, Collection<String> tenants, boolean delete)
            throws IOException {
        List<Report.Space> spaces = new ArrayList<>();
        for (String prefix : declared.sharedPrefixes()) {
            space(spaces, prefix, delete);
        }
        for (String tenant : tenants) {
            ArtifactStore.segment(tenant);
            for (String prefix : declared.tenantPrefixes()) {
                space(spaces, tenant + "/" + prefix, delete);
            }
            if (declared.repositoryPrefixes().isEmpty()) {
                continue;
            }
            for (String repository : store.list(tenant)) {
                // The same predicate every other enumeration derives repositories with: a tenant's children include
                // its reserved key spaces beside its repositories, and only Scopes can tell them apart. This used to
                // skip dot-prefixed names alone, which is most of the rule but not the rule: `quota` is a
                // declared reserved name inside a tenant, carries no leading dot, and was therefore walked as if it
                // were a repository - so a module declaring a repository-scoped prefix had `<tenant>/quota/<prefix>`
                // in its plan, and so in its purge's blast radius.
                if (!Scopes.valid(repository)) {
                    continue;
                }
                for (String prefix : declared.repositoryPrefixes()) {
                    space(spaces, tenant + "/" + repository + "/" + prefix, delete);
                }
            }
        }
        long objects = 0;
        long bytes = 0;
        for (Report.Space space : spaces) {
            objects += space.objects();
            bytes += space.bytes();
        }
        return new Report(declared.module(), !delete, List.copyOf(spaces), objects, bytes);
    }

    private void space(List<Report.Space> spaces, String prefix, boolean delete) throws IOException {
        long[] totals = new long[2];
        walk(prefix, delete, totals);
        if (totals[0] > 0) {
            spaces.add(new Report.Space(prefix, totals[0], totals[1]));
        }
    }

    /** The bounds a namespace count/purge descends one declared prefix under. A dry-run plan that under-counted, or a
     *  purge that silently left objects behind, would report a namespace as emptied while it still stores bytes - the
     *  operator then deletes the module believing its space is gone. The entry cap is therefore only the per-call
     *  continuation {@link #walk} follows to exhaustion, and the binding bound is the step budget (one
     *  {@link ArtifactStore#exists} probe per opened node), which raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} rather than answering short. */
    private static final PagedTreeWalk SPACE = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Count (and, purging, delete) every stored object under one declared prefix, through the shared bounded tree
     *  walk: iterative, so a deploy-authorised client's path depth never reaches the call stack, and paged, so
     *  a wide level is never listed whole. An absent prefix holds no key and therefore counts and deletes nothing.
     *
     *  <p>Deleting behind the cursor is safe: the descent only ever pages forward from the last key it delivered, so a
     *  removed earlier key can never displace a later one. */
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

    /** What one plan, purge or orphan scan found: the module, whether this was a dry run, and the per-prefix
     *  counts (each prefix fully scoped, {@code <tenant>/<repository>/<prefix>} or {@code <tenant>/<prefix>},
     *  listing only prefixes that hold data). */
    public record Report(String module, boolean dryRun, List<Space> spaces, long objects, long bytes) {

        public Report {
            spaces = List.copyOf(spaces);
        }

        /** One fully-scoped prefix and what it holds. */
        public record Space(String prefix, long objects, long bytes) {
        }
    }

    /** The manifest document: a flat, sorted {@code key=value} text object ({@code repository=}, {@code tenant=}
     *  and {@code shared=} carrying comma-joined prefixes - a prefix can never contain a comma, its segments are
     *  store-validated). Hand-written like the settings documents, so this contract module stays
     *  {@code java.base}-only; deterministic, so an unchanged registration compares equal byte-for-byte. */
    private static byte[] serialize(StorageNamespace.Declared declared) {
        String content = "repository=" + String.join(",", declared.repositoryPrefixes()) + "\n"
                + "tenant=" + String.join(",", declared.tenantPrefixes()) + "\n"
                + "shared=" + String.join(",", declared.sharedPrefixes()) + "\n";
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /** The stored entry for {@code module}, or {@code null} when the document is not a manifest we understand or
     *  carries an unsafe or mis-scoped prefix (a shared declaration outside the auth/config roots) - a malformed
     *  entry is skipped, never purged on. A document predating the {@code shared=} line parses to an empty
     *  shared set, per-tenant by default. */
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
