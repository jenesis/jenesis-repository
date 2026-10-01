/**
 * The admin console's identity layer: the per-tenant membership directory ({@code UserDirectory}), the super-admin set
 * ({@code Superadmins}), the login decision every sign-in mechanism shares ({@code LoginAuthorization}), the starter
 * credential ({@code StarterCredential}) and the console's {@code jenrepo.ui.*} properties ({@code UiProperties}), with
 * the configuration a console imports to declare them as beans.
 *
 * <p>A module of its own so key login, SAML, OIDC and the SCIM provisioner reach these classes without requiring the
 * whole console. It needs the authorization store's contract, the store's documents, the console module's seams, the
 * domain layer's tenant marker, Spring Security's authority type and Spring Boot's properties binding.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui.identity {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.server.spi;
    requires transitive build.jenesis.repository.store;
    requires spring.beans;
    requires spring.boot;
    requires spring.context;
    requires spring.security.core;
    exports build.jenesis.repository.ui.identity;
}
