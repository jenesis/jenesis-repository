package build.jenesis.repository.server.spi;

import module java.base;

/**
 * Who is in which group of a tenant, and the rights that membership gives them.
 *
 * <p>A group holds grants exactly as a person or a key does ({@link Authorization#setGrant(String,
 * Authorization.Subject, String, String)} with a {@link Authorization.Subject#group group} subject); this is the
 * other half - its members, one small object each under the group's subject path, and the union of their groups'
 * grants that each principal carries in a {@code derived} document. {@link Authorization#authorize(String,
 * Authorization.Subject, String, String, String)} reads that union by one point read, so the fan-out over a tenant's
 * groups is paid here, on the write that changes membership or a group's grants, and never on a request.
 *
 * <p>Every membership records the source that made it, so an operator's hand and each directory integration
 * reconcile their own rows without undoing one another's.
 */
public final class GroupMembership {

    /** {@code System.Logger} rather than SLF4J: this module is java.base plus the store, and the one thing it has
     *  to say is a best-effort repair that did not happen. */
    private static final System.Logger LOGGER = System.getLogger(GroupMembership.class.getName());

    /** The source a membership carries when nobody says otherwise: an operator put them there, and no
     *  reconciliation may take them out again. */
    public static final String MANUAL = "manual";

    /** How many subjects a derivation walk takes at a time. Small objects, off the request path: the page exists
     *  so the walk is bounded in memory, not because the count is tuned. */
    private static final int DERIVE_PAGE = 500;

    private final CredentialSpace space;

    private final PrincipalTenants tenants;

    GroupMembership(CredentialSpace space, PrincipalTenants tenants) {
        this.space = space;
        this.tenants = tenants;
    }

    /**
     * Put {@code principal} in {@code group}, and give them the group's rights from the next request.
     *
     * <p>The group is not required to exist first, and that is deliberate rather than lax: a group with members and
     * no grants confers nothing, so there is no state here in which an unmade decision reads as access. It is also
     * the order an identity provider pushes in, which would otherwise need a create-then-fill dance this cannot
     * make atomic anyway.
     *
     * <p>Re-deriving the one principal is part of the write. A membership that took effect on the next repair
     * rather than the next request would be a grant that is real in the store and absent from every decision,
     * which is the failure mode this whole model exists to remove.
     */
    public void addMember(String tenant, String group, String principal) throws IOException {
        addMember(tenant, group, principal, MANUAL);
    }

    /**
     * Put {@code principal} in {@code group}, recording which {@code source} says so.
     *
     * <p><b>The source is what lets four sources of one concept coexist.</b> Group membership arrives from an
     * OIDC {@code groups} claim, a SAML attribute, a SCIM push, an LDAP search and an operator's own hand, and
     * each of them reconciles: it removes the memberships it no longer sees. Without a source recorded on the row,
     * the first mechanism to reconcile would delete every membership the others had made - an operator's manual
     * grant silently undone by the next sign-in, which is the failure that makes a directory integration
     * untrustworthy rather than merely wrong.
     *
     * <p>So {@link #reconcile} only ever touches rows carrying its own source, and a person may be in one group by
     * claim and another by hand without the two knowing about each other.
     */
    public void addMember(String tenant, String group, String principal, String source) throws IOException {
        space.require();
        Authorization.Subject member = Authorization.Subject.principal(principal);   // rejects a traversal id
        Properties recorded = new Properties();
        recorded.setProperty("joined", Instant.now().toString());
        recorded.setProperty("source", source == null || source.isBlank() ? MANUAL : source.trim());
        space.write(CredentialSpace.memberPath(tenant, group, member.id()), recorded);
        rederive(tenant, member.id());
    }

