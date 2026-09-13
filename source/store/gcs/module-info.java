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
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.api-client/google-api-client 2.9.0 SHA-256/461377a5c904e8e4e0091cd1b4752bc9ef58b7223d608886d9642436d4b21273
 * @jenesis.pin com.google.api.client 2.2.0 SHA-256/36cea7079c550aeb12fa7366a1ae65d503eaf15bcfc4f50124fde2a8f784e568
 * @jenesis.pin com.google.api.client.json.gson 2.2.0 SHA-256/3242dc7e91d355d118a355c5d586271bedfeb84c3f2f6fa2f3b9b42503a4dc59
 * @jenesis.pin com.google.api.services.storage v1-rev20260821-2.0.0 SHA-256/840d8cecabb9ed64aca335d866acb8ca65cbbd72bc166cda906a2787a1050815
 * @jenesis.pin com.google.api/api-common 2.68.0 SHA-256/499d5aa4a554630bb59ee07e62da8e0b149c8a3f0ecd195940b4b87bd292c4ac
 * @jenesis.pin com.google.apis/google-api-services-storage v1-rev20260821-2.0.0 SHA-256/840d8cecabb9ed64aca335d866acb8ca65cbbd72bc166cda906a2787a1050815
 * @jenesis.pin com.google.auth 1.52.0 SHA-256/fa78b2d850b944be7f2e4182e979dc21e3160e04afc79ee6155fbfe0499136c2
 * @jenesis.pin com.google.auth.oauth2 1.52.0 SHA-256/bf998504deb2ac4c5b402fb8983dd08dd7a9ea2fd23817be273f09bb34a2ede0
 * @jenesis.pin com.google.auth/google-auth-library-credentials 1.52.0 SHA-256/fa78b2d850b944be7f2e4182e979dc21e3160e04afc79ee6155fbfe0499136c2
 * @jenesis.pin com.google.auth/google-auth-library-oauth2-http 1.52.0 SHA-256/bf998504deb2ac4c5b402fb8983dd08dd7a9ea2fd23817be273f09bb34a2ede0
 * @jenesis.pin com.google.code.findbugs/jsr305 3.0.2 SHA-256/766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7
 * @jenesis.pin com.google.code.gson/gson 2.14.0 SHA-256/2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin com.google.guava/failureaccess 1.0.3 SHA-256/cbfc3906b19b8f55dd7cfd6dfe0aa4532e834250d7f080bd8d211a3e246b59cb
 * @jenesis.pin com.google.guava/guava 33.7.1-jre SHA-256/796d8e28ac64e83a47c4c5935a8fecc4682650a04bbdead738ef0f5a3a0e6c46
 * @jenesis.pin com.google.guava/listenablefuture 9999.0-empty-to-avoid-conflict-with-guava SHA-256/b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99
 * @jenesis.pin com.google.http-client/google-http-client 2.2.0 SHA-256/36cea7079c550aeb12fa7366a1ae65d503eaf15bcfc4f50124fde2a8f784e568
 * @jenesis.pin com.google.http-client/google-http-client-gson 2.2.0 SHA-256/3242dc7e91d355d118a355c5d586271bedfeb84c3f2f6fa2f3b9b42503a4dc59
 * @jenesis.pin com.google.j2objc/j2objc-annotations 3.1 SHA-256/84d3a150518485f8140ea99b8a985656749629f6433c92b80c75b36aba3b099b
 * @jenesis.pin com.google.oauth-client/google-oauth-client 1.39.0 SHA-256/27fc61ee2d526e33d31350b5ea383091c0879345e261f9b2e6fcc97a20c86f88
 * @jenesis.pin commons-codec/commons-codec 1.22.1 SHA-256/78a5d732fbd715e2d10bd7150d2f8030bae57267f8aacc5c88f642cb6c2e5d3f
 * @jenesis.pin google.api.client 2.9.0 SHA-256/461377a5c904e8e4e0091cd1b4752bc9ef58b7223d608886d9642436d4b21273
 * @jenesis.pin io.grpc/grpc-api 1.84.0 SHA-256/a2a57594571a4392751609bf4c63cf838ba7dcdbc8e5fd07983c313cb1565ac8
 * @jenesis.pin io.grpc/grpc-context 1.84.0 SHA-256/e6f6e5db0d704e34d03f013cceb6be326c9ed3916346378ca1587ed41f943080
 * @jenesis.pin io.opencensus/opencensus-api 0.31.1 SHA-256/f1474d47f4b6b001558ad27b952e35eda5cc7146788877fc52938c6eba24b382
 * @jenesis.pin io.opencensus/opencensus-contrib-http-util 0.31.1 SHA-256/3ea995b55a4068be22989b70cc29a4d788c2d328d1d50613a7a9afd13fdd2d0a
 * @jenesis.pin org.apache.httpcomponents/httpclient 4.5.14 SHA-256/c8bc7e1c51a6d4ce72f40d2ebbabf1c4b68bfe76e732104b04381b493478e9d6
 * @jenesis.pin org.apache.httpcomponents/httpcore 4.4.16 SHA-256/6c9b3dd142a09dc468e23ad39aad6f75a0f2b85125104469f026e52a474e464f
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
