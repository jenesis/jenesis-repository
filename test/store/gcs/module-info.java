/**
 * Tests for the GCS artifact-store backend, in three legs. The data-path leg (ranged reads and a &gt;1000-key list
 * paging boundary) runs against a MinIO container - the backend's S3-compatible surface is exactly the {@code s3}
 * backend's, so the same emulator drives it; the suite self-skips without a Docker daemon. The cross-backend
 * {@code ArtifactStore} contract itself lives in the shared {@code StoreContract} kit and runs against this backend
 * from {@code test/store/contract}, where the versioned half is excluded with a reason for exactly the emulator gap
 * below. The GCS-specific conditional writes ({@code x-goog-if-generation-match},
 * which MinIO does not honour) are proven against an in-process generation-aware stub on
 * {@code jdk.httpserver}, driven through the real SDK client: create-if-absent, update-if-unchanged,
 * both rejections, and the {@code x-goog-generation} version token. A live smoke runs the full
 * contract against a real GCS bucket when the {@code JENESIS_REPOSITORY_GCS_*} HMAC credentials are present in
 * the environment - an entitlement is not a tool, so it skips (plain assumption) even on a strict run.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.gcs
 * @jenesis.alias org.containers org.containers/containers
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.pin com.ethlo.time/itu 1.14.0 SHA-256/5cf40ab0cc77828ab2b875b1f3ecd71c8295d7721933476abc2e08fddcea164a
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.21 SHA-256/53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b
 * @jenesis.pin com.fasterxml.jackson.core/jackson-core 2.21.3 SHA-256/baf8b739e9d9b93bcdb33f25046bfdb8dbd74c97de2a8698539fbe0c7eeac0bb
 * @jenesis.pin com.fasterxml.jackson.core/jackson-databind 2.21.3 SHA-256/f397563d8e67630c10cab8c2334ca0e55af832fa3ebde160a379c2c96d43bf25
 * @jenesis.pin com.fasterxml.jackson.dataformat/jackson-dataformat-yaml 2.18.3 SHA-256/3ca00e47cfcb43e79438ddcfc8fc735e9ebac3824fb7769138609be6bd56e483
 * @jenesis.pin com.fasterxml.jackson.datatype/jackson-datatype-jsr310 2.21.3 SHA-256/c6f721d6ea16f5557567a476069f7c08c598ff8f02766f229b45fe1e87139a20
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
 * @jenesis.pin io.netty/netty-codec-http 4.1.135.Final SHA-256/4018529d3d6aecf4044b98c75d9a90c91839ddf49c7aa484c5ac81c90a15da02
 * @jenesis.pin io.netty/netty-codec-http2 4.1.135.Final SHA-256/aa4e81ab5fa3b7b243eb3e814aa582ab26c073d31b0abffdbb58ee150fa49c16
 * @jenesis.pin io.netty/netty-common 4.1.135.Final SHA-256/26775ca95820711403cf065fa2ec0134a0a04ff5417c688c0237aee68b55838d
 * @jenesis.pin io.netty/netty-handler 4.1.135.Final SHA-256/245e74e04b6f4e8ef98853152412e3bf1499ce6fcf15329b798c8ce36c3537e2
 * @jenesis.pin io.netty/netty-resolver 4.1.135.Final SHA-256/77dd03865965b6c12b9e521bddec82f035caeb33156e09c158289c5094318481
 * @jenesis.pin io.netty/netty-transport 4.1.135.Final SHA-256/6bde734d1ec073142eed31b1e68cd5d68fbf241e060b37f07a164e5ecb15631c
 * @jenesis.pin io.netty/netty-transport-classes-epoll 4.1.135.Final SHA-256/9d9537ab9e15164c9f0dc0748884c148814a18d78ac6dfa65cf4b3d06068ce01
 * @jenesis.pin io.netty/netty-transport-native-unix-common 4.1.135.Final SHA-256/a7895075f112611d1640a596c2678a28aab92d5681c1c14755b109b8998f995e
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
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.3 SHA-256/d78396e3c5bce3f2865c9186647481e5589d34cacc632484715b686108d17c66
 * @jenesis.pin net.bytebuddy/byte-buddy-agent 1.12.10 SHA-256/5e8606d14a844c1ec70d2eb8f50c4009fb16138905dee8ca50a328116c041257
 * @jenesis.pin net.java.dev.jna/jna 5.18.1 SHA-256/260c4b1e22b1db9e110ee441c4f13ce115f841fa48c41d78750986214b395557
 * @jenesis.pin net.javacrumbs.json-unit/json-unit-core 5.1.2 SHA-256/184607efe6d314150f2b0373a2f9a3f40c2071594d948fdcad6715b4dd774d66
 * @jenesis.pin net.minidev/accessors-smart 2.6.0 SHA-256/222c9f547bb20a99fc486403a398352d1306fb671b38abd7ecab6401df170e61
 * @jenesis.pin net.minidev/json-smart 2.6.0 SHA-256/1ae4b561458afb540be8ec5c6dbb4f2e715a319a7ae64854998aaf924770d61b
 * @jenesis.pin org.apache.commons/commons-compress 1.28.0 SHA-256/e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca
 * @jenesis.pin org.apache.commons/commons-lang3 3.18.0 SHA-256/4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720
 * @jenesis.pin org.apache.commons/commons-text 1.15.0 SHA-256/58d2da30f058512a1e7f914e39241deca4dff5c27a085b4ed2faa9e7208067f6
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.1 SHA-256/1e3d8444c3c27772e4b9d42a790f06b3345a8ece4fd16d00981f2f2460e1e772
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.2 SHA-256/7c34a25506e7207b6748cef9e91163ed03081bee805cef930d82e1d8761d62f1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4 SHA-256/2e0f4ace15db2d1609c2b06eca6012e7582afe4a99ad8d15073f62dd8edb3460
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
 * @jenesis.pin org.mockito/mockito-core 4.6.0 SHA-256/125899de3dad49e375ad9ed34004d2ed582fa60b1f070a5b344bd928f9eac876
 * @jenesis.pin org.mockito/mockito-junit-jupiter 4.6.0 SHA-256/a773c0a51530291b72d03aca35191928bb18967700a8ceb53694e9bc8a1cff15
 * @jenesis.pin org.objenesis/objenesis 3.2 SHA-256/03d960bd5aef03c653eb000413ada15eb77cdd2b8e4448886edf5692805e35f3
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
 * @jenesis.pin org.containers/containers 2.0.5 SHA-256/0466f481343d5f350a91274cd7bf984308cbaf90d706247fd1cf4b1a8010c2e1
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
 * @jenesis.pin software.amazon.awssdk.auth 2.46.15
 * @jenesis.pin software.amazon.awssdk.http.urlconnection 2.46.15
 * @jenesis.pin software.amazon.awssdk.regions 2.46.15
 * @jenesis.pin software.amazon.awssdk.services.s3 2.46.15
 * @jenesis.pin software.amazon.awssdk/annotations 2.46.17 SHA-256/98f9f6b41781620d4b625cf84bc180860d5824a294012e7074ff77f49e129392
 * @jenesis.pin software.amazon.awssdk/apache5-client 2.46.17 SHA-256/5dbcf96d87c75bfa4e4bb4243aa2f3ac041b7696ddfd3af5ef159375768c587c
 * @jenesis.pin software.amazon.awssdk/arns 2.46.17 SHA-256/f7ddb5641f77b8009437dd6278334bf81d992db831bd7a4db3b73d66f3a610c5
 * @jenesis.pin software.amazon.awssdk/auth 2.46.15 SHA-256/cdbf3d383f97e2653246758982e46f0f26051b1cd8b12aeadf5c79a20ad7857a
 * @jenesis.pin software.amazon.awssdk/aws-core 2.46.17 SHA-256/3281031ab23504626ddbb76a2192f28da22091472e9ee4cddfad72f7f3535467
 * @jenesis.pin software.amazon.awssdk/aws-query-protocol 2.46.17 SHA-256/4586f9bfeee34ba08ea37e5c6ef67064b037d3de401f1f8121b0190769251c89
 * @jenesis.pin software.amazon.awssdk/aws-xml-protocol 2.46.17 SHA-256/8cbfda0698a4df4be9802637da2b5a68b8d325645ad57b83cb2e71ec1299b63c
 * @jenesis.pin software.amazon.awssdk/checksums 2.46.17 SHA-256/785a062e218d18846f5ce4ba3268924a7f29ed9af269873bcea8611fe31fca45
 * @jenesis.pin software.amazon.awssdk/checksums-spi 2.46.17 SHA-256/7c9e338beb0d5495c49c70c4f32e097423082405a24401a393a68c09057dd59c
 * @jenesis.pin software.amazon.awssdk/crt-core 2.46.17 SHA-256/f1f16f156a42f4920a029148489ecf0f7317a80a2b4cca010264993f8af09afd
 * @jenesis.pin software.amazon.awssdk/endpoints-spi 2.46.17 SHA-256/aa4e9cab7d29d9289bc00e18a9eef2ae7939cb1d4cbc8ee63890a360e6111437
 * @jenesis.pin software.amazon.awssdk/http-auth 2.46.17 SHA-256/5d52a9bfbb491c4f505123461c80c1acfe1e0bae1acdc494b9667089f52da607
 * @jenesis.pin software.amazon.awssdk/http-auth-aws 2.46.17 SHA-256/d588b14c191129e97cd7f6c8d53c22b469ae4ddf3d6cd407ef8c6442e605d282
 * @jenesis.pin software.amazon.awssdk/http-auth-aws-eventstream 2.46.17 SHA-256/d89ced4eb8e32a26ca931ac4247472a01d00c60f431326b601100842a1914096
 * @jenesis.pin software.amazon.awssdk/http-auth-spi 2.46.17 SHA-256/2fe8cc03ae5180a854afa4f60f0c1b39b4e51d753ef358ab85109f184f0b9fca
 * @jenesis.pin software.amazon.awssdk/http-client-spi 2.46.17 SHA-256/ba0b3d37b30c977b75f4e959297e98dae31912a14539d30e74b9d9ec02a95182
 * @jenesis.pin software.amazon.awssdk/identity-spi 2.46.17 SHA-256/6fc4ebdc03089d97d5d7eb32baf9a8a77ba5db012ce14ccc9cd372ab494c7326
 * @jenesis.pin software.amazon.awssdk/json-utils 2.46.17 SHA-256/72ec5509482efdc8ece656ce166b4d4dc349f9253e2f3fcea3f6ccdfd5c94913
 * @jenesis.pin software.amazon.awssdk/metrics-spi 2.46.17 SHA-256/66ba37e5e06180fa0f2118f3b1e3780ac46e901a2e9055ff087437fda04a0702
 * @jenesis.pin software.amazon.awssdk/netty-nio-client 2.46.17 SHA-256/92e1df3f7314869aef2b19eaa1c385b5050689c42b6b241c792a0de3c3b0197b
 * @jenesis.pin software.amazon.awssdk/profiles 2.46.17 SHA-256/c537e290eeccb21e7f15ea8095d4e1ef8ed2f45afa4b467cca432ede02ce5541
 * @jenesis.pin software.amazon.awssdk/protocol-core 2.46.17 SHA-256/b4d047127f67f25417204d8fd3d460302a0b2bc76ca12477a2f80512bc5327c9
 * @jenesis.pin software.amazon.awssdk/regions 2.46.15 SHA-256/3c1773be3f40d95a563511f97dac8f15b8a68d2a0de4550df37854c5c7af652b
 * @jenesis.pin software.amazon.awssdk/retries 2.46.17 SHA-256/d139a0b137055782e0e273249592c04ef46e4e365ebb0b9f6121c634cc080af8
 * @jenesis.pin software.amazon.awssdk/retries-spi 2.46.17 SHA-256/526014a15604513d0e28a201de4252dff52e2d12fcdc1c124e7cd941ab8e6998
 * @jenesis.pin software.amazon.awssdk/s3 2.46.15 SHA-256/124798312e104cf72067c9ce1abc950ceee2bee716e32cda69727c5d3bcfc6df
 * @jenesis.pin software.amazon.awssdk/sdk-core 2.46.17 SHA-256/129fa9e17b2847913f7e95e3a47de751f84348259473c360e17f6184f7107e85
 * @jenesis.pin software.amazon.awssdk/third-party-jackson-core 2.46.17 SHA-256/702689c84d4124db958e658112a84cface9933f7fd20ac5ada2497d1c54ae7bb
 * @jenesis.pin software.amazon.awssdk/url-connection-client 2.46.15 SHA-256/633cd8230c69414af9113c510d4607a010396d614b5733cfa02edab50560ac8c
 * @jenesis.pin software.amazon.awssdk/utils 2.46.17 SHA-256/4f9ee28ee6b6d9771fad18bac10cb806d7bebc0b0abfb6515fc7b4952fbb8507
 * @jenesis.pin software.amazon.awssdk/utils-lite 2.46.17 SHA-256/1d5bcc1929c7adb9d82d3f66e95b410602bd567c7704f8c73aca4e62c35ab5dd
 * @jenesis.pin software.amazon.eventstream/eventstream 1.0.1 SHA-256/0c37d8e696117f02c302191b8110b0d0eb20fa412fce34c3a269ec73c16ce822
 */
open module build.jenesis.repository.store.gcs.test {
    requires build.jenesis.repository.store.gcs;
    requires build.jenesis.repository.store;
    requires software.amazon.awssdk.services.s3;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.auth;
    requires software.amazon.awssdk.http.urlconnection;
    requires org.junit.jupiter;
    requires org.assertj.core;
    requires org.containers;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
