/**
 * Homebrew bottles: a bottle domain a {@code brew install} pours from, reached with
 * {@code HOMEBREW_BOTTLE_DOMAIN=<base>/homebrew/<repo>}. Bottles only: a tap is a git repository served over git's
 * smart-HTTP protocol, which nothing here models, and the {@code formulae.brew.sh} JSON API is a separate surface. A
 * bottle domain serves flat files, not an OCI layout (see {@code HomebrewFormat}).
 *
 * <p>homebrew-core's own bottles pull through the OCI format instead: brew replaces ghcr.io in every homebrew-core
 * bottle URL with {@code HOMEBREW_ARTIFACT_DOMAIN}, so with that set to this repository's base a bottle is asked for at
 * {@code /v2/homebrew/core/<formula>/blobs/sha256:<digest>} - a repository {@code core} in tenant {@code homebrew},
 * defined as {@code proxy https://ghcr.io/homebrew/core}, holds each bottle to its digest and serves it from the store
 * after the first install. brew sends ghcr.io's anonymous {@code Bearer QQ==} when nothing else is configured, a
 * keyless request answered for the default tenant alone, so an anonymous mirror makes {@code homebrew} that tenant;
 * {@code HOMEBREW_DOCKER_REGISTRY_TOKEN} presents a key instead.
 *
 * <p>No stored listing: the formula names the file, so a bottle domain is asked for one file at a time. It is an
 * {@link build.jenesis.repository.format.ArtifactLayout} for the {@code "Homebrew"} ecosystem and a {@code BlobLayout}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.homebrew {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.blobs;
    // The attestations document beside a bottle is read one bundle per element.
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.homebrew;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.homebrew.HomebrewFormat;
}
