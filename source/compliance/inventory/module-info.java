/**
 * The licence inventory of a repository: how many of its versions declare each licence category and each SPDX id,
 * counted on request off the request path and read back as a stored report by the API, the console and the CLI
 * alike - and the resolution of one version's declared licences that the count and the full-text index's licence
 * fields share. It indexes nothing and needs no index: the inventory is the same whether or not a repository's
 * full-text search is on.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.inventory {
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    requires org.slf4j;
    exports build.jenesis.repository.compliance.inventory;
}
