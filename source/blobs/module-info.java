/**
 * The content-addressed storage view the language formats share, so npm, PyPI, Go and NuGet store their bytes under
 * the same {@code blobs/<sha256>} namespace as the Maven and OCI layouts and dedupe across all of them. A thin
 * decorator over the {@code ArtifactStore} SPI. Also home to {@link build.jenesis.repository.blobs.ProxyRelay},
 * the pull-through relay mechanics (conditional-GET forwarding, cache-validator relay, {@code Content-Length}
 * parse) every proxying format shares, and to {@link build.jenesis.repository.blobs.ProxyLeg}, the seam those
 * formats implement instead of {@code ProxyFormat} directly so the front-door request-path screen
 * ({@link build.jenesis.repository.blobs.Keys#unsafePath}) is applied once for all of them rather than copied into
 * each leg - both live here because this is the one module every format module already requires. The same
 * argument puts {@link build.jenesis.repository.blobs.OutboundTargets} here: the screen a leg owes the
 * <em>outbound</em> URL it then fetches, which had been written out seven times in two contradictory policies.
 * Pure JDK beyond the format and store SPIs and the shared settings guard the outbound screen delegates to.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.blobs {
    // ProxyRelay's enumeration refusal says in the log which upstream target could not be asked and how, beside the
    // 502 it answers the client - the operator-visible half of the unreachable-versus-absent split, which a status code
    // alone cannot carry.
    requires org.slf4j;
    // Transitive because BlobRoots EXTENDS BlobReferences: the supertype is part of this module's API, so every
    // module that reads a BlobRoots - to call blobRoots(), or to hand a format to the collector as a lender -
    // must read the format SPI too.
    requires transitive build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.server.spi;
    // OutboundTargets delegates the two dangerous halves rather than restating them: the transport half, the dial and
    // the http(s)/host capability floor are PrivateHostGuard's, the blocked address ranges are PrivateHosts
    // table above. This module contributes only the wrapper the legs used to copy.
    requires build.jenesis.repository.settings;
    exports build.jenesis.repository.blobs;
}
