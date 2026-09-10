package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;

/**
 * The people this deployment has seen sign in, recorded as they arrive so that an administrator can grant to them
 * from a list.
 *
 * <h2>The problem it exists for</h2>
 * A person is named here by a provider-qualified stable id - {@code oidc/8f3c1a...}, a subject an identity provider
 * mints and nobody can guess, remember or type. Granting access therefore used to need somebody to obtain that
 * opaque string out of band, and the console made it worse by refusing the sign-in that would have produced it: the
 * deployment had never seen the person, so there was nothing to grant to, and there never would be. That is the
 * chicken-and-egg an open sign-in dissolves, and this is the half that turns it into an administrator's list rather
 * than a string somebody has to be told.
 *
 * <h2>Where a sighting lives</h2>
 * A sighting is the subject's <em>label</em> at the deployment scope - the same metadata document a granted subject
 * carries, in the same key space, so a person who is later granted something does not become a second record. It is
 * deployment-scoped rather than per-tenant because a person who holds nothing is a member of no tenant, and per
 * tenant there would be nowhere to put them; listing them is correspondingly a deployment-wide read, for whoever may
 * make a deployment-wide decision.
 *
 * <h2>It is bookkeeping, and it never fails a sign-in</h2>
 * A recording that could refuse a sign-in would be the refusal this design just removed, wearing a different hat -
 * and it would refuse on a read-only deployment, where a person may legitimately sign in and where nothing can be
 * written at all. So a failure is logged and swallowed. The cost is one cached point read per sign-in and a write
 * only when the deployment has not seen this person before, or when their display name has changed.
 */
public class KnownPrincipals {

    private static final System.Logger LOGGER = System.getLogger(KnownPrincipals.class.getName());

    private final Authorization authorization;

    public KnownPrincipals(Authorization authorization) {
        this.authorization = authorization;
    }

    /**
     * Note that {@code qualifiedId} has signed in, under the display name it presented.
     *
     * <p>The display name is stored and never matched on - it exists so an administrator granting access reads
     * "ada (github/1024025)" rather than a number. Every authority decision in this product is made on the id.
     */
    public void record(String qualifiedId, String displayName) {
        if (qualifiedId == null || qualifiedId.isBlank()) {
            return;
        }
        String label = displayName == null || displayName.isBlank() ? qualifiedId : displayName;
        try {
            Authorization.Subject subject = Authorization.Subject.principal(qualifiedId);
            // Read first: a returning person costs a cached point read and no write at all, which matters because
            // this is on the sign-in path of every mechanism.
            if (authorization.label(Authorization.DEPLOYMENT, subject).filter(label::equals).isPresent()) {
                return;
            }
            authorization.setLabel(Authorization.DEPLOYMENT, subject, label);
        } catch (IOException | RuntimeException failed) {
            // Deliberately everything. This runs inside the sign-in of every mechanism, and the store can refuse a
            // write for reasons that are not defects: a read-only deployment answers ReadOnlyException, which is
            // unchecked, and a person may legitimately sign in to one. An exception escaping here would be the
            // refusal this whole design removed, re-arriving as a stack trace on the login page - so the failure
            // mode of bookkeeping is a log line and a list that is missing one row.
            LOGGER.log(System.Logger.Level.DEBUG, "Could not record the sign-in of " + qualifiedId
                    + "; they are signed in regardless and an administrator can still grant to their id", failed);
        }
    }

    /** One person this deployment has seen: the id to grant to, and the name they signed in under. */
    public record Seen(String id, String label) {
    }

    /**
     * A page of the people this deployment has seen, in the store's own order, {@code after} exclusive.
     *
     * <p>Paged rather than listed because it grows with everyone who has ever signed in, and a screen that read all
     * of them would cost more on the deployment where it is most useful.
     */
    public List<Seen> page(String after, int limit) {
        List<Seen> seen = new ArrayList<>();
        for (String id : authorization.subjects(Authorization.DEPLOYMENT, Authorization.Kind.PRINCIPAL,
                after, limit).ids()) {
            String label;
            try {
                label = authorization.label(Authorization.DEPLOYMENT,
                        Authorization.Subject.principal(id)).orElse(id);
            } catch (IOException unreadable) {
                label = id;   // a row whose name cannot be read is still a row an administrator can grant to
            }
            seen.add(new Seen(id, label));
        }
        return List.copyOf(seen);
    }
}
