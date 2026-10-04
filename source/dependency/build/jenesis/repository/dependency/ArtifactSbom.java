package build.jenesis.repository.dependency;

import module java.base;

import build.jenesis.repository.store.ArchiveInflation;

/**
 * Extracts the SBOM embedded in a jar and parses it into a {@link DependencyGraph}, so a reverse-dependency sweep
 * learns what a stored artifact depends on without a sidecar. A Jenesis jar records it at
 * {@code META-INF/sbom/<artifact>.cdx.json} (or {@code .cdx.xml}) and names it in the manifest attribute
 * {@code Sbom-Location}; the {@code META-INF/sbom/*.cdx.{json,xml}} convention is recognised too, as is an embedded
 * SPDX document ({@code *.spdx.json}, tag-value {@code *.spdx}/{@code *.spdx.txt}). The extracted bytes are sniffed -
 * an SPDX {@code spdxVersion} marker routes to {@link SpdxParser}, anything else to {@link CycloneDxParser}.
 *
 * <p>Only the manifest and the SBOM entry are materialised: the jar streams through a {@link JarInputStream} and the
 * read stops at the SBOM, which a jar places among its leading {@code META-INF/} entries. The entry is capped at
 * {@link CycloneDxParser#MAX_DOCUMENT}. A non-jar, a jar without an SBOM, or an oversized or unparseable one yields
 * {@link Optional#empty()} - a permanent negative for a content-addressed blob, reached only by reading the archive to
 * its end. A read that fails part way throws its {@link IOException}, so the caller can tell an incomplete read from
 * "no SBOM" rather than recording a transient failure as a fact.
 *
 * <p>Reaching the SBOM inflates every entry before it, so the hunt is bounded at {@link #MAX_SCAN} inflated bytes: a
 * deflate bomb or a huge entry before the SBOM cannot pin the thread, and a jar that does not surface its SBOM within
 * the budget is read as carrying none. That is what lets {@code /api/sbom} decompress an untrusted jar on a request
 * thread. The shared {@code ArchiveWalk} bound is separate because this budget counts inflated bytes across the whole
 * hunt.
 */
public final class ArtifactSbom {

    private ArtifactSbom() {
    }

    private static final String SBOM_LOCATION = "Sbom-Location";
    private static final String SBOM_DIRECTORY = "META-INF/sbom/";

    /** The SBOM entry's byte ceiling - both parsers' {@code MAX_DOCUMENT}, so the extractor and the parser agree
     *  whatever the format. */
    private static final int MAX_DOCUMENT = CycloneDxParser.MAX_DOCUMENT;

    /** The lower-case {@code spdxVersion} key both SPDX serialisations declare in their header, which routes a document
     *  to {@link SpdxParser}; CycloneDX never carries it. */
    private static final byte[] SPDX_MARKER = "spdxversion".getBytes(StandardCharsets.US_ASCII);

    /** The inflated-byte budget for the entries preceding the SBOM, so a decompression bomb before it reads as no SBOM
     *  rather than being inflated without limit. A real jar surfaces its SBOM well inside it. */
    private static final long MAX_SCAN = 64L * 1024 * 1024;

    /** The dependency graph embedded in {@code artifact} (a jar or zip stream), or empty when the archive was read to
     *  its end and carries no readable SBOM - a permanent negative for a content-addressed blob. A read that fails part
     *  way throws its {@link IOException} instead. The caller owns and closes {@code artifact}; this reads only as far
     *  as the SBOM entry. */
    public static Optional<DependencyGraph> graph(InputStream artifact) throws IOException {
        return graph(artifact, false);
    }

    /** Like {@link #graph(InputStream)}, for the served view: an embedded SBOM that is present but does not decode
     *  throws {@link MalformedSbomException}, so the SBOM subsection renders "could not derive this SBOM" rather than
     *  "none". A jar with no SBOM entry still returns empty. */
    public static Optional<DependencyGraph> graphStrict(InputStream artifact) throws IOException {
        return graph(artifact, true);
    }

    /** Whether {@code name} is a bill of materials published as a file of its own - a Maven {@code -cyclonedx.json}
     *  attachment, a {@code .cdx.json}, an {@code .spdx.json} - rather than an artifact one might be embedded in. */
    public static boolean isDocument(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("cyclonedx.json") || lower.endsWith("cyclonedx.xml") || lower.endsWith(".cdx.json")
                || lower.endsWith(".cdx.xml") || lower.endsWith(".spdx.json");
    }

    /** The dependency graph a bill of materials published as a file of its own declares, read up to
     *  {@link CycloneDxParser#MAX_DOCUMENT} and parsed as the format it carries; empty for one past that bound or one
     *  that declares nothing. The caller owns and closes {@code document}. */
    public static Optional<DependencyGraph> document(InputStream document) throws IOException {
        byte[] read = document.readNBytes(CycloneDxParser.MAX_DOCUMENT + 1);
        if (read.length > CycloneDxParser.MAX_DOCUMENT) {
            return Optional.empty();
        }
        DependencyGraph graph = parse(read);
        return graph.isEmpty() ? Optional.empty() : Optional.of(graph);
    }

    private static Optional<DependencyGraph> graph(InputStream artifact, boolean strict) throws IOException {
        // A non-jar blob has no local-header signature, so the manifest is null and the loop ends at once; only a
        // truncated or reset read of a signature-bearing stream throws, and that propagates.
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

    /** Inflate and discard a non-SBOM entry, charging its inflated bytes against {@code budget}. Returns the remaining
     *  budget, or {@code -1} the moment the total would exceed it. Drained here rather than through
     *  {@link JarInputStream}'s implicit skip so the inflated bytes are metered. */
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

    /** Dispatch the SBOM bytes by the format they carry, sniffed rather than trusted from the entry name: SPDX to
     *  {@link SpdxParser}, anything else to {@link CycloneDxParser}, which yields an empty graph for what it does not
     *  recognise. */
    private static DependencyGraph parse(byte[] document) {
        return isSpdx(document) ? SpdxParser.parse(document) : CycloneDxParser.parse(document);
    }

    /** As {@link #parse(byte[])}, but a present-but-unparseable document throws {@link MalformedSbomException}, an
     *  SPDX one as a CycloneDX one. */
    private static DependencyGraph parseStrict(byte[] document) throws MalformedSbomException {
        return isSpdx(document) ? SpdxParser.parseStrict(document) : CycloneDxParser.parseStrict(document);
    }

    /** Whether {@code document} is SPDX, by the marker both its serialisations declare in their header. Only the header
     *  is scanned, so the sniff is bounded. */
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

    /** The whole SBOM entry, or {@code null} when it inflates past {@code max}, through the product's one
     *  archive-inflation read. The bound is the parser's own ({@link CycloneDxParser#MAX_DOCUMENT}), since an SBOM is a
     *  whole dependency closure. An embedded SBOM is an optional declaration, so an oversized one degrades to "no
     *  readable SBOM" - never the inflated prefix, which could parse into a plausible, shorter closure. */
    private static byte[] readBounded(InputStream entry, int max) throws IOException {
        return ArchiveInflation.entry(entry, max).orNull();
    }
}
