package build.jenesis.repository.ui.identity;

import module java.base;

import build.jenesis.repository.ui.ConsoleAdministrators;

/**
 * The env-configured super-admins ({@code JENREPO_UI_ADMINS}): provider-qualified ids ({@code <provider>/<id>})
 * that are admins of every tenant and the only ones who may create, delete and see all tenants. The set is fixed at
 * deploy time and overrides any stored role, so a deployment cannot lock itself out. A super-admin is matched only on
 * the provider-verified stable id, never on a mutable display login (a reclaimable username or an unverified email),
 * so acquiring a super-admin's handle does not grant super-admin.
 *
 * <p><b>There is no {@code *} wildcard.</b> Under any multi-tenant routing it would make every member of any tenant
 * an administrator of all of them, and it is refused under every routing alike: an administrator is a
 * holder of rights, and a wildcard names no holder, so there is nothing an operator can read back, revoke, or see
 * in a list of who administers this deployment. "Everyone this deployment authenticates holds X" is a real need
 * and gets a holder of its own; it does not get a magic value inside a list of ids.
 *
 * <p>Refused rather than ignored, which is the part that was already right: ignoring fails in both directions at
 * once - the operator believes they granted something, the advisory told them they had, and in fact nobody holds
 * super-admin, which locks tenant administration rather than opening it.
 */
public class Superadmins {

    private final ConsoleAdministrators administrators;

    public Superadmins(ConsoleAdministrators administrators) {
        this.administrators = administrators;
    }

    /** Whether this provider-qualified id administers the deployment - one point read of that principal's
     *  deployment-wide grant, the same answer the console's authority policy gets from the same place. */
    public boolean is(String id) {
        return administrators.is(id);
    }
}
