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
 * @jenesis.pin com.azure.storage.blob 12.35.0
 * @jenesis.pin com.azure/azure-core 1.58.1 SHA-256/7b339126e92af79b07fcf96fe16fa5ba2a2854bb8ce7e03ac4776b9474fe7df5
 * @jenesis.pin com.azure/azure-core-http-netty 1.16.5 SHA-256/61091ba5634e711e396721edfcca5c6782be1c1e86f2ecf856eb57aa20260c0c
 * @jenesis.pin com.azure/azure-json 1.5.1 SHA-256/bad21d5eb306d82b85951b58a1d9e501a9b09970e452bee6d4d445fd5a91c519
 * @jenesis.pin com.azure/azure-storage-blob 12.35.0 SHA-256/c1f7dac599b0c057e406db76e7684bf2a5aae8f960f58bcecc18233298092eb8
 * @jenesis.pin com.azure/azure-storage-common 12.34.0 SHA-256/9ddbf4a4e7680e6d062995928b3933e496353d1e62449f2ce5662f9db0820325
 * @jenesis.pin com.azure/azure-storage-internal-avro 12.20.0 SHA-256/b80addb78cdc7ea6af99b8e76ac91c9a553e1a088850391bf2d7b3f7e2bc8dab
 * @jenesis.pin com.azure/azure-xml 1.2.1 SHA-256/08b458481b656554605215ab0b165f68e6025359e52bea4736d032328d40ba3b
 * @jenesis.pin com.ethlo.time/itu 1.14.0 SHA-256/5cf40ab0cc77828ab2b875b1f3ecd71c8295d7721933476abc2e08fddcea164a
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.18.7 SHA-256/4c992ecef3569e73f19cd6b3be027108fb73139bb67d55d1218ac72e92219ebc
 * @jenesis.pin com.fasterxml.jackson.core/jackson-core 2.18.7 SHA-256/e1c578d374f519aa9aa74cbdc251c6705ffa08ac78faea5fa36bad213de30dc8
 * @jenesis.pin com.fasterxml.jackson.core/jackson-databind 2.18.7 SHA-256/aa3c034534fce966b6dbd706b1f466b8a15c266127e5a15f96522091093dbd9b
 * @jenesis.pin com.fasterxml.jackson.dataformat/jackson-dataformat-yaml 2.18.3 SHA-256/3ca00e47cfcb43e79438ddcfc8fc735e9ebac3824fb7769138609be6bd56e483
 * @jenesis.pin com.fasterxml.jackson.datatype/jackson-datatype-jsr310 2.18.7 SHA-256/29b8f1f8e055653297b07c3844a056541bdbf5c8199517598d9fa6edbefcc82e
 * @jenesis.pin com.github.docker-java/docker-java-api 3.7.1 SHA-256/dad153d484b1f4ef009e2fdbad27e07aeb3191122da52b8985507ac504300081
 * @jenesis.pin com.github.docker-java/docker-java-transport 3.7.1 SHA-256/d15eec8034bf0f92c2a48ca9172691804048115c96dc853272f9486fa2695c3c
 * @jenesis.pin com.github.docker-java/docker-java-transport-zerodep 3.7.1 SHA-256/b89bdb1754160323597f9ea32a7fe7a4a3aa8f5b3b43b88e8d71fff3b267ab21
 * @jenesis.pin com.github.jknack/handlebars 4.5.3 SHA-256/ea3be4f2cde8cc7b912448edd764a754debfeedd0c85f426c4de77361216cfd2
 * @jenesis.pin com.github.jknack/handlebars-helpers 4.5.3 SHA-256/c46e4f5d01069924d02ec474343555190fe645e1bbd4ba0b0399d8b488519573
 * @jenesis.pin com.google.code.findbugs/jsr305 3.0.2 SHA-256/766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7
 * @jenesis.pin com.google.code.gson/gson 2.8.9 SHA-256/d3999291855de495c94c743761b8ab5176cfeabe281a5ab0d8e8d45326fd703e
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.47.0 SHA-256/5364bc6f22e72e98195e406a58d3ba1c09ffa11dea0729592cb870dc2de4056d
 * @jenesis.pin com.google.guava/failureaccess 1.0.3 SHA-256/cbfc3906b19b8f55dd7cfd6dfe0aa4532e834250d7f080bd8d211a3e246b59cb
 * @jenesis.pin com.google.guava/guava 33.6.0-jre SHA-256/dc573e1fca4fd5454f4a5fd3d7da2df03002876a4175bafc14a95980dd7713b3
 * @jenesis.pin com.google.guava/listenablefuture 9999.0-empty-to-avoid-conflict-with-guava SHA-256/b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99
 * @jenesis.pin com.google.j2objc/j2objc-annotations 3.1 SHA-256/84d3a150518485f8140ea99b8a985656749629f6433c92b80c75b36aba3b099b
 * @jenesis.pin com.jayway.jsonpath/json-path 3.0.0 SHA-256/e4e49440701674ace75af44a98840d9e13f53b34aab280446707661318405dc8
 * @jenesis.pin com.networknt/json-schema-validator 2.0.1 SHA-256/216fa6f496d4390ec6ba208593ad91d3a6fae21b7f5d8d327c4d628946ff9ea6
 * @jenesis.pin commons-codec/commons-codec 1.19.0 SHA-256/5c3881e4f556855e9c532927ee0c9dfde94cc66760d5805c031a59887070af5f
 * @jenesis.pin commons-fileupload/commons-fileupload 1.5 SHA-256/51f7b3dcb4e50c7662994da2f47231519ff99707a5c7fb7b05f4c4d3a1728c14
 * @jenesis.pin commons-io/commons-io 2.20.0 SHA-256/df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72
 * @jenesis.pin io.netty/netty-buffer 4.1.135.Final SHA-256/2a194f99fc93d07c4d442d04ac71bd2dc56d3188cd0e4270cdc2a953d1956bf9
 * @jenesis.pin io.netty/netty-codec 4.1.135.Final SHA-256/7252171264dbb5bb8ed38e77f89643b31e3cabc96144ec27b6882435d718a61e
 * @jenesis.pin io.netty/netty-codec-dns 4.1.135.Final SHA-256/5e996d7ac7597f368ab114fbb91d16788918c7e5bf166345c51e56db54d50fd1
 * @jenesis.pin io.netty/netty-codec-http 4.1.135.Final SHA-256/4018529d3d6aecf4044b98c75d9a90c91839ddf49c7aa484c5ac81c90a15da02
 * @jenesis.pin io.netty/netty-codec-http2 4.1.135.Final SHA-256/aa4e81ab5fa3b7b243eb3e814aa582ab26c073d31b0abffdbb58ee150fa49c16
 * @jenesis.pin io.netty/netty-codec-socks 4.1.135.Final SHA-256/ec7a39e8d7d7e223014115a021273f011c3cb1e8fb187cbfb90a74e76d68c25c
 * @jenesis.pin io.netty/netty-common 4.1.135.Final SHA-256/26775ca95820711403cf065fa2ec0134a0a04ff5417c688c0237aee68b55838d
 * @jenesis.pin io.netty/netty-handler 4.1.135.Final SHA-256/245e74e04b6f4e8ef98853152412e3bf1499ce6fcf15329b798c8ce36c3537e2
 * @jenesis.pin io.netty/netty-handler-proxy 4.1.135.Final SHA-256/75661010630a44468f0e85d7ed8be7779c0cb1369fe85d30799cedc52e9ed3b7
 * @jenesis.pin io.netty/netty-resolver 4.1.135.Final SHA-256/77dd03865965b6c12b9e521bddec82f035caeb33156e09c158289c5094318481
 * @jenesis.pin io.netty/netty-resolver-dns 4.1.135.Final SHA-256/ca25581e4cebd55797ef3b4d0953b75df32c1af77fe771b96bfaa9e701cdb7c3
 * @jenesis.pin io.netty/netty-resolver-dns-classes-macos 4.1.135.Final SHA-256/4aab49a507dbbe446ad2c6a7587fe69c511defa6c273ce1a559e3458a3378a5b
 * @jenesis.pin io.netty/netty-tcnative-boringssl-static 2.0.78.Final SHA-256/0e21ede32de7363affc2ae1bc412ed612853957c7081d87ca5320281db3f30bf
 * @jenesis.pin io.netty/netty-tcnative-classes 2.0.78.Final SHA-256/3ca66d8c6c0f003242f954cc1822a32445109ac25b8582840ba3d8e3c92f0a3e
 * @jenesis.pin io.netty/netty-transport 4.1.135.Final SHA-256/6bde734d1ec073142eed31b1e68cd5d68fbf241e060b37f07a164e5ecb15631c
 * @jenesis.pin io.netty/netty-transport-classes-epoll 4.1.135.Final SHA-256/9d9537ab9e15164c9f0dc0748884c148814a18d78ac6dfa65cf4b3d06068ce01
 * @jenesis.pin io.netty/netty-transport-classes-kqueue 4.1.135.Final SHA-256/b1f2c39d9bf7af4ecd1eb40b6bb92c5741460623aabf351de166beecbd06827d
 * @jenesis.pin io.netty/netty-transport-native-unix-common 4.1.135.Final SHA-256/a7895075f112611d1640a596c2678a28aab92d5681c1c14755b109b8998f995e
 * @jenesis.pin io.projectreactor.netty/reactor-netty-core 1.2.18 SHA-256/2d1ff55147102d4284c6f9c59c06d4288e3a59b1921da01647fef24869cfefc3
 * @jenesis.pin io.projectreactor.netty/reactor-netty-http 1.2.18 SHA-256/5b8409741ebe7fd95ae44519a90115352fb4bf9d32f2af579c89da7003b0db10
 * @jenesis.pin io.projectreactor/reactor-core 3.7.18 SHA-256/7d9b507c0d651de30a20dac634e7cb7ca908a7c23d57ce05e71bbb9bb79bf0c4
 * @jenesis.pin jakarta.annotation/jakarta.annotation-api 3.0.0 SHA-256/b01f55552284cfb149411e64eabca75e942d26d2e1786b32914250e4330afaa2
 * @jenesis.pin jakarta.el/jakarta.el-api 6.0.0 SHA-256/f33d0becf2d5516730ba5cc99a7b5a2b1f62986bf0a3370249cdff9a2f171507
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.cdi-api 4.1.0 SHA-256/c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.lang-model 4.1.0 SHA-256/bb56f571f60d2862b2387d5468fe8f5540f8094727283ed991f89082708095ee
 * @jenesis.pin jakarta.inject/jakarta.inject-api 2.0.1 SHA-256/f7dc98062fccf14126abb751b64fab12c312566e8cbdc8483598bffcea93af7c
 * @jenesis.pin jakarta.interceptor/jakarta.interceptor-api 2.2.0 SHA-256/d240d72b4dd38a2e431c804079810010cb97903678fa5f987fb7434878b04398
 * @jenesis.pin jakarta.servlet/jakarta.servlet-api 6.1.0 SHA-256/8a31f465f3593bf2351531a5c952014eb839da96a605b5825b93dd54714c48c4
 * @jenesis.pin jakarta.transaction/jakarta.transaction-api 2.0.1 SHA-256/50c0a7c760c13ae6c042acf182b28f0047413db95b4636fb8879bcffab5ba875
 * @jenesis.pin jakarta.websocket/jakarta.websocket-api 2.2.0 SHA-256/541d00436cbca0a5e1f6a457c9f70a64f00bd2f83e10ed89c2b372bc34843b7e
 * @jenesis.pin jakarta.websocket/jakarta.websocket-client-api 2.2.0 SHA-256/aa6fa9331a3f470daee0dbfcf084abfbd7a49507297575d5bb8bfbf3d62fe8c0
 * @jenesis.pin javax.el/javax.el-api 3.0.0 SHA-256/8d21ac8c3a38027be27ff4c4fe24806ae2fc188559123253ddc7425066d78fa1
 * @jenesis.pin javax.enterprise/cdi-api 1.2 SHA-256/cc5ce2cbc62fe96bf59af00bba00bde823a1094462b4364747863510b76c0518
 * @jenesis.pin javax.inject/javax.inject 1 SHA-256/91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff
 * @jenesis.pin javax.interceptor/javax.interceptor-api 1.2 SHA-256/62acf2da0e19e813e0f5aa5de09108368b12e40b4a2f47c66a88f984f4f5143b
 * @jenesis.pin main/maven/io.netty/netty-resolver-dns-native-macos/jar/osx-x86_64 4.1.135.Final SHA-256/0c86fa27317c4172fff03a0c20286e2c62ef9d60ad78f389a83ede48a5bb54cd
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-aarch_64 2.0.78.Final SHA-256/85f6e25942df7308c9a6e66015a5ba87589d6f239231fb5b175138afe451b592
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-x86_64 2.0.78.Final SHA-256/bb830d661dc70fac2df8d147ffb64d61566211455272bb75d09d1662ec843aae
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-aarch_64 2.0.78.Final SHA-256/29019bf2e3045acaf4fd17b9e4033536141c8971939cd78cc82a12fe74fe24c1
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-x86_64 2.0.78.Final SHA-256/6c6c574bf9ee85b53f176d7de1101d348cf4374014df2ea26b691e7f335d69ba
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/windows-x86_64 2.0.78.Final SHA-256/c720390d4733fa4997f4648327fcb63a688a72afd3ddd05d368759c6c65aef6b
 * @jenesis.pin main/maven/io.netty/netty-transport-native-epoll/jar/linux-x86_64 4.1.135.Final SHA-256/18a40063da3364cffff81c6c2097fb6ebcb45c62264dabcce45aade4fdac3125
 * @jenesis.pin main/maven/io.netty/netty-transport-native-kqueue/jar/osx-x86_64 4.1.135.Final SHA-256/412e10daef5aa4647984397fa6728acf88dffd0d4c53ad91f486ea6492f8f08f
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.3 SHA-256/d78396e3c5bce3f2865c9186647481e5589d34cacc632484715b686108d17c66
 * @jenesis.pin net.bytebuddy/byte-buddy-agent 1.12.19 SHA-256/3a70240de7cdcde04e7c504c2327d7035b9c25ae0206881e3bf4e6798a273ed8
 * @jenesis.pin net.java.dev.jna/jna 5.18.1 SHA-256/260c4b1e22b1db9e110ee441c4f13ce115f841fa48c41d78750986214b395557
 * @jenesis.pin net.javacrumbs.json-unit/json-unit-core 5.1.2 SHA-256/184607efe6d314150f2b0373a2f9a3f40c2071594d948fdcad6715b4dd774d66
 * @jenesis.pin net.minidev/accessors-smart 2.6.0 SHA-256/222c9f547bb20a99fc486403a398352d1306fb671b38abd7ecab6401df170e61
 * @jenesis.pin net.minidev/json-smart 2.6.0 SHA-256/1ae4b561458afb540be8ec5c6dbb4f2e715a319a7ae64854998aaf924770d61b
 * @jenesis.pin org.apache.commons/commons-compress 1.28.0 SHA-256/e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca
 * @jenesis.pin org.apache.commons/commons-lang3 3.18.0 SHA-256/4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720
 * @jenesis.pin org.apache.commons/commons-text 1.15.0 SHA-256/58d2da30f058512a1e7f914e39241deca4dff5c27a085b4ed2faa9e7208067f6
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.2 SHA-256/f3bf571b04dffe005ff29b965b9e3a88846aac6e63f24eae38a86d1979ea49a3
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.3 SHA-256/18bfbbabb478dfb67f31aeaf428c387f3c3df654582e1309f708ee1f3086830a
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4.3 SHA-256/c7db7026b8e2dea39132b04a6069f6671e2858309b20a146ec5c7dd6ed73a0b6
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.bouncycastle/bcpkix-jdk18on 1.84 SHA-256/c87f16ed9e5ec61bc94151e9f3646ac44e50cd448121ce84367fa4b7ec7ec1bb
 * @jenesis.pin org.bouncycastle/bcprov-jdk18on 1.84 SHA-256/64d6c5a6121fcd927152dd182cbed39afe0fda641a970d9bcc0c9cb1858b2731
 * @jenesis.pin org.bouncycastle/bcutil-jdk18on 1.84 SHA-256/b374e16963421fb9cfb01cc20d7ad8fd2f8b8188e3eef0ec0a8965e245f7619a
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-common 12.1.10 SHA-256/7c02e58d5589549a5e9b19a903134a0e868fdb9ee38459b2c4495a6a3b342ba0
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-gzip 12.1.10 SHA-256/8c112b9ebb3413626026ba6782afde5e22575eb6fb071c98ae3541cb8d2dc79e
 * @jenesis.pin org.eclipse.jetty.ee/jetty-ee-webapp 12.1.10 SHA-256/a75c0f3ec4aff18295e8ebeba5de07e67c22892f511e7637e6e51ba7b01f35e1
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-client 12.1.10 SHA-256/65165fa83e24362c03a0617d70503acb05f4ae4141dbdbd39815b560c00a3695
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-common 12.1.10 SHA-256/f9c5b829fcd6bef1cb7594c1230d0b71333a4c541428c25c6bae9b5bf2a52c1b
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-server 12.1.10 SHA-256/0c8de85e674fdb81e430f6830fc6b2f88d4ca3e6127d1d4637d43055937e227b
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jetty-server 12.1.10 SHA-256/71f1124a57daebceb0ca1f70a8f3e4c3909a37ff78fe3a141ea3d14110d342e3
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-servlet 12.1.10 SHA-256/2473da8b3583d0b375690ea9030bd4e4ff2c7831d80400bc750989ed6ce06bc8
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-annotations 12.1.10 SHA-256/0f5ea662cf1835bf312e6935b3a935d47e616c0495fd07be546dfa442d167c00
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-plus 12.1.10 SHA-256/3a190d91396ee0fdf6f4fd72c5be155d99937a40b8b6e9f413543f5706a82e2b
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-servlet 12.1.10 SHA-256/668b12749f2043d15953beb0b9fbd2ba6f863b61de5b4682ff12778034c6895a
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-servlets 12.1.10 SHA-256/d99d1c84292bd234d1ca9a5fa4f7da7ed86febfff5fae075064a656ef0455ffc
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-webapp 12.1.10 SHA-256/a0e30b909c6653f94a2af93fff865413a52e86b82bd2c53f21e2388ebb7b0064
 * @jenesis.pin org.eclipse.jetty.http2/jetty-http2-common 12.1.10 SHA-256/1e96e4a1137df8d3b92aab545da6ef81e585d5241ee58611188f6ff0e9779a99
 * @jenesis.pin org.eclipse.jetty.http2/jetty-http2-hpack 12.1.10 SHA-256/a8b7a048dc32619c432a558da33d7baa81b8a227fb821829bcc467afc0b06525
 * @jenesis.pin org.eclipse.jetty.http2/jetty-http2-server 12.1.10 SHA-256/f7c96249ea4f343fe28aebe7784e51f25e64501beb3dded6ef383bc066fb5cce
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-client 12.1.10 SHA-256/10ee92d90fe1d112a6d6d612083de853f28935310902443bdbe5daea84e530f5
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-common 12.1.10 SHA-256/bc6397bf67feeb18d7851b5fbf817c4ed11016024481202e8db790bb5a8321e1
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-server 12.1.10 SHA-256/ca9e5cdecde06d21d0637f7b0c15e40b90463290fa8c75d2c66b4bad6910608d
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-api 12.1.10 SHA-256/f3b33c3c284c12917bef8a5af9b347dfecfbcb3b1a416a7d00041f5ad1b2b76f
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-common 12.1.10 SHA-256/b7b6766b0f5eec268b17e971cd8ac8058cffba350aed53e5bf120c00081be4cf
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-server 12.1.10 SHA-256/e9fb2d66de7edd797f95089afad93717f24e8948097f238fb2bb94bbc1718584
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-client 12.1.10 SHA-256/4eed96763ac18fe310ad162d3266cf64ec718d86f5ac87b19a91f3f62a2c4520
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-java-client 12.1.10 SHA-256/9be6a894020407fb4a6195860e1a1e06f9978449a8679b8fe48aff6559c717bf
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-java-server 12.1.10 SHA-256/cc002690501f4135d145d326a4b1b4c4872c710daf654ced9ef7028257c7925b
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-server 12.1.10 SHA-256/d00e8b8ecb3b9fe02f68dd31402af3ce67200e0dec6bf4e571a824a6284c1b65
 * @jenesis.pin org.eclipse.jetty/jetty-annotations 12.1.10 SHA-256/b10d017a3ee94fa446c4d1756daf9571adc9e510a6dd4c97c96c952d748a06da
 * @jenesis.pin org.eclipse.jetty/jetty-client 12.1.10 SHA-256/6e72623ef6ef1ed39a77934452e9d0340d7977882f3d8e9184ed8a2176330c68
 * @jenesis.pin org.eclipse.jetty/jetty-http 12.1.10 SHA-256/090f276739fd9bf8c30511007caec669ea3804b1df1061c37f44467474bee71d
 * @jenesis.pin org.eclipse.jetty/jetty-io 12.1.10 SHA-256/448fc0f8f6f5f7251fc46de8e3aae7da14bd7c571a8043dac3dc381d08228fa0
 * @jenesis.pin org.eclipse.jetty/jetty-jndi 12.1.10 SHA-256/d80daed161e97b94e8dcfe105c19e668f464f95b5522db99b1f36ce5110f84b4
 * @jenesis.pin org.eclipse.jetty/jetty-plus 12.1.10 SHA-256/d2cbf417d2f196ecc041e13cae0b801fc71fddf39ebab320c61e5919de05cdb5
 * @jenesis.pin org.eclipse.jetty/jetty-security 12.1.10 SHA-256/2f34b7895cec4e3547a1b52e12e7e92b3f3a110bf3c17637f8743ca3f4e42f0c
 * @jenesis.pin org.eclipse.jetty/jetty-server 12.1.10 SHA-256/4b0108e87abada7027123deca17186249413232dad0a2bd58a4e70a987b5354a
 * @jenesis.pin org.eclipse.jetty/jetty-session 12.1.10 SHA-256/164649123d15a3f2be5c196e82aa652dff05c75362db71b0f4ab6bb35165020a
 * @jenesis.pin org.eclipse.jetty/jetty-util 12.1.10 SHA-256/c52b4ff62cdacca8a399c611231129e6bd1074db7e3892e78cd106725cdd0ef1
 * @jenesis.pin org.eclipse.jetty/jetty-xml 12.1.10 SHA-256/d4660c9fa82eabfa9e5996eff5b36ae22e5e14430c3dcc485920aef889149a89
 * @jenesis.pin org.javassist 3.32.0-GA
 * @jenesis.pin org.javassist/javassist 3.32.0-GA SHA-256/712ef75bc3406782bb4529b0408cce8155b53f2124c6ae03d2c5fbfa13d62c1c
 * @jenesis.pin org.jetbrains.annotations 26.1.0
 * @jenesis.pin org.jetbrains/annotations 26.1.0 SHA-256/ebc7aec252ed0c7d2d04c039d7f00e69f7b86b1f493c741d67b3ef31b986b054
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.junit.jupiter 6.0.3
 * @jenesis.pin org.junit.jupiter.api 6.0.3
 * @jenesis.pin org.junit.jupiter/junit-jupiter 6.0.3 SHA-256/784b65815f479a0c99a9d3a573b142e2a525efb6025d97f751b19e72f90aeda3
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.0.3 SHA-256/d655d7e6f0c7ae07f10a2f3bbaaebb6d30e9b26204a068ad9e9b3950aa28792c
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.0.3 SHA-256/1e2fab61ad27ea08fc7c70dd9677cf8c6d1ae5434d42dcfdd633b12c7e7c04d0
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.0.3 SHA-256/cf2947e2302b9f8c8a059259a277881c1cadae8fbc2514c16a925cfeb7beb2e5
 * @jenesis.pin org.junit.platform.commons 6.0.3
 * @jenesis.pin org.junit.platform.console 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.0.3 SHA-256/39f262d09c3d52719fe0b77f080e90a3695e285d779a41b232e17963ae5da200
 * @jenesis.pin org.junit.platform/junit-platform-console 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.0.3 SHA-256/491e9e4f745f161b8a8e4186a1a7c6a450ea12c70930c9aedae427215301d947
 * @jenesis.pin org.junit.platform/junit-platform-launcher 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-reporting 6.0.3
 * @jenesis.pin org.mockito/mockito-core 4.11.0 SHA-256/4b909690cab288c761eb94c0bf0e814496cf3921d8affac84cd87774530351e5
 * @jenesis.pin org.objenesis/objenesis 3.3 SHA-256/02dfd0b0439a5591e35b708ed2f5474eb0948f53abf74637e959b8e4ef69bfeb
 * @jenesis.pin org.openjdk.nashorn/nashorn-core 15.4 SHA-256/6f816e84dfd63a81d4eaa7829c08337bbaff3ec683ff3bf6bbd90d017a00dc6f
 * @jenesis.pin org.opentest4j 1.3.0
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.4
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.ow2.asm/asm 7.3.1 SHA-256/2f67e11ceec819ebd88ddee5300aba699b1cbab2e20c22e97cf027d3be93959b
 * @jenesis.pin org.ow2.asm/asm-analysis 7.3.1 SHA-256/46b8a8efd4b94facb5ab4b35afe30ee0546ae7a43d2c64e6def56c2f168fefa5
 * @jenesis.pin org.ow2.asm/asm-commons 7.3.1 SHA-256/87cd8bb3c6bf6bcbb33fca48060c5065f66ebf6a3d7de9bf18bff51bcf156ebc
 * @jenesis.pin org.ow2.asm/asm-tree 7.3.1 SHA-256/f91a4a8aa868c5c4665bb4fd134019a91f9f8b9216527fba295e3c8b5422b78b
 * @jenesis.pin org.ow2.asm/asm-util 7.3.1 SHA-256/182128592742ed4883ac82bf205f137b6bfbe1234c68e6feb13759e75a85b729
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.reflections/reflections 0.10.2 SHA-256/938a2d08fe54050d7610b944d8ddc3a09355710d9e6be0aac838dbc04e9a2825
 * @jenesis.pin org.rnorth.duct-tape/duct-tape 1.0.8 SHA-256/31cef12ddec979d1f86d7cf708c41a17da523d05c685fd6642e9d0b2addb7240
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 * @jenesis.pin org.wiremock/certificate-generator 4.0.0-beta.38 SHA-256/09b7af9b1c6cb9669504c3b318f94db7e48c69f42eb7091f659e3f7c65d84ee5
 * @jenesis.pin org.wiremock/wiremock-core 4.0.0-beta.38 SHA-256/b1611d85ca6f5a2b5d2929a8244a4807b3f843704f2bc0225992cea59e1fe419
 * @jenesis.pin org.wiremock/wiremock-httpclient-apache5 4.0.0-beta.38 SHA-256/789f40cad051cb8296b04b581f12aba4e32301e2dcb1cc5a67f572c4a7262cc7
 * @jenesis.pin org.wiremock/wiremock-jetty 4.0.0-beta.38 SHA-256/9d68dc73977fb7969a37262e77c520b678c5ba48fed71b2bc03155a71fc9ab8b
 * @jenesis.pin org.wiremock/wiremock-string-parser 4.0.0-beta.38 SHA-256/f2188d759b4ec3d27ba93974df1024e12a7c6e79a77bcbcdfb2b72b98e78d576
 * @jenesis.pin org.wiremock/wiremock-string-parser-jackson2 4.0.0-beta.38 SHA-256/de828408fb522fa46f632a9274f4a48d93bdb8aa77b076a442a251fe83b77566
 * @jenesis.pin org.wiremock/wiremock-url 4.0.0-beta.38 SHA-256/30da9fc7594a8e2d8b8b78652a83a88af3c73d1180ab00bf7c2b3fa857bb6f6e
 * @jenesis.pin org.wiremock/wiremock-url-jackson2 4.0.0-beta.38 SHA-256/02a166a2210f30ed290bfbd8508c4d43153907c4bc9c527a2df7f001d8d09b76
 * @jenesis.pin org.xmlunit/xmlunit-core 2.12.0 SHA-256/b2697e52e3a824e0874b3698b0c4f676c736301bc28e8285b67ab5385604d4fe
 * @jenesis.pin org.xmlunit/xmlunit-legacy 2.12.0 SHA-256/d32d0409345e15ae2d02273f92a8dd324dcea447d655b9f0d2fdba98a41bcff3
 * @jenesis.pin org.xmlunit/xmlunit-placeholders 2.12.0 SHA-256/ea0662ac8109e8a0a5959ef4e17f18ea724ba77f82b2bbeefaff3786f01f48bc
 * @jenesis.pin org.yaml/snakeyaml 2.3 SHA-256/63a76fe66b652360bd4c2c107e6f0258daa7d4bb492008ba8c26fcd230ff9146
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
