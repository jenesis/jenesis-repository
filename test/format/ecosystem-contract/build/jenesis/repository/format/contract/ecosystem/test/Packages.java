package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

/**
 * The genuine wire bodies the fixtures publish through their formats' own write paths - an npm publish
 * envelope, a twine multipart form, a {@code .nupkg}, a {@code .gem}, a {@code .deb}, an {@code .rpm}, a Cargo publish
 * frame, a conda {@code .tar.bz2} and {@code .conda}, and the Composer / CocoaPods zips.
 *
 * <p>They are here rather than inside each fixture because seven of the thirteen ecosystems <em>parse</em> the artifact
 * at publish - a NuGet push reads the {@code .nuspec}, a gem push the gzipped YAML gemspec, a Debian push the
 * {@code control} inside an {@code ar}/tar, an RPM push the binary header, a conda push the {@code info/index.json}
 * inside the archive, a Composer push the {@code composer.json} and a CocoaPods push the {@code .podspec.json} - so
 * those fixtures cannot hand the format an arbitrary byte body the way Maven or the raw layout can. Every builder
 * produces the smallest body that is still <em>real</em>: a container the format's own reader accepts, never a stub the
 * parse is loosened for.
 *
 * <p>Nothing here is a second implementation of a format: each builder writes the ecosystem's published container
 * format with the same library the format module reads it with (Commons Compress for the tar/ar members, the JDK's
 * gzip and zip), so a change that broke the reader would break these too rather than being papered over.
 */
final class Packages {

    private Packages() {
    }

    // --- npm ---------------------------------------------------------------------------------------------------

