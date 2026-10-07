package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.store.Documents;

/**
 * The principals of a tenant that hold a grant of their own there: one small marker per principal,
 * {@code .system/auth/<tenant>/members/<id>}, so a tenant's membership is paged - and counted - over the people who
 * are members and nobody else.
 *
 * <p>It exists because the principal listing is not the membership. A group naming a person gives them a subject in
 * the tenant - their derived grants live under it - whether or not they were ever granted anything of their own, so
 * the listing holds every member and every person a group mentions. A count over it, which is what SCIM's
 * {@code totalResults} was, counts people the same answer's {@code Resources} never shows; a page over it reads on
 * past them. The markers are the listing those two want, kept by the writes that change it, in the shape the tenant
 * index ({@link PrincipalTenants}) already takes.
 *
 * <p><b>A candidate set that never omits a member.</b> A principal's own grant is written only after its marker
 * ({@link #admit}), and a marker is taken away only after the grants it stands for, read past the cache, are found
 * to hold nothing live - and is put back when a grant crossed that removal. A crash between the two writes therefore
 * leaves at most a marker with nothing behind it, which a reader confirms against the grants and passes by, and never
 * a member nobody can list. A grant that lapses by its own expiry leaves its marker too, since nothing writes at the
 * moment it lapses.
 *
 * <p><b>Repaired at start-up.</b> The start-up repair ({@link GroupMembership#repairDerivedGrants}) walks every
 * principal of every tenant and reconciles each one here, which takes away the stale markers and writes the markers
 * of principals granted before this index was kept. Both happen off the request path, which is where a walk of the
 * principals belongs.
 */
final class TenantMembers {

    /** The tenant document the markers live under, beside the kind segments. Not a kind's name, so no subject
     *  listing reaches it. */
    static final String NAME = "members";

    private final CredentialSpace space;

    TenantMembers(CredentialSpace space) {
        this.space = space;
    }

    /** One page of the principals {@code tenant} names as members, in key order - the shape and cursor rule of
     *  {@link Authorization#subjects}. */
    Authorization.SubjectPage page(String tenant, String after, int limit) {
        return space.subjects(CredentialSpace.tenantDocument(tenant, NAME), after, limit);
    }

    /** Mark {@code subject} a member of {@code tenant} before a grant of its own is written, so no reader can find the
     *  grant without the marker. Only a principal is listed: a key belongs to its tenant by construction and a group
     *  is read through. */
    void admit(String tenant, Authorization.Subject subject) throws IOException {
        if (subject.kind() == Authorization.Kind.PRINCIPAL && !space.store().exists(path(tenant, subject))) {
            mark(tenant, subject);
        }
    }

    /** Make {@code subject}'s marker agree with what it holds of its own in {@code tenant} now: written when it holds
     *  a live grant and has none, taken away when it holds nothing - and put back if a grant landed meanwhile. */
    void reconcile(String tenant, Authorization.Subject subject) throws IOException {
        if (subject.kind() != Authorization.Kind.PRINCIPAL) {
            return;
        }
        boolean marked = space.store().exists(path(tenant, subject));
        if (holds(tenant, subject)) {
            if (!marked) {
                mark(tenant, subject);
            }
        } else if (marked) {
            space.delete(path(tenant, subject));
            // A grant written between the read above and the delete found the marker present and wrote none; this
            // second read sees it, and every grant written after it finds the marker gone and writes one itself.
            if (holds(tenant, subject)) {
                mark(tenant, subject);
            }
        }
    }

    /** Whether {@code subject} holds a live grant of its own in {@code tenant}, read past the cache. */
    private boolean holds(String tenant, Authorization.Subject subject) throws IOException {
        Properties grants = space.fresh(CredentialSpace.grantsPath(tenant, subject));
        if (grants == null) {
            return false;
        }
        Instant now = Instant.now();
        for (String scope : grants.stringPropertyNames()) {
            if (!scope.startsWith(GrantMatching.EXPIRES) && !GrantMatching.expired(grants, scope, now)) {
                return true;
            }
        }
        return false;
    }

    private void mark(String tenant, Authorization.Subject subject) throws IOException {
        space.require();
        Properties marker = new Properties();
        marker.setProperty("since", Instant.now().toString());
        space.store().write(path(tenant, subject), new ByteArrayInputStream(Documents.bytes(marker)));
    }

    private static String path(String tenant, Authorization.Subject subject) {
        return CredentialSpace.tenantDocument(tenant, NAME) + "/" + CredentialSpace.segment(subject.id());
    }
}
