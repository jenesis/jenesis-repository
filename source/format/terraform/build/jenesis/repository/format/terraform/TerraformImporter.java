package build.jenesis.repository.format.terraform;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Imports a Terraform registry laid out as Artifactory lays out a Terraform repository, replaying each artifact
 * through {@link TerraformFormat}'s own {@code PUT}:
 * <ul>
 *   <li>a provider release's per-platform zip, {@code <namespace>/<type>/<version>/terraform-provider-<type>_<version>_<os>_<arch>.zip},
 *       as it is - its bytes are what the {@code SHA256SUMS} this repository derives and signs will vouch for;</li>
 *   <li>a module version, {@code <namespace>/<name>/<system>/<version>.zip}, re-packed as the {@code .tar.gz} this
 *       registry serves every module as. A client unpacks what {@code X-Terraform-Get} names by its extension and
 *       verifies no checksum of a module, so the files arrive as they were; only the envelope changes.</li>
 * </ul>
 *
 * <p>Only those migrate. {@code SHA256SUMS} and its signature are derived and signed with this repository's own key,
 * so an incumbent's - signed with the publisher's key, which a client of this repository is not handed - are skipped,
 * with every other asset. A provider's file name must name the type and version of the folders it sits in, as the
 * protocol's {@code filename} does.
 *
 * <p>All of it migrates into a single {@code /terraform/terraform/...} registry, the flat migration the other
 * importers use.
 */
public final class TerraformImporter implements RepositoryImporter {

    private static final String REPO = "terraform";

    private static final String ZIP = ".zip";

    private static final String PROVIDER = "terraform-provider-";

    /** The most a module may hold once unpacked. The zip's own directory declares each entry's size and the tar
     *  refuses a byte past a declared one, so summing the declarations bounds what the re-pack writes. */
    private static final long LARGEST_MODULE = 1L << 30;

    /** Where a source path goes, and whether its archive is a module's, which is re-packed on the way. */
    private record Target(String path, boolean module) {
    }

    @Override
    public boolean imports(String format) {
        return format.equals("terraform");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        return target(path).flatMap(target -> new TerraformFormat().describe(target.path()));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        Optional<Target> target = target(path);
        if (target.isEmpty()) {
            return;                 // derived sums, their signature and other assets carry no release
        }
        if (!target.get().module()) {
            new TerraformFormat().handle(new ReplayExchange(target.get().path(), content), store);
            return;
        }
        Path zip = Files.createTempFile("terraform-module", ZIP);
        Path archive = Files.createTempFile("terraform-module", ".tar.gz");
        try {
            Files.copy(content, zip, StandardCopyOption.REPLACE_EXISTING);
            repack(zip, archive);
            try (InputStream body = Files.newInputStream(archive)) {
                new TerraformFormat().handle(new ReplayExchange(target.get().path(), body), store);
            }
        } finally {
            Files.deleteIfExists(zip);
            Files.deleteIfExists(archive);
        }
    }

    private static Optional<Target> target(String path) {
        String[] segments = RepositoryImporter.importablePath(path, "terraform").split("/");
        if (segments.length < 4 || !segments[segments.length - 1].endsWith(ZIP)) {
            return Optional.empty();
        }
        String namespace = segments[segments.length - 4], file = segments[segments.length - 1];
        if (file.startsWith(PROVIDER)) {
            String type = segments[segments.length - 3], version = segments[segments.length - 2];
            if (!file.startsWith(PROVIDER + type + "_" + version + "_")) {
                return Optional.empty();
            }
            return Optional.of(new Target("/terraform/" + REPO + "/providers/" + namespace + "/" + type + "/"
                    + version + "/" + file, false));
        }
        String version = file.substring(0, file.length() - ZIP.length());
        if (version.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Target("/terraform/" + REPO + "/modules/" + namespace + "/"
                + segments[segments.length - 3] + "/" + segments[segments.length - 2] + "/" + version + ".tar.gz",
                true));
    }

    /**
     * The module's files, in a gzipped tar. An entry naming a place outside the archive - absolute, or climbing out
     * through {@code ..} - refuses the module rather than carrying the name into an archive a client would unpack,
     * and so does one declaring more than {@link #LARGEST_MODULE} unpacked.
     */
    private static void repack(Path zip, Path archive) throws IOException {
        try (ZipFile source = new ZipFile(zip.toFile(), StandardCharsets.UTF_8);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(
                     new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(archive))), "UTF-8")) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            List<? extends ZipEntry> entries = Collections.list(source.entries());
            long declared = 0;
            for (ZipEntry entry : entries) {
                declared += Math.max(0, entry.getSize());
                if (entry.getSize() < 0 || declared > LARGEST_MODULE) {
                    throw new IOException("a module archive declares more than " + LARGEST_MODULE
                            + " bytes unpacked, or an entry of no known size");
                }
            }
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\")
                        || Arrays.asList(name.split("/")).contains("..")) {
                    throw new IOException("a module archive names an entry outside itself: " + name);
                }
                TarArchiveEntry member = new TarArchiveEntry(name);
                member.setModTime(entry.getTime());
                if (!entry.isDirectory()) {
                    member.setSize(entry.getSize());
                }
                tar.putArchiveEntry(member);
                if (!entry.isDirectory()) {
                    try (InputStream in = source.getInputStream(entry)) {
                        in.transferTo(tar);
                    }
                }
                tar.closeArchiveEntry();
            }
        }
    }
}
