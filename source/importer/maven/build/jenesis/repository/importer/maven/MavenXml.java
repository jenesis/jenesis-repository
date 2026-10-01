package build.jenesis.repository.importer.maven;

import module java.base;
import build.jenesis.repository.xml.Xml;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The two Maven documents the metadata refresh reads, through the shared hardened reader - no doctype, no external
 * entity, since they come from the migration source. Both navigate direct children only, so a {@code packaging} in a
 * plugin configuration or a {@code version} outside the versions block is never mistaken for the value.
 */
final class MavenXml {

    private MavenXml() {
    }

    /** The versions a {@code maven-metadata.xml} lists ({@code metadata > versioning > versions > version}), empty when
     *  it does not parse - a broken metadata skips the refresh. */
    static List<String> versions(byte[] metadata) {
        Element root = parse(metadata);
        Element versions = child(child(root, "versioning"), "versions");
        if (versions == null) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (Node node = versions.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals("version")) {
                String version = element.getTextContent().trim();
                if (!version.isEmpty()) {
                    parsed.add(version);
                }
            }
        }
        return parsed;
    }

    /** A pom's project-level packaging, {@code jar} when it declares none, or {@code null} when it does not parse (the
     *  pom is then imported without a derived primary artifact). */
    static String packaging(byte[] pom) {
        Element root = parse(pom);
        if (root == null) {
            return null;
        }
        Element packaging = child(root, "packaging");
        if (packaging == null) {
            return "jar";
        }
        String value = packaging.getTextContent().trim();
        return value.isEmpty() ? "jar" : value;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static Element parse(byte[] document) {
        try {
            return Xml.parse(document).getDocumentElement();
        } catch (SAXException | IOException unparseable) {
            return null;
        }
    }
}
