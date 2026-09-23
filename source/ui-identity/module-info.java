/**
 * The admin console's identity layer: the per-tenant membership directory and its reverse user-to-tenants index
 * ({@code UserDirectory}, {@code MembershipIndex}), the deployment's super-admin set ({@code Superadmins}), the
 * provider-independent login decision every sign-in mechanism shares ({@code LoginAuthorization}), what counts as
 * the starter credential ({@code StarterCredential}) and the console's own {@code jenreg.ui.*} properties
 * ({@code UiProperties}), with the one configuration class a console imports to declare them as beans.
 *
 * <p>It exists because these classes lived in the admin console's own module, exported package by package to
 * the modules that sign a person in - key login, SAML, OIDC - and to the SCIM provisioner, so each of those required
 * the whole console to reach five classes, and the console and its sign-in modules could only ever move together.
 * Measured 2026-09-20: thirteen imports across the four, every one of them one of these classes. What the layer
 * itself needs is the authorization store's contract, the store's documents, the console module's seams (the current
 * tenant, the administrators, the known principals, the login authorities) and the domain layer's tenant marker;
 * Spring Security's authority type for the two classes that speak it, and Spring Boot's properties binding for the
 * one that is bound.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui.identity {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.scope;
    requires transitive build.jenesis.repository.store;
    requires org.slf4j;
    requires spring.beans;
    requires spring.boot;
    requires spring.context;
    requires spring.security.core;
    exports build.jenesis.repository.ui.identity to build.jenesis.repository.ui.identity.test,
            build.jenesis.repository.ui.admin,
            build.jenesis.repository.auth.keylogin,
            build.jenesis.repository.auth.saml,
            build.jenesis.repository.auth.oidc,
            build.jenesis.repository.scim,
            build.jenesis.repository.ui.admin.test,
            build.jenesis.repository.ui.admin.enterprise.test,
            build.jenesis.repository.ui.admin.browser.test,
            build.jenesis.repository.auth.keylogin.test,
            build.jenesis.repository.auth.oidc.test,
            build.jenesis.repository.auth.saml.test,
            build.jenesis.repository.bundle.full.test,
            build.jenesis.repository.server.kernel.test;
}
