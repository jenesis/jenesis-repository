package build.jenesis.repository.dependency;

import module java.base;

import build.jenesis.repository.store.ArchiveInflation;

/**
 * Extracts the SBOM a Jenesis build embeds in a jar and parses it into a {@link DependencyGraph}, so a
 * reverse-dependency sweep can learn what a stored artifact depends on without a separate metadata sidecar. A
 * Jenesis jar records the SBOM at {@code META-INF/sbom/<artifact>.cdx.json} (or {@code .cdx.xml}) and points at it
 * with the manifest attribute {@code Sbom-Location}; this reader honours that pointer and also recognises the
 * {@code META-INF/sbom/*.cdx.{json,xml}} convention, so a jar produced by another CycloneDX tool is read too.
 * Both SBOM standards are ingested at parity: an embedded SPDX document ({@code META-INF/sbom/*.spdx.json} or the
 * tag-value {@code *.spdx}/{@code *.spdx.txt}, or a {@code Sbom-Location} pointing at one) is recognised the same
 * way, and the extracted bytes are sniffed - an SPDX {@code spdxVersion} marker routes to {@link SpdxParser},
 * everything else to {@link CycloneDxParser} - so a jar shipping SPDX no longer contributes no edges at all.
 *
 * <p>Only the manifest and the one small SBOM entry are materialised: the jar is streamed through a
 * {@link JarInputStream} and the reader stops at the SBOM entry (which a jar places among its leading
 * {@code META-INF/} entries), so an arbitrarily large artifact never lands in heap - the streaming principle the
 * whole publish/proxy path holds to. The SBOM entry itself is bounded at {@link CycloneDxParser#MAX_DOCUMENT}; a
 * non-jar blob (no zip entries at all), a jar without an embedded SBOM, or an oversized/unparseable one yields
 * {@link Optional#empty()} - a genuine, permanent negative for a content-addressed blob, reached only by reading the
 * archive to its end. A read that fails part way instead - a truncated or interrupted stream, a socket reset mid-jar -
 * throws the {@link IOException} rather than returning empty, so the caller can tell an incomplete read from an
 * authoritative "no SBOM" and skip/retry it rather than recording a transient failure as a permanent fact
 * (PRINCIPLES 5). Either way one bad artifact never derails the sweep that scans every blob.
 *
 * <p>Reaching that SBOM entry means inflating every entry the jar places before it, so the hunt is bounded at
 * {@link #MAX_SCAN} inflated bytes: a max-ratio deflated ("deflate bomb") or arbitrarily large entry standing before
 * (or in place of) the SBOM can no longer pin the reading thread inflating gigabytes - a jar that does not surface its
 * SBOM within the budget is read as carrying none (a deterministic, permanent negative for that blob). This is what
 * lets {@code /api/sbom} decompress an untrusted hosted jar on a request thread safely; it is the same concern the
 * shared {@code ArchiveWalk} bound answers for the publish-path archive walks, kept separate here because this budget
 * counts INFLATED bytes across the whole hunt rather than the archive's own footprint.
 */
public final class ArtifactSbom {

    private ArtifactSbom() {
    }

    private static final String SBOM_LOCATION = "Sbom-Location";
    private static final String SBOM_DIRECTORY = "META-INF/sbom/";

    /** The BOM-entry byte ceiling, held whole in heap by whichever parser reads it. Both SBOM parsers pin the same
     *  32&nbsp;MiB cap ({@link CycloneDxParser#MAX_DOCUMENT} == {@link SpdxParser#MAX_DOCUMENT}), so the bound the
     *  extractor enforces is the one the parser it dispatches to would, whichever format the entry turns out to be. */
    private static final int MAX_DOCUMENT = CycloneDxParser.MAX_DOCUMENT;

