package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.posture.ConsoleAdmins;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Who administers this deployment - one reader, over grants rather than over a setting.
 *
 * <h2>One key, three readers, three meanings</h2>
 * {@code jenreg.ui.admins} was read in three places that had drifted: the free console honoured its wildcard, the
 * downstream super-admin set refused it, and the security advisory asserted the first regardless. The parse was
 * unified first, which stopped them disagreeing about what was <em>written</em>; they still each decided for
 * themselves what it <em>granted</em>. This is the other half: there is one reader, and what it answers is a
 * grant - the same thing a minted key holds, in the same store, matched by the same code.
 *
 * <h2>The setting is a seed, not the source of truth</h2>
 * At construction - every boot, before the console serves anything - each named id is granted every right at the
 * deployment scope, exactly as {@code jenreg.bootstrap-key} is re-provisioned on every boot for as long as it is
 * set. Two consequences an operator has to know, and both are the price of a seed rather than a mirror:
 *
 * <ul>
 *   <li><b>Removing an id from the list does not remove their admin.</b> The grant stands until it is revoked
 *       through the API, because nothing here deletes what it did not just write - a seed that reconciled would
 *       silently undo every grant made through the console, which is the surface an operator is told to use.</li>
 *   <li><b>An administrator granted through the API is a real administrator</b>, listed and revocable, whether or
 *       not the setting ever mentioned them. That is the point of the model: the answer lives somewhere an
 *       operator can read it back.</li>
 * </ul>
 *
 * <h2>A read-only deployment that names an administrator refuses to boot</h2>
 * Seeding is a store write, so under {@code jenreg.read-only=true} it is refused and the console does not start -
 * naming the id it could not grant. That is the same answer {@code jenreg.bootstrap-key} already gives, and for
 * the same reason: a named administrator who holds nothing is the failure that reports success in both directions
 * at once, with the operator believing they granted something. A read-only deployment that leaves the setting
 * empty starts normally and reads back whatever grants the store already holds.
 *
 * <h2>No wildcard</h2>
 * {@code *} is refused before this can be asked - see {@link ConsoleAdmins#refusal()}. An administrator is a holder
 * of rights, and the wildcard names no holder.
 */
public class ConsoleAdministrators {

    private static final System.Logger LOGGER = System.getLogger(ConsoleAdministrators.class.getName());

    /** Every right, on every repository: what "administers the deployment itself" means as a grant. */
    private static final String EVERYTHING = "*";

    private final Authorization authorization;

    /**
     * Over {@code store}, seeded from a configured {@code jenreg.ui.admins} value.
     *
     * <p>It takes the <em>value</em> rather than a properties object on purpose. The two consoles bind that prefix
     * with their own configuration types - disjoint keys, deliberately not merged - so a shared reader that named
     * one of them would either drag a console's config surface into the other or quietly bind the wrong one. The
     * value is the only thing they agree on, so the value is what crosses the seam.
     */
    public ConsoleAdministrators(ArtifactStore store, String configuredAdmins) {
        this(Authorization.enforcing(store), ConsoleAdmins.parse(configuredAdmins));
    }

    /**
     * Over an {@link Authorization} a composition already holds, seeded from a configured {@code jenreg.ui.admins}
     * value.
     *
     * <p>The one to take where the deployment has an authorization bean of its own: a second instance over the same
     * store would carry a second cache, and two caches over one set of grants disagree for as long as the shorter
     * of their windows.
     */
    public ConsoleAdministrators(Authorization authorization, String configuredAdmins) {
        this(authorization, ConsoleAdmins.parse(configuredAdmins));
    }

    /**
     * For a caller that has already parsed the value - and for a test that wants to say what was seeded without
     * going through a properties object. The seeding and the refusal are the same either way: this is the
     * constructor the other two delegate to.
     */
    public ConsoleAdministrators(Authorization authorization, Set<String> seeded) {
        this.authorization = authorization;
        if (ConsoleAdmins.carriesWildcard(seeded)) {
            throw new IllegalStateException(ConsoleAdmins.refusal());
        }
        seed(seeded);
    }

    /**
     * Write the configured ids as deployment-wide grants. Idempotent, so re-applying on every boot writes the same
     * rows; a failure names the id rather than leaving the deployment half-seeded and silent about which half.
     */
    private void seed(Set<String> ids) {
        for (String id : ids) {
            try {
                authorization.setGrant(Authorization.DEPLOYMENT,
                        Authorization.Subject.principal(id), EVERYTHING, EVERYTHING);
            } catch (IOException | RuntimeException failed) {
                // Including the unchecked ones: a read-only store answers ReadOnlyException, and that is precisely
                // the case this message exists for - the refusal has to name the id it could not grant, or an
                // operator reads a bare "writes are refused" with no way to tell which setting caused it.
                throw new IllegalStateException("jenreg.ui.admins names '" + id + "', which could not be granted "
                        + "administration of this deployment: " + failed.getMessage(), failed);
            }
        }
        if (!ids.isEmpty()) {
            LOGGER.log(System.Logger.Level.INFO, "Granted deployment administration to {0} configured id(s) from "
                    + "jenreg.ui.admins. The setting SEEDS these grants on every boot; it does not remove one that "
                    + "is dropped from it, and an administrator granted through the API is equally real.",
                    ids.size());
        }
    }

    /**
     * Whether this provider-qualified id administers the deployment.
     *
     * <p>A point read of that principal's deployment-wide grant, so it costs one small object however many
     * administrators there are - and it sees a grant made through the API since boot, which reading a setting
     * never could.
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
