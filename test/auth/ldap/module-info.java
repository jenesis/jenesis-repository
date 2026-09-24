/**
 * Directory sign-in in isolation over a real filesystem store: the configurations refused at boot and why, a
 * directory account signed in under a case-folded {@code ldap/} id with its groups reconciled into each configured
 * tenant, an administrators group conferring super-admin, a refused password and a throttled client each failing
 * and audited, and the console role a group's grant confers reaching its members. The directory itself is a fake
 * here; the real bind against an OpenLDAP server is proved by a container suite.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.auth.ldap
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.auth.ldap.test {
    requires build.jenesis.repository.auth.ldap;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.identity;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires spring.security.core;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