    /**
     * Make {@code source}'s view of which groups {@code principal} is in the truth, and change nothing else.
     *
     * <p>This is the seam every directory integration plugs into, defined over "the groups this principal has"
     * rather than over any one mechanism's way of saying it - because LDAP groups, OIDC {@code groups} claims,
     * SAML attributes and SCIM group memberships are four spellings of one concept, and specified any other way it
     * would be built four times.
     *
     * <p>It adds what is missing and removes what this source no longer names, leaving every other source's rows
     * and the operator's own alone. A group the source names need not exist first: one with members and no grants
     * confers nothing.
     */
    public void reconcile(String tenant, String principal, Set<String> groups, String source) throws IOException {
        space.require();
        String owner = source == null || source.isBlank() ? MANUAL : source.trim();
        Authorization.Subject member = Authorization.Subject.principal(principal);
        for (String group : groups) {
            addMember(tenant, group, member.id(), owner);
        }
        for (String group : groups(tenant)) {
            if (groups.contains(group)) {
                continue;
            }
            Properties recorded = space.read(CredentialSpace.memberPath(tenant, group, member.id()));
            if (recorded != null && owner.equals(recorded.getProperty("source", MANUAL))) {
                removeMember(tenant, group, member.id());
            }
        }
    }

    /** Take {@code principal} out of {@code group}, and take the group's rights off them from the next request.
     *  Silent when they were not a member, so a repeated removal and one racing another answer the same way. */
    public void removeMember(String tenant, String group, String principal) throws IOException {
        space.require();
        Authorization.Subject member = Authorization.Subject.principal(principal);
        space.remove(CredentialSpace.memberPath(tenant, group, member.id()));
        rederive(tenant, member.id());
    }

    /** One page of a group's member ids, in key order - the same shape and cursor rule as
     *  {@link Authorization#subjects}. */
    public Authorization.SubjectPage members(String tenant, String group, String after, int limit) {
        return space.subjects(CredentialSpace.membersPrefix(tenant, group), after, limit);
    }

    /**
     * Recompute what {@code principal} holds through the groups of {@code tenant}, and write it down.
     *
     * <p>This is the fan-out, moved off the authorization path and paid where it is affordable. It walks the
     * tenant's groups - an operator-created set, so tens rather than millions - and unions the grants of the ones
     * this principal belongs to. A scope granted by two groups keeps both their rights, joined, because a union is
     * the only answer that does not depend on which group was read first.
     *
     * <p>Idempotent, so the repair and the write path can both call it and a second call changes nothing. It
     * writes even when the union is empty, because the absence of the document and an empty one mean different
     * things to a reader that has to distinguish "no groups" from "never derived".
     *
     * <p>The principal's tenant index is reconciled with what they now hold here, so a tenant reached only through a
     * group is listed as one reached by a direct grant is, and leaves the list when neither remains.
     */
    public void rederive(String tenant, String principal) throws IOException {
        space.require();
        Authorization.Subject subject = Authorization.Subject.principal(principal);
        Properties union = new Properties();
        for (String group : groups(tenant)) {
            if (!space.store().exists(CredentialSpace.memberPath(tenant, group, subject.id()))) {
                continue;
            }
            Properties held = space.read(CredentialSpace.grantsPath(tenant, Authorization.Subject.group(group)));
            if (held == null) {
                continue;
            }
            for (String scope : held.stringPropertyNames()) {
                String already = union.getProperty(scope);
                union.setProperty(scope, already == null ? held.getProperty(scope)
                        : already + "," + held.getProperty(scope));
            }
        }
        space.write(CredentialSpace.derivedPath(tenant, subject), union);
        tenants.reconcile(tenant, subject);
    }

    /**
     * Recompute every member of {@code group} - what a change to the group's own rights obliges.
     *
     * <p>Bounded by the group's membership rather than by the tenant's population, and off the request path: a
     * grant an operator gives a group of five hundred costs five hundred small writes once, against five hundred
     * fan-outs on every request for as long as the grant stands.
     */
    public void rederiveGroup(String tenant, String group) throws IOException {
        for (String cursor = null;;) {
            Authorization.SubjectPage page = members(tenant, group, cursor, DERIVE_PAGE);
            for (String member : page.ids()) {
                rederive(tenant, member);
            }
            if (page.next() == null) {
                return;
            }
            cursor = page.next();
        }
    }

