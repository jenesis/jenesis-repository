/**
 * The store-backend contract suite: the JUnit driver for the testkit's {@code StoreContract}, one fixture per
 * {@code ArtifactStoreProvider} backend, and the completeness census that keeps the two in step.
 *
 * <p>The suite exists because {@code StoreInvariants} / {@code FaultInjectingStore} were shared but the backend
 * <em>contract</em> was not: {@code test/store/{filesystem,s3,gcs,azure}} each hand-wrote their own idea of
 * what an {@code ArtifactStore} promises and drifted apart. Here the contract is stated once in the testkit and every
 * backend runs all of it through a {@link build.jenesis.repository.store.testkit.StoreFixture}: the filesystem inline
 * on a temporary directory, {@code s3} and {@code gcs} against one MinIO container (the GCS backend speaks the
 * S3-compatible XML surface), {@code azure-blob} against Azurite. The containerised fixtures self-skip without a
 * Docker daemon and <em>fail</em> under the strict lane's {@code -Djenreg.test.required}, where the environment is
 * declared complete and a skip would be a broken lane reported as green.
 *
 * <p>This module deliberately requires all four backend implementations and reaches them only through
 * {@code ArtifactStoreProvider.resolve} - the way a deployment does - so it is simultaneously the runtime-discovery
 * graph the census needs: a backend module omitted here disappears from {@code ServiceLoader}, and the census fails
 * because the source {@code provides} scan still declares it.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.testkit
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
 * @jenesis.pin com.google.api-client/google-api-client 2.9.0 SHA-256/461377a5c904e8e4e0091cd1b4752bc9ef58b7223d608886d9642436d4b21273
 * @jenesis.pin com.google.api/api-common 2.68.0 SHA-256/499d5aa4a554630bb59ee07e62da8e0b149c8a3f0ecd195940b4b87bd292c4ac
 * @jenesis.pin com.google.apis/google-api-services-storage v1-rev20260821-2.0.0 SHA-256/840d8cecabb9ed64aca335d866acb8ca65cbbd72bc166cda906a2787a1050815
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
 * @jenesis.pin io.grpc/grpc-api 1.84.0 SHA-256/a2a57594571a4392751609bf4c63cf838ba7dcdbc8e5fd07983c313cb1565ac8
 * @jenesis.pin io.grpc/grpc-context 1.84.0 SHA-256/e6f6e5db0d704e34d03f013cceb6be326c9ed3916346378ca1587ed41f943080
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
 * @jenesis.pin io.opencensus/opencensus-api 0.31.1 SHA-256/f1474d47f4b6b001558ad27b952e35eda5cc7146788877fc52938c6eba24b382
 * @jenesis.pin io.opencensus/opencensus-contrib-http-util 0.31.1 SHA-256/3ea995b55a4068be22989b70cc29a4d788c2d328d1d50613a7a9afd13fdd2d0a
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
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.13 SHA-256/aa0013fbcb7e8086f4775f3dcb69a6c6833f7fd9023fe027d2d0241834cc51d5
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.4 SHA-256/bdef5f8841145cd76c4c605ff301bc840eea0d5292332b7b892a93316c0d8ee1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.3 SHA-256/18bfbbabb478dfb67f31aeaf428c387f3c3df654582e1309f708ee1f3086830a
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4.3 SHA-256/c7db7026b8e2dea39132b04a6069f6671e2858309b20a146ec5c7dd6ed73a0b6
 * @jenesis.pin org.apache.httpcomponents/httpclient 4.5.14 SHA-256/c8bc7e1c51a6d4ce72f40d2ebbabf1c4b68bfe76e732104b04381b493478e9d6
 * @jenesis.pin org.apache.httpcomponents/httpcore 4.4.16 SHA-256/6c9b3dd142a09dc468e23ad39aad6f75a0f2b85125104469f026e52a474e464f
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.junit.jupiter 6.1.3 SHA-256/6b0a14075c74221de53047e363860b7d19fce802bebc152df2c35c4089c6c379
 * @jenesis.pin org.junit.jupiter/junit-jupiter 6.1.3 SHA-256/6b0a14075c74221de53047e363860b7d19fce802bebc152df2c35c4089c6c379
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.1.3 SHA-256/555d6cf20fa1710884dd01b86cc5785397ba73e21ada2d4b784f5f1a14dcafc4
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.1.3 SHA-256/414559e78bbfa3bdb2eab009ac3ab1e22369ac31117c270a0bd666da115c5c6c
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.1.3 SHA-256/05c51cedba2c06a9770707391e006cee825876937dea580b94a265ce145d4939
 * @jenesis.pin org.junit.platform.console 6.1.3 SHA-256/913554ad65b9420936822889a7268a6793a942e5f77ad8b653b8ec05068fa3be
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.1.3 SHA-256/a4774ae923c109544034aeae7c762cf017fbcdd1342403ba90e0921984b608c3
 * @jenesis.pin org.junit.platform/junit-platform-console 6.1.3 SHA-256/913554ad65b9420936822889a7268a6793a942e5f77ad8b653b8ec05068fa3be
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.1.3 SHA-256/21ad7ad3a35beda16387979317be1833a72a71d812f9a66ee4b9a7bafb56334c
 * @jenesis.pin org.junit.platform/junit-platform-launcher 6.1.3 SHA-256/1dec64b1fc0b7c47a11302aa5b4d37d8160c041b396e27067709ce775601a7b6
 * @jenesis.pin org.junit.platform/junit-platform-reporting 6.1.3 SHA-256/d584a16cd87da5609af5e35428ed4f40b192681a2487dc59d02ecb62f6c417c2
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.7 SHA-256/ff1cf9d62786d067fa19c56f13cd6ed1077d0f8782ff269824b7a2c586101bf0
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
 * @jenesis.pin software.amazon.awssdk/url-connection-client 2.54.17 SHA-256/72be77373c797aaf46e2a20d6a439eb68b4e8a3ef98d335c56cb7a1bf2812217
 * @jenesis.pin software.amazon.awssdk/utils 2.54.17 SHA-256/8fd44e79dd8ef4c4115af13ce9f9453b7200a7cce949b79fb58ed8bbaf010dcd
 * @jenesis.pin software.amazon.awssdk/utils-lite 2.54.17 SHA-256/42e8a092447ce5b722dd55352515ec14fa62a73d829a16f1823469575782d33f
 * @jenesis.pin software.amazon.eventstream/eventstream 1.0.1 SHA-256/0c37d8e696117f02c302191b8110b0d0eb20fa412fce34c3a269ec73c16ce822
 */
open module build.jenesis.repository.store.contract.test {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.contract.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.s3;
    requires build.jenesis.repository.store.gcs;
    requires build.jenesis.repository.store.azure;
    requires org.junit.jupiter;
    requires org.assertj.core;

    // Discovery is the thing under test here, so this module loads the SPI itself rather than through a resolver
    // static: the census has to enumerate what ServiceLoader really sees in this graph and compare it against the
    // source `provides` scan. The same `uses`-in-a-test-module shape the importer census already uses in test/server.
    uses build.jenesis.repository.store.ArtifactStoreProvider;
}
