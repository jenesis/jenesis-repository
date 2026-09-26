package build.jenesis.repository.format;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The optional export capability of a {@link RepositoryFormat}: a format that also implements this can publish what a
 * repository holds to another repository - another deployment of this product, or any repository manager - exactly as
 * the format's own client publishes, so leaving the product needs no importer on the other side. It is the
 * write-out twin of {@link RepositoryImporter}, and the same kind of {@code instanceof} role on the one discovered
 * format seam.
 *
 * <p>The unit is one version, because that is what a client publishes in one go: npm, Cargo, NuGet and RubyGems in a
 * single request, OCI as its blobs and then its manifest, Maven as each file with the checksums after them. The export
 * job walks the repository's versions - the inventory's, or for a format that records none, its published paths
 * ({@link #units()}) - and hands each one here; after a coordinate's last version it calls {@link #exported} once,
 * which is where a format sends what describes the coordinate as a whole, the way {@code mvn deploy} sends
 * {@code maven-metadata.xml} after the version it adds to it.
 *
 * <p>What is sent is what the repository serves: a withheld version - held for review, rejected, retroactively held -
 * is not sent, and nothing is derived that a client did not publish. Listings and indexes are the target's to build.
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@link RepositoryFormat}: that contract still binds, and the clauses below state what
 * publishing out adds. {@code ExportContract} in the ecosystem suite proves every exporter end to end, one instance
 * exporting into another.
 * <ol>
 * <li><b>Thread-safety.</b> The exporter is the format singleton; one job calls it from one thread, and several jobs
 *     may run at once, so it keeps no per-export state on the instance.</li>
 * <li><b>Idempotency / replay.</b> A job is resumable and therefore replayed: exporting a version the target already
 *     holds with the same bytes answers {@link Exported#ALREADY_PRESENT} rather than failing, and changes nothing.
 *     A target holding <em>different</em> bytes for it is a failure naming the version - never an overwrite.</li>
 * <li><b>Absence sentinel.</b> A version with nothing servable - every file withheld, or nothing stored under it -
 *     answers {@link Exported#WITHHELD}, never {@code null} and never an exception.</li>
 * <li><b>Streaming (&sect;1).</b> An artifact streams from the store to the target; it is never read into memory
 *     whole. A document a format must rebuild to publish (npm's publish envelope, Cargo's publish metadata) may be
 *     assembled in memory only under the cap its own importer applies.</li>
 * <li><b>Error visibility (&sect;9).</b> A version the target refused throws an {@link IOException} carrying the
 *     target's status and message; it is not reported as exported.</li>
 * <li><b>Read purity (&sect;10).</b> An exporter reads the repository and writes nothing to it.</li>
 * <li><b>Lifecycle / ownership.</b> The exporter owns no client and no thread: every request goes through the
 *     {@link ExportTarget} it is handed.</li>
 * </ol>
 */
public interface RepositoryExporter {

    /** Publish {@code version} of {@code coordinate}, as {@code repository} holds it, to {@code target}. For a format
     *  whose {@link #units()} are its published paths, {@code coordinate} is the path and {@code version} empty. */
    Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException;

    /** After {@code coordinate}'s last version: send what describes the coordinate as a whole, if the format's client
     *  sends anything. Nothing, by default. */
    default void exported(ArtifactStore repository, String coordinate, ExportTarget target) throws IOException {
    }

    /** What the job walks to find this format's versions. */
    /**
     * The ecosystem whose inventory rows name this format's versions, for a format that is not itself the
     * {@link EcosystemLayout} they were recorded under - OCI, whose layout is a capability provider of its own. Empty,
     * the default, reads the format's own.
     */
    default Optional<String> inventory() {
        return Optional.empty();
    }

    /**
     * Where this format's client is pointed, under a repository's URL: the URL an export target is given is a
     * repository's URL with this appended, and every path {@link #export} sends is relative to it. {@code /} for
     * almost every format; Maven's lays its paths out from {@code /maven/}, so its client is pointed there.
     */
    default String clientPath() {
        return "/";
    }

    default Units units() {
        return Units.INVENTORY;
    }

    /** What one version's export came to. */
    enum Exported {

        /** Sent, and accepted by the target. */
        PUBLISHED,

        /** The target already held it, with the same bytes. */
        ALREADY_PRESENT,

        /** Nothing of it is servable here, so nothing was sent. */
        WITHHELD
    }

    /** How a job enumerates a format's versions. */
    enum Units {

        /** The inventory's versions of the format's ecosystem ({@link EcosystemLayout#ecosystem()}). */
        INVENTORY,

        /** Every path published under the format, one unit each - for a format that records no coordinates. */
        PUBLISHED_PATHS
    }
}
