/**
 * The licence inventory of a repository - how many versions declare each licence category and SPDX id, counted off the
 * request path and read back as a stored report by the API, the console and the CLI - and the resolution of a version's
 * declared licences that the count and the search index's licence fields share. It needs no index.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.inventory {
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    requires org.slf4j;
    exports build.jenesis.repository.compliance.inventory;
}
