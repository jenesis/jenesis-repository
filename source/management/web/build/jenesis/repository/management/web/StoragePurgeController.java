package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Tenants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's explicit reclamation of a removed module's data, a framework primitive over the per-module storage
 * manifest, present whichever modules an image carries, since a module's leftovers are purged when it is gone. Two
 * deployment-global, operator-tenant-only verbs: {@code GET /api/admin/orphans} reports manifest entries whose module
 * is not installed but whose key-spaces hold data - informational, since absence never triggers deletion, an incomplete
 * image looking like a removal - and {@code POST /api/admin/purge?namespace=<module>} reaps that module's key-spaces.
 *
 * <p>The purge is a dry run unless {@code dryRun=false}: it lists what would go, with per-prefix counts and bytes, so
 * nothing is removed unless the operator names the target and turns the dry run off. A real purge is audited.
 *
 * <p><strong>Both responses name what the purge cannot reach</strong> ({@code unreachable}, with its {@code note}): the
 * reserved root spaces no {@code StorageNamespace} may declare, from {@link StorageNamespaces#UNREACHABLE}, so a blast
 * radius listing none of them is not read as "there is no data there".
 */
@RestController
public class StoragePurgeController {

    private final StorageNamespaces namespaces;
    private final Tenants tenants;
    private final AuditTrail audit;
    private final RepositoryRouting routing;

    public StoragePurgeController(StorageNamespaces namespaces, Tenants tenants, AuditTrail audit,
                                  RepositoryRouting routing) {
        this.namespaces = namespaces;
        this.tenants = tenants;
        this.audit = audit;
        this.routing = routing;
    }

    /** The orphaned-data diagnostic: every manifest entry whose module is not installed yet whose key-spaces hold data.
     *  A report, never an action. */
    @GetMapping("/api/admin/orphans")
    public OrphansView orphans() throws IOException {
        List<OrphanView> orphans = new ArrayList<>();
        for (StorageNamespaces.Report report : namespaces.orphans(tenants.list())) {
            orphans.add(new OrphanView(report.module(), report.objects(), report.bytes()));
        }
        return new OrphansView(orphans, unreachable(), StorageNamespaces.UNREACHABLE_NOTE);
    }

    /** Purge the named module's key-spaces, a dry run unless {@code dryRun=false}; {@code 404} when no manifest entry
     *  names it. */
    @PostMapping("/api/admin/purge")
    public ResponseEntity<PurgeView> purge(@RequestParam("namespace") String namespace,
                                           @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
                                           @RequestHeader(value = Repositories.KEY, required = false) String key,
                                           HttpServletRequest request) throws IOException {
        String tenant = routing.tenant(request);
        List<String> all = tenants.list();
        Optional<StorageNamespaces.Report> report = dryRun
                ? namespaces.plan(namespace, all)
                : namespaces.purge(namespace, all);
        if (report.isEmpty()) {
            return ResponseEntity.status(404).build();
        }
        if (!dryRun) {
            audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), AuditActions.STORAGE_PURGE,
                    namespace + " (" + report.get().objects() + " objects, " + report.get().bytes() + " bytes)");
        }
        List<SpaceView> spaces = new ArrayList<>();
        for (StorageNamespaces.Report.Space space : report.get().spaces()) {
            spaces.add(new SpaceView(space.prefix(), space.objects(), space.bytes()));
        }
        List<KeptView> kept = new ArrayList<>();
        for (StorageNamespaces.Report.Kept space : report.get().kept()) {
            kept.add(new KeptView(space.scope(), space.prefix(), space.owners()));
        }
        return ResponseEntity.ok(new PurgeView(namespace, dryRun,
                spaces, report.get().objects(), report.get().bytes(), kept,
                unreachable(), StorageNamespaces.UNREACHABLE_NOTE));
    }

    /** The reserved roots the purge cannot reach, as the {@code <root>/} prefixes an operator sees, from the purge's
     *  own derivation. */
    private static List<String> unreachable() {
        return StorageNamespaces.UNREACHABLE.stream().map(root -> root + "/").toList();
    }

    /** The orphan report, with the reserved spaces no manifest entry describes, so an empty report reads as "no
     *  orphaned plug-in data", never "no data outside the declared spaces". */
    public record OrphansView(List<OrphanView> orphans, List<String> unreachable, String note) {
    }

    /** One orphaned module's leftovers, summed over its declared key-spaces across all tenants. */
    public record OrphanView(String namespace, long objects, long bytes) {
    }

    /** What one purge or dry run covered, per fully scoped prefix, and the reserved spaces it cannot reach. */
    public record PurgeView(String namespace, boolean dryRun, List<SpaceView> spaces, long objects, long bytes,
                            List<KeptView> kept, List<String> unreachable, String note) {
    }

    /** A declared prefix the purge leaves at its {@code scope}, because the installed {@code owners} declare it too. */
    public record KeptView(String scope, String prefix, List<String> owners) {
    }

    /** One fully scoped prefix ({@code <tenant>/<repository>/<prefix>} or {@code <tenant>/<prefix>}) and what it holds
     *  or held. */
    public record SpaceView(String prefix, long objects, long bytes) {
    }
}
