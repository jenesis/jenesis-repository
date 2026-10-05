/**
 * The carrier: one launchable module whose {@code requires} closure is every free SPI implementation - every layout,
 * retention, staging, the scheduled walk, search, the settings and management APIs, the build cache for Jenesis,
 * Gradle, Maven and Bazel builds, every store backend, every import connector, the upstream fetcher, the OIDC token
 * exchange, the rate limiter, the credential usage tracker, the publish gate with its review screens and advisory
 * sources, signed webhooks for what the gate decided, and the web console - so the image built from this module
 * carries the complete free product. Nothing here names a plugin: the server discovers everything through
 * {@code ServiceLoader}, and the image is trimmed by configuration rather than rebuilt -
 * {@code jenrepo.<feature>=false} (or {@code JENREPO_<FEATURE>=false}) disables an implementation as if its module
 * were absent, and {@code jenrepo.<spi>=<feature>} selects among exclusive ones, per the
 * {@code build.jenesis.repository.store.Features} convention.
 *
 * <p>{@link build.jenesis.repository.bundle.Server} boots the repository, the console and the build cache under the
 * config name {@code bundle}, because two modules on this path carry a root {@code application.properties}.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.repository.bundle.Server
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.bundle {
    exports build.jenesis.repository.bundle;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.admin;
    requires build.jenesis.repository.application;
    // The console's build-cache space resolves through this SPI, and the delegating provider is the default
    // every composition answers to: without it on the graph the console cannot open its own storage.
    requires build.jenesis.repository.cache.storage.delegating;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.s3;
    requires build.jenesis.repository.store.gcs;
    requires build.jenesis.repository.store.azure;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.importer.artifactory;
    requires build.jenesis.repository.importer.index;
    requires build.jenesis.repository.importer.jenesis;
    requires build.jenesis.repository.importer.maven;
    requires build.jenesis.repository.importer.nexus;
    requires build.jenesis.repository.oidc;
    // Console sign-in through GitHub or any OpenID Connect issuer, off until a provider is configured.
    requires build.jenesis.repository.auth.keylogin;
    requires build.jenesis.repository.auth.oidc;
    // Console sign-in against an LDAP or Active Directory server, off until jenrepo.ui.ldap.url names one.
    requires build.jenesis.repository.auth.ldap;
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.usage;
    // Reclamation: the collector, and the walk consumer that runs it at the end of a pass, without which storage
    // only grows.
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.gc.store;
    requires build.jenesis.repository.gc.walk;
    // The wiring arms the gate: without it the server imports no screen and admits every publish as clean.
    requires build.jenesis.repository.gate.wiring;
    // What the gate decides from: the package a Maven or OCI publish names, the two advisory databases every
    // ecosystem is covered by (both switched off until an operator names them - nothing is fetched unasked), and
    // the operator-authored and attestation dimensions.
    requires build.jenesis.repository.compliance.maven;
    requires build.jenesis.repository.compliance.oci;
    // An image is recorded in the inventory only through this layout, and a version the inventory cannot name
    // cannot be held after the fact - by a later advisory or by a scanner's report about its layers.
    requires build.jenesis.repository.format.oci.inventory;
    requires build.jenesis.repository.compliance.osv;
    requires build.jenesis.repository.compliance.github;
    // The OpenSSF malicious-packages records, since no advisory database lists a typosquat as a vulnerability. Off
    // until an operator names it, like the two above.
    requires build.jenesis.repository.compliance.openssf;
    requires build.jenesis.repository.compliance.policy;
    requires build.jenesis.repository.compliance.admission;
    // Where a verdict is kept, and the screens an operator reviews and releases a hold on, which ship with the gate.
    requires build.jenesis.repository.findings.store;
    requires build.jenesis.repository.health.store;
    requires build.jenesis.repository.compliance.web;
    // A version published here is screened through its closure, which this resolves from what the store holds.
    requires build.jenesis.repository.closure;
    // A Maven release's closure as Maven resolves it, over the POMs the repository holds.
    requires build.jenesis.repository.closure.maven;
    // A lock file an npm package or a crate carries, taken as its closure as written.
    requires build.jenesis.repository.closure.lock;
    // How a requirement in a Maven, npm, Cargo or Composer manifest is read, for the closure and the dependents index.
    requires build.jenesis.repository.dependents.requirements;
    // Every hold, refusal and release as a signed, retried webhook, sent once an operator names an endpoint.
    requires build.jenesis.repository.webhook;
    requires build.jenesis.repository.webhook.web;
    // Every other format this tree serves, with the release signing Debian, RPM and Terraform need and the
    // lifecycle surface over the deprecate and yank marks. The free image serves what the free tree holds.
    requires build.jenesis.repository.format.apk;
    requires build.jenesis.repository.format.cargo;
    requires build.jenesis.repository.format.cocoapods;
    requires build.jenesis.repository.format.composer;
    requires build.jenesis.repository.format.conan;
    requires build.jenesis.repository.format.conda;
    requires build.jenesis.repository.format.debian;
    requires build.jenesis.repository.format.gems;
    requires build.jenesis.repository.format.go;
    requires build.jenesis.repository.format.helm;
    requires build.jenesis.repository.format.homebrew;
    requires build.jenesis.repository.format.huggingface;
    requires build.jenesis.repository.format.ivy;
    requires build.jenesis.repository.format.jvm;
    requires build.jenesis.repository.format.npm;
    requires build.jenesis.repository.format.nuget;
    requires build.jenesis.repository.format.pypi;
    requires build.jenesis.repository.format.rpm;
    requires build.jenesis.repository.format.swift;
    requires build.jenesis.repository.format.terraform;
    requires build.jenesis.repository.format.winget;
    requires build.jenesis.repository.format.signing;
    requires build.jenesis.repository.format.lifecycle.web;
    requires build.jenesis.repository.format.lifecycle.console;
    requires build.jenesis.repository.format.terraform.web;
    // Retention and the scheduled walk that runs it, staging and promotion, the stored metadata the passes keep,
    // the index and the download counter: the operation of a repository, not an organisation's extra.
    requires build.jenesis.repository.cleanup.task;
    requires build.jenesis.repository.cleanup.web;
    requires build.jenesis.repository.walk.store;
    requires build.jenesis.repository.walk.task;
    requires build.jenesis.repository.walk.web;
    requires build.jenesis.repository.staging.store;
    requires build.jenesis.repository.staging.web;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.index;
    requires build.jenesis.repository.index.web;
    requires build.jenesis.repository.downloads;
    // Search: a lookup by name in every repository, and the full-text index for a repository that switches it on.
    requires build.jenesis.repository.search.web;
    requires build.jenesis.repository.search.lucene;
    // The settings and management APIs the CLI and the first-run guide speak to, and the console's deploy screen.
    requires build.jenesis.repository.config.web;
    requires build.jenesis.repository.management.web;
    requires build.jenesis.repository.export;
    requires build.jenesis.repository.export.web;
    requires build.jenesis.repository.console.api;
    requires build.jenesis.repository.deploy.web;
    // The demo the first-run guide offers an empty deployment, loaded only when an operator confirms it.
    requires build.jenesis.repository.demo.web;
    // Credentials for a private upstream, and the tokens AWS registries issue in place of one.
    requires build.jenesis.repository.upstream.store;
    requires build.jenesis.repository.upstream.aws;
    // The build cache, served beside the repository, in every wire protocol it speaks: the Jenesis build tool's own,
    // Gradle's HTTP build cache, the Maven build-cache extension's layout and Bazel's HTTP remote cache.
    requires build.jenesis.repository.cache.server;
    requires build.jenesis.repository.cache.protocol.jenesis;
    requires build.jenesis.repository.cache.protocol.gradle;
    requires build.jenesis.repository.cache.protocol.maven;
    requires build.jenesis.repository.cache.protocol.bazel;
    requires spring.boot;
    requires spring.context;
}
