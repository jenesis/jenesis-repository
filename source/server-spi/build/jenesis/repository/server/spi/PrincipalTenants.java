package build.jenesis.repository.server.spi;

import module java.base;

/**
 * Which tenants a principal holds a grant in, directly or through a group: one document per principal,
 * {@code .system/auth/.principals/<id>}, whose keys are those tenants.
 *
 * <p>It answers the one cross-tenant question a person's sign-in asks - which tenants may I open - by one point
 * read. The honest computation is a probe of every tenant for the person's grants, and that is a request whose cost
 * grows with the deployment rather than with the person, which the bounded-read rule forbids. So it takes the shape
 * the derived document takes: kept by the writes that change it, and read as it is.
 *
 * <p><b>Kept by every write that changes what a principal holds in a tenant</b>, through {@link #reconcile}: a direct
 * grant set or removed, the principal removed, and a derivation of what their groups confer - which is also what a
 * group's own grants changing, a membership and a group's removal reach, since each of those re-derives the members
 * it touches. The decision is taken from the documents as they are rather than from what the caller meant to do, so
 * any of these paths on any node leaves the index agreeing with the grants. It is written only when the tenant set
 * changes: a grant within a tenant the principal already holds something in reads the index and writes nothing.
 *
 * <p><b>A candidate set, never the answer.</b> The index may name a tenant the principal no longer holds anything in
 * - a tenant purged whole through the store rather than a grant at a time, a grant lapsed by its own expiry, or two
 * writers crossing as described below - and it must never omit one they do. So a reader confirms each tenant it
 * names against the grants themselves, which is a point read per tenant the person belongs to. The omission is the
 * direction that matters: an extra name costs one read and shows nothing, a missing one is a membership nobody can
 * reach.
 *
 * <p><b>Two writers.</b> The document is changed under compare-and-set, so two nodes adding different tenants at once
 * both land. What compare-and-set alone does not cover is a removal crossing a grant: one writer finds the principal
 * holds nothing in a tenant and removes it while another has just granted there, read the index, found the tenant
 * already named and written nothing. So a removal that changed the document reads the grants again afterwards and
 * puts the tenant back if it finds any: every grant written before that second read is seen by it, and every grant
 * written after it reads an index that no longer names the tenant and adds it.
 *
 * <p><b>An index that does not exist yet</b> - a deployment whose principals were granted before it was kept - is
 * built by the start-up repair ({@link GroupMembership#repairDerivedGrants}), which already re-derives every principal
 * of every tenant and reconciles each one as it goes. It is built there rather than by a request that finds it
 * absent, because a request building it would have to probe every tenant, which is the unbounded read this document
 * exists to remove; the repair walks the store off the request path, runs on every start, and writes only the
 * tenants that are missing. A read therefore answers what the document says and never walks.
 */
final class PrincipalTenants {

    private final CredentialSpace space;

    PrincipalTenants(CredentialSpace space) {
        this.space = space;
    }

    /**
     * Make {@code subject}'s index agree with what it holds in {@code tenant} now. Only a principal has one - a key
     * belongs to its one tenant and a group is read through, not about - and a deployment-wide grant is not a
     * tenant: it is held in every tenant, which no per-tenant list could say.
     */
    void reconcile(String tenant, Authorization.Subject subject) throws IOException {
        if (subject.kind() != Authorization.Kind.PRINCIPAL || Authorization.DEPLOYMENT.equals(tenant)) {
            return;
        }
        boolean holds = holds(tenant, subject);
        if (record(tenant, subject, holds) && !holds && holds(tenant, subject)) {
            record(tenant, subject, true);
        }
    }

    /** The tenants the index names for {@code principal}, in order; empty when it names none. */
    List<String> of(String principal) throws IOException {
        Properties tenants = space.read(CredentialSpace.tenantsPath(Authorization.Subject.principal(principal).id()));
        return tenants == null ? List.of() : List.copyOf(new TreeSet<>(tenants.stringPropertyNames()));
    }

    /** Whether {@code subject} holds any scope in {@code tenant}, of its own or through its groups, read past the
     *  cache so a peer's grant of a moment ago counts. */
    private boolean holds(String tenant, Authorization.Subject subject) throws IOException {
        return anyScope(space.fresh(CredentialSpace.grantsPath(tenant, subject)))
                || anyScope(space.fresh(CredentialSpace.derivedPath(tenant, subject)));
    }

    private static boolean anyScope(Properties document) {
        if (document == null) {
            return false;
        }
        for (String key : document.stringPropertyNames()) {
            if (!key.startsWith(GrantMatching.EXPIRES)) {
                return true;
            }
        }
        return false;
    }

    /** Name {@code tenant} in the index or take it out; answers whether the document was asked to change. An absent
     *  document is not created to record that a principal holds nothing. */
    private boolean record(String tenant, Authorization.Subject subject, boolean held) throws IOException {
        return space.decide(CredentialSpace.tenantsPath(subject.id()), tenants -> {
            if (tenants.containsKey(tenant) == held) {
                return null;
            }
            if (held) {
                tenants.setProperty(tenant, "");
            } else {
                tenants.remove(tenant);
            }
            return tenants;
        });
    }
}
