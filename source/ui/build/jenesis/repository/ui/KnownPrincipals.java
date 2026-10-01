package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;

/**
 * The people this deployment has seen sign in, recorded as they arrive so an administrator can grant to them from a
 * list rather than obtain a provider's opaque subject id ({@code oidc/8f3c1a...}) out of band.
 *
 * <p>A sighting is the subject's label at the deployment scope, the same document a granted subject carries, so a
 * person later granted something is one record. It is deployment-scoped because a person who holds nothing is a member
 * of no tenant.
 *
 * <p>Recording never fails a sign-in, including on a read-only deployment: a failure is logged and swallowed. It costs
 * one cached point read per sign-in, and a write only for a new person or a changed display name.
 */
public class KnownPrincipals {

    private static final System.Logger LOGGER = System.getLogger(KnownPrincipals.class.getName());

    private final Authorization authorization;

    public KnownPrincipals(Authorization authorization) {
        this.authorization = authorization;
    }

    /**
     * Notes that {@code qualifiedId} signed in under the display name it presented. The name is shown, never matched
     * on; every authority decision is made on the id.
     */
    public void record(String qualifiedId, String displayName) {
        if (qualifiedId == null || qualifiedId.isBlank()) {
            return;
        }
        String label = displayName == null || displayName.isBlank() ? qualifiedId : displayName;
        try {
            Authorization.Subject subject = Authorization.Subject.principal(qualifiedId);
            // A returning person costs a cached point read and no write.
            if (authorization.label(Authorization.DEPLOYMENT, subject).filter(label::equals).isPresent()) {
                return;
            }
            authorization.setLabel(Authorization.DEPLOYMENT, subject, label);
        } catch (IOException | RuntimeException failed) {
            // Everything, ReadOnlyException included: bookkeeping never fails the sign-in it runs inside.
            LOGGER.log(System.Logger.Level.DEBUG, "Could not record the sign-in of " + qualifiedId
                    + "; they are signed in regardless and an administrator can still grant to their id", failed);
        }
    }

    /** One person this deployment has seen: the id to grant to, and the name they signed in under. */
    public record Seen(String id, String label) {
    }

    /**
     * A page of the people this deployment has seen, in the store's order, {@code after} exclusive; paged because it
     * grows with everyone who ever signed in.
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
