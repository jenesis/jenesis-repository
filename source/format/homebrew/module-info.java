/**
 * Homebrew bottles as a plugin module: a bottle domain a {@code brew install} pours from, reached by pointing one
 * variable at it - {@code HOMEBREW_BOTTLE_DOMAIN=<base>/homebrew/<repo>}.
 *
 * <p><b>Bottles only, and that is what the leading incumbent ships too.</b> A tap is a git repository of formulae
 * and serving one means speaking git's smart-HTTP protocol, which nothing here models; Artifactory's own guide
 * likewise leaves the tap where it is and redirects only the bottle download. The {@code formulae.brew.sh} JSON
 * API is a second surface and a separate change.
 *
 * <p><b>A bottle domain serves files, not an OCI layout</b> - measured against a real client rather than inferred
 * from Homebrew's own hosting, which does use OCI. See {@code HomebrewFormat} for what {@code brew} actually
 * requests and what it does when the answer is a {@code 404}.
 *
 * <p><b>No stored listing, because there is nothing to list.</b> The formula names the file, its checksum and its
 * platform, and the formula lives in a tap - so a bottle domain is asked for one file at a time and never asked
 * what it holds. This is the only format here with no index to maintain.
 *
 * <p>It provides {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "Homebrew"}
 * ecosystem, and {@code BlobLayout}, the coordinate seam an eviction and a compliance read follow.
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
    exports build.jenesis.repository.format.homebrew to
            build.jenesis.repository.format.contract.ecosystem.test,
            build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.gateway.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.homebrew.HomebrewFormat;
}
