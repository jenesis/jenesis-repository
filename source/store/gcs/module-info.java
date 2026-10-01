/**
 * The Google Cloud Storage artifact-store backend over the JSON API, through Google's generated API client
 * ({@code google-api-services-storage}) and auth library, discovered through {@code provides} and selected with
 * {@code jenrepo.store=gcs}.
 *
 * <p>Credentials are Application Default Credentials - a key file ({@code jenrepo.gcs.credentials} or
 * {@code GOOGLE_APPLICATION_CREDENTIALS}), a {@code gcloud} login, or the metadata server, which lets a deployment run
 * keyless under Workload Identity. The version token is the object generation and a conditional write is
 * {@code ifGenerationMatch}, so a lost compare-and-set is the service's 412.
 *
 * <p>Not the Cloud Client Library ({@code google-cloud-storage}): it cannot load on the module path, since
 * {@code google-cloud-core} and {@code proto-google-common-protos} both own {@code com.google.cloud} and neither can be
 * excluded. The API client resolves once the Apache HTTP transport ({@code google-http-client-apache-v2},
 * {@code httpclient}, {@code httpcore}, {@code commons-logging}) and two annotation-only jars (one splitting
 * {@code javax.annotation} against {@code jsr305}) are dropped - the JDK transport is used. {@code grpc-api} stays,
 * required by name, because OpenCensus reaches {@code io.grpc.Context} through it; {@code grpc-context} is an empty jar
 * and dropped. The two OpenCensus jars have no module name, so they are aliased. {@code listenablefuture} and
 * {@code jsr305} are dropped on every path reaching them, and Guava is stated as its JRE flavour, since negotiation
 * picks Android's.
 *
 * <p>Each exclusion is declared on every path that reaches its jar, because an exclusion prunes only its own path: the
 * aliased client ({@code google.api.client} 2.9.1 maps to another artifact in the hosted module index) resolves in a
 * second pass of its own, after the storage service's path has reached the client without it.
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
    exports build.jenesis.repository.store.gcs;
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