    /** The lower-case ASCII of the {@code spdxVersion}/{@code SPDXVersion} key both SPDX serialisations declare in
     *  their header - the telltale that routes a document to {@link SpdxParser}. CycloneDX (JSON {@code bomFormat} or
     *  the XML {@code <bom>} root) never carries it, so its absence routes to {@link CycloneDxParser}. */
    private static final byte[] SPDX_MARKER = "spdxversion".getBytes(StandardCharsets.US_ASCII);

    /** The inflated-byte budget for the scan that hunts the SBOM entry: the sum of the inflated sizes of the entries
     *  that precede it may not exceed this, so a decompression bomb standing before the SBOM is refused (an empty
     *  graph) rather than inflated without limit. A real jar surfaces its SBOM among its leading {@code META-INF/}
     *  entries, well inside this; the SBOM entry itself is separately capped at {@link CycloneDxParser#MAX_DOCUMENT}. */
    private static final long MAX_SCAN = 64L * 1024 * 1024;

    /**
     * The dependency graph embedded in {@code artifact} (a jar / zip stream), or empty when the archive was read to
     * its end and carries no readable SBOM - a non-jar (no zip entries), a jar whose entries hold no SBOM,
     * an SBOM larger than any real BOM, or one that parses to nothing. That empty is a genuine, permanent negative for
     * a content-addressed blob. A read that fails part way - a truncated or interrupted stream, a socket reset mid-jar
     * - is <em>not</em> a negative: it throws the {@link IOException} rather than swallowing it into an empty result,
     * so the caller can tell an incomplete read from an authoritative "no SBOM" and skip/retry it rather than caching
     * a transient failure as a permanent fact (PRINCIPLES 5). The caller owns {@code artifact} and closes it; this
     * method consumes only as far as the SBOM entry.
     */
    public static Optional<DependencyGraph> graph(InputStream artifact) throws IOException {
        return graph(artifact, false);
    }

    /**
     * Like {@link #graph(InputStream)} but for the served view path: when the jar carries an embedded SBOM that is
     * present but unparseable (announced JSON/XML that does not decode), it throws {@link MalformedSbomException}
     * instead of returning empty, so the SBOM subsection can render a scoped "could not derive this SBOM" error
     * distinct from the empty a jar genuinely carrying no SBOM yields. A jar with no SBOM entry still returns empty.
     * The sweep keeps the fail-soft {@link #graph(InputStream)}.
     */
    public static Optional<DependencyGraph> graphStrict(InputStream artifact) throws IOException {
        return graph(artifact, true);
    }

    private static Optional<DependencyGraph> graph(InputStream artifact, boolean strict) throws IOException {
        // A genuinely non-jar blob has no local-header signature, so JarInputStream constructs with a null manifest
        // and the loop below ends at once (a clean, empty end-of-archive); only a stream that fails part way - a
        // signature-bearing but truncated/reset read - throws here, and that IOException is left to propagate.
        JarInputStream jar = new JarInputStream(new BufferedInputStream(artifact));
        Manifest manifest = jar.getManifest();
        String declared = manifest == null ? null : manifest.getMainAttributes().getValue(SBOM_LOCATION);
        long budget = MAX_SCAN;
        JarEntry entry;
        while ((entry = jar.getNextJarEntry()) != null) {
            String name = entry.getName();
            if (entry.isDirectory() || !isSbom(name, declared)) {
                budget = skip(jar, budget);
                if (budget < 0) {
                    return Optional.empty();   // a decompression bomb before the SBOM - bounded; read as carrying none
                }
                continue;
            }
            byte[] document = readBounded(jar, MAX_DOCUMENT);
            if (document == null) {
                return Optional.empty();       // the SBOM entry is larger than any real BOM - a permanent negative
            }
            DependencyGraph graph = strict ? parseStrict(document) : parse(document);
            return graph.isEmpty() ? Optional.empty() : Optional.of(graph);
        }
        return Optional.empty();               // clean end-of-archive: the whole archive was read, genuinely no SBOM
    }

