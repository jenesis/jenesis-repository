package build.jenesis.repository.xml.test;

import module java.base;
import module java.xml;
import module org.junit.jupiter.api;
import build.jenesis.repository.xml.Xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlTest {

    private static final byte[] POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>demo</artifactId></project>
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    void a_document_parses_with_names_as_written_or_namespace_aware() throws Exception {
        Element plain = Xml.parse(POM).getDocumentElement();
        Element namespaced = Xml.namespaced(POM).getDocumentElement();

        assertThat(plain.getTagName()).isEqualTo("project");
        assertThat(plain.getNamespaceURI()).as("a plain read records no namespace").isNull();
        assertThat(namespaced.getLocalName()).isEqualTo("project");
        assertThat(namespaced.getNamespaceURI()).isEqualTo("http://maven.apache.org/POM/4.0.0");
    }

    @Test
    void a_doctype_is_refused_so_no_entity_is_declared_or_fetched() {
        byte[] entity = """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <project>&secret;</project>
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> Xml.parse(entity)).isInstanceOf(SAXException.class);
        assertThatThrownBy(() -> Xml.namespaced(entity)).isInstanceOf(SAXException.class);
    }

    @Test
    void a_body_that_is_not_xml_raises_and_prints_nothing() throws Exception {
        byte[] html = "Not Found".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertThatThrownBy(() -> Xml.parse(html)).isInstanceOf(SAXParseException.class);
            assertThatThrownBy(() -> Xml.namespaced(html)).isInstanceOf(SAXParseException.class);
        } finally {
            System.setErr(stderr);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .as("the fault travels in the exception; the JDK's default handler would print [Fatal Error]")
                .isEmpty();
    }
}
