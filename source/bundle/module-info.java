/**
 * The carrier: one launchable module whose {@code requires} closure is every free SPI implementation -
 * all twenty-five layouts, retention, staging, the scheduled walk, the settings and management APIs, the build cache
 * for Jenesis builds, all four store backends
 * ({@code filesystem}, {@code s3}, {@code gcs}, {@code azure}), all five import connectors, the upstream HTTP
 * fetcher ({@code proxy}), the OIDC token exchange ({@code oidc}), the token-bucket rate limiter, the credential
 * usage tracker, the publish gate with its review screens (screening against OSV, the GitHub Advisory Database
 * and the OpenSSF malicious-packages records once an operator names them), signed webhooks for what the gate
 * decided, and the web console ({@code ui}) - so the packaging {@code bundle} step emits a {@code bundle.zip}
 * carrying the complete free product, and the {@code Dockerfile} turns that one zip into the image.
 * Nothing here names a plugin: the server keeps discovering everything through {@code ServiceLoader}, and the image
 * is trimmed by configuration instead of rebuilt - {@code jenreg.<feature>=false} (settable as
 * {@code JENREG_<FEATURE>=false} through relaxed binding) disables an implementation exactly as if its
 * module were absent, and {@code jenreg.<spi>=<feature>} selects among exclusive implementations
 * (the store defaults to {@code filesystem}), per the {@code build.jenesis.repository.store.Features} convention.
 *
 * <p>{@link build.jenesis.repository.bundle.Server} boots the repository server under the config name
 * {@code bundle} ({@code bundle.properties} in this module), because with the server and the console both on
 * the module path two root {@code application.properties} would be ambiguous;
 * so the one image also runs the console node via {@code MAINMODULE}/{@code MAINCLASS}.
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
    // Console sign-in against an LDAP or Active Directory server, off until jenreg.ui.ldap.url names one.
    requires build.jenesis.repository.auth.ldap;
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.usage;
    // Reclamation: the collector, and the walk consumer that runs it at the end of a pass. Without these an
    // installed collector is never called and a deployment's storage only ever grows.
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.gc.store;
    requires build.jenesis.repository.gc.walk;
    // The publish gate. The wiring is the arming, not the gate: without it on the graph the server imports no
    // screen and every publish is admitted as clean, so a composition that carries the gate carries this too.
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
    // The curated OpenSSF malicious-packages records: a typosquat is what actually reaches most users, and no
    // advisory database lists one as a vulnerability. Off until an operator names it, like the two above.
    requires build.jenesis.repository.compliance.openssf;
    requires build.jenesis.repository.compliance.policy;
    requires build.jenesis.repository.compliance.admission;
    // Where a verdict is kept, and the screens an operator reviews a hold on and releases it from. A hold nobody
    // can see or release is worse than none, so the review surface ships with the gate.
    requires build.jenesis.repository.findings.store;
    requires build.jenesis.repository.health.store;
    requires build.jenesis.repository.compliance.web;
    // Being told without looking: every hold, refusal and release is an event, and a configured endpoint receives
    // it as a signed, retried webhook. Nothing is sent until an operator names an endpoint.
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
    // The settings and management APIs the CLI and the first-run guide speak to, and the console's deploy screen.
    requires build.jenesis.repository.config.web;
    requires build.jenesis.repository.management.web;
    requires build.jenesis.repository.console.api;
    requires build.jenesis.repository.deploy.web;
    // Credentials for a private upstream, and the tokens AWS registries issue in place of one.
    requires build.jenesis.repository.upstream.store;
    requires build.jenesis.repository.upstream.aws;
    // The build cache for Jenesis builds, served beside the repository.
    requires build.jenesis.repository.cache.server;
    requires build.jenesis.repository.cache.protocol.jenesis;
    requires spring.boot;
    requires spring.context;
}
