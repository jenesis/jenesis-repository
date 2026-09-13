/**
 * The carrier: one launchable module whose {@code requires} closure is every free SPI implementation -
 * all four layouts ({@code maven}, {@code jenesis}, {@code oci}, {@code raw}), all four store backends
 * ({@code filesystem}, {@code s3}, {@code gcs}, {@code azure}), all five import connectors, the upstream HTTP
 * fetcher ({@code proxy}), the OIDC token exchange ({@code oidc}), the token-bucket rate limiter, the credential
 * usage tracker and the web console ({@code ui}) - so the packaging {@code bundle} step emits a {@code bundle.zip}
 * carrying the complete free product, and the {@code Dockerfile} turns that one zip into the image.
 * Nothing here names a plugin: the server keeps discovering everything through {@code ServiceLoader}, and the image
 * is trimmed by configuration instead of rebuilt - {@code jenreg.<feature>=false} (settable as
 * {@code JENREG_<FEATURE>=false} through relaxed binding) disables an implementation exactly as if its
 * module were absent, and {@code jenreg.<spi>=<feature>} selects among exclusive implementations
 * (the store defaults to {@code filesystem}), per the {@code build.jenesis.repository.store.Features} convention.
 *
 * <p>{@link build.jenesis.repository.bundle.Server} boots the repository server under the config name
 * {@code bundle} ({@code bundle.properties} in this module), because with the server and the console both on
 * the module path two root {@code application.properties} would be ambiguous;
 * so the one image also runs the console node via {@code MAINMODULE}/{@code MAINCLASS}.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.repository.bundle.Server
 * @jenesis.pin ch.qos.logback/logback-classic 1.6.3 SHA-256/beebede8db065fe1b72909ecc66bb49acda618901635d86c25fce14a5915e37e
 * @jenesis.pin ch.qos.logback/logback-core 1.6.3 SHA-256/a6967a28c8b086dee75a694d2f6fb8830c71153539866d7d8af065c9b6df91e0
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
 * @jenesis.pin com.nimbusds/nimbus-jose-jwt 10.9.1 SHA-256/33152ea83ec50d22706fdaf3b07acbcd716f9a68edcabdd7c4d02843cbdcdcf6
 * @jenesis.pin commons-codec/commons-codec 1.22.1 SHA-256/78a5d732fbd715e2d10bd7150d2f8030bae57267f8aacc5c88f642cb6c2e5d3f
 * @jenesis.pin commons-logging/commons-logging 1.4.0 SHA-256/d175dbd751dd782a63bde28c7a039520e971f25e84b79c19b8435edc3603e0dc
 * @jenesis.pin io.grpc/grpc-api 1.84.0 SHA-256/a2a57594571a4392751609bf4c63cf838ba7dcdbc8e5fd07983c313cb1565ac8
 * @jenesis.pin io.grpc/grpc-context 1.84.0 SHA-256/e6f6e5db0d704e34d03f013cceb6be326c9ed3916346378ca1587ed41f943080
 * @jenesis.pin io.micrometer/micrometer-commons 1.17.1 SHA-256/f2cee6ef046e72eec8128474c7f58c6217173b45ae373c30de0289f2a56ab0fd
 * @jenesis.pin io.micrometer/micrometer-core 1.17.1 SHA-256/9ca47dfa831409b40253c8dc98c17b9d3e050ebbde4d1543c5521dca2b95ad56
 * @jenesis.pin io.micrometer/micrometer-jakarta9 1.17.1 SHA-256/37fbb0a98ba92e185ae7ebe0804fe6c1394bf7fba7573b204fa6fc3998c2e246
 * @jenesis.pin io.micrometer/micrometer-observation 1.17.1 SHA-256/eb34f0cd84a879393ae5c21160fc06385ccdc74f36a4d1e31b199f59acfdaf71
 * @jenesis.pin io.micrometer/micrometer-registry-prometheus 1.17.1 SHA-256/ac24d43f123e103828a23a86f092a8709d87d309ad3212e798305654e951a8ce
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
 * @jenesis.pin io.prometheus/prometheus-metrics-config 1.8.0 SHA-256/7b16c71dbbe9f6e9b2e9afef15746a371a7961229f4460ebe0157e8b711969d9
 * @jenesis.pin io.prometheus/prometheus-metrics-core 1.8.0 SHA-256/b5eaf00a8d6f9fd23714af0ed3846e954bbb2e53b484a54b53f18d2155111dc7
 * @jenesis.pin io.prometheus/prometheus-metrics-exposition-formats 1.8.0 SHA-256/1fdc5caa726f3d6cd60959894bf1c1ba12cc8e3e2283439915b9d689ebd845d0
 * @jenesis.pin io.prometheus/prometheus-metrics-exposition-textformats 1.8.0 SHA-256/17b6e78ad224cb1921e66ea622aea7fc00f143e292c540db8dc1a9af5cf660bc
 * @jenesis.pin io.prometheus/prometheus-metrics-model 1.8.0 SHA-256/18a5c698073306b46234280530ab6e36046b14fb681aa42fac640a94865246f2
 * @jenesis.pin io.prometheus/prometheus-metrics-tracer-common 1.8.0 SHA-256/037ee7c19e97aa6f36d77285812ae2c56ddfd32de4d9b1c759b97779c71a26d9
 * @jenesis.pin jakarta.annotation/jakarta.annotation-api 3.0.0 SHA-256/b01f55552284cfb149411e64eabca75e942d26d2e1786b32914250e4330afaa2
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.cdi-api 4.1.0 SHA-256/c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.lang-model 4.1.0 SHA-256/bb56f571f60d2862b2387d5468fe8f5540f8094727283ed991f89082708095ee
 * @jenesis.pin jakarta.inject/jakarta.inject-api 2.0.1 SHA-256/f7dc98062fccf14126abb751b64fab12c312566e8cbdc8483598bffcea93af7c
 * @jenesis.pin jakarta.interceptor/jakarta.interceptor-api 2.2.0 SHA-256/d240d72b4dd38a2e431c804079810010cb97903678fa5f987fb7434878b04398
 * @jenesis.pin jakarta.servlet/jakarta.servlet-api 6.1.0 SHA-256/8a31f465f3593bf2351531a5c952014eb839da96a605b5825b93dd54714c48c4
 * @jenesis.pin jakarta.transaction/jakarta.transaction-api 2.0.1 SHA-256/50c0a7c760c13ae6c042acf182b28f0047413db95b4636fb8879bcffab5ba875
 * @jenesis.pin jakarta.websocket/jakarta.websocket-api 2.2.0 SHA-256/541d00436cbca0a5e1f6a457c9f70a64f00bd2f83e10ed89c2b372bc34843b7e
 * @jenesis.pin jakarta.websocket/jakarta.websocket-client-api 2.2.0 SHA-256/aa6fa9331a3f470daee0dbfcf084abfbd7a49507297575d5bb8bfbf3d62fe8c0
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
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.4 SHA-256/bdef5f8841145cd76c4c605ff301bc840eea0d5292332b7b892a93316c0d8ee1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.3 SHA-256/18bfbbabb478dfb67f31aeaf428c387f3c3df654582e1309f708ee1f3086830a
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4.3 SHA-256/c7db7026b8e2dea39132b04a6069f6671e2858309b20a146ec5c7dd6ed73a0b6
 * @jenesis.pin org.apache.httpcomponents/httpclient 4.5.14 SHA-256/c8bc7e1c51a6d4ce72f40d2ebbabf1c4b68bfe76e732104b04381b493478e9d6
 * @jenesis.pin org.apache.httpcomponents/httpcore 4.4.16 SHA-256/6c9b3dd142a09dc468e23ad39aad6f75a0f2b85125104469f026e52a474e464f
 * @jenesis.pin org.apache.logging.log4j/log4j-api 2.26.1 SHA-256/f1810a4704ccce019d02bba029dc02a4f2ac0c997f647b465e7d409c1927f822
 * @jenesis.pin org.apache.logging.log4j/log4j-to-slf4j 2.26.1 SHA-256/6b10bc838a3c773b3fd02979c7ef4e7a443371fa89bda4415d89099bbee224df
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.attoparser/attoparser 2.0.9.RELEASE SHA-256/e0915e564346696639fae612745301996d26ab8e95ad7846a2331abc078d52b1
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-common 12.1.13 SHA-256/91bf7b735619870fd369aed9d79372ed8e6cdba2e4b4c44131a2a78fa15d4737
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-gzip 12.1.13 SHA-256/987afca3706a82595533ab49b60281f77daba688a46f2a7b2ac9498c9484be7d
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-server 12.1.13 SHA-256/69e69cd1ee46139a2abbe16abd2f98342e6e50ac82651ed0859dcd0c515e3a53
 * @jenesis.pin org.eclipse.jetty.ee/jetty-ee-webapp 12.1.13 SHA-256/15c82c0ecd1be08ef776a06bf138f5351c2554d84fd02bd72aa1d0dc88c10a0e
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-client 12.1.13 SHA-256/a2083743d8b0ba48eba0b05feeff5c97dfb46b073609d4525f300dde59fcabf0
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-common 12.1.13 SHA-256/5da0b18b0905a241c306d996a91ada0727932969b79df882c51ba0b0537932a0
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-server 12.1.13 SHA-256/2d3c633f306adb93f870936fbdf4f66af7c5491d450b49c3e54443d9c22aef14
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jetty-server 12.1.13 SHA-256/36f873ae12183a261dec1c944f286e5379389402e20c596bae94ebea268490ef
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-servlet 12.1.13 SHA-256/a28767255bc89e952dd8e3be6e8f6bce43a5d9dad19d36244732003e6fabae8b
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-annotations 12.1.13 SHA-256/f2476c80785ff4feb2508c847933c16e6a615dc892be7967cb874d99e13bf656
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-plus 12.1.13 SHA-256/9cee89b657538d445bdc597444494918fd3bd17a344a1b4e2dc6ed3c891860aa
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-servlet 12.1.13 SHA-256/c6fb3047015c4ba543e869056e68451574417af0ab3fc0a28a9c73993499f45e
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-webapp 12.1.13 SHA-256/de7c17288e1374698ec464018ac4106e38c87e407fd72991c10fc2fa5e438eea
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-client 12.1.13 SHA-256/7b6d472b40a44f09459de816882a06667850c8664fd585201b634fa597095479
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-common 12.1.13 SHA-256/fcd3abf1e4749751fc85a7b39c614f85feb07eee0bcbe7b8cb10b9bbb15904d0
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-server 12.1.13 SHA-256/dbe21adebf357f27033b2b76bb6b40ae5ecaa40fe3ba4b9d10bf7f4795fa93dc
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-api 12.1.13 SHA-256/2b71c6fd53619289188c169adaedf617ed01213d2c8f7063e4c2d0a88bbf7ece
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-common 12.1.13 SHA-256/3d1eced18d1aee79aa832fa90eb021e1eee9f717c437aa0ab81b8d85affe35c3
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-server 12.1.13 SHA-256/643fb282a75b1a8503990020db5cbef480be12dc997d37d8ab26dd2d5c191eef
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-client 12.1.13 SHA-256/26aa9b360efa88c50915f6f62916d5527f24dbcf75995038a3b0d9553553e407
 * @jenesis.pin org.eclipse.jetty/jetty-annotations 12.1.13 SHA-256/386524d7a5211ce454e5043a9afc55c1f6f23decd743a0d9764ee3e56e86a1b9
 * @jenesis.pin org.eclipse.jetty/jetty-client 12.1.13 SHA-256/a08216cc1388e454d5ddaa566bd3f3c5b7ac6b017773f36b37cdf462819f8415
 * @jenesis.pin org.eclipse.jetty/jetty-http 12.1.13 SHA-256/3bb84bfdd0992453986a8f7422dbf55cf779a0a780ff8a68041bb6119fb997fd
 * @jenesis.pin org.eclipse.jetty/jetty-io 12.1.13 SHA-256/946338a5db4e906ba0698dcdae350394736062cf915b5f16ebf787bcee7fffae
 * @jenesis.pin org.eclipse.jetty/jetty-jndi 12.1.13 SHA-256/3cd388f18cf7da3551f164b2a44506229cd3f04e0e7e9379f4bef178f1f3003c
 * @jenesis.pin org.eclipse.jetty/jetty-plus 12.1.13 SHA-256/2b8e399ba811951c44aca506de1c35df7700210673b542dfc059b7a393785cbf
 * @jenesis.pin org.eclipse.jetty/jetty-security 12.1.13 SHA-256/64b15214158097af40db345a31bb71cfe0d4a6c7c19cb59cd4c0a97245999ee8
 * @jenesis.pin org.eclipse.jetty/jetty-server 12.1.13 SHA-256/978265f1ac637c504b537f5d56e07d4a47e7c4bb0752d9dd66b79f5be754abfa
 * @jenesis.pin org.eclipse.jetty/jetty-session 12.1.13 SHA-256/30ebd5d63895f97aad0d626a567b40552d4c2bd9978adedc46e7a23b4652f595
 * @jenesis.pin org.eclipse.jetty/jetty-util 12.1.13 SHA-256/3e408e60fe71a337979004fc4e47bfd3c08a931cf33b46b6a5dc4dbdd088c2fd
 * @jenesis.pin org.eclipse.jetty/jetty-xml 12.1.13 SHA-256/ccf30ddde0326b9691818a3f797a1fc443a0633bf1b04989ed2387b7574f1e01
 * @jenesis.pin org.hdrhistogram/HdrHistogram 2.2.2 SHA-256/22d1d4316c4ec13a68b559e98c8256d69071593731da96136640f864fa14fad8
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.1.3 SHA-256/555d6cf20fa1710884dd01b86cc5785397ba73e21ada2d4b784f5f1a14dcafc4
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.1.3 SHA-256/414559e78bbfa3bdb2eab009ac3ab1e22369ac31117c270a0bd666da115c5c6c
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.1.3 SHA-256/05c51cedba2c06a9770707391e006cee825876937dea580b94a265ce145d4939
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.1.3 SHA-256/a4774ae923c109544034aeae7c762cf017fbcdd1342403ba90e0921984b608c3
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.1.3 SHA-256/21ad7ad3a35beda16387979317be1833a72a71d812f9a66ee4b9a7bafb56334c
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.ow2.asm/asm 9.10.1 SHA-256/ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb
 * @jenesis.pin org.ow2.asm/asm-commons 9.10.1 SHA-256/6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895
 * @jenesis.pin org.ow2.asm/asm-tree 9.10.1 SHA-256/3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.slf4j/jul-to-slf4j 2.0.19 SHA-256/a5764fbd2f0053399ef75d40653c4e7c97e733b45676946b0a072416fc56ff30
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.springframework.boot/spring-boot 4.1.1 SHA-256/0d92b532b1d4020640e72d78c31af175c23db8e0ddd45b81855b7fdf4c9c70f0
 * @jenesis.pin org.springframework.boot/spring-boot-actuator 4.1.1 SHA-256/2bbb38417af25cfc6f57a9b01a85db9b6ccdcadb9e5d40f6b1b2f54a08f8b798
 * @jenesis.pin org.springframework.boot/spring-boot-actuator-autoconfigure 4.1.1 SHA-256/bedbd4282a0f0c6a08bd5342d3940b50d8a013420d8a536695aedb1421b5e0f5
 * @jenesis.pin org.springframework.boot/spring-boot-autoconfigure 4.1.1 SHA-256/59228756ddd76cea050f95d86deff334789bc53b6688beaa673b1af2b9794592
 * @jenesis.pin org.springframework.boot/spring-boot-health 4.1.1 SHA-256/f60151e1f014b40761d0644be1cb04f82df7fc3e9b7bf6e946848547eec399e7
 * @jenesis.pin org.springframework.boot/spring-boot-http-converter 4.1.1 SHA-256/52a167430a918c223b2ccd2eb62aff91a8e43e6daebe5a07caa37b9a04c61cb9
 * @jenesis.pin org.springframework.boot/spring-boot-jetty 4.1.1 SHA-256/7ae005dba4dd00392c8260c4f28d1f9b0994c854b3955896a89402bf0aafeb77
 * @jenesis.pin org.springframework.boot/spring-boot-micrometer-metrics 4.1.1 SHA-256/70dd91dcc319d00998ce5e410b03b841e682aa2f60e4bab159c78ff0ecdb7d0f
 * @jenesis.pin org.springframework.boot/spring-boot-micrometer-observation 4.1.1 SHA-256/09ebd9ff7de21d10b1f11c70ca0ea8766a8a727affd19642584bee5a314ea11a
 * @jenesis.pin org.springframework.boot/spring-boot-security 4.1.1 SHA-256/c940697be9bc67820d5011b86d58105836b7cae7c8fb5d4e61d635b51b392cbe
 * @jenesis.pin org.springframework.boot/spring-boot-security-oauth2-client 4.1.1 SHA-256/f194178e5ba4ea7e27b2c1ba172f4f4b9ebcce4abc7780c93b82ad45d0a2ba38
 * @jenesis.pin org.springframework.boot/spring-boot-servlet 4.1.1 SHA-256/4dcb156e311a4c1ad5f1ed7eabd94a78ea74510b05c429a4fcfdcf8beb9d5882
 * @jenesis.pin org.springframework.boot/spring-boot-starter 4.1.1 SHA-256/de5b2dd28400eda20914fd4a6054d0c201e68d2e4de35a25a4d89f054ccc2e63
 * @jenesis.pin org.springframework.boot/spring-boot-starter-actuator 4.1.1 SHA-256/d26bb8461ccc417c97a869fbd2e9f0067ce8a56a9ab6058093097ca16e4cc128
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jetty 4.1.1 SHA-256/c516d9709ed3dc7ac4d0b5654cb4858f1eaed9bd231b872a359d2521c7039959
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jetty-runtime 4.1.1 SHA-256/685fe160af1fc854981829ebc7128735a328bd9eb71fcbf16ba50aec712bc1c7
 * @jenesis.pin org.springframework.boot/spring-boot-starter-logging 4.1.1 SHA-256/8eca7d72c8434f5c3b935385c656cd5337f473434bdbcb4913b3b3a34897d178
 * @jenesis.pin org.springframework.boot/spring-boot-starter-micrometer-metrics 4.1.1 SHA-256/ad34ce3535a82b3920efea7f9c5d6322de484969f3ab5fa6da05030edd7c14d4
 * @jenesis.pin org.springframework.boot/spring-boot-starter-oauth2-client 4.1.1 SHA-256/38d0ed03316db125a5122742495c90393e6cb0e22aac68064efba8543c3eac75
 * @jenesis.pin org.springframework.boot/spring-boot-starter-security 4.1.1 SHA-256/38628875b75cbed6ba4642f3b0a4baf4735bd4f82b49450e4ae5a5e4d1b7b4a9
 * @jenesis.pin org.springframework.boot/spring-boot-starter-thymeleaf 4.1.1 SHA-256/85e6e02b6c4df2b157e24eb88e75bf50ef64f3a30ac781a9027c2e3ebef4ee28
 * @jenesis.pin org.springframework.boot/spring-boot-thymeleaf 4.1.1 SHA-256/a74614ffe39bd63f54609b9bbcc01c16497de60e9530df3a6155284b5237bc86
 * @jenesis.pin org.springframework.boot/spring-boot-web-server 4.1.1 SHA-256/6912fb1fdf7f657a61ac050760fa835fb1e7496399291697e2901f82887a4fee
 * @jenesis.pin org.springframework.boot/spring-boot-webmvc 4.1.1 SHA-256/9f08c1fb938c45a8693fec5f3065be3aca203948a7ab4d56b06444986f75a6b2
 * @jenesis.pin org.springframework.security/spring-security-config 7.1.1 SHA-256/1f947c853f14cab76563464ee22e72f6672f3913db52647d2f5d1df5f2b1e5a5
 * @jenesis.pin org.springframework.security/spring-security-core 7.1.1 SHA-256/98a5011baa78df36fb184e6ce0e8e086ca9a64fcdd8f161c0a96475a3a0907bd
 * @jenesis.pin org.springframework.security/spring-security-crypto 7.1.1 SHA-256/6e8bb2337faccabd30626f917ed1d2bfa9dde982229b6b373b644d1a01635403
 * @jenesis.pin org.springframework.security/spring-security-oauth2-client 7.1.1 SHA-256/d871b3571201c69e0142cca3a61fbcca4fd4ec9c0dedb21fdd4fe4a77ff9991d
 * @jenesis.pin org.springframework.security/spring-security-oauth2-core 7.1.1 SHA-256/29140d71b37cf1246bf16e33619e88e7baaa1c66bf5574e3c3e9a9f225f0ef6e
 * @jenesis.pin org.springframework.security/spring-security-oauth2-jose 7.1.1 SHA-256/e07d47cac04de4f01be6a6c3f8be249472444b24fd4fad8ff030b45bfe6c5b9c
 * @jenesis.pin org.springframework.security/spring-security-web 7.1.1 SHA-256/dece134a2332c976a2c94ecf13f816c64e8ea7f2139795485afe655fba0408f3
 * @jenesis.pin org.springframework/spring-aop 7.0.9 SHA-256/b8c5d6bfcb1f4993f2cc124f7ab7fac40edf5256b9e43c2f7250a733cb5510e9
 * @jenesis.pin org.springframework/spring-beans 7.0.9 SHA-256/ff218b827a25c9e8929b0cd56dfb56916cea9d5b669ed97dc7cb262508ff548b
 * @jenesis.pin org.springframework/spring-context 7.0.9 SHA-256/7552a2fcfa30cea53eb14d7a65a7a8e1b1dd82e832a7164fb7e6fb105f438858
 * @jenesis.pin org.springframework/spring-core 7.0.9 SHA-256/5195f4722699b39878d99a832549fe65df2890b159d063b88fff31b1ca65ae36
 * @jenesis.pin org.springframework/spring-expression 7.0.9 SHA-256/046434c40f43819729b9b1db0e6c659dfa68184c1c2c2efa5f7b3a5b27c4e2e2
 * @jenesis.pin org.springframework/spring-web 7.0.9 SHA-256/941ced476427bde2533872f293da535fd0258de3f3a650a7f1bfe01ed2927302
 * @jenesis.pin org.springframework/spring-webmvc 7.0.9 SHA-256/8f114c1461692c5e534e82b27de23b7fb23370db8dee7ce0c92d5106906c9555
 * @jenesis.pin org.thymeleaf/thymeleaf 3.1.5.RELEASE SHA-256/4011795f8494dd69e764b7709443dd13d3068ba8ac37624f61d7084f4429cbe2
 * @jenesis.pin org.thymeleaf/thymeleaf-spring6 3.1.5.RELEASE SHA-256/fd5d306052d7aa6769a8ec77778d328e6f7c83af5ac074df38035bbb1e9cd72b
 * @jenesis.pin org.unbescape/unbescape 1.1.6.RELEASE SHA-256/597cf87d5b1a4f385b9d1cec974b7b483abb3ee85fc5b3f8b62af8e4bec95c2c
 * @jenesis.pin org.yaml/snakeyaml 2.7 SHA-256/2e194eba45a67dee19a4e272f4a04b18de8054e9f598b094382f6dae0b0e4b5e
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
 * @jenesis.pin spring.boot 4.1.1 SHA-256/0d92b532b1d4020640e72d78c31af175c23db8e0ddd45b81855b7fdf4c9c70f0
 * @jenesis.pin spring.context 7.0.9 SHA-256/7552a2fcfa30cea53eb14d7a65a7a8e1b1dd82e832a7164fb7e6fb105f438858
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.2 SHA-256/93d9090b8c505b2f942ad85dd5821e8c401bfbafafff11708a3fbe2ef9da18ca
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
 */
open module build.jenesis.repository.bundle {
    exports build.jenesis.repository.bundle;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.s3;
    requires build.jenesis.repository.store.gcs;
    requires build.jenesis.repository.store.azure;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.importer.artifactory;
    requires build.jenesis.repository.importer.index;
    requires build.jenesis.repository.importer.jenesis;
    requires build.jenesis.repository.importer.maven;
    requires build.jenesis.repository.importer.nexus;
    requires build.jenesis.repository.oidc;
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.usage;
    // Reclamation: the collector, and the walk consumer that runs it at the end of a pass. Without these an
    // installed collector is never called and a deployment's storage only ever grows.
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.gc.store;
    requires build.jenesis.repository.gc.walk;
    requires spring.boot;
    requires spring.context;
}
