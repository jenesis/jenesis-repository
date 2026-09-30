/**
 * The Google Cloud Storage artifact-store backend over GCS's JSON API, through Google's own API client
 * ({@code google-api-services-storage}, the generated client the Cloud Client Library itself speaks HTTP with) and
 * Google's auth library. A pure storage provider: it implements the {@code ArtifactStore} SPI and is discovered
 * through {@code provides}, so the server adds it to its module graph at deploy time and selects it with
 * {@code jenrepo.store=gcs}, with no compile-time dependency from the server.
 *
 * <p>Credentials are Application Default Credentials: a service-account key file named by
 * {@code jenrepo.gcs.credentials} or {@code GOOGLE_APPLICATION_CREDENTIALS}, a developer's {@code gcloud} login, or
 * the metadata server on GCE, GKE and Cloud Run - which is what lets a deployment run keyless under Workload
 * Identity. No HMAC interoperability key is involved. The version token is the object <em>generation</em>, GCS's own
 * per-incarnation number, and a conditional write is {@code ifGenerationMatch} on the insert, so a lost
 * compare-and-set is the service's 412 (see {@code GcsArtifactStore}).
 *
 * <p>Why the API client and not the Cloud Client Library ({@code google-cloud-storage}): its closure is ninety-odd
 * jars with the gRPC transport and about half that without, and it cannot load on the module path either way -
 * {@code google-cloud-core} and {@code proto-google-common-protos} both own the package
 * {@code com.google.cloud} (the latter through two compute-only classes), and the common protos are reached from
 * gax's exception path, so neither can be excluded; the JDK refuses the graph with a {@code ResolutionException}.
 * The API client resolves cleanly once four jars this backend never loads are dropped: the Apache HTTP transport
 * ({@code google-http-client-apache-v2}, {@code httpclient}, {@code httpcore}, and the {@code commons-logging} only
 * they pull - the JDK transport is used) and two annotation-only jars, one of which splits {@code javax.annotation}
 * against {@code jsr305}. What is left is 21 jars and 5.7 MB, and every module required below declares its name.
 * {@code grpc-api} stays: OpenCensus, which the HTTP client instruments its requests with, reaches
 * {@code io.grpc.Context} through it. It used to arrive through {@code grpc-context}, which is an empty jar since
 * {@code Context} moved into {@code grpc-api}, so that jar is dropped and {@code grpc-api} required under its own
 * name. The two OpenCensus jars carry no module name of their own, so they are aliased and required here: an
 * unnamed jar would put the image on the class path for them alone. {@code listenablefuture} (an empty jar that
 * exists to shadow Guava) and {@code jsr305} (annotations the JVM does not need to load a class) are dropped
 * on every requirement whose path reaches them, since an exclusion prunes only the path it is declared on. Guava is stated as its JRE flavour: negotiated, a closure takes the Android one.
 * Each of those jars is dropped on both paths that reach it - the API client required here, and the storage service
 * that depends on the same client - because an exclusion is scoped to the path it is declared on, and the client's
 * own requirement resolves in a second pass once it is aliased (the alias names the artifact, since the hosted module
 * index maps {@code google.api.client} 2.9.1 to another artifact), by which time the service's path has reached the
 * client without it. Aliasing the client alone brings the Apache transport back through the service's path and the
 * two annotation jars through the client's own second-pass subtree, which the exclusion under
 * {@code com.google.auth.oauth2} does not reach.
 *
 * @jenesis.release 25
 * @jenesis.alias google.api.client com.google.api-client/google-api-client
 * @jenesis.alias io.grpc io.grpc/grpc-api
 * @jenesis.alias io.opencensus.api io.opencensus/opencensus-api
 * @jenesis.alias io.opencensus.contrib.http.util io.opencensus/opencensus-contrib-http-util
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.exclude com.google.auth.oauth2 com.google.auto.value/auto-value-annotations javax.annotation/javax.annotation-api com.google.guava/listenablefuture com.google.code.findbugs/jsr305 io.grpc/grpc-context
 * @jenesis.exclude google.api.client com.google.http-client/google-http-client-apache-v2 org.apache.httpcomponents/httpclient org.apache.httpcomponents/httpcore com.google.auto.value/auto-value-annotations javax.annotation/javax.annotation-api com.google.guava/listenablefuture com.google.code.findbugs/jsr305 io.grpc/grpc-context
 * @jenesis.exclude com.google.api.services.storage com.google.http-client/google-http-client-apache-v2 org.apache.httpcomponents/httpclient org.apache.httpcomponents/httpcore com.google.auto.value/auto-value-annotations javax.annotation/javax.annotation-api com.google.guava/listenablefuture com.google.code.findbugs/jsr305 io.grpc/grpc-context
 * @jenesis.exclude com.google.api.client com.google.code.findbugs/jsr305 com.google.guava/listenablefuture io.grpc/grpc-context
 * @jenesis.exclude com.google.api.client.json.gson com.google.code.findbugs/jsr305 com.google.guava/listenablefuture io.grpc/grpc-context
 * @jenesis.exclude com.google.auth com.google.code.findbugs/jsr305 com.google.guava/listenablefuture io.grpc/grpc-context
 * @jenesis.exclude io.grpc com.google.code.findbugs/jsr305 com.google.guava/listenablefuture
 * @jenesis.exclude io.opencensus.api io.grpc/grpc-context
 * @jenesis.exclude io.opencensus.contrib.http.util io.grpc/grpc-context com.google.guava/listenablefuture com.google.code.findbugs/jsr305
 */
module build.jenesis.repository.store.gcs {
    // The storage client speaks over the product's own HTTP client, not a URL connection.
    requires build.jenesis.repository.net.http;
    exports build.jenesis.repository.store.gcs to build.jenesis.repository.store.gcs.test,
            build.jenesis.repository.store.backends.e2e;
    requires build.jenesis.repository.store;
    requires com.google.api.services.storage;
    requires google.api.client;
    requires com.google.api.client;
    requires com.google.api.client.json.gson;
    requires com.google.auth;
    requires com.google.auth.oauth2;
    requires io.grpc;
    requires io.opencensus.api;
    requires io.opencensus.contrib.http.util;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.gcs.GcsArtifactStoreProvider;
}
