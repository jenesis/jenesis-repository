/**
 * The Hugging Face Hub registry format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the {@code /huggingface/...} HF Hub HTTP protocol, so
 * {@code huggingface_hub} ({@code hf_hub_download}, {@code snapshot_download}) resolves models, datasets and spaces over
 * the shared store. It owns {@code /huggingface/<repo>/...}: a repository file streamed straight into the
 * content-addressed store on a {@code PUT /<repo_id>/resolve/<revision>/<path>} (a dataset's under
 * {@code datasets/<repo_id>/resolve/...}, a space's under {@code spaces/<repo_id>/resolve/...}), served back from the
 * same resolve path, and the repository index a client reads - {@code GET /api/models/<repo_id>} with its
 * {@code siblings} file list, and {@code /api/models/<repo_id>/tree/<revision>} with each file's {@code oid} and
 * {@code size} - derived from the revision's stored file list on every upload. A file is addressed by its repository
 * revision (a git branch like {@code main} or a commit sha) exactly as the client requests it, so this format is a
 * streaming, revision-addressed file store with a stored, write-maintained index - nothing is buffered and no archive
 * is cracked (the coordinate is in the request path). It also provides {@link build.jenesis.repository.format.ArtifactLayout}, declaring
 * the {@code "Hugging Face"} ecosystem and resolving a resolve download path to its {@code <repo_id>} coordinate and
 * {@code <revision>} version. JSON index documents are emitted with the Jackson databind on the server's module path (a
 * library, not a hand-rolled writer). Discovered through {@code provides}. It also implements
 * {@link build.jenesis.repository.format.ProxyFormat}, so a proxy registry serves a local miss from an upstream Hub (the
 * canonical {@code https://huggingface.co/}): a {@code resolve} file streams from upstream into the store and is cached,
 * the mutable {@code /api/...} index is streamed through fresh - detected by the dispatcher through {@code instanceof},
 * so the {@code provides} clause and the module graph are unchanged. Finally it provides a
 * {@link build.jenesis.repository.format.RepositoryImporter} that migrates a Nexus/Artifactory {@code huggingfaceml}
 * registry, replaying each revision file (its {@code repo_id} and client-uploaded revision preserved in the resolve
 * path) as a streaming publish through the format's own {@code PUT} path so an exported registry round-trips. The
 * compliance inspector is a sibling module.
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
    exports build.jenesis.repository.format.huggingface to build.jenesis.repository.gateway.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.huggingface.HuggingFaceFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.huggingface.HuggingFaceListingObserver;
}
