package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Tenants;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's explicit reclamation of a removed module's data - a framework primitive over the per-module
 * storage manifest, always present regardless of which feature modules an image carries (an operator must be able
 * to purge a module's leftovers precisely when that module is gone). Two deployment-global admin verbs, both
 * operator-tenant-only: {@code GET /api/admin/orphans} reports manifest entries whose declaring module is no
 * longer installed but whose key-spaces still hold data (purely informational - module absence never triggers
 * deletion, since an incomplete image or a mid-rolling deploy looks identical to an intentional removal), and
 * {@code POST /api/admin/purge?namespace=<module>} reaps the named module's declared key-spaces.
 *
 * <p>The purge is dry-run by default: without {@code dryRun=false} it only lists what would be deleted, with
 * per-prefix object counts and bytes, so nothing is ever removed unless the operator both names the target and
 * explicitly turns the dry run off. A real purge records an audit event. Re-homed beside the credential and
 * authorization management surface (its natural admin peer) and contributed through the {@code ServerModuleProvider}
 * seam; with this module absent the server carries no {@code /api/admin/orphans} or {@code /api/admin/purge} endpoint.
 *
 * <p><strong>Both responses name what this purge cannot reach</strong> ({@code unreachable}, with the one-sentence
 * {@code note} behind it): the reserved root key spaces no {@code StorageNamespace} may declare - today
 * {@code audit/}, {@code locks/} and {@code quota/} - and which the manifest therefore cannot describe. That is a
 * deliberate exclusion rather than a gap, and this is where an operator meets it: a blast radius that lists no
 * {@code audit/} rows must not read as "there is no audit data", and a report is the only place that distinction can
 * be drawn at the moment it matters. The list is derived from {@link StorageNamespaces#UNREACHABLE} rather than spelled
 * here, so the endpoint can never describe a different exclusion from the one the purge actually has.
 */
@RestController
public class StoragePurgeController {

    private final StorageNamespaces namespaces;
    private final Tenants tenants;
    private final AuditTrail audit;
    private final String operatorTenant;

    public StoragePurgeController(StorageNamespaces namespaces, Tenants tenants, AuditTrail audit,
                                  RepositoryProperties properties) {
        this.namespaces = namespaces;
        this.tenants = tenants;
        this.audit = audit;
        this.operatorTenant = properties.getOperatorTenant().isBlank()
                ? properties.getDefaultTenant()
                : properties.getOperatorTenant();
    }

    /** The orphaned-data diagnostic: every manifest entry whose declaring module is not installed yet whose
     *  key-spaces still hold data. A report, never an action. */
    @GetMapping("/api/admin/orphans")
    public OrphansView orphans() throws IOException {
        List<OrphanView> orphans = new ArrayList<>();
        for (StorageNamespaces.Report report : namespaces.orphans(tenants.list())) {
            orphans.add(new OrphanView(report.module(), report.objects(), report.bytes()));
        }
        return new OrphansView(orphans, unreachable(), StorageNamespaces.UNREACHABLE_NOTE);
    }

    /** Purge the named module's declared key-spaces - dry-run unless {@code dryRun=false} is passed explicitly.
     *  {@code 404} when no manifest entry names the module (the target is named, never inferred). */
    @PostMapping("/api/admin/purge")
    public ResponseEntity<PurgeView> purge(@RequestParam("namespace") String namespace,
                                           @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
                                           @RequestHeader(value = Repositories.KEY, required = false) String key)
            throws IOException {
        List<String> all = tenants.list();
        Optional<StorageNamespaces.Report> report = dryRun
                ? namespaces.plan(namespace, all)
                : namespaces.purge(namespace, all);
        if (report.isEmpty()) {
            return ResponseEntity.status(404).build();
        }
        if (!dryRun) {
            audit.record(operatorTenant, key == null ? "anonymous" : Authorization.hash(key), "storage.purge",
                    namespace + " (" + report.get().objects() + " objects, " + report.get().bytes() + " bytes)");
        }
        List<SpaceView> spaces = new ArrayList<>();
        for (StorageNamespaces.Report.Space space : report.get().spaces()) {
            spaces.add(new SpaceView(space.prefix(), space.objects(), space.bytes()));
        }
        return ResponseEntity.ok(new PurgeView(namespace, dryRun,
                spaces, report.get().objects(), report.get().bytes(),
                unreachable(), StorageNamespaces.UNREACHABLE_NOTE));
    }

    /** The reserved roots this purge can never reach, rendered as the {@code <root>/} prefixes an operator sees in a
     *  key. Read from the purge primitive's own derivation, never restated here. */
    private static List<String> unreachable() {
        return StorageNamespaces.UNREACHABLE.stream().map(root -> root + "/").toList();
    }

    /** The orphan report: what data remains from modules this image was not built with, and the reserved key spaces
     *  no manifest entry can describe - so an empty report is read as "no orphaned plug-in data", never as "no data
     *  outside the declared spaces". */
    public record OrphansView(List<OrphanView> orphans, List<String> unreachable, String note) {
    }

    /** One orphaned module's leftovers, summed over its declared key-spaces across all tenants. */
    public record OrphanView(String namespace, long objects, long bytes) {
    }

    /** What one purge (or its dry run) covered, with the per-prefix breakdown of the fully scoped key-spaces - and
     *  the reserved key spaces it deliberately cannot reach, so the blast radius is read for what it is. */
    public record PurgeView(String namespace, boolean dryRun, List<SpaceView> spaces, long objects, long bytes,
                            List<String> unreachable, String note) {
    }

    /** One fully scoped prefix ({@code <tenant>/<repository>/<prefix>} or {@code <tenant>/<prefix>}) and what it
     *  holds (dry run) or held (purge). */
    public record SpaceView(String prefix, long objects, long bytes) {
    }
}
