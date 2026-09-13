/**
 * The Azure Blob artifact-store backend. azure-storage-blob ships a real Java module, so this is a plain
 * requires; the rest of the Azure SDK closure resolves transitively through Maven and is pinned here. A
 * pure storage provider: it implements the {@code ArtifactStore} SPI and is discovered through
 * {@code provides}, so the server adds it to its module graph at deploy time and selects it with
 * {@code jenreg.store=azure-blob}. The version token is the blob ETag, giving a true
 * cross-node compare-and-set on conditional writes (see {@code AzureArtifactStore}).
 *
 * <p>Netty 4.2 split {@code netty-codec} into codecs, and {@code netty-codec-marshalling} declares
 * {@code requires org.jboss.marshalling} without {@code static} for a dependency its own POM marks optional, so a
 * module-path boot layer that carries it fails on the missing module (measured 2026-09-13: nine test JVMs).
 * {@code netty-codec-protobuf} has the same shape ({@code protobuf.javanano}). Nothing here marshals or speaks
 * protobuf; the exclusion below drops both codecs from what the Azure HTTP client pulls in.
 *
 * @jenesis.release 25
 * @jenesis.exclude com.azure.storage.blob io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.pin com.azure.storage.blob 12.35.1 SHA-256/087bd34819f9d443cb9f745318e38548d5377f664959481f8acffa72f194e7d0
 * @jenesis.pin com.azure/azure-core 1.59.1 SHA-256/d8b1b21eeb0a60e7d1f98d435553278f1a8984bec1373d722a890bbaa7fffba5
 * @jenesis.pin com.azure/azure-core-http-netty 1.16.7 SHA-256/11a6f17d5b9efefaaba48f051c1beead12366b2dd1c2a5cf0092b42c2406aeb4
 * @jenesis.pin com.azure/azure-json 1.5.1 SHA-256/bad21d5eb306d82b85951b58a1d9e501a9b09970e452bee6d4d445fd5a91c519
 * @jenesis.pin com.azure/azure-storage-blob 12.35.1 SHA-256/087bd34819f9d443cb9f745318e38548d5377f664959481f8acffa72f194e7d0
 * @jenesis.pin com.azure/azure-storage-common 12.34.1 SHA-256/47f0fdd29ebcd05503131852dfc3e85f46697a6c7cdb7cfc7867e16290fb33ef
 * @jenesis.pin com.azure/azure-storage-internal-avro 12.20.1 SHA-256/6db9c9b6d2b0e6d063932443b3b4ae1134db725757fa48a777b2daf570ea7f07
 * @jenesis.pin com.azure/azure-xml 1.2.1 SHA-256/08b458481b656554605215ab0b165f68e6025359e52bea4736d032328d40ba3b
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.fasterxml.jackson.core/jackson-core 2.22.2 SHA-256/ff167a6317be15895706c26668f45b898efe40ab8780970658210fe1393d52a6
 * @jenesis.pin com.fasterxml.jackson.core/jackson-databind 2.22.2 SHA-256/d0da14c12b16b5d54719aa172d83b542ff4abeb8b0fb7db476fde8ceece760ca
 * @jenesis.pin com.fasterxml.jackson.datatype/jackson-datatype-jsr310 2.22.2 SHA-256/9df71cc7fb3781fd0bed6c05eebbfa8b39292a49f5619e76fdd0d889f64c8f33
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin io.netty/netty-buffer 4.2.18.Final SHA-256/fdf236d2b76aa9710684401fdad7dff9dec56e43da5d39172c9a55ba5f14b360
 * @jenesis.pin io.netty/netty-codec 4.2.18.Final SHA-256/439645eb5061f8f50fbf27afacce394e4cecc4d373e1f7a384837e4661d5f430
 * @jenesis.pin io.netty/netty-codec-base 4.2.18.Final SHA-256/7e4612bead7ba88ac6f7908fa722f90ad6835ea96505c9e40b2f93686c1f61f3
 * @jenesis.pin io.netty/netty-codec-classes-quic 4.2.18.Final SHA-256/d1c4c4327fde2bf6ec773b070344907a7a4844f1843528ae917905e2c3547478
 * @jenesis.pin io.netty/netty-codec-compression 4.2.18.Final SHA-256/9d8a4b9a6a2a166ec5a4bbe51a78b4f6a726457c51d81495743c7e7ccb9f4765
 * @jenesis.pin io.netty/netty-codec-dns 4.2.18.Final SHA-256/6f4b6da1483a82a637804740c2fb61385c9337a5e7ae5e357720cc03ba60b728
 * @jenesis.pin io.netty/netty-codec-http 4.2.18.Final SHA-256/2d50765eb58591ce35146c54ca032a257808d2165d8985a8522c70ea1470e8d7
 * @jenesis.pin io.netty/netty-codec-http2 4.2.18.Final SHA-256/a45a2b06c377b9c87c21ae2b37f04c8d808192590a73d977e7a60fd59921f283
 * @jenesis.pin io.netty/netty-codec-http3 4.2.18.Final SHA-256/cc1652ece111e35a7677c8260499cc6709950061998f9d1a36496b352f456273
 * @jenesis.pin io.netty/netty-codec-marshalling 4.2.18.Final SHA-256/eccf83cbbd1319db879424c4559d465dc43251fe1a8ff759c4320b5adbcf26b7
 * @jenesis.pin io.netty/netty-codec-protobuf 4.2.18.Final SHA-256/1bcaedd0da94f8579477f0848e910c24aced8b1ed7c3e1bff16d56fd0229b516
 * @jenesis.pin io.netty/netty-codec-socks 4.2.18.Final SHA-256/25dea7bc1ad990ab8959065f0690b63574d8db4f51033cd98458fc84c59367f3
 * @jenesis.pin io.netty/netty-common 4.2.18.Final SHA-256/5d97cae5669685872339698efe13f74fe3cdb2dccdb36963b2352bd95acf7070
 * @jenesis.pin io.netty/netty-handler 4.2.18.Final SHA-256/6d5a08d9dd6d7d0211202e22dbb1629b23a62550335c7e80892da1d4cb115540
 * @jenesis.pin io.netty/netty-handler-proxy 4.2.18.Final SHA-256/d7786de71c0d405b80b4262e0c585618e6f20e7fd3188cb4f599ffc4029ee9ea
 * @jenesis.pin io.netty/netty-resolver 4.2.18.Final SHA-256/68373ec544cf769ba17bf2ef455166d98f2c260dff536edf35ef57067a9c155f
 * @jenesis.pin io.netty/netty-resolver-dns 4.2.18.Final SHA-256/7dd3ec96bef04abc91a0f461f8acc19ebc0738c257ea653aea9dd0ad67b3b1ce
 * @jenesis.pin io.netty/netty-resolver-dns-classes-macos 4.2.18.Final SHA-256/377abd8b198bde174d18b74ef9bac84251b56c8e5ce373d7099bde0d297055c7
 * @jenesis.pin io.netty/netty-tcnative-boringssl-static 2.0.84.Final SHA-256/15fe111906d28b1075da6362db6b76590b232dd400e1401cf4af36fc4f321add
 * @jenesis.pin io.netty/netty-tcnative-classes 2.0.84.Final SHA-256/7a4437abf98b0cec053af4443fe321177d2cfccfec7bcad4e7d8b75571bbec4d
 * @jenesis.pin io.netty/netty-transport 4.2.18.Final SHA-256/eac4f12068db4489e60c6520fad663e5d872f0436a7c641aa9e08db649947863
 * @jenesis.pin io.netty/netty-transport-classes-epoll 4.2.18.Final SHA-256/3677ca998f3db749d3fdb0fb1b1674f9809f4fbe8f819a346f4858dfb16c4d95
 * @jenesis.pin io.netty/netty-transport-classes-kqueue 4.2.18.Final SHA-256/be48ee5b8a2328a1f4f2160270344175612dd9567787727de0dbaecb694beb51
 * @jenesis.pin io.netty/netty-transport-native-unix-common 4.2.18.Final SHA-256/cada7023d09136af128511ca94421d69dc84d0f1f64c9e1f127a1271bb62932a
 * @jenesis.pin io.projectreactor.netty/reactor-netty-core 1.3.7 SHA-256/8bf232cbaef9ee7445adecc714999aa8ccc8f027d00d881a686cc99c1a4952e9
 * @jenesis.pin io.projectreactor.netty/reactor-netty-http 1.3.7 SHA-256/f351ff98022e1b73dd93aa59283c3e69cdbb11e21ba217bed732cc0a7d9b83fa
 * @jenesis.pin io.projectreactor/reactor-core 3.8.7 SHA-256/9a5f1bfc5ad0416a410ff63beaa279cc30c2da3ae9b111a678c83c99298a1551
 * @jenesis.pin main/maven/io.netty/netty-codec-native-quic/jar/linux-aarch_64 4.2.18.Final SHA-256/df4d14edc1f6e03b27ba63ed8a72687b23c021cae2979c5ab76ef46e9af9fa38
 * @jenesis.pin main/maven/io.netty/netty-codec-native-quic/jar/linux-x86_64 4.2.18.Final SHA-256/45952dcfa200317add275fbbedf32967cb3e5528fc84a5d2b7ccc4439e8d3f88
 * @jenesis.pin main/maven/io.netty/netty-codec-native-quic/jar/osx-aarch_64 4.2.18.Final SHA-256/f99bd674139c0e70d82aee64911047a7bd69e5a8fb7c28fc828e1076490a9471
 * @jenesis.pin main/maven/io.netty/netty-codec-native-quic/jar/osx-x86_64 4.2.18.Final SHA-256/827c871846eab219e222d24af19c3b713549a51ecbc6769c70ec118eea03eefc
 * @jenesis.pin main/maven/io.netty/netty-codec-native-quic/jar/windows-x86_64 4.2.18.Final SHA-256/149bdc03262c145ab509a6464d27be4f32fa5a02723d178e1f2c445766f5102f
 * @jenesis.pin main/maven/io.netty/netty-resolver-dns-native-macos/jar/osx-x86_64 4.2.18.Final SHA-256/144823796e9b8222cce8ee8ab3f632f263564bdb4231c8569615506530df83f4
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-aarch_64 2.0.84.Final SHA-256/610f92fa30ee2a4f5022ddb005728b6914b53101de195a99266f526ce1aeda1e
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-x86_64 2.0.84.Final SHA-256/19d7cd7c3081c80ebe417af870e2fe62be1ca5969683d9cd402bb3ebc754d257
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-aarch_64 2.0.84.Final SHA-256/00e078d2296c987206a10a40bcc2328800e2233b1517a8254ed324a223301206
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-x86_64 2.0.84.Final SHA-256/06131177483011b3fc5c7a6636da89df53551cca0ea0f843b3f2fbf932c147ec
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/windows-x86_64 2.0.84.Final SHA-256/1aed01f988166b8c9c16381f885e5c521652167789b9d822e84f9992f9c1bae0
 * @jenesis.pin main/maven/io.netty/netty-transport-native-epoll/jar/linux-x86_64 4.2.18.Final SHA-256/32a340971182d901cea513588549cfadc392bb7342f930379a6e34d853a331f0
 * @jenesis.pin main/maven/io.netty/netty-transport-native-kqueue/jar/osx-x86_64 4.2.18.Final SHA-256/ad0a842bc9f8064dec6ee3c264e0f24131ebe690e9757616c4700490956d7f1f
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.store.azure {
    exports build.jenesis.repository.store.azure to build.jenesis.repository.store.azure.test,
            build.jenesis.repository.store.backends.e2e;
    requires build.jenesis.repository.store;
    requires com.azure.storage.blob;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.azure.AzureArtifactStoreProvider;
}
