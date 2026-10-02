package build.jenesis.repository.demo.web;

import module java.base;

import java.util.jar.Attributes;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The first-party packages the demo publishes, generated in memory rather than shipped: a Java library is a jar
 * holding its manifest and one resource, beside a POM declaring its licence; an npm package is a tarball holding its
 * {@code package.json}, an entry point and a readme, wrapped in the envelope {@code npm publish} sends. Each is a few
 * hundred bytes, and every entry carries one fixed time, so the same sample is the same bytes on every run.
 */
final class Samples {

    /** The modification time of every entry, so a sample's bytes do not depend on when it was made. */
    private static final FileTime MADE = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Samples() {
    }

    /** A Java library: its coordinate, what it says it is and the licence its POM declares. */
    record Library(String group, String artifact, String version, String description, String licence) {

        /** {@code group:artifact}, as a deny list names it. */
        String coordinate() {
            return group + ":" + artifact;
        }

        /** The path the Maven format lays the jar out at. */
        String jar() {
            return directory() + artifact + "-" + version + ".jar";
        }

        /** The path the Maven format lays the POM out at. */
        String pom() {
            return directory() + artifact + "-" + version + ".pom";
        }

        private String directory() {
            return "/maven/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/";
        }
    }

    /** An npm package: its name, version, what it says it is and its licence. */
    record NpmPackage(String name, String version, String description, String licence) {

        /** The path the npm format takes a publish of the package at. */
        String path() {
            return "/npm/" + name;
        }
    }

    /** The library's jar: its manifest, then one resource saying what it is. */
    static byte[] jar(Library library) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.IMPLEMENTATION_TITLE, library.artifact());
        main.put(Attributes.Name.IMPLEMENTATION_VERSION, library.version());
        main.put(Attributes.Name.IMPLEMENTATION_VENDOR, "Jenesis demo");
        main.putValue("Bundle-License", library.licence());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream jar = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            jar.putNextEntry(entry(JarFile.MANIFEST_NAME));
            manifest.write(jar);
            jar.closeEntry();
            jar.putNextEntry(entry(library.group().replace('.', '/') + "/" + library.artifact().replace('-', '_')
                    + "/about.txt"));
            jar.write((library.description() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(MADE);
        return entry;
    }

    /** The library's POM, declaring its licence. */
    static byte[] pom(Library library) {
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 "
                + "https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>" + library.group() + "</groupId>\n"
                + "  <artifactId>" + library.artifact() + "</artifactId>\n"
                + "  <version>" + library.version() + "</version>\n"
                + "  <packaging>jar</packaging>\n"
                + "  <name>" + library.artifact() + "</name>\n"
                + "  <description>" + library.description() + "</description>\n"
                + "  <licenses>\n"
                + "    <license>\n"
                + "      <name>" + library.licence() + "</name>\n"
                + "    </license>\n"
                + "  </licenses>\n"
                + "</project>\n";
        return pom.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * What {@code npm publish} sends for the package: its document, the {@code latest} tag pointing at the version,
     * and the tarball base64-encoded under {@code _attachments}, with the digests a client writes into {@code dist}.
     */
    static byte[] publish(NpmPackage npm) throws IOException {
        byte[] tarball = tarball(npm);
        String file = npm.name() + "-" + npm.version() + ".tgz";
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("_id", npm.name());
        envelope.put("name", npm.name());
        envelope.put("description", npm.description());
        envelope.putObject("dist-tags").put("latest", npm.version());
        ObjectNode version = manifest(npm);
        version.put("_id", npm.name() + "@" + npm.version());
        ObjectNode dist = version.putObject("dist");
        dist.put("shasum", HexFormat.of().formatHex(digest("SHA-1", tarball)));
        dist.put("integrity", "sha512-" + Base64.getEncoder().encodeToString(digest("SHA-512", tarball)));
        dist.put("tarball", npm.name() + "/-/" + file);
        envelope.putObject("versions").set(npm.version(), version);
        ObjectNode attachment = envelope.putObject("_attachments").putObject(file);
        attachment.put("content_type", "application/octet-stream");
        attachment.put("data", Base64.getEncoder().encodeToString(tarball));
        attachment.put("length", tarball.length);
        return JSON.writeValueAsBytes(envelope);
    }

    /** The package's tarball: {@code package/package.json}, its entry point and its readme, gzipped. */
    static byte[] tarball(NpmPackage npm) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes), "UTF-8")) {
            add(tar, "package/package.json", JSON.writeValueAsBytes(manifest(npm)));
            add(tar, "package/index.js", ("module.exports = function greet(name) {\n"
                    + "    return 'Hello, ' + name + '!';\n"
                    + "};\n").getBytes(StandardCharsets.UTF_8));
            add(tar, "package/README.md", ("# " + npm.name() + "\n\n" + npm.description() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static ObjectNode manifest(NpmPackage npm) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("name", npm.name());
        manifest.put("version", npm.version());
        manifest.put("description", npm.description());
        manifest.put("main", "index.js");
        manifest.put("license", npm.licence());
        return manifest;
    }

    private static void add(TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        entry.setModTime(MADE);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    private static byte[] digest(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(algorithm + " is a digest every Java runtime carries", absent);
        }
    }
}
