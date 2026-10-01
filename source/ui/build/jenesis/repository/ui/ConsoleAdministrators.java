package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.posture.ConsoleAdmins;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Who administers this deployment, read from grants rather than from a setting: {@code jenrepo.ui.admins} has one
 * reader, and what it answers is a grant in the same store as a minted key's, matched by the same code.
 *
 * <p>The setting is a seed: on every boot each named id is granted every right at the deployment scope, as
 * {@code jenrepo.bootstrap-key} is re-provisioned. Removing an id does not revoke the grant, since a reconciling seed
 * would undo every grant made through the console; and an administrator granted through the API is as real as a
 * seeded one.
 *
 * <p>Seeding writes, so a read-only deployment ({@code jenrepo.read-only=true}) that names an administrator refuses to
 * boot, naming the id, rather than report a grant it could not make. The wildcard {@code *} names no holder and is
 * refused ({@link ConsoleAdmins#refusal()}).
 */
public class ConsoleAdministrators {

    private static final System.Logger LOGGER = System.getLogger(ConsoleAdministrators.class.getName());

    /** Every right, on every repository: what "administers the deployment itself" means as a grant. */
    private static final String EVERYTHING = "*";

    private final Authorization authorization;

    /**
     * Over {@code store}, seeded from a configured {@code jenrepo.ui.admins} value. It takes the value rather than a
     * properties object because the two consoles bind that prefix with their own, disjoint configuration types.
     */
    public ConsoleAdministrators(ArtifactStore store, String configuredAdmins) {
        this(Authorization.enforcing(store), ConsoleAdmins.parse(configuredAdmins));
    }

    /**
     * Over an {@link Authorization} the composition already holds, so its grants are read through one cache rather than
     * two that disagree for a window.
     */
    public ConsoleAdministrators(Authorization authorization, String configuredAdmins) {
        this(authorization, ConsoleAdmins.parse(configuredAdmins));
    }

    /**
     * Over an already parsed value; the constructor the other two delegate to.
     */
    public ConsoleAdministrators(Authorization authorization, Set<String> seeded) {
        this.authorization = authorization;
        if (ConsoleAdmins.carriesWildcard(seeded)) {
            throw new IllegalStateException(ConsoleAdmins.refusal());
        }
        seed(seeded);
    }

    /**
     * Writes the configured ids as deployment-wide grants, idempotently. A failure names the id.
     */
    private void seed(Set<String> ids) {
        for (String id : ids) {
            try {
                authorization.setGrant(Authorization.DEPLOYMENT,
                        Authorization.Subject.principal(id), EVERYTHING, EVERYTHING);
            } catch (IOException | RuntimeException failed) {
                // Unchecked too: a read-only store answers ReadOnlyException, which must name the id it refused.
                throw new IllegalStateException("jenrepo.ui.admins names '" + id + "', which could not be granted "
                        + "administration of this deployment: " + failed.getMessage(), failed);
            }
        }
        if (!ids.isEmpty()) {
            LOGGER.log(System.Logger.Level.INFO, "Granted deployment administration to {0} configured id(s) from "
                    + "jenrepo.ui.admins. The setting SEEDS these grants on every boot; it does not remove one that "
                    + "is dropped from it, and an administrator granted through the API is equally real.",
                    ids.size());
        }
    }

    /**
     * Whether this provider-qualified id administers the deployment: a point read of its deployment-wide grant, which
     * sees a grant made through the API since boot.
     */
    public boolean is(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            return authorization.authorize(Authorization.DEPLOYMENT,
                    Authorization.Subject.principal(id), null, Authorization.MANAGE_WRITE)
                    == Authorization.Decision.ALLOWED;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read whether " + id + " administers this deployment",
                    unreadable);
        } catch (IllegalArgumentException notASubject) {
            return false;   // an id that cannot name a subject administers nothing
        }
    }
}
