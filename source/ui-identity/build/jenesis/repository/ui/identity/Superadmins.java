package build.jenesis.repository.ui.identity;

import module java.base;

import build.jenesis.repository.ui.ConsoleAdministrators;

/**
 * The super-admins: provider-qualified ids ({@code <provider>/<id>}) that administer every tenant and alone may create,
 * delete and see all tenants. A super-admin is matched only on the provider-verified stable id, never a mutable display
 * login, so acquiring someone's handle grants nothing.
 *
 * <p><b>There is no {@code *} wildcard</b>; it is refused under every routing. An administrator is a holder of rights,
 * and a wildcard names no holder an operator could read back, revoke or list. Refused rather than ignored, since
 * ignoring it would leave the operator believing they granted something while nobody holds super-admin.
 */
public class Superadmins {

    private final ConsoleAdministrators administrators;

    public Superadmins(ConsoleAdministrators administrators) {
        this.administrators = administrators;
    }

    /** Whether this provider-qualified id administers the deployment - one point read of its deployment-wide grant, the
     *  answer the console's authority policy gets from the same place. */
    public boolean is(String id) {
        return administrators.is(id);
    }
}
