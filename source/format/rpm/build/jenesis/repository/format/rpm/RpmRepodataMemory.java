package build.jenesis.repository.format.rpm;

import module java.base;
import module java.xml;
import build.jenesis.repository.blobs.FamilyMemory;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A proxied repository's {@code repomd.xml} with its signature and key - {@code repomd.xml.asc} and
 * {@code repomd.xml.key} - remembered together as a {@link FamilyMemory.Family} for
 * {@code jenrepo.cache.upstream-ttl}.
 *
 * <p>{@code repomd.xml} names the repository's metadata files by location with their checksums, and dnf refuses a
 * file that does not match. Remembered, it agrees with what it names only while those files are the ones it named, so
 * the family is pinnable only where every location carries its own checksum in its name - createrepo's
 * unique metadata file names, the default - which makes each a file that never changes under its name. Such a
 * repository's {@code repomd.xml} is remembered with its signature and key, and the files it names are fetched by
 * those names; one the upstream has since removed answers {@code 404} and the node forgets the family, so the next
 * read fetches the current one. A repository whose metadata names do not carry their checksums is relayed fresh.
 */
final class RpmRepodataMemory {

    private static final Pattern FAMILY = Pattern.compile("(.*repodata/)(repomd\\.xml(?:\\.asc|\\.key)?)");
    private static final Pattern REPODATA = Pattern.compile("(.*repodata/).+");

    private RpmRepodataMemory() {
    }

    /** Whether {@code rest} names a member of a repository's {@code repomd.xml} family. */
    static boolean member(String rest) {
        return FAMILY.matcher(rest).matches();
    }

    /** Answer a member of the family from what the node remembers of it. */
    static boolean relay(ProxyFormat.Fetcher fetcher, String root, String rest, FormatExchange exchange,
                         ArtifactStore store) throws IOException {
        Matcher matcher = FAMILY.matcher(rest);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(rest + " is not a repomd.xml family member");
        }
        return FamilyMemory.relay(fetcher, family(root, matcher.group(1)), matcher.group(2), exchange, store,
                ProxyRelay.Document.ENUMERATION, null, (_, _) -> {
                });
    }

    /** Forget the family of the repository {@code rest} lies in, for a metadata file the upstream no longer has. */
    static void forget(String root, String rest, ArtifactStore store) {
        Matcher matcher = REPODATA.matcher(rest);
        if (matcher.matches()) {
            FamilyMemory.forget(family(root, matcher.group(1)), store);
        }
    }

    private static FamilyMemory.Family family(String root, String repodata) {
        return new FamilyMemory.Family(URI.create(root + repodata),
                List.of("repomd.xml", "repomd.xml.asc", "repomd.xml.key"), List.of("repomd.xml"),
                RpmRepodataMemory::uniquelyNamed);
    }

    /** Whether every metadata file a {@code repomd.xml} names carries its checksum in its location's name; a document
     *  that cannot be read, or names none, does not. */
    static boolean uniquelyNamed(byte[] repomd) {
        try {
            XMLStreamReader reader = RpmPackageDigests.factory()
                    .createXMLStreamReader(new ByteArrayInputStream(repomd));
            int named = 0;
            String checksum = null;
            String location = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "data" -> {
                            checksum = null;
                            location = null;
                        }
                        case "checksum" -> checksum = reader.getElementText().strip();
                        case "location" -> location = reader.getAttributeValue(null, "href");
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("data")) {
                    if (checksum == null || checksum.isEmpty() || location == null
                            || !location.substring(location.lastIndexOf('/') + 1).contains(checksum)) {
                        return false;
                    }
                    named++;
                }
            }
            return named > 0;
        } catch (XMLStreamException _) {
            return false;
        }
    }
}
