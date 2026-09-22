/**
 * The audit-trail contracts: a durable, queryable record of security-relevant changes (who did what to what, per
 * tenant), and the {@code ServiceLoader} SPI a persistence module implements to supply it. With no module
 * installed the none trail stands in - nothing records, and the audit surface says the feature is not installed -
 * so a deployment that must keep no audit data removes the module rather than trusting a flag.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.audit {
    // The shared ceiling the paged and streaming defaults refuse past (the earlier ruling, one call).
    requires build.jenesis.repository.bounds;
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.audit;
    uses build.jenesis.repository.audit.AuditTrailProvider;
}
