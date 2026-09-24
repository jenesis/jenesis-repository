/**
 * Tests for the key-based console sign-in module: the issued-key store (hash-at-rest, resolve, revoke, concurrency),
 * the authentication provider (env admin key to super-admin, issued key to its tenant role, invalid key refused,
 * rate-limit and audit), the operator admin API (super-admin gate, membership binding) and ServiceLoader discovery of
 * the mechanism and its settings. Pure in-JVM over a real {@code Documents} store; nothing external, so it always
 * runs.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.auth.keylogin
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.auth.keylogin.test {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.auth.keylogin;
    requires build.jenesis.repository.ui.identity;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.cache.storage.delegating;
    requires build.jenesis.repository.cache.storage.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.maintenance;
    requires spring.security.core;
    requires spring.web;
    requires org.junit.jupiter;
    requires org.assertj.core;
    uses build.jenesis.repository.ui.ConsoleModuleProvider;
    uses build.jenesis.repository.settings.SettingsContributor;
    uses build.jenesis.repository.maintenance.StorageNamespace;
}