    /** Inflate and discard a non-SBOM entry, charging its inflated bytes against {@code budget} - so advancing the
     *  scan past an entry standing before the SBOM inflates at most the budget's worth, whatever the entry's
     *  compression ratio. Returns the budget that remains, or {@code -1} the instant the inflated total would exceed
     *  it (the deflate-bomb signal). The entry is drained here, rather than through {@link JarInputStream}'s implicit
     *  skip, precisely so the inflated bytes are observed and bounded rather than decompressed unmetered. */
    private static long skip(InputStream entry, long budget) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = entry.read(buffer)) != -1) {
            budget -= read;
            if (budget < 0) {
                return -1;
            }
        }
        return budget;
    }

    private static boolean isSbom(String name, String declared) {
        return declared != null && name.equals(declared)
                || name.startsWith(SBOM_DIRECTORY) && (name.endsWith(".cdx.json") || name.endsWith(".cdx.xml")
                        || name.endsWith(".spdx.json") || name.endsWith(".spdx") || name.endsWith(".spdx.txt"));
    }

    /** Dispatch the extracted SBOM bytes to the parser for the format they carry, sniffed from the bytes rather than
     *  trusted from the entry name (a {@code Sbom-Location} pointer may name any file): an SPDX document routes to
     *  {@link SpdxParser}, everything else - CycloneDX JSON or XML - to {@link CycloneDxParser}, which itself yields an
     *  empty graph for anything it does not recognise. */
    private static DependencyGraph parse(byte[] document) {
        return isSpdx(document) ? SpdxParser.parse(document) : CycloneDxParser.parse(document);
    }

    /** As {@link #parse(byte[])}, but on the served view path a present-but-unparseable CycloneDX document routes to
     *  {@link CycloneDxParser#parseStrict} so a malformed BOM throws {@link MalformedSbomException} rather than reading
     *  as empty. SPDX has no strict variant yet (a malformed SPDX still reads as empty) - a small follow-up to mirror
     *  {@code parseStrict} in {@link SpdxParser}. */
    private static DependencyGraph parseStrict(byte[] document) throws MalformedSbomException {
        return isSpdx(document) ? SpdxParser.parse(document) : CycloneDxParser.parseStrict(document);
    }

    /** Whether {@code document} is an SPDX SBOM, detected by the {@code spdxVersion}/{@code SPDXVersion} key both its
     *  serialisations declare in their header. Only the header is scanned (both forms put the marker there), so the
     *  sniff is bounded regardless of document size and a CycloneDX BOM - which never carries the marker - falls
     *  through to its own parser. */
    private static boolean isSpdx(byte[] document) {
        int limit = Math.min(document.length, 8192);        // both SPDX serialisations declare the marker up top
        for (int start = 0; start + SPDX_MARKER.length <= limit; start++) {
            int offset = 0;
            while (offset < SPDX_MARKER.length && (document[start + offset] | 0x20) == SPDX_MARKER[offset]) {
                offset++;                                    // (| 0x20) lower-cases an ASCII letter for the compare
            }
            if (offset == SPDX_MARKER.length) {
                return true;
            }
        }
        return false;
    }

    /**
     * The whole SBOM entry, or {@code null} when it inflates past {@code max} (an oversized BOM), through the
     * product's one archive-inflation read.
     *
     * <p>{@code max} is passed explicitly rather than taken from the shared default because this document is
     * legitimately larger than a manifest - it is a whole dependency closure, and the bound is pinned to the parser
     * that will hold it in heap anyway ({@link CycloneDxParser#MAX_DOCUMENT}), so the extractor and the parser cannot
     * disagree about what is too large. An embedded SBOM is an <b>optional declaration</b> - the coordinate comes from
     * the request path and the sibling attachment is tried first - so the bound degrades through {@code orNull()} and
     * the caller reads it as "this jar carries no readable SBOM", never as the prefix that was inflated before the
     * ceiling (a truncated CycloneDX document can still parse into a plausible, shorter closure).
     */
    private static byte[] readBounded(InputStream entry, int max) throws IOException {
        return ArchiveInflation.entry(entry, max).orNull();
    }
}
