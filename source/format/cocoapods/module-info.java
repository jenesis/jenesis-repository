/**
 * The CocoaPods registry format as a plugin module: it provides {@link build.jenesis.repository.format.RepositoryFormat}
 * for the {@code /cocoapods/...} CocoaPods CDN layout - a streaming pod upload
 * ({@code PUT /cocoapods/<repo>/<name>/<version>}, the pod zip as the body, streamed into the content-addressed store
 * while only its embedded {@code .podspec.json} is materialised), the root {@code CocoaPods-version.yml}, the sharded
 * {@code all_pods_versions_<a>_<b>_<c>.txt} version listings (stored listings every publish maintains), and each
 * version's {@code Specs/<a>/<b>/<c>/<name>/<version>/<name>.podspec.json} generated on read, and the pod downloads. It also
 * provides {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "CocoaPods"} ecosystem and
 * resolving a pod download path to its {@code <name>} coordinate and version. A pod's {@code .podspec.json} lives
 * inside the archive, so the just-stored blob is reopened and walked with {@code java.util.zip} (java.base) as far as
 * its podspec, whose JSON is parsed and the metadata emitted with the Jackson databind on the server module path. The
 * CDN shard for a pod is the first three hex characters of {@code MD5(name)} (java.base's {@code MessageDigest}), the
 * protocol's own bucketing so a client computing the same shard finds the pod. Discovered through {@code provides}. It
 * also implements {@link build.jenesis.repository.format.ProxyFormat}: a proxy registry serves a miss from an upstream
 * CocoaPods CDN ({@code cdn.cocoapods.org} by default), streaming the mutable {@code all_pods_versions_*} listings and
 * {@code Specs/.../<name>.podspec.json} files through fresh (rewriting an http-zip pod {@code source} to route the
 * download back through here) and caching an immutable pod archive into the CAS on first fetch. Finally it provides a
 * {@link build.jenesis.repository.format.RepositoryImporter} that migrates a Nexus/Artifactory {@code cocoapods}
 * repository, replaying each pod zip through the format's own publish path so the archive streams into the CAS as a
 * hosted import (the coordinate taken from the source path's {@code <name>/<version>/<file>.zip} layout). The
 * compliance inspector is a sibling {@code compliance/cocoapods} module.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.cocoapods {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.cocoapods to
            build.jenesis.repository.gateway.test, build.jenesis.repository.gateway.census.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.cocoapods.CocoaPodsFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.cocoapods.CocoaPodsListingObserver;
}
