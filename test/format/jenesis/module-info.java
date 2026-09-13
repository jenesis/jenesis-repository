/**
 * Focused unit tests for the Jenesis module layout, driving {@link build.jenesis.repository.format.jenesis.JenesisFormat}
 * through an in-memory {@code FakeExchange} and calling {@link build.jenesis.repository.format.jenesis.ModuleViewPublisher}
 * directly, against a real {@code FilesystemArtifactStore} rooted at a JUnit
 * {@code @TempDir}: the {@code /module/} and {@code /artifact/} publish-and-serve round trip, and the cross-publish that
 * links a modular jar by module name and version (and by name alone) over one content-addressed blob.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.jenesis
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.13 SHA-256/aa0013fbcb7e8086f4775f3dcb69a6c6833f7fd9023fe027d2d0241834cc51d5
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.junit.jupiter 6.1.3 SHA-256/6b0a14075c74221de53047e363860b7d19fce802bebc152df2c35c4089c6c379
 * @jenesis.pin org.junit.jupiter/junit-jupiter 6.1.3 SHA-256/6b0a14075c74221de53047e363860b7d19fce802bebc152df2c35c4089c6c379
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.1.3 SHA-256/555d6cf20fa1710884dd01b86cc5785397ba73e21ada2d4b784f5f1a14dcafc4
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.1.3 SHA-256/414559e78bbfa3bdb2eab009ac3ab1e22369ac31117c270a0bd666da115c5c6c
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.1.3 SHA-256/05c51cedba2c06a9770707391e006cee825876937dea580b94a265ce145d4939
 * @jenesis.pin org.junit.platform.console 6.1.3 SHA-256/913554ad65b9420936822889a7268a6793a942e5f77ad8b653b8ec05068fa3be
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.1.3 SHA-256/a4774ae923c109544034aeae7c762cf017fbcdd1342403ba90e0921984b608c3
 * @jenesis.pin org.junit.platform/junit-platform-console 6.1.3 SHA-256/913554ad65b9420936822889a7268a6793a942e5f77ad8b653b8ec05068fa3be
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.1.3 SHA-256/21ad7ad3a35beda16387979317be1833a72a71d812f9a66ee4b9a7bafb56334c
 * @jenesis.pin org.junit.platform/junit-platform-launcher 6.1.3 SHA-256/1dec64b1fc0b7c47a11302aa5b4d37d8160c041b396e27067709ce775601a7b6
 * @jenesis.pin org.junit.platform/junit-platform-reporting 6.1.3 SHA-256/d584a16cd87da5609af5e35428ed4f40b192681a2487dc59d02ecb62f6c417c2
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.7 SHA-256/ff1cf9d62786d067fa19c56f13cd6ed1077d0f8782ff269824b7a2c586101bf0
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
open module build.jenesis.repository.format.jenesis.test {
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
