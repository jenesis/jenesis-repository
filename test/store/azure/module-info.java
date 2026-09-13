/**
 * Integration tests for what is <em>particular</em> to the Azure Blob artifact-store backend, run against an Azurite
 * emulator: an aborted upload that must abandon its {@code BlobOutputStream} unclosed so the staged block list is
 * never committed as a truncated blob, a container-level 404 that must surface as a transport error rather than a
 * silent compare-and-set conflict, a ranged read over a real {@code BlobRange}, a presigned SAS URL, owner-only upload
 * spooling, and a &gt;1000-key prefix that must drain every page of the SDK's {@code PagedIterable}. The cross-backend
 * {@code ArtifactStore} contract itself lives in the shared {@code StoreContract} kit and runs against this backend
 * from {@code test/store/contract}. The suite skips itself (JUnit assumptions) when no Docker daemon is reachable, so
 * a checkout without Docker still builds green.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.azure
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.pin com.azure.storage.blob 12.35.1 SHA-256/087bd34819f9d443cb9f745318e38548d5377f664959481f8acffa72f194e7d0
 * @jenesis.pin com.azure/azure-core 1.59.1 SHA-256/d8b1b21eeb0a60e7d1f98d435553278f1a8984bec1373d722a890bbaa7fffba5
 * @jenesis.pin com.azure/azure-core-http-netty 1.16.7 SHA-256/11a6f17d5b9efefaaba48f051c1beead12366b2dd1c2a5cf0092b42c2406aeb4
 * @jenesis.pin com.azure/azure-json 1.5.1 SHA-256/bad21d5eb306d82b85951b58a1d9e501a9b09970e452bee6d4d445fd5a91c519
 * @jenesis.pin com.azure/azure-storage-blob 12.35.1 SHA-256/087bd34819f9d443cb9f745318e38548d5377f664959481f8acffa72f194e7d0
 * @jenesis.pin com.azure/azure-storage-common 12.34.1 SHA-256/47f0fdd29ebcd05503131852dfc3e85f46697a6c7cdb7cfc7867e16290fb33ef
 * @jenesis.pin com.azure/azure-storage-internal-avro 12.20.1 SHA-256/6db9c9b6d2b0e6d063932443b3b4ae1134db725757fa48a777b2daf570ea7f07
 * @jenesis.pin com.azure/azure-xml 1.2.1 SHA-256/08b458481b656554605215ab0b165f68e6025359e52bea4736d032328d40ba3b
 * @jenesis.pin com.ethlo.time/itu 1.14.0 SHA-256/5cf40ab0cc77828ab2b875b1f3ecd71c8295d7721933476abc2e08fddcea164a
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.fasterxml.jackson.core/jackson-core 2.22.2 SHA-256/ff167a6317be15895706c26668f45b898efe40ab8780970658210fe1393d52a6
 * @jenesis.pin com.fasterxml.jackson.core/jackson-databind 2.22.2 SHA-256/d0da14c12b16b5d54719aa172d83b542ff4abeb8b0fb7db476fde8ceece760ca
 * @jenesis.pin com.fasterxml.jackson.datatype/jackson-datatype-jsr310 2.22.2 SHA-256/9df71cc7fb3781fd0bed6c05eebbfa8b39292a49f5619e76fdd0d889f64c8f33
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.github.jknack/handlebars 4.5.4 SHA-256/3b486535d0606306c9abca7b5a48066a2ec8186ecdd03e2ff02c70b15c13840c
 * @jenesis.pin com.github.jknack/handlebars-helpers 4.5.4 SHA-256/f3f57ced6a0380be940ac418cd858c537d54b715814573c7c1f47e9087fbe603
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin com.google.guava/failureaccess 1.0.3 SHA-256/cbfc3906b19b8f55dd7cfd6dfe0aa4532e834250d7f080bd8d211a3e246b59cb
 * @jenesis.pin com.google.guava/guava 33.7.1-jre SHA-256/796d8e28ac64e83a47c4c5935a8fecc4682650a04bbdead738ef0f5a3a0e6c46
 * @jenesis.pin com.google.guava/listenablefuture 9999.0-empty-to-avoid-conflict-with-guava SHA-256/b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99
 * @jenesis.pin com.google.j2objc/j2objc-annotations 3.1 SHA-256/84d3a150518485f8140ea99b8a985656749629f6433c92b80c75b36aba3b099b
 * @jenesis.pin com.jayway.jsonpath/json-path 3.0.0 SHA-256/e4e49440701674ace75af44a98840d9e13f53b34aab280446707661318405dc8
 * @jenesis.pin com.networknt/json-schema-validator 3.0.7 SHA-256/27b90bce60845f353b203b735cda2e0cb9a32c4962550e249141a2b065b9be5c
 * @jenesis.pin commons-fileupload/commons-fileupload 1.5 SHA-256/51f7b3dcb4e50c7662994da2f47231519ff99707a5c7fb7b05f4c4d3a1728c14
 * @jenesis.pin commons-io/commons-io 2.22.0 SHA-256/2b9a7b1f726fb86216dbd2c8321eabe0221dbd5b1be81c18e1cb53811b104758
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
 * @jenesis.pin jakarta.annotation/jakarta.annotation-api 3.0.0 SHA-256/b01f55552284cfb149411e64eabca75e942d26d2e1786b32914250e4330afaa2
 * @jenesis.pin jakarta.el/jakarta.el-api 6.0.1 SHA-256/7e84b5bed49de32b79cc5e85d90b6f5adb1a953ac67283adbb41c1e297f9c605
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
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.13 SHA-256/aa0013fbcb7e8086f4775f3dcb69a6c6833f7fd9023fe027d2d0241834cc51d5
 * @jenesis.pin net.javacrumbs.json-unit/json-unit-core 6.2.0 SHA-256/712998bb69fc62a68e1aec4352906d276b8613d3ca53202e3113e4e0aac5c352
 * @jenesis.pin net.minidev/accessors-smart 2.6.0 SHA-256/222c9f547bb20a99fc486403a398352d1306fb671b38abd7ecab6401df170e61
 * @jenesis.pin net.minidev/json-smart 2.6.0 SHA-256/1ae4b561458afb540be8ec5c6dbb4f2e715a319a7ae64854998aaf924770d61b
 * @jenesis.pin org.apache.commons/commons-lang3 3.20.0 SHA-256/69e5c9fa35da7a51a5fd2099dfe56a2d8d32cf233e2f6d770e796146440263f4
 * @jenesis.pin org.apache.commons/commons-text 1.15.0 SHA-256/58d2da30f058512a1e7f914e39241deca4dff5c27a085b4ed2faa9e7208067f6
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.4 SHA-256/bdef5f8841145cd76c4c605ff301bc840eea0d5292332b7b892a93316c0d8ee1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.3 SHA-256/18bfbbabb478dfb67f31aeaf428c387f3c3df654582e1309f708ee1f3086830a
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4.3 SHA-256/c7db7026b8e2dea39132b04a6069f6671e2858309b20a146ec5c7dd6ed73a0b6
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.bouncycastle/bcpkix-jdk18on 1.83 SHA-256/d3c4c6b700c74ef8164bb15e549d939721b8f14fc0ff89fe19b220243bcfcbd8
 * @jenesis.pin org.bouncycastle/bcprov-jdk18on 1.83 SHA-256/82cf3a2af766c3bc874f6d36b9f20a8b99a8f09762dc776e8a227a45d8daaafb
 * @jenesis.pin org.bouncycastle/bcutil-jdk18on 1.83 SHA-256/ee7d0eb4e74de70a735f7fb36b604dd5c6ad35720d50b914604db042114a0185
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-common 12.1.13 SHA-256/91bf7b735619870fd369aed9d79372ed8e6cdba2e4b4c44131a2a78fa15d4737
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-gzip 12.1.13 SHA-256/987afca3706a82595533ab49b60281f77daba688a46f2a7b2ac9498c9484be7d
 * @jenesis.pin org.eclipse.jetty.ee/jetty-ee-webapp 12.1.13 SHA-256/15c82c0ecd1be08ef776a06bf138f5351c2554d84fd02bd72aa1d0dc88c10a0e
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-client 12.1.13 SHA-256/a2083743d8b0ba48eba0b05feeff5c97dfb46b073609d4525f300dde59fcabf0
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-common 12.1.13 SHA-256/5da0b18b0905a241c306d996a91ada0727932969b79df882c51ba0b0537932a0
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-server 12.1.13 SHA-256/2d3c633f306adb93f870936fbdf4f66af7c5491d450b49c3e54443d9c22aef14
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jetty-server 12.1.13 SHA-256/36f873ae12183a261dec1c944f286e5379389402e20c596bae94ebea268490ef
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-servlet 12.1.13 SHA-256/a28767255bc89e952dd8e3be6e8f6bce43a5d9dad19d36244732003e6fabae8b
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-annotations 12.1.13 SHA-256/f2476c80785ff4feb2508c847933c16e6a615dc892be7967cb874d99e13bf656
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-plus 12.1.13 SHA-256/9cee89b657538d445bdc597444494918fd3bd17a344a1b4e2dc6ed3c891860aa
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-servlet 12.1.13 SHA-256/c6fb3047015c4ba543e869056e68451574417af0ab3fc0a28a9c73993499f45e
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-servlets 12.1.13 SHA-256/3fa64ffe3bc0b30c8c511e6f2b0a2a4aa5421d2a41bf44425e9a50a380109b70
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-webapp 12.1.13 SHA-256/de7c17288e1374698ec464018ac4106e38c87e407fd72991c10fc2fa5e438eea
 * @jenesis.pin org.eclipse.jetty.http2/jetty-http2-common 12.1.13 SHA-256/611bcccb69ec472af076f356667a53d75e75a61a4c213d15928a65e4342d452e
 * @jenesis.pin org.eclipse.jetty.http2/jetty-http2-hpack 12.1.13 SHA-256/d96a2e7c902a33206b3181d5e5c10855367f5305a45c5bcf7bb7c52959638350
 * @jenesis.pin org.eclipse.jetty.http2/jetty-http2-server 12.1.13 SHA-256/608f849b824d8f8ee0c34a704651157fe793ab73f7d793d86dbd84c04e1d79f2
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-client 12.1.13 SHA-256/7b6d472b40a44f09459de816882a06667850c8664fd585201b634fa597095479
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-common 12.1.13 SHA-256/fcd3abf1e4749751fc85a7b39c614f85feb07eee0bcbe7b8cb10b9bbb15904d0
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-server 12.1.13 SHA-256/dbe21adebf357f27033b2b76bb6b40ae5ecaa40fe3ba4b9d10bf7f4795fa93dc
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-api 12.1.13 SHA-256/2b71c6fd53619289188c169adaedf617ed01213d2c8f7063e4c2d0a88bbf7ece
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-common 12.1.13 SHA-256/3d1eced18d1aee79aa832fa90eb021e1eee9f717c437aa0ab81b8d85affe35c3
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-server 12.1.13 SHA-256/643fb282a75b1a8503990020db5cbef480be12dc997d37d8ab26dd2d5c191eef
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-client 12.1.13 SHA-256/26aa9b360efa88c50915f6f62916d5527f24dbcf75995038a3b0d9553553e407
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-java-client 12.1.13 SHA-256/d4ce3aca4001519475a4189ba2519faaa4e6b049fe666eb78f3463b1651d95f0
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-java-server 12.1.13 SHA-256/746df9c84a60d865d33d90fe69b8f09c010a2b55f1979147357a90172ec1e07f
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-server 12.1.13 SHA-256/e282181358dcb5a4872c9363c926c856564c94e5432a91e67e1b2ae309c7d50a
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
 * @jenesis.pin org.openjdk.nashorn/nashorn-core 15.7 SHA-256/3f2b62e55b5458ba2e8a0cc4599aa3abe81b1422e31c38bb8294a7096ceee6f2
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.7 SHA-256/ff1cf9d62786d067fa19c56f13cd6ed1077d0f8782ff269824b7a2c586101bf0
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.ow2.asm/asm 9.10.1 SHA-256/ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb
 * @jenesis.pin org.ow2.asm/asm-analysis 9.10.1 SHA-256/dede75a21306b65974ecd8f87114ff6970f09fb794157a4ca09ab25c888c2bfc
 * @jenesis.pin org.ow2.asm/asm-commons 9.10.1 SHA-256/6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895
 * @jenesis.pin org.ow2.asm/asm-tree 9.10.1 SHA-256/3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b
 * @jenesis.pin org.ow2.asm/asm-util 9.10.1 SHA-256/1bb99d091fba2597dc6d51193e9bbcf0d8447e7ed96bd8f0198b18152f09655c
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.snakeyaml/snakeyaml-engine 3.1.1 SHA-256/59d73655cf077f154137e2d6f6f92c041a954c0b1c534c63800047a0d70a6947
 * @jenesis.pin org.wiremock/certificate-generator 4.0.0-beta.38 SHA-256/09b7af9b1c6cb9669504c3b318f94db7e48c69f42eb7091f659e3f7c65d84ee5
 * @jenesis.pin org.wiremock/wiremock-core 4.0.0-beta.38 SHA-256/b1611d85ca6f5a2b5d2929a8244a4807b3f843704f2bc0225992cea59e1fe419
 * @jenesis.pin org.wiremock/wiremock-httpclient-apache5 4.0.0-beta.38 SHA-256/789f40cad051cb8296b04b581f12aba4e32301e2dcb1cc5a67f572c4a7262cc7
 * @jenesis.pin org.wiremock/wiremock-jetty 4.0.0-beta.38 SHA-256/9d68dc73977fb7969a37262e77c520b678c5ba48fed71b2bc03155a71fc9ab8b
 * @jenesis.pin org.wiremock/wiremock-string-parser 4.0.0-beta.38 SHA-256/f2188d759b4ec3d27ba93974df1024e12a7c6e79a77bcbcdfb2b72b98e78d576
 * @jenesis.pin org.wiremock/wiremock-string-parser-jackson2 4.0.0-beta.38 SHA-256/de828408fb522fa46f632a9274f4a48d93bdb8aa77b076a442a251fe83b77566
 * @jenesis.pin org.wiremock/wiremock-url 4.0.0-beta.38 SHA-256/30da9fc7594a8e2d8b8b78652a83a88af3c73d1180ab00bf7c2b3fa857bb6f6e
 * @jenesis.pin org.wiremock/wiremock-url-jackson2 4.0.0-beta.38 SHA-256/02a166a2210f30ed290bfbd8508c4d43153907c4bc9c527a2df7f001d8d09b76
 * @jenesis.pin org.xmlunit/xmlunit-core 2.13.0 SHA-256/0c8f73be3bdd4ebc22020f86c89e08bd1523036d6765c1e8eeacaf22e311219b
 * @jenesis.pin org.xmlunit/xmlunit-legacy 2.13.0 SHA-256/31748108d1a6ac036abc785e79e61f75bebaa08d9b3f78dde0159bfa66dd0256
 * @jenesis.pin org.xmlunit/xmlunit-placeholders 2.13.0 SHA-256/8d87fed3d094745cdbe3bb194a1759c09681ca0a56ae6c63347c5bf8ec02c1f9
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.2 SHA-256/93d9090b8c505b2f942ad85dd5821e8c401bfbafafff11708a3fbe2ef9da18ca
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
 * @jenesis.pin tools.jackson.dataformat/jackson-dataformat-yaml 3.2.2 SHA-256/c2c24e93676832dbb14196685e24292bb04258165c70d6c1e41e475e34fc93d3
 */
open module build.jenesis.repository.store.azure.test {
    requires build.jenesis.repository.store.azure;
    requires build.jenesis.repository.store;
    requires com.azure.storage.blob;
    requires org.junit.jupiter;
    requires org.assertj.core;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
