/**
 * Key-based console sign-in as a removable module: it provides {@link build.jenesis.repository.ui.ConsoleModuleProvider},
 * so the console imports its configuration through {@code ServiceLoader} discovery and names no mechanism. It adds a
 * "Sign in with a key" option to {@code /login} - the way in before single sign-on is set up, on unless
 * {@code jenreg.key-login=false}. Three key sources: the env bootstrap admin key ({@code JENREG_UI_ADMIN_KEY}, full
 * super-admin), the one-time key a deployment nobody can sign in to yet prints at start, and admin-issued login keys
 * bound to a principal's tenant role, the last two stored hashed through the store. A valid key yields the same Spring Security session an OIDC or LDAP login does, so every
 * per-tenant authorization rule applies to it unchanged. With the module absent or the switch off, only the other mechanisms are
 * offered and the production chain is untouched. A {@code ConsoleModuleProvider} sibling to {@code auth/oidc} and
 * {@code auth/ldap}, and the way into a deployment's console before either is configured.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.auth.keylogin {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.scope;
    exports build.jenesis.repository.auth.keylogin to build.jenesis.repository.auth.keylogin.test,
            build.jenesis.repository.auth.keylogin.e2e;
    requires build.jenesis.repository.ui.identity;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.maintenance;
    requires org.slf4j;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.security.config;
    requires spring.security.core;
    requires spring.security.web;
    requires thymeleaf;
    requires thymeleaf.spring6;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.auth.keylogin.KeyLoginMechanism;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.auth.keylogin.KeyLoginSettingsContributor;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.auth.keylogin.KeyLoginStorageNamespace;
}
