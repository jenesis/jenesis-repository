/**
 * The listing half of the S3-compatible object-store backends, shared by {@code s3} and {@code gcs}.
 *
 * <p>Both speak the same S3 API through the same modular AWS SDK client; they differ only in the version token and
 * in how a conditional write is expressed. This module owns what they share so a fix to paging, scanning or
 * reading lands in both at once.
 *
 * <p>Netty 4.2 split {@code netty-codec} into codecs whose descriptors require their optional peers without
 * {@code static} ({@code netty-codec-marshalling} wants {@code org.jboss.marshalling}, {@code netty-codec-protobuf}
 * {@code protobuf.javanano}), so a module-path boot layer carrying them fails on the missing module. The S3 SDK pulls
 * netty for its async client, which this store never uses, and this module's flattened POM is where every consumer
 * of the S3 stores meets that closure - so the exclusion below is declared here as well as on the S3 store, since a
 * Maven exclusion drops an artifact only from the path it names (measured 2026-09-13).
 *
 * @jenesis.release 25
 * @jenesis.exclude software.amazon.awssdk.services.s3 io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin io.netty/netty-buffer 4.2.18.Final SHA-256/fdf236d2b76aa9710684401fdad7dff9dec56e43da5d39172c9a55ba5f14b360
 * @jenesis.pin io.netty/netty-codec 4.2.18.Final SHA-256/439645eb5061f8f50fbf27afacce394e4cecc4d373e1f7a384837e4661d5f430
 * @jenesis.pin io.netty/netty-codec-base 4.2.18.Final SHA-256/7e4612bead7ba88ac6f7908fa722f90ad6835ea96505c9e40b2f93686c1f61f3
 * @jenesis.pin io.netty/netty-codec-compression 4.2.18.Final SHA-256/9d8a4b9a6a2a166ec5a4bbe51a78b4f6a726457c51d81495743c7e7ccb9f4765
 * @jenesis.pin io.netty/netty-codec-http 4.2.18.Final SHA-256/2d50765eb58591ce35146c54ca032a257808d2165d8985a8522c70ea1470e8d7
 * @jenesis.pin io.netty/netty-codec-http2 4.2.18.Final SHA-256/a45a2b06c377b9c87c21ae2b37f04c8d808192590a73d977e7a60fd59921f283
 * @jenesis.pin io.netty/netty-codec-marshalling 4.2.18.Final SHA-256/eccf83cbbd1319db879424c4559d465dc43251fe1a8ff759c4320b5adbcf26b7
 * @jenesis.pin io.netty/netty-codec-protobuf 4.2.18.Final SHA-256/1bcaedd0da94f8579477f0848e910c24aced8b1ed7c3e1bff16d56fd0229b516
 * @jenesis.pin io.netty/netty-common 4.2.18.Final SHA-256/5d97cae5669685872339698efe13f74fe3cdb2dccdb36963b2352bd95acf7070
 * @jenesis.pin io.netty/netty-handler 4.2.18.Final SHA-256/6d5a08d9dd6d7d0211202e22dbb1629b23a62550335c7e80892da1d4cb115540
 * @jenesis.pin io.netty/netty-resolver 4.2.18.Final SHA-256/68373ec544cf769ba17bf2ef455166d98f2c260dff536edf35ef57067a9c155f
 * @jenesis.pin io.netty/netty-transport 4.2.18.Final SHA-256/eac4f12068db4489e60c6520fad663e5d872f0436a7c641aa9e08db649947863
 * @jenesis.pin io.netty/netty-transport-classes-epoll 4.2.18.Final SHA-256/3677ca998f3db749d3fdb0fb1b1674f9809f4fbe8f819a346f4858dfb16c4d95
 * @jenesis.pin io.netty/netty-transport-native-unix-common 4.2.18.Final SHA-256/cada7023d09136af128511ca94421d69dc84d0f1f64c9e1f127a1271bb62932a
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.4 SHA-256/bdef5f8841145cd76c4c605ff301bc840eea0d5292332b7b892a93316c0d8ee1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.3 SHA-256/18bfbbabb478dfb67f31aeaf428c387f3c3df654582e1309f708ee1f3086830a
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4.3 SHA-256/c7db7026b8e2dea39132b04a6069f6671e2858309b20a146ec5c7dd6ed73a0b6
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin software.amazon.awssdk.core 2.54.17 SHA-256/e359d931e304774f8053fa0b7e1268a0dab8d034ba20d4a82324d71805dff880
 * @jenesis.pin software.amazon.awssdk.services.s3 2.54.17 SHA-256/36f93b70181dbd91aa5efe2bf124c9b25e7359b13b281da0b2a4b82e83cc5b58
 * @jenesis.pin software.amazon.awssdk/annotations 2.54.17 SHA-256/f136c28a8841bff1e538bf10b4d9dc8c54621d2f812a1612985da9daecccb388
 * @jenesis.pin software.amazon.awssdk/apache5-client 2.54.17 SHA-256/871e7b53b7329bd1fc19b7252dcadbaeb9cb072fde2b05fe005b815d64a9d50e
 * @jenesis.pin software.amazon.awssdk/arns 2.54.17 SHA-256/c95c78e5fa26d4d88cb252988d3ac87ee40f3a03662b5dee7adaf259b18c97f2
 * @jenesis.pin software.amazon.awssdk/auth 2.54.17 SHA-256/ab9ff0c662d8653ea9acb59f79a95e34a6eca3a824e75497add8cf2c89252fca
 * @jenesis.pin software.amazon.awssdk/aws-core 2.54.17 SHA-256/1d9644f510edc6d9a28e5cdd933a2e57a343fe714b216836233b648361c7bd75
 * @jenesis.pin software.amazon.awssdk/aws-query-protocol 2.54.17 SHA-256/1998851761d17faaca6d627fb321920f632c2dbf4a352cbc537373625ed0cc52
 * @jenesis.pin software.amazon.awssdk/aws-xml-protocol 2.54.17 SHA-256/5bfe77d8449c1e4d78203365aaafd33bd5dcfa2d9a151d3d2979168d1ccd5db8
 * @jenesis.pin software.amazon.awssdk/checksums 2.54.17 SHA-256/f3b4db1b2648b1aa0b55811e9548cfef544e901c1c9c785eea402e37d834af5a
 * @jenesis.pin software.amazon.awssdk/checksums-spi 2.54.17 SHA-256/dbb45658bd5be8b4232ebc626a16c850d8155c6ad0ac38c7950ef31db1cf6053
 * @jenesis.pin software.amazon.awssdk/crt-core 2.54.17 SHA-256/6c82723f00fdb48a895225dea278274db8f7755678a1204f0490b374434e91b0
 * @jenesis.pin software.amazon.awssdk/endpoints-spi 2.54.17 SHA-256/9c303d27894ba63a53a1cc141a08f0331e37e903a9b0144d1179bf7f49a0264b
 * @jenesis.pin software.amazon.awssdk/http-auth 2.54.17 SHA-256/f2b75db255e032e1c88f0019a2064079b35d8895aa5d8f9a1151686380b421f4
 * @jenesis.pin software.amazon.awssdk/http-auth-aws 2.54.17 SHA-256/6b9c8a7cb7fe56fd9da4cec47295cb8ee177cc8c369cd34afb3c1fe25012bc28
 * @jenesis.pin software.amazon.awssdk/http-auth-aws-eventstream 2.54.17 SHA-256/5a480d2cb1190ccbbef273f511bd8aab76ca1fd52d313022afd4f5e31451095e
 * @jenesis.pin software.amazon.awssdk/http-auth-spi 2.54.17 SHA-256/c9ec1fcea7936beb7c18d73ccd5011653125b950de3590a0000676f7c06eded3
 * @jenesis.pin software.amazon.awssdk/http-client-spi 2.54.17 SHA-256/77848741c05636c40e23eb00aac514d4aae57d420582e03063e4d0543e78a409
 * @jenesis.pin software.amazon.awssdk/identity-spi 2.54.17 SHA-256/2919de075f4fc66a0aae12721e98f489a18bdcb340fc77aa592fca2027ac3834
 * @jenesis.pin software.amazon.awssdk/json-utils 2.54.17 SHA-256/b62027fa942806962802c4fa01f89a26ed5e6a54aade4ddbd5b8ef110c1938e5
 * @jenesis.pin software.amazon.awssdk/metrics-spi 2.54.17 SHA-256/7db7c26c858f809c4ec28a055bae927198473c902b566a7255f2a1ba3a8aef92
 * @jenesis.pin software.amazon.awssdk/netty-nio-client 2.54.17 SHA-256/4faaae2db7a602c2ae6241fc6c0fe9d55f0e46886c3713cd5d2fef2f677ea64a
 * @jenesis.pin software.amazon.awssdk/profiles 2.54.17 SHA-256/fb7fe665153ed20af07e302cd296a271c2b183659a90a8f011bb9933701da81d
 * @jenesis.pin software.amazon.awssdk/protocol-core 2.54.17 SHA-256/ebcfffe2ec348d028a84e4c9f918ba03c5b12907189f724a1a09c0270ef8f8c6
 * @jenesis.pin software.amazon.awssdk/regions 2.54.17 SHA-256/f45d1e32510d0aadd8072298ad207576dec507caa627c8b5f3f690ed8a2a4f7e
 * @jenesis.pin software.amazon.awssdk/retries 2.54.17 SHA-256/89a743b5f9cdd7fc32c406c3d7a25de7fb899ed68b5dc6d1a4a7ff6461caf8fb
 * @jenesis.pin software.amazon.awssdk/retries-spi 2.54.17 SHA-256/140c4ec460ab7c6b1aca606ddfd866223e7f5a1b143aa4bcc0a4261ce2af31c5
 * @jenesis.pin software.amazon.awssdk/s3 2.54.17 SHA-256/36f93b70181dbd91aa5efe2bf124c9b25e7359b13b281da0b2a4b82e83cc5b58
 * @jenesis.pin software.amazon.awssdk/sdk-core 2.54.17 SHA-256/e359d931e304774f8053fa0b7e1268a0dab8d034ba20d4a82324d71805dff880
 * @jenesis.pin software.amazon.awssdk/third-party-jackson-core 2.54.17 SHA-256/fb432be8d025865e3aa31af909c7d61fd69613dd3e2a992dc14fd8dbebd79ca0
 * @jenesis.pin software.amazon.awssdk/utils 2.54.17 SHA-256/8fd44e79dd8ef4c4115af13ce9f9453b7200a7cce949b79fb58ed8bbaf010dcd
 * @jenesis.pin software.amazon.awssdk/utils-lite 2.54.17 SHA-256/42e8a092447ce5b722dd55352515ec14fa62a73d829a16f1823469575782d33f
 * @jenesis.pin software.amazon.eventstream/eventstream 1.0.1 SHA-256/0c37d8e696117f02c302191b8110b0d0eb20fa412fce34c3a269ec73c16ce822
 */
module build.jenesis.repository.store.s3compatible {
    requires build.jenesis.repository.store;
    requires software.amazon.awssdk.services.s3;
    requires software.amazon.awssdk.core;
    exports build.jenesis.repository.store.s3compatible;
}
