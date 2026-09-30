package build.jenesis.repository.xml;

import module java.base;
import module java.xml;

/**
 * Parses a document that came from outside the product into a DOM, hardened and silent.
 *
 * <p>Hardened: a DOCTYPE is refused outright, so no entity is declared, expanded or fetched, and XInclude is off -
 * the document rides inside an artifact anyone may upload or an upstream may serve. Silent: a malformed document
 * raises its {@link SAXException} and nothing else, where the JDK's default error handler also writes the fault to
 * stderr. A recoverable error or a warning does not stop the parse, as with the default handler, and is not
 * printed either.
 *
 * <p>{@code DocumentBuilderFactory.newInstance()} is a provider lookup, too costly to repeat for every document a
 * refresh parses, and a factory is not safe to share across threads - so each thread keeps one configured factory
 * per namespace mode and a fresh {@code DocumentBuilder} is made per parse.
 */
public final class Xml {

    private static final ThreadLocal<DocumentBuilderFactory> PLAIN = ThreadLocal.withInitial(() -> factory(false));

    private static final ThreadLocal<DocumentBuilderFactory> NAMESPACED = ThreadLocal.withInitial(() -> factory(true));

    private static final ErrorHandler SILENT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
        }

        @Override
        public void error(SAXParseException exception) {
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    private Xml() {
    }

    /** The document, element names as written ({@code getTagName}); a namespace-aware reader is {@link #namespaced}. */
    public static Document parse(byte[] document) throws SAXException, IOException {
        return parse(PLAIN.get(), new ByteArrayInputStream(document));
    }

    /** The document, read namespace-aware, so {@code getLocalName} and {@code getNamespaceURI} answer. */
    public static Document namespaced(byte[] document) throws SAXException, IOException {
        return parse(NAMESPACED.get(), new ByteArrayInputStream(document));
    }

    private static Document parse(DocumentBuilderFactory factory, InputStream input) throws SAXException, IOException {
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException impossible) {
            throw new IllegalStateException("The configured XML parser is unavailable", impossible);
        }
        builder.setErrorHandler(SILENT);
        builder.setEntityResolver((_, _) -> {
            throw new SAXException("An external entity is never resolved");
        });
        return builder.parse(input);
    }

    private static DocumentBuilderFactory factory(boolean namespaceAware) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException impossible) {
            throw new IllegalStateException("A hardened XML parser is unavailable", impossible);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(namespaceAware);
        return factory;
    }
}
