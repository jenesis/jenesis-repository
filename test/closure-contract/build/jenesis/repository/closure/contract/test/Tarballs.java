package build.jenesis.repository.closure.contract.test;

import module java.base;
import module org.apache.commons.compress;

/** A gzipped tarball of named text members, as an npm package or a crate is shipped. */
final class Tarballs {

    private Tarballs() {
    }

    static byte[] of(Map<String, String> members) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes), "UTF-8")) {
            for (Map.Entry<String, String> member : new TreeMap<>(members).entrySet()) {
                byte[] content = member.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(member.getKey());
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
}
