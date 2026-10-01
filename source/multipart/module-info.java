/**
 * The one {@code multipart/form-data} reader request bodies are parsed with: a forward-only, streaming, explicitly
 * bounded cursor over an envelope's parts ({@code MultipartBody}), and the writer beside it.
 *
 * <p>The NuGet push and the PyPI upload stream their file part into the store, and the console's settings import reads
 * a bounded document; Spring's {@code MultipartResolver} is off in every app because it would drain an artifact upload.
 * A module of its own, since a format module may require only its SPI and the console may not pull a format in -
 * {@code java.base} plus the pinned {@code org.apache.commons.fileupload2.core} boundary parser, so requiring it costs
 * a caller nothing.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.multipart {
    requires org.apache.commons.fileupload2.core;
    exports build.jenesis.repository.multipart;
}
