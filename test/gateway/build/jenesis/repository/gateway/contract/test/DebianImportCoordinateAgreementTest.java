package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.debian.DebianImporter;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Debian importer's manifest-vs-path agreement (audit finding N2, Debian leg). {@link
 * DebianImporter#importTarget} screens the coordinate parsed from the {@code .deb} FILENAME
 * ({@code <pkg>_<version>_<arch>.deb}); {@link DebianImporter#importArtifact} replays the package through {@code
 * DebianFormat.handle}, whose {@code push} reads the served {@code Packages} stanza's {@code Package:} from the embedded
 * {@code control}. The format now REFUSES a package whose control {@code Package} disagrees with the screened filename -
 * so a {@code .deb} can never be screened under one package name and then served under another (the screen-label
 * bypass), the way Composer/CocoaPods refuse a manifest that disagrees with the deploy path. The {@code .deb} is
 * hand-assembled (an {@code ar} of {@code debian-binary} + a gzipped {@code control.tar.gz}), so no {@code dpkg-deb}
 * container is needed. Container-free.
 */
class DebianImportCoordinateAgreementTest {

    @TempDir
    Path root;

    /** The Debian Release family and the .gz twins are derived off the request; they are finished before the
     *  store goes. */
    @AfterEach
    void settleDerivations() {
        StoredListing.settle();
    }

    /**
     * A package screened under the filename name {@code innocent} whose {@code control} declares a DIFFERENT
     * {@code Package} ({@code evil-payload}) is REFUSED - its pool pointer is never written, so it is never served.
     */
    @Test
    void a_deb_whose_control_package_disagrees_with_the_screened_filename_is_refused() throws IOException {
        ArtifactStore store = store();
        String path = "stable/pool/main/innocent_1.0_amd64.deb";

        ArtifactDescriptor screened = new DebianImporter().importTarget(path).orElseThrow();
        assertThat(screened.coordinate()).isEqualTo("innocent");

        new DebianImporter().importArtifact(path, new ByteArrayInputStream(deb("evil-payload", "amd64")), store);

        assertThat(new Blobs(store).exists("debian/stable/pool/main/innocent_1.0_amd64.deb"))
                .as("a control Package disagreeing with the screened filename is refused - the pool pointer is unwritten")
                .isFalse();
    }

    /**
     * A control that itself declares a reserved index field ({@code Filename}, {@code Size}, {@code MD5sum},
     * {@code SHA1}, {@code SHA256}) is REFUSED: the server appends the AUTHORITATIVE values of these to build the
     * Packages stanza, so a control-supplied one produces a stanza with a duplicate field whose apt resolution is
     * undefined - an injected {@code Filename} could point apt at a different blob than the one published.
     */
    @Test
    void a_control_declaring_a_reserved_index_field_is_refused() throws IOException {
        ArtifactStore store = store();
        String path = "stable/pool/main/innocent_1.0_amd64.deb";
        String control = "Package: innocent\nVersion: 1.0\nArchitecture: amd64\n"
                + "Maintainer: Test <test@example.com>\nDescription: a test package\n"
                + "Filename: pool/main/evil.deb\n";   // an injected Filename the server must not let stand

        new DebianImporter().importArtifact(path, new ByteArrayInputStream(deb(control)), store);

        assertThat(new Blobs(store).exists("debian/stable/pool/main/innocent_1.0_amd64.deb"))
                .as("a control declaring a reserved index field is refused - the pool pointer is never written")
                .isFalse();
    }

    /** The honest baseline: a control Package that matches the filename lays the package out in the pool. */
    @Test
    void a_matching_deb_is_laid_out_under_the_coordinate_it_was_screened_against() throws IOException {
        ArtifactStore store = store();
        String path = "stable/pool/main/innocent_1.0_amd64.deb";

        new DebianImporter().importArtifact(path, new ByteArrayInputStream(deb("innocent", "amd64")), store);

        assertThat(new Blobs(store).exists("debian/stable/pool/main/innocent_1.0_amd64.deb"))
                .as("a control that matches the filename serves under the screened coordinate").isTrue();
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /**
     * A multi-paragraph {@code control} (a blank line then a second, attacker-chosen stanza) must be REFUSED: echoed
     * verbatim into the Packages index it would splice in a phantom package pointing {@code Filename:} at any blob -
     * repository-wide apt poisoning the single-line Package-vs-filename check does not catch.
     */
    @Test
    void a_deb_with_a_multi_stanza_control_is_refused() throws IOException {
        ArtifactStore store = store();
        String path = "stable/pool/main/innocent_1.0_amd64.deb";
        String control = "Package: innocent\nVersion: 1.0\nArchitecture: amd64\nDescription: x\n"
                + "\n"
                + "Package: openssh-server\nVersion: 999:9.9\nArchitecture: amd64\n"
                + "Filename: pool/main/o/openssh-server/evil.deb\nSHA256: deadbeef\n";

        new DebianImporter().importArtifact(path, new ByteArrayInputStream(deb(control)), store);

        Blobs blobs = new Blobs(store);
        assertThat(blobs.exists("debian/stable/pool/main/innocent_1.0_amd64.deb"))
                .as("a multi-stanza control is refused - the pool pointer is never written").isFalse();
        assertThat(blobs.list("debian/stable/index/main/amd64"))
                .as("no stanza is indexed for either the innocent or the injected package").isEmpty();
    }

    /** A minimal {@code .deb}: an {@code ar} of {@code debian-binary} and a gzipped {@code control.tar.gz} whose sole
     *  {@code ./control} entry declares the package {@code name}. No {@code data.tar} is needed for the index read. */
    private static byte[] deb(String name, String architecture) throws IOException {
        return deb("Package: " + name + "\nVersion: 1.0\nArchitecture: " + architecture
                + "\nMaintainer: Test <test@example.com>\nDescription: a test package\n");
    }

    /** As {@link #deb(String, String)} but with a caller-supplied {@code control} body verbatim. */
    private static byte[] deb(String control) throws IOException {
        ByteArrayOutputStream controlTar = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(controlTar)) {
            byte[] bytes = control.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("./control");
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        }
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(controlTar.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArArchiveOutputStream ar = new ArArchiveOutputStream(out)) {
            byte[] version = "2.0\n".getBytes(StandardCharsets.US_ASCII);
            ar.putArchiveEntry(new ArArchiveEntry("debian-binary", version.length));
            ar.write(version);
            ar.closeArchiveEntry();
            byte[] controlGz = gzipped.toByteArray();
            ar.putArchiveEntry(new ArArchiveEntry("control.tar.gz", controlGz.length));
            ar.write(controlGz);
            ar.closeArchiveEntry();
        }
        return out.toByteArray();
    }
}
