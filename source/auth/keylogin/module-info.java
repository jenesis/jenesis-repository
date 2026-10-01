/**
 * Key-based console sign-in as a removable module: a {@link build.jenesis.repository.ui.ConsoleModuleProvider} adding
 * "Sign in with a key" to {@code /login}, on unless {@code jenrepo.key-login=false} - the way in before single sign-on
 * is configured. Three key sources: the environment's admin key ({@code JENREPO_UI_ADMIN_KEY}, super-admin), the
 * one-time key a deployment nobody can sign in to prints at start, and issued keys bound to a principal's tenant role,
 * the last two stored hashed. A valid key yields the same session an OIDC or LDAP login does. Absent or switched off,
 * only the other mechanisms are offered.
 *
 * <p>Issued keys are administered through one implementation reached by the {@code /api/keylogin} routes (held to a
 * manage key of the operator tenant), the CLI's {@code keylogin} commands and the console's login keys screen.
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
    requires build.jenesis.repository.ui.store;
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
    requires spring.webmvc;
    requires jakarta.servlet;
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