    /**
     * One npm publish envelope: the version's metadata document plus the tarball carried inline, base64 under
     * {@code _attachments}, exactly as {@code npm publish} sends it. No {@code dist-tags} block, so the packument's
     * {@code latest} is computed from the surviving versions - which is what makes the withhold leg's dist-tags screen
     * observable rather than reading back a tag the fixture itself wrote.
     */
    static byte[] npmEnvelope(String name, String version, byte[] tarball) {
        String file = shortName(name) + "-" + version + ".tgz";
        return ("{\"_id\":\"" + name + "\",\"name\":\"" + name + "\","
                + "\"versions\":{\"" + version + "\":{"
                + "\"name\":\"" + name + "\",\"version\":\"" + version + "\","
                + "\"dist\":{\"shasum\":\"" + sha1Hex(tarball) + "\"}}},"
                + "\"_attachments\":{\"" + file + "\":{"
                + "\"content_type\":\"application/octet-stream\","
                + "\"length\":" + tarball.length + ","
                + "\"data\":\"" + Base64.getEncoder().encodeToString(tarball) + "\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The unscoped half of an npm package name - the token the tarball filename is built from. */
    /**
     * One Helm chart: a gzipped tar whose {@code <name>/Chart.yaml} is written first, so the coordinate the publish
     * reads sits inside the prefix the inspector is bounded to. The licence rides in the Artifact Hub annotation,
     * there being no licence field in the chart schema.
     */
    static byte[] helmChart(String name, String version) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(tar)) {
            entry(out, name + "/Chart.yaml", ("apiVersion: v2\nname: " + name + "\nversion: " + version
                    + "\ndescription: a contract chart\nannotations:\n  artifacthub.io/license: MIT\n")
                    .getBytes(StandardCharsets.UTF_8));
            entry(out, name + "/values.yaml", "replicaCount: 1\n".getBytes(StandardCharsets.UTF_8));
            entry(out, name + "/templates/service.yaml",
                    "apiVersion: v1\nkind: Service\n".getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(gz)) {
            zip.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void entry(TarArchiveOutputStream out, String name, byte[] content) throws IOException {
        TarArchiveEntry member = new TarArchiveEntry(name);
        member.setSize(content.length);
        out.putArchiveEntry(member);
        out.write(content);
        out.closeArchiveEntry();
    }

    static String shortName(String name) {
        return name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
    }

    // --- PyPI --------------------------------------------------------------------------------------------------

    /** The {@code twine upload} multipart form: the {@code name} text field, then the {@code content} file part
     *  carrying the distribution. Field order is the client's, and twine really does send {@code name} first. */
    static byte[] twineForm(String boundary, String project, String filename, byte[] distribution)
            throws IOException {
        ByteArrayOutputStream form = new ByteArrayOutputStream();
        form.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + project + "\r\n").getBytes(StandardCharsets.UTF_8));
        form.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"content\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        form.write(distribution);
        form.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return form.toByteArray();
    }

    // --- NuGet -------------------------------------------------------------------------------------------------

    /** A {@code .nupkg}: a zip whose {@code <id>.nuspec} names the coordinate the push keys the package by. */
    static byte[] nupkg(String id, String version) throws IOException {
        String nuspec = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<package><metadata>"
                + "<id>" + id + "</id><version>" + version + "</version>"
                + "<authors>contract</authors><description>a contract fixture package</description>"
                // A version-distinct dependency, so the sidecar the push precomputes differs between two versions of
                // one package: an identical sidecar would dedupe to one blob and a hold on either version's content
                // would mark the other's too, quietly coupling two versions that must be held independently.
                + "<dependencies><group targetFramework=\"net8.0\">"
                + "<dependency id=\"contract.dep." + version + "\" version=\"1.0.0\"/>"
                + "</group></dependencies>"
                + "</metadata></package>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(id + ".nuspec"));
            zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("lib/net8.0/" + id + ".dll"));
            zip.write(("payload of " + id + " " + version).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    // --- RubyGems ----------------------------------------------------------------------------------------------

    /** A {@code .gem}: a tar whose {@code metadata.gz} is the gzipped YAML gemspec the push reads the name and version
     *  from. The Ruby object tags are present because the format strips them itself - a fixture that pre-stripped them
     *  would stop exercising that. */
    static byte[] gem(String name, String version) throws IOException {
        String gemspec = "--- !ruby/object:Gem::Specification\n"
                + "name: " + name + "\n"
                + "version: !ruby/object:Gem::Version\n  version: " + version + "\n"
                + "licenses:\n- MIT\n"
                + "dependencies: []\n";
        byte[] metadata = gzip(gemspec.getBytes(StandardCharsets.UTF_8));
        return tar(Map.of("metadata.gz", metadata));
    }

    // --- Debian ------------------------------------------------------------------------------------------------

    /** A {@code .deb}: the {@code ar} archive of {@code debian-binary} and a gzipped {@code control.tar} whose
     *  {@code ./control} carries the package stanza the push indexes. */
    static byte[] deb(String pkg, String version, String architecture) throws IOException {
        String control = "Package: " + pkg + "\n"
                + "Version: " + version + "\n"
                + "Architecture: " + architecture + "\n"
                + "Maintainer: Contract <contract@example.invalid>\n"
                + "Description: a contract fixture package\n";
        byte[] controlTar = gzip(tar(Map.of("./control", control.getBytes(StandardCharsets.UTF_8))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ArArchiveOutputStream archive = new ArArchiveOutputStream(bytes)) {
            member(archive, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
            member(archive, "control.tar.gz", controlTar);
            member(archive, "data.tar", tar(Map.of("./usr/share/doc/" + pkg + "/README",
                    ("payload of " + pkg + " " + version).getBytes(StandardCharsets.UTF_8))));
        }
        return bytes.toByteArray();
    }

    // --- Swift Package Registry ---------------------------------------------------------------------------------

    /**
     * The multipart body SE-0292 defines for creating a package release: the source archive, and optionally the
     * release metadata and the package manifest.
     *
     * <p>Written here rather than in the fixture because it is the ecosystem's wire body and not the fixture's -
     * the same reason the twine form above is here - and because a registry that accepted anything looser would be
     * accepting something no {@code swift package-registry publish} sends.
     */
    static byte[] swiftForm(String boundary, byte[] archive, String metadata, String manifest) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(part(boundary, "source-archive", "application/zip", archive));
        if (metadata != null) {
            body.write(part(boundary, "metadata", "application/json",
                    metadata.getBytes(StandardCharsets.UTF_8)));
        }
        if (manifest != null) {
            body.write(part(boundary, "package-manifest", "text/x-swift",
                    manifest.getBytes(StandardCharsets.UTF_8)));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static byte[] part(String boundary, String name, String type, byte[] content) throws IOException {
        ByteArrayOutputStream part = new ByteArrayOutputStream();
        part.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        part.write(content);
        part.write("\r\n".getBytes(StandardCharsets.UTF_8));
        return part.toByteArray();
    }

    // --- Alpine apk --------------------------------------------------------------------------------------------

    /**
     * An {@code .apk}: a control segment carrying {@code .PKGINFO}, then the payload.
     *
     * <p><b>It is one tar stream split across two gzip members, not two tars concatenated.</b> Only the last member
     * carries the tar end-of-archive blocks and neither carries a tar writer's 10 KiB record padding - measured
     * against Alpine's own {@code musl-1.2.5-r3.apk}, whose two members are exactly 2560 and 665600 uncompressed
     * bytes, and confirmed by a real {@code apk add}, which answers {@code BAD archive} on a container with an
     * end-of-archive marker in the middle. A Java tar reader parses that container happily, because it opens each
     * member separately and never sees the seam, so building it the wrong way here would produce a fixture only
     * this product can read.
     */
    static byte[] apk(String name, String version, String architecture) throws IOException {
        byte[][] members = apkMembers(name, version, architecture);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(members[0].length + members[1].length);
        bytes.write(members[0]);
        bytes.write(members[1]);
        return bytes.toByteArray();
    }

    /** {@link #apk}'s two gzip members, control then data, for a test that needs the control member's checksum. */
    static byte[][] apkMembers(String name, String version, String architecture) throws IOException {
        byte[] data = apkMember("usr/share/" + name + "/README",
                ("payload of " + name + " " + version).getBytes(StandardCharsets.UTF_8), true);
        String info = "# Generated by a contract fixture\n"
                + "pkgname = " + name + "\n"
                + "pkgver = " + version + "\n"
                + "pkgdesc = a contract fixture package\n"
                + "url = https://example.invalid/" + name + "\n"
                + "builddate = 0\n"
                + "size = 64\n"
                + "arch = " + architecture + "\n"
                + "origin = " + name + "\n"
                + "maintainer = Contract <contract@example.invalid>\n"
                + "license = MIT\n"
                + "datahash = " + sha256(data) + "\n";
        byte[] control = apkMember(".PKGINFO", info.getBytes(StandardCharsets.UTF_8), false);
        return new byte[][] {control, data};
    }

    /** One gzip member holding one tar entry, cut to the blocks it uses - plus the end-of-archive pair when this is
     *  the member that ends the stream. */
    static byte[] apkMember(String name, byte[] content, boolean end) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(content.length + 2048);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(content.length);
            entry.setModTime(0L);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        int used = 512 + (content.length + 511) / 512 * 512 + (end ? 1024 : 0);
        return gzip(Arrays.copyOf(raw.toByteArray(), used));
    }

    // --- RPM ---------------------------------------------------------------------------------------------------

    /**
     * An {@code .rpm}: the 96-byte lead, a signature header with a five-byte store (so the reader's eight-byte
     * alignment padding is really exercised rather than always zero), the main header carrying the tags the
     * {@code repodata} generation reads, and a stand-in payload. The same spec-faithful assembler the RPM format's own
     * suite uses, which is itself pinned against a real {@code rpmbuild} package.
     */
    static byte[] rpm(String name, String version, String release, String architecture) throws IOException {
        ByteArrayOutputStream store = new ByteArrayOutputStream();
        List<int[]> index = new ArrayList<>();
        index.add(new int[]{1000, 6, string(store, name), 1});                                  // NAME
        index.add(new int[]{1001, 6, string(store, version), 1});                               // VERSION
        index.add(new int[]{1002, 6, string(store, release), 1});                               // RELEASE
        index.add(new int[]{1004, 9, string(store, "a contract fixture package"), 1});          // SUMMARY
        index.add(new int[]{1005, 9, string(store, "a contract fixture package body"), 1});     // DESCRIPTION
        index.add(new int[]{1014, 6, string(store, "MIT"), 1});                                 // LICENSE
        index.add(new int[]{1016, 9, string(store, "Unspecified"), 1});                         // GROUP
        index.add(new int[]{1022, 6, string(store, architecture), 1});                          // ARCH
        index.add(new int[]{1006, 4, int32(store, 1_700_000_000), 1});                          // BUILDTIME
        index.add(new int[]{1009, 4, int32(store, 4096), 1});                                   // SIZE

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(lead(name));
        byte[] signatureStore = {1, 2, 3, 4, 5};
        out.write(header(List.of(new int[]{62, 7, 0, signatureStore.length}), signatureStore));
        out.write(new byte[(8 - (signatureStore.length % 8)) % 8]);
        out.write(header(index, store.toByteArray()));
        out.write(("rpm payload of " + name + "-" + version).getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] lead(String name) {
        byte[] lead = new byte[96];
        lead[0] = (byte) 0xED;
        lead[1] = (byte) 0xAB;
        lead[2] = (byte) 0xEE;
        lead[3] = (byte) 0xDB;
        byte[] label = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(label, 0, lead, 10, Math.min(label.length, 65));
        return lead;
    }

    private static byte[] header(List<int[]> entries, byte[] store) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x8E, (byte) 0xAD, (byte) 0xE8, 0x01, 0, 0, 0, 0});
        int32(out, entries.size());
        int32(out, store.length);
        for (int[] entry : entries) {
            for (int value : entry) {
                int32(out, value);
            }
        }
        out.write(store);
        return out.toByteArray();
    }

    private static int string(ByteArrayOutputStream store, String value) throws IOException {
        int offset = store.size();
        store.write(value.getBytes(StandardCharsets.UTF_8));
        store.write(0);
        return offset;
    }

    private static int int32(ByteArrayOutputStream store, int value) {
        int offset = store.size();
        store.write((value >>> 24) & 0xFF);
        store.write((value >>> 16) & 0xFF);
        store.write((value >>> 8) & 0xFF);
        store.write(value & 0xFF);
        return offset;
    }

    // --- Cargo -------------------------------------------------------------------------------------------------

    /** Cargo's publish frame, as {@code cargo publish} sends it: a little-endian {@code u32} JSON-metadata length, the
     *  metadata, a {@code u32} {@code .crate} length, then the archive bytes. The archive itself is opaque to the
     *  protocol - the coordinate rides in the metadata - so the kit's own generated body publishes here unchanged. */
    static byte[] cargoFrame(String name, String version, byte[] crate) throws IOException {
        byte[] metadata = ("{\"name\":\"" + name + "\",\"vers\":\"" + version + "\",\"deps\":[],"
                + "\"features\":{},\"links\":null}").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        littleEndian(frame, metadata.length);
        frame.write(metadata);
        littleEndian(frame, crate.length);
        frame.write(crate);
        return frame.toByteArray();
    }

    private static void littleEndian(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    // --- conda -------------------------------------------------------------------------------------------------

    /** The legacy conda container: a bzip2 tar whose {@code info/index.json} carries the coordinate the push reads and
     *  the record the generated {@code repodata.json} buckets under {@code packages}. */
    static byte[] condaTarBz2(String name, String version, String build) throws IOException {
        byte[] tar = tar(Map.of("info/index.json", condaIndex(name, version, build).getBytes(StandardCharsets.UTF_8),
                "lib/marker.py", ("payload of " + name + " " + version + " " + build)
                        .getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(bytes)) {
            bzip2.write(tar);
        }
        return bytes.toByteArray();
    }

    /**
     * The modern conda container: a zip whose {@code info-<stem>.tar.zst} member is a Zstandard tar carrying
     * {@code info/index.json}, beside the {@code metadata.json} and {@code pkg-<stem>.tar.zst} members a real
     * {@code .conda} ships. Written with the same zstd stack the format's reader decompresses it with, and bucketed by
     * the generated {@code repodata.json} under {@code packages.conda} rather than {@code packages} - the second
     * container shape whose screening the withhold leg has to reach.
     */
    static byte[] conda(String name, String version, String build) throws IOException {
        String stem = name + "-" + version + "-" + build;
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        try (ZstdCompressorOutputStream zstd = new ZstdCompressorOutputStream(info)) {
            zstd.write(tar(Map.of("info/index.json",
                    condaIndex(name, version, build).getBytes(StandardCharsets.UTF_8))));
        }
        return zip(members(
                "metadata.json", "{\"conda_pkg_format_version\":2}".getBytes(StandardCharsets.UTF_8),
                "info-" + stem + ".tar.zst", info.toByteArray(),
                "pkg-" + stem + ".tar.zst", ("payload of " + stem).getBytes(StandardCharsets.UTF_8)));
    }

    /** A conda package's {@code info/index.json} - the coordinate, build and dependency metadata the publish reads out
     *  of the archive and re-emits as the package's {@code repodata} record. */
    private static String condaIndex(String name, String version, String build) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"build\":\"" + build + "\","
                + "\"build_number\":0,\"depends\":[\"python >=3.11\"],\"license\":\"BSD-3-Clause\"}";
    }

    // --- Composer / CocoaPods ----------------------------------------------------------------------------------

    /** A Composer package archive: a zip whose root {@code composer.json} names the coordinate the deploy path must
     *  agree with, plus the dependency and license metadata the generated {@code p2} stanza carries through. */
    static byte[] composer(String coordinate, String description) throws IOException {
        return zip(members("composer.json",
                ("{\"name\":\"" + coordinate + "\",\"description\":\"" + description + "\",\"license\":\"MIT\","
                        + "\"require\":{\"php\":\">=8.1\"}}").getBytes(StandardCharsets.UTF_8),
                "src/Widget.php", ("<?php // " + description).getBytes(StandardCharsets.UTF_8)));
    }

    /** A CocoaPods pod archive: a zip whose root {@code <name>.podspec.json} names the coordinate the deploy path must
     *  agree with. A {@code null} version omits the podspec's {@code version} field, which the format tolerates (it
     *  only refuses a podspec that names a <em>different</em> version) - the shape the traversal probe needs, since its
     *  version is a probe vector rather than a value a podspec could sensibly declare. */
    static byte[] podspec(String name, String version) throws IOException {
        StringBuilder podspec = new StringBuilder("{\"name\":\"").append(name).append('"');
        if (version != null) {
            podspec.append(",\"version\":\"").append(version).append('"');
        }
        podspec.append(",\"summary\":\"a contract fixture pod\",\"license\":\"MIT\"}");
        return zip(members(name + ".podspec.json", podspec.toString().getBytes(StandardCharsets.UTF_8),
                "Sources/" + name + ".swift", ("// " + name).getBytes(StandardCharsets.UTF_8)));
    }

    // --- shared containers -------------------------------------------------------------------------------------

    /** The named members in declaration order, so an archive's entries land in the order a real producer writes them
     *  (and the bytes are reproducible) rather than in {@code Map.of}'s per-JVM randomised one. */
    private static Map<String, byte[]> members(Object... pairs) {
        Map<String, byte[]> members = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            members.put((String) pairs[index], (byte[]) pairs[index + 1]);
        }
        return members;
    }

    /** A zip of the named members, written with java.base's own {@code ZipOutputStream} - the reader every zip-cracking
     *  format here uses. Entry times are pinned rather than defaulted to <em>now</em>, so two "identical" archives
     *  built a second apart do not differ byte for byte (and with them every content address) - the flake hit
     *  through {@code TarArchiveEntry}'s defaulted mtime, one container over. */
    static byte[] zip(Map<String, byte[]> members) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                ZipEntry entry = new ZipEntry(member.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(member.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** A small uncompressed tar of the named members, written with the library the formats read it with. */
    static byte[] tar(Map<String, byte[]> members) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> member : new TreeMap<>(members).entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(member.getKey());
                entry.setSize(member.getValue().length);
                // A tar entry's modification time defaults to NOW at one-second granularity, which would make two
                // "identical" fixtures built a second apart differ byte for byte - and every content address with them.
                entry.setModTime(0L);
                tar.putArchiveEntry(entry);
                tar.write(member.getValue());
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content);
        }
        return bytes.toByteArray();
    }

    private static void member(ArArchiveOutputStream archive, String name, byte[] content) throws IOException {
        archive.putArchiveEntry(new ArArchiveEntry(name, content.length));
        archive.write(content);
        archive.closeArchiveEntry();
    }

    // --- digests -----------------------------------------------------------------------------------------------

    /** The lower-case SHA-256 hex a blob is content-addressed under. */
    static String sha256(byte[] content) {
        return HexFormat.of().formatHex(digest("SHA-256").digest(content));
    }

    /** The lower-case SHA-1 hex an npm packument publishes as {@code dist.shasum} - and a Composer {@code p2} entry as
     *  its own {@code dist.shasum}, the digest {@code composer install} verifies a downloaded archive against. */
    static String sha1Hex(byte[] content) {
        return HexFormat.of().formatHex(digest("SHA-1").digest(content));
    }


    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
