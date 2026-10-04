package build.jenesis.repository.format.terraform;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Imports a Terraform registry laid out as Artifactory lays one out, replaying each artifact through
 * {@link TerraformFormat}'s own {@code PUT}:
 * <ul>
 *   <li>a provider's per-platform zip,
 *       {@code <namespace>/<type>/<version>/terraform-provider-<type>_<version>_<os>_<arch>.zip}, as it is;</li>
 *   <li>a module version, {@code <namespace>/<name>/<system>/<version>.zip}, re-packed as the {@code .tar.gz} this
 *       registry serves: a client unpacks by extension and verifies no module checksum, so only the envelope
 *       changes.</li>
 * </ul>
 * {@code SHA256SUMS} and its signature are derived and signed with this repository's key, so an incumbent's are skipped
 * with every other asset. A provider's file name must name the type and version of its folders. These land in one
 * {@code /terraform/terraform/...} registry. A path of this format's own served shape -
 * {@code <registry>/providers/<namespace>/<type>/<version>/<file>.zip} or
 * {@code <registry>/modules/<namespace>/<name>/<system>/<version>.tar.gz}, another deployment's listing - keeps its
 * registry, and a module comes as the archive it is served as.
 */
public final class TerraformImporter implements RepositoryImporter {

    private static final String REPO = "terraform";

    private static final String ZIP = ".zip";

    private static final String TAR_GZ = ".tar.gz";

    private static final String PROVIDER = "terraform-provider-";

    /** The most a module may hold unpacked: the zip's directory declares each entry's size and the tar refuses a byte
     *  past it, so summing the declarations bounds the re-pack. */
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
        if (segments.length == 6 && segments[1].equals("providers") && segments[5].endsWith(ZIP)
                && segments[5].startsWith(PROVIDER + segments[3] + "_" + segments[4] + "_")
                || segments.length == 6 && segments[1].equals("modules") && segments[5].endsWith(TAR_GZ)
                && segments[5].length() > TAR_GZ.length()) {
            return Optional.of(new Target("/terraform/" + String.join("/", segments), false));
        }
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

    /** The module's files in a gzipped tar. An entry naming a place outside the archive - absolute, or climbing with
     *  {@code ..} - refuses the module, as does one declaring more than {@link #LARGEST_MODULE} unpacked. */
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
