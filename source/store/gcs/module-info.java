/**
 * The Google Cloud Storage artifact-store backend over GCS's JSON API, through Google's own API client
 * ({@code google-api-services-storage}, the generated client the Cloud Client Library itself speaks HTTP with) and
 * Google's auth library. A pure storage provider: it implements the {@code ArtifactStore} SPI and is discovered
 * through {@code provides}, so the server adds it to its module graph at deploy time and selects it with
 * {@code jenreg.store=gcs}, with no compile-time dependency from the server.
 *
 * <p>Credentials are Application Default Credentials: a service-account key file named by
 * {@code jenreg.gcs.credentials} or {@code GOOGLE_APPLICATION_CREDENTIALS}, a developer's {@code gcloud} login, or
 * the metadata server on GCE, GKE and Cloud Run - which is what lets a deployment run keyless under Workload
 * Identity. No HMAC interoperability key is involved. The version token is the object <em>generation</em>, GCS's own
 * per-incarnation number, and a conditional write is {@code ifGenerationMatch} on the insert, so a lost
 * compare-and-set is the service's 412 (see {@code GcsArtifactStore}).
 *
 * <p>Why the API client and not the Cloud Client Library ({@code google-cloud-storage}): measured 2026-09-05 against
 * 2.73.0, its closure is 91 jars and 57 MB with the gRPC transport, 48 and 20 MB without, and it cannot load on the
 * module path either way - {@code google-cloud-core} and {@code proto-google-common-protos} both own the package
 * {@code com.google.cloud} (the latter through two compute-only classes), and the common protos are reached from
 * gax's exception path, so neither can be excluded; the JDK refuses the graph with a {@code ResolutionException}.
 * The API client resolves cleanly once four jars this backend never loads are dropped: the Apache HTTP transport
 * ({@code google-http-client-apache-v2}, {@code httpclient}, {@code httpcore}, and the {@code commons-logging} only
 * they pull - the JDK transport is used) and two annotation-only jars, one of which splits {@code javax.annotation}
 * against {@code jsr305}. What is left is 21 jars and 5.7 MB, and every module required below declares its name.
 * {@code grpc-api} stays: OpenCensus, which the HTTP client instruments its requests with, reaches
 * {@code io.grpc.Context} through it. Guava is stated as its JRE flavour: negotiated, a closure takes the Android one.
 * Each of those jars is dropped on both paths that reach it - the API client required here, and the storage service
 * that depends on the same client - because an exclusion is scoped to the path it is declared on, and the client's
 * own requirement resolves in a second pass once it is aliased (the alias names the artifact, since the hosted module
 * index maps {@code google.api.client} 2.9.1 to another artifact), by which time the service's path has reached the
 * client without it. Measured 2026-09-14: aliasing the client alone brought the Apache transport back through the
 * service's path and the two annotation jars through the client's own second-pass subtree, which the exclusion
 * under {@code com.google.auth.oauth2} no longer reached.
 *
 * @jenesis.release 25
 * @jenesis.alias google.api.client com.google.api-client/google-api-client
 * @jenesis.bom pin-repository.properties
 * @jenesis.exclude com.google.auth.oauth2 com.google.auto.value/auto-value-annotations javax.annotation/javax.annotation-api
 * @jenesis.exclude google.api.client com.google.http-client/google-http-client-apache-v2 org.apache.httpcomponents/httpclient org.apache.httpcomponents/httpcore com.google.auto.value/auto-value-annotations javax.annotation/javax.annotation-api
 * @jenesis.exclude com.google.api.services.storage com.google.http-client/google-http-client-apache-v2 org.apache.httpcomponents/httpclient org.apache.httpcomponents/httpcore com.google.auto.value/auto-value-annotations javax.annotation/javax.annotation-api
 */
module build.jenesis.repository.store.gcs {
    exports build.jenesis.repository.store.gcs to build.jenesis.repository.store.gcs.test,
            build.jenesis.repository.store.backends.e2e;
    requires build.jenesis.repository.store;
    requires com.google.api.services.storage;
    requires google.api.client;
    requires com.google.api.client;
    requires com.google.api.client.json.gson;
    requires com.google.auth;
    requires com.google.auth.oauth2;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.gcs.GcsArtifactStoreProvider;
}
