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
 *
 * @jenesis.release 25
 * @jenesis.pin com.google.api-client/google-api-client 2.9.0 SHA-256/461377a5c904e8e4e0091cd1b4752bc9ef58b7223d608886d9642436d4b21273
 * @jenesis.pin com.google.api.client 2.2.0
 * @jenesis.pin com.google.api.client.json.gson 2.2.0
 * @jenesis.pin com.google.api.services.storage v1-rev20260524-2.0.0
 * @jenesis.pin com.google.api/api-common 2.68.0 SHA-256/499d5aa4a554630bb59ee07e62da8e0b149c8a3f0ecd195940b4b87bd292c4ac
 * @jenesis.pin com.google.apis/google-api-services-storage v1-rev20260524-2.0.0 SHA-256/a00a1466c80f21318d134d77382082f2ffbad3fef537d1cb22ff9a913c9d95d9
 * @jenesis.pin com.google.auth 1.51.0
 * @jenesis.pin com.google.auth.oauth2 1.52.0
 * @jenesis.pin com.google.auth/google-auth-library-credentials 1.51.0 SHA-256/bce65ca6d689855a88ef2d45254f8f541d26d793472593e5f906dcaf05274db1
 * @jenesis.pin com.google.auth/google-auth-library-oauth2-http 1.52.0 SHA-256/bf998504deb2ac4c5b402fb8983dd08dd7a9ea2fd23817be273f09bb34a2ede0
 * @jenesis.pin com.google.code.findbugs/jsr305 3.0.2 SHA-256/766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7
 * @jenesis.pin com.google.code.gson/gson 2.11.0 SHA-256/57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.36.0 SHA-256/77440e270b0bc9a249903c5a076c36a722c4886ca4f42675f2903a1c53ed61a5
 * @jenesis.pin com.google.guava/failureaccess 1.0.3 SHA-256/cbfc3906b19b8f55dd7cfd6dfe0aa4532e834250d7f080bd8d211a3e246b59cb
 * @jenesis.pin com.google.guava/guava 33.6.0-jre SHA-256/dc573e1fca4fd5454f4a5fd3d7da2df03002876a4175bafc14a95980dd7713b3
 * @jenesis.pin com.google.guava/listenablefuture 9999.0-empty-to-avoid-conflict-with-guava SHA-256/b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99
 * @jenesis.pin com.google.http-client/google-http-client 2.2.0 SHA-256/36cea7079c550aeb12fa7366a1ae65d503eaf15bcfc4f50124fde2a8f784e568
 * @jenesis.pin com.google.http-client/google-http-client-gson 2.2.0 SHA-256/3242dc7e91d355d118a355c5d586271bedfeb84c3f2f6fa2f3b9b42503a4dc59
 * @jenesis.pin com.google.j2objc/j2objc-annotations 3.0.0 SHA-256/88241573467ddca44ffd4d74aa04c2bbfd11bf7c17e0c342c94c9de7a70a7c64
 * @jenesis.pin com.google.oauth-client/google-oauth-client 1.36.0 SHA-256/8fee7bbe7aaee214ce461f0cd983e3c438fd43941697394391aaa01edb7d703b
 * @jenesis.pin commons-codec/commons-codec 1.17.1 SHA-256/f9f6cb103f2ddc3c99a9d80ada2ae7bf0685111fd6bffccb72033d1da4e6ff23
 * @jenesis.pin google.api.client 2.9.0
 * @jenesis.pin io.grpc/grpc-api 1.70.0 SHA-256/45faf2ac1bf2791e8fdabce53684a86b62c99b84cba26fb13a5ba3f4abf80d6c
 * @jenesis.pin io.grpc/grpc-context 1.70.0 SHA-256/eb2824831c0ac03e741efda86b141aa863a481ebc4aaf5a5c1f13a481dbb40ff
 * @jenesis.pin io.opencensus/opencensus-api 0.31.1 SHA-256/f1474d47f4b6b001558ad27b952e35eda5cc7146788877fc52938c6eba24b382
 * @jenesis.pin io.opencensus/opencensus-contrib-http-util 0.31.1 SHA-256/3ea995b55a4068be22989b70cc29a4d788c2d328d1d50613a7a9afd13fdd2d0a
 * @jenesis.pin org.apache.httpcomponents/httpclient 4.5.14 SHA-256/c8bc7e1c51a6d4ce72f40d2ebbabf1c4b68bfe76e732104b04381b493478e9d6
 * @jenesis.pin org.apache.httpcomponents/httpcore 4.4.16 SHA-256/6c9b3dd142a09dc468e23ad39aad6f75a0f2b85125104469f026e52a474e464f
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 * @jenesis.exclude com.google.auth.oauth2 com.google.auto.value/auto-value-annotations javax.annotation/javax.annotation-api
 * @jenesis.exclude google.api.client com.google.http-client/google-http-client-apache-v2 org.apache.httpcomponents/httpclient org.apache.httpcomponents/httpcore
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
