/**
 * The one {@code multipart/form-data} reader the product parses request bodies with: a forward-only, streaming,
 * explicitly bounded cursor over an envelope's parts ({@code MultipartBody}).
 *
 * <p>Three call sites need it and none of them may know about the others - the NuGet push and the PyPI (twine) upload
 * hand their file part straight to the content-addressed store, and the console's settings import reads a bounded
 * document. Spring's {@code MultipartResolver} cannot serve any of them: it is switched off in every app on purpose,
 * because it (and {@code FormContentFilter}) would drain an <em>artifact</em> upload body before the format handler
 * read it. So the walk lived as two private copies inside the two format modules and the console simply did not work
 * wherever the resolver was off.
 *
 * <p>Hence a module of its own rather than a home inside one of them: a {@code format/*} module must require only its
 * SPI and never a peer or the console (&sect;2), and the console must not pull a format in. The module is
 * deliberately weightless - {@code java.base} plus the single already-pinned, permissively licensed boundary parser
 * both formats were already using ({@code org.apache.commons.fileupload2.core}; &sect;8 says reach for the library
 * rather than hand-scan a binary body) - so requiring it costs a caller nothing it did not already carry.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.multipart {
    requires org.apache.commons.fileupload2.core;
    exports build.jenesis.repository.multipart;
}
