/**
 * Focused unit tests for the proxy caches' observability adoption - that the two composed {@link
 * build.jenesis.repository.proxy.RevalidatingFetcher} and {@link build.jenesis.repository.proxy.NegativeCachingFetcher}
 * decorators are each an {@link build.jenesis.repository.observation.ObservabilitySource} reporting their bounded
 * {@code jenesis.proxy.*} used-vs-available gauges (the remembered upstream misses against the map bound, the cached
 * index bytes against the byte ceiling) and a presence health check, all collected into the single {@link
 * build.jenesis.repository.observation.ObservabilityReport} view - exercised without the server, Micrometer or any
 * network through a stub upstream fetcher. The caches' proxying behaviour itself is covered by the server test module.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.proxy
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.pin com.ethlo.time/itu 1.14.0 SHA-256/5cf40ab0cc77828ab2b875b1f3ecd71c8295d7721933476abc2e08fddcea164a
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.21 SHA-256/53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b
 * @jenesis.pin com.fasterxml.jackson.core/jackson-core 2.21.3 SHA-256/baf8b739e9d9b93bcdb33f25046bfdb8dbd74c97de2a8698539fbe0c7eeac0bb
 * @jenesis.pin com.fasterxml.jackson.core/jackson-databind 2.21.3 SHA-256/f397563d8e67630c10cab8c2334ca0e55af832fa3ebde160a379c2c96d43bf25
 * @jenesis.pin com.fasterxml.jackson.dataformat/jackson-dataformat-yaml 2.18.3 SHA-256/3ca00e47cfcb43e79438ddcfc8fc735e9ebac3824fb7769138609be6bd56e483
 * @jenesis.pin com.fasterxml.jackson.datatype/jackson-datatype-jsr310 2.21.3 SHA-256/c6f721d6ea16f5557567a476069f7c08c598ff8f02766f229b45fe1e87139a20
 * @jenesis.pin com.github.jknack/handlebars 4.5.3 SHA-256/ea3be4f2cde8cc7b912448edd764a754debfeedd0c85f426c4de77361216cfd2
 * @jenesis.pin com.github.jknack/handlebars-helpers 4.5.3 SHA-256/c46e4f5d01069924d02ec474343555190fe645e1bbd4ba0b0399d8b488519573
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.47.0 SHA-256/5364bc6f22e72e98195e406a58d3ba1c09ffa11dea0729592cb870dc2de4056d
 * @jenesis.pin com.google.guava/failureaccess 1.0.3 SHA-256/cbfc3906b19b8f55dd7cfd6dfe0aa4532e834250d7f080bd8d211a3e246b59cb
 * @jenesis.pin com.google.guava/guava 33.6.0-jre SHA-256/dc573e1fca4fd5454f4a5fd3d7da2df03002876a4175bafc14a95980dd7713b3
 * @jenesis.pin com.google.guava/listenablefuture 9999.0-empty-to-avoid-conflict-with-guava SHA-256/b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99
 * @jenesis.pin com.google.j2objc/j2objc-annotations 3.1 SHA-256/84d3a150518485f8140ea99b8a985656749629f6433c92b80c75b36aba3b099b
 * @jenesis.pin com.jayway.jsonpath/json-path 3.0.0 SHA-256/e4e49440701674ace75af44a98840d9e13f53b34aab280446707661318405dc8
 * @jenesis.pin com.networknt/json-schema-validator 2.0.1 SHA-256/216fa6f496d4390ec6ba208593ad91d3a6fae21b7f5d8d327c4d628946ff9ea6
 * @jenesis.pin commons-fileupload/commons-fileupload 1.5 SHA-256/51f7b3dcb4e50c7662994da2f47231519ff99707a5c7fb7b05f4c4d3a1728c14
 * @jenesis.pin commons-io/commons-io 2.19.0 SHA-256/824268919b4b62f9f40f08c54381de5993b078f58667e332d17348ae019d72b9
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
 * @jenesis.pin net.javacrumbs.json-unit/json-unit-core 5.1.2 SHA-256/184607efe6d314150f2b0373a2f9a3f40c2071594d948fdcad6715b4dd774d66
 * @jenesis.pin net.minidev/accessors-smart 2.6.0 SHA-256/222c9f547bb20a99fc486403a398352d1306fb671b38abd7ecab6401df170e61
 * @jenesis.pin net.minidev/json-smart 2.6.0 SHA-256/1ae4b561458afb540be8ec5c6dbb4f2e715a319a7ae64854998aaf924770d61b
 * @jenesis.pin org.apache.commons/commons-lang3 3.20.0 SHA-256/69e5c9fa35da7a51a5fd2099dfe56a2d8d32cf233e2f6d770e796146440263f4
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
 * @jenesis.pin org.openjdk.nashorn/nashorn-core 15.4 SHA-256/6f816e84dfd63a81d4eaa7829c08337bbaff3ec683ff3bf6bbd90d017a00dc6f
 * @jenesis.pin org.opentest4j 1.3.0
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.4
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.ow2.asm/asm 7.3.1 SHA-256/2f67e11ceec819ebd88ddee5300aba699b1cbab2e20c22e97cf027d3be93959b
 * @jenesis.pin org.ow2.asm/asm-analysis 7.3.1 SHA-256/46b8a8efd4b94facb5ab4b35afe30ee0546ae7a43d2c64e6def56c2f168fefa5
 * @jenesis.pin org.ow2.asm/asm-commons 7.3.1 SHA-256/87cd8bb3c6bf6bcbb33fca48060c5065f66ebf6a3d7de9bf18bff51bcf156ebc
 * @jenesis.pin org.ow2.asm/asm-tree 7.3.1 SHA-256/f91a4a8aa868c5c4665bb4fd134019a91f9f8b9216527fba295e3c8b5422b78b
 * @jenesis.pin org.ow2.asm/asm-util 7.3.1 SHA-256/182128592742ed4883ac82bf205f137b6bfbe1234c68e6feb13759e75a85b729
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
open module build.jenesis.repository.proxy.test {
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.observation;
    requires org.junit.jupiter;
    requires org.assertj.core;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
