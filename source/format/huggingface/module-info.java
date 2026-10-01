/**
 * The Hugging Face Hub registry format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat}
 * for {@code /huggingface/...}, serving models, datasets and spaces by revision - files streamed into the store on a
 * {@code PUT} to their resolve path and served back from it, with the repo-info and tree API derived from each
 * revision's stored file list - and an {@link build.jenesis.repository.format.ArtifactLayout} declaring the
 * {@code "Hugging Face"} ecosystem. It proxies an upstream Hub and imports a {@code huggingfaceml} registry by
 * replaying each file through its own {@code PUT}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.huggingface {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.huggingface to build.jenesis.repository.gateway.test,
            build.jenesis.repository.gateway.contract.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.huggingface.HuggingFaceFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.huggingface.HuggingFaceListingObserver;
}