    /**
     * Recompute every principal of {@code tenant} - the repair, for the drift a partial write leaves behind.
     *
     * <p>A node that dies between writing a group's grants and re-deriving the last of its members leaves those
     * members holding what the group used to grant. Nothing detects that on a read, because a stale derived
     * document is a well-formed one; so it is recomputed on a cadence rather than checked. Every principal, not
     * every member of every group, because a principal whose last group dropped them has a derived document
     * nothing else would ever revisit.
     */
    public void rederive(String tenant) throws IOException {
        for (String cursor = null;;) {
            Authorization.SubjectPage page = space.subjects(
                    CredentialSpace.kindPrefix(tenant, Authorization.Kind.PRINCIPAL), cursor, DERIVE_PAGE);
            for (String principal : page.ids()) {
                rederive(tenant, principal);
            }
            if (page.next() == null) {
                return;
            }
            cursor = page.next();
        }
    }

    /**
     * Recompute every principal of every tenant this store holds grants for, and never fail a boot doing it.
     *
     * <p><b>The repair runs at start-up, because start-up is when the drift it repairs has just happened.</b> The
     * only way a derived document goes stale is a node dying between writing a group's grants and re-deriving the
     * last of its members - every ordinary path re-derives before it returns - and a process that died is a
     * process that comes back. A weekly sweep would leave the window open for a week to fix something a restart
     * closes in seconds, and would need a scheduler this module does not have: the walk's consumers are handed a
     * repository-scoped store and no tenant, and the rebuild driver is switched off in the composition that
     * has a scheduler of its own.
     *
     * <p>Best effort by construction. A read-only deployment cannot write a derived document and must still start;
     * so must a deployment whose store is briefly unreachable. What a failure costs is the repair, not the boot,
     * and it says so in the log rather than in an exception nobody can act on at that moment.
     *
     * <p>The tenants come from the auth space itself rather than from the tenancy SPI, which is what keeps this one
     * method rather than one per tenancy mode: a tenant with no subjects has nothing to re-derive, and a tenant
     * that has any is here by definition.
     *
     * <p>It is also what builds a principal's tenant index where none has been kept yet: every principal of every
     * tenant is reconciled as it is re-derived, so a start-up leaves every index naming each tenant its principal
     * holds a grant in.
     */
    public void repairDerivedGrants() {
        if (!space.enforcing()) {
            return;
        }
        try {
            for (String tenant : space.store().list(CredentialSpace.AUTH)) {
                if (!CredentialSpace.PRINCIPALS.equals(tenant)) {
                    rederive(tenant);
                }
            }
        } catch (IOException | RuntimeException failed) {
            // Deliberately everything, for the reason the javadoc gives: a store that refuses this write is a
            // deployment that must still serve, and the cost of the failure is a group grant that may be stale
            // until the next start - not a node that will not come up.
            LOGGER.log(System.Logger.Level.WARNING, "Could not repair group-derived grants at start-up; a member "
                    + "whose derivation was interrupted may hold what their group used to grant, and a principal "
                    + "granted before the tenant index was kept may be missing tenants from it, until this "
                    + "succeeds", failed);
        }
    }

    /** Every member of a group, for the callers that must act on the whole set rather than a page of it. */
    List<String> allMembers(String tenant, String group) {
        return all(CredentialSpace.membersPrefix(tenant, group));
    }

    /** Every group name in {@code tenant}. Operator-created and therefore enumerable - and read whole only off
     *  the request path, which is the difference between this and the fan-out the derived document removes. */
    private List<String> groups(String tenant) {
        return all(CredentialSpace.kindPrefix(tenant, Authorization.Kind.GROUP));
    }

    private List<String> all(String prefix) {
        List<String> all = new ArrayList<>();
        for (String cursor = null;;) {
            Authorization.SubjectPage page = space.subjects(prefix, cursor, DERIVE_PAGE);
            all.addAll(page.ids());
            if (page.next() == null) {
                return all;
            }
            cursor = page.next();
        }
    }
}
