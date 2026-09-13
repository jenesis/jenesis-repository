/**
 * End-to-end test of the dual-layout repository. It boots the real {@link build.jenesis.repository.server.RepositoryApplication}
 * on an ephemeral port over a temporary filesystem store, publishes artifacts over HTTP, and resolves them back
 * under both layouts - a Maven library that carries a module name is consumable by module name, and a module is
 * consumable by its Maven coordinate, off one content-addressed blob. The {@code jdk.httpserver} requirement backs
 * the in-test fake Nexus and Artifactory upstreams the import tests drive, not the repository server itself.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.server
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.attach org.mockito
 * @jenesis.pin ch.qos.logback.classic 1.6.3 SHA-256/beebede8db065fe1b72909ecc66bb49acda618901635d86c25fce14a5915e37e
 * @jenesis.pin ch.qos.logback.core 1.6.3 SHA-256/a6967a28c8b086dee75a694d2f6fb8830c71153539866d7d8af065c9b6df91e0
 * @jenesis.pin ch.qos.logback/logback-classic 1.6.3 SHA-256/beebede8db065fe1b72909ecc66bb49acda618901635d86c25fce14a5915e37e
 * @jenesis.pin ch.qos.logback/logback-core 1.6.3 SHA-256/a6967a28c8b086dee75a694d2f6fb8830c71153539866d7d8af065c9b6df91e0
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
 * @jenesis.pin com.nimbusds/nimbus-jose-jwt 10.9.1 SHA-256/33152ea83ec50d22706fdaf3b07acbcd716f9a68edcabdd7c4d02843cbdcdcf6
 * @jenesis.pin commons-fileupload/commons-fileupload 1.5 SHA-256/51f7b3dcb4e50c7662994da2f47231519ff99707a5c7fb7b05f4c4d3a1728c14
 * @jenesis.pin commons-io/commons-io 2.22.0 SHA-256/2b9a7b1f726fb86216dbd2c8321eabe0221dbd5b1be81c18e1cb53811b104758
 * @jenesis.pin commons-logging/commons-logging 1.4.0 SHA-256/d175dbd751dd782a63bde28c7a039520e971f25e84b79c19b8435edc3603e0dc
 * @jenesis.pin io.micrometer/micrometer-commons 1.17.1 SHA-256/f2cee6ef046e72eec8128474c7f58c6217173b45ae373c30de0289f2a56ab0fd
 * @jenesis.pin io.micrometer/micrometer-core 1.17.1 SHA-256/9ca47dfa831409b40253c8dc98c17b9d3e050ebbde4d1543c5521dca2b95ad56
 * @jenesis.pin io.micrometer/micrometer-jakarta9 1.17.1 SHA-256/37fbb0a98ba92e185ae7ebe0804fe6c1394bf7fba7573b204fa6fc3998c2e246
 * @jenesis.pin io.micrometer/micrometer-observation 1.17.1 SHA-256/eb34f0cd84a879393ae5c21160fc06385ccdc74f36a4d1e31b199f59acfdaf71
 * @jenesis.pin io.micrometer/micrometer-registry-prometheus 1.17.1 SHA-256/ac24d43f123e103828a23a86f092a8709d87d309ad3212e798305654e951a8ce
 * @jenesis.pin io.prometheus/prometheus-metrics-config 1.8.0 SHA-256/7b16c71dbbe9f6e9b2e9afef15746a371a7961229f4460ebe0157e8b711969d9
 * @jenesis.pin io.prometheus/prometheus-metrics-core 1.8.0 SHA-256/b5eaf00a8d6f9fd23714af0ed3846e954bbb2e53b484a54b53f18d2155111dc7
 * @jenesis.pin io.prometheus/prometheus-metrics-exposition-formats 1.8.0 SHA-256/1fdc5caa726f3d6cd60959894bf1c1ba12cc8e3e2283439915b9d689ebd845d0
 * @jenesis.pin io.prometheus/prometheus-metrics-exposition-textformats 1.8.0 SHA-256/17b6e78ad224cb1921e66ea622aea7fc00f143e292c540db8dc1a9af5cf660bc
 * @jenesis.pin io.prometheus/prometheus-metrics-model 1.8.0 SHA-256/18a5c698073306b46234280530ab6e36046b14fb681aa42fac640a94865246f2
 * @jenesis.pin io.prometheus/prometheus-metrics-tracer-common 1.8.0 SHA-256/037ee7c19e97aa6f36d77285812ae2c56ddfd32de4d9b1c759b97779c71a26d9
 * @jenesis.pin jakarta.annotation/jakarta.annotation-api 3.0.0 SHA-256/b01f55552284cfb149411e64eabca75e942d26d2e1786b32914250e4330afaa2
 * @jenesis.pin jakarta.el/jakarta.el-api 6.0.1 SHA-256/7e84b5bed49de32b79cc5e85d90b6f5adb1a953ac67283adbb41c1e297f9c605
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.cdi-api 4.1.0 SHA-256/c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.lang-model 4.1.0 SHA-256/bb56f571f60d2862b2387d5468fe8f5540f8094727283ed991f89082708095ee
 * @jenesis.pin jakarta.inject/jakarta.inject-api 2.0.1 SHA-256/f7dc98062fccf14126abb751b64fab12c312566e8cbdc8483598bffcea93af7c
 * @jenesis.pin jakarta.interceptor/jakarta.interceptor-api 2.2.0 SHA-256/d240d72b4dd38a2e431c804079810010cb97903678fa5f987fb7434878b04398
 * @jenesis.pin jakarta.servlet 6.1.0 SHA-256/8a31f465f3593bf2351531a5c952014eb839da96a605b5825b93dd54714c48c4
 * @jenesis.pin jakarta.servlet/jakarta.servlet-api 6.1.0 SHA-256/8a31f465f3593bf2351531a5c952014eb839da96a605b5825b93dd54714c48c4
 * @jenesis.pin jakarta.transaction/jakarta.transaction-api 2.0.1 SHA-256/50c0a7c760c13ae6c042acf182b28f0047413db95b4636fb8879bcffab5ba875
 * @jenesis.pin jakarta.websocket/jakarta.websocket-api 2.2.0 SHA-256/541d00436cbca0a5e1f6a457c9f70a64f00bd2f83e10ed89c2b372bc34843b7e
 * @jenesis.pin jakarta.websocket/jakarta.websocket-client-api 2.2.0 SHA-256/aa6fa9331a3f470daee0dbfcf084abfbd7a49507297575d5bb8bfbf3d62fe8c0
 * @jenesis.pin micrometer.observation 1.17.1 SHA-256/eb34f0cd84a879393ae5c21160fc06385ccdc74f36a4d1e31b199f59acfdaf71
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.13 SHA-256/aa0013fbcb7e8086f4775f3dcb69a6c6833f7fd9023fe027d2d0241834cc51d5
 * @jenesis.pin net.bytebuddy/byte-buddy-agent 1.18.13 SHA-256/990c630297fb8c84fea5b16085a4b3035b81748084a94a98f0b9dc0c5d63d499
 * @jenesis.pin net.javacrumbs.json-unit/json-unit-core 6.2.0 SHA-256/712998bb69fc62a68e1aec4352906d276b8613d3ca53202e3113e4e0aac5c352
 * @jenesis.pin net.minidev/accessors-smart 2.6.0 SHA-256/222c9f547bb20a99fc486403a398352d1306fb671b38abd7ecab6401df170e61
 * @jenesis.pin net.minidev/json-smart 2.6.0 SHA-256/1ae4b561458afb540be8ec5c6dbb4f2e715a319a7ae64854998aaf924770d61b
 * @jenesis.pin org.apache.commons/commons-lang3 3.20.0 SHA-256/69e5c9fa35da7a51a5fd2099dfe56a2d8d32cf233e2f6d770e796146440263f4
 * @jenesis.pin org.apache.commons/commons-text 1.15.0 SHA-256/58d2da30f058512a1e7f914e39241deca4dff5c27a085b4ed2faa9e7208067f6
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.4 SHA-256/bdef5f8841145cd76c4c605ff301bc840eea0d5292332b7b892a93316c0d8ee1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.3 SHA-256/18bfbbabb478dfb67f31aeaf428c387f3c3df654582e1309f708ee1f3086830a
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4.3 SHA-256/c7db7026b8e2dea39132b04a6069f6671e2858309b20a146ec5c7dd6ed73a0b6
 * @jenesis.pin org.apache.logging.log4j/log4j-api 2.26.1 SHA-256/f1810a4704ccce019d02bba029dc02a4f2ac0c997f647b465e7d409c1927f822
 * @jenesis.pin org.apache.logging.log4j/log4j-to-slf4j 2.26.1 SHA-256/6b10bc838a3c773b3fd02979c7ef4e7a443371fa89bda4415d89099bbee224df
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.bouncycastle/bcpkix-jdk18on 1.83 SHA-256/d3c4c6b700c74ef8164bb15e549d939721b8f14fc0ff89fe19b220243bcfcbd8
 * @jenesis.pin org.bouncycastle/bcprov-jdk18on 1.83 SHA-256/82cf3a2af766c3bc874f6d36b9f20a8b99a8f09762dc776e8a227a45d8daaafb
 * @jenesis.pin org.bouncycastle/bcutil-jdk18on 1.83 SHA-256/ee7d0eb4e74de70a735f7fb36b604dd5c6ad35720d50b914604db042114a0185
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
 * @jenesis.pin org.hdrhistogram/HdrHistogram 2.2.2 SHA-256/22d1d4316c4ec13a68b559e98c8256d69071593731da96136640f864fa14fad8
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
 * @jenesis.pin org.mockito 5.23.0 SHA-256/ae295bebd5d11fab97ab297815dc7617188b86003cbce3dfd5c0d5c3a6cc4a0c
 * @jenesis.pin org.mockito/mockito-core 5.23.0 SHA-256/ae295bebd5d11fab97ab297815dc7617188b86003cbce3dfd5c0d5c3a6cc4a0c
 * @jenesis.pin org.objenesis/objenesis 3.6 SHA-256/6ecc0fc776af04e2bfd2bf42d618ab5161440dcb8a0e7dff9e84c065c7cec3dd
 * @jenesis.pin org.openjdk.nashorn/nashorn-core 15.7 SHA-256/3f2b62e55b5458ba2e8a0cc4599aa3abe81b1422e31c38bb8294a7096ceee6f2
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.7 SHA-256/ff1cf9d62786d067fa19c56f13cd6ed1077d0f8782ff269824b7a2c586101bf0
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.ow2.asm/asm 9.10.1 SHA-256/ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb
 * @jenesis.pin org.ow2.asm/asm-analysis 9.10.1 SHA-256/dede75a21306b65974ecd8f87114ff6970f09fb794157a4ca09ab25c888c2bfc
 * @jenesis.pin org.ow2.asm/asm-commons 9.10.1 SHA-256/6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895
 * @jenesis.pin org.ow2.asm/asm-tree 9.10.1 SHA-256/3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b
 * @jenesis.pin org.ow2.asm/asm-util 9.10.1 SHA-256/1bb99d091fba2597dc6d51193e9bbcf0d8447e7ed96bd8f0198b18152f09655c
 * @jenesis.pin org.slf4j 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.slf4j/jul-to-slf4j 2.0.19 SHA-256/a5764fbd2f0053399ef75d40653c4e7c97e733b45676946b0a072416fc56ff30
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.snakeyaml/snakeyaml-engine 3.1.1 SHA-256/59d73655cf077f154137e2d6f6f92c041a954c0b1c534c63800047a0d70a6947
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
 * @jenesis.pin org.springframework.boot/spring-boot-servlet 4.1.1 SHA-256/4dcb156e311a4c1ad5f1ed7eabd94a78ea74510b05c429a4fcfdcf8beb9d5882
 * @jenesis.pin org.springframework.boot/spring-boot-starter 4.1.1 SHA-256/de5b2dd28400eda20914fd4a6054d0c201e68d2e4de35a25a4d89f054ccc2e63
 * @jenesis.pin org.springframework.boot/spring-boot-starter-actuator 4.1.1 SHA-256/d26bb8461ccc417c97a869fbd2e9f0067ce8a56a9ab6058093097ca16e4cc128
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jetty 4.1.1 SHA-256/c516d9709ed3dc7ac4d0b5654cb4858f1eaed9bd231b872a359d2521c7039959
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jetty-runtime 4.1.1 SHA-256/685fe160af1fc854981829ebc7128735a328bd9eb71fcbf16ba50aec712bc1c7
 * @jenesis.pin org.springframework.boot/spring-boot-starter-logging 4.1.1 SHA-256/8eca7d72c8434f5c3b935385c656cd5337f473434bdbcb4913b3b3a34897d178
 * @jenesis.pin org.springframework.boot/spring-boot-starter-micrometer-metrics 4.1.1 SHA-256/ad34ce3535a82b3920efea7f9c5d6322de484969f3ab5fa6da05030edd7c14d4
 * @jenesis.pin org.springframework.boot/spring-boot-starter-security 4.1.1 SHA-256/38628875b75cbed6ba4642f3b0a4baf4735bd4f82b49450e4ae5a5e4d1b7b4a9
 * @jenesis.pin org.springframework.boot/spring-boot-web-server 4.1.1 SHA-256/6912fb1fdf7f657a61ac050760fa835fb1e7496399291697e2901f82887a4fee
 * @jenesis.pin org.springframework.boot/spring-boot-webmvc 4.1.1 SHA-256/9f08c1fb938c45a8693fec5f3065be3aca203948a7ab4d56b06444986f75a6b2
 * @jenesis.pin org.springframework.security/spring-security-config 7.1.1 SHA-256/1f947c853f14cab76563464ee22e72f6672f3913db52647d2f5d1df5f2b1e5a5
 * @jenesis.pin org.springframework.security/spring-security-core 7.1.1 SHA-256/98a5011baa78df36fb184e6ce0e8e086ca9a64fcdd8f161c0a96475a3a0907bd
 * @jenesis.pin org.springframework.security/spring-security-crypto 7.1.1 SHA-256/6e8bb2337faccabd30626f917ed1d2bfa9dde982229b6b373b644d1a01635403
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
 * @jenesis.pin org.yaml/snakeyaml 2.7 SHA-256/2e194eba45a67dee19a4e272f4a04b18de8054e9f598b094382f6dae0b0e4b5e
 * @jenesis.pin spring.security.core 7.1.1 SHA-256/98a5011baa78df36fb184e6ce0e8e086ca9a64fcdd8f161c0a96475a3a0907bd
 * @jenesis.pin spring.security.web 7.1.1 SHA-256/dece134a2332c976a2c94ecf13f816c64e8ea7f2139795485afe655fba0408f3
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.2 SHA-256/93d9090b8c505b2f942ad85dd5821e8c401bfbafafff11708a3fbe2ef9da18ca
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
 * @jenesis.pin tools.jackson.databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
 * @jenesis.pin tools.jackson.dataformat/jackson-dataformat-yaml 3.2.2 SHA-256/c2c24e93676832dbb14196685e24292bb04258165c70d6c1e41e475e34fc93d3
 */
open module build.jenesis.repository.test {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.contract.testkit;
    // The shared traversal probe vectors: ImporterContractTest probes every discovered importer with the same list the
    // format kit probes every format with, so the importer seam cannot rot into its own private set of shapes.
    requires build.jenesis.repository.format.testkit;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    // The shared fault fixture: MavenCrossPublishSequenceTest drives a real store failure at each step of the Maven
    // cross-publish rather than substituting a throwing ModuleView, so the crash windows it asserts are the ones a
    // backend outage really produces.
    requires build.jenesis.repository.store.testkit;
    // The capability-signal census reads GarbageCollectorProvider.installed() and WalkProvider.installed() off the
    // linked types, not just off their sources: a rename a text scan stops matching reads exactly like a pass.
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.walk;
    // ... and its shipped implementation, so the same suite can run a real rebuild pass over the residue a crashed
    // cross-publish leaves and prove the repair converges.
    requires build.jenesis.repository.walk.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.oidc;
    requires build.jenesis.repository.usage;
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.importer.nexus;
    requires build.jenesis.repository.importer.artifactory;
    requires build.jenesis.repository.importer.jenesis;
    requires build.jenesis.repository.importer.maven;
    requires build.jenesis.repository.importer.index;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.observation;
    requires micrometer.observation;
    requires tools.jackson.databind;
    requires jakarta.servlet;
    requires java.net.http;
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires org.junit.jupiter;
    requires org.assertj.core;
    // RepositoryAuthorizationManagerFailClosedTest drives the AuthorizationManager directly (no booted server), so the
    // test module reads the Spring Security types it takes and returns - RequestAuthorizationContext (web) and
    // AuthorizationResult (core) - which the server module requires but does not re-export.
    requires spring.security.web;
    requires spring.security.core;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
    requires org.mockito;
    // ImporterContractTest discovers every RepositoryImporter the way the server does - ServiceLoader over the
    // RepositoryFormat providers, filtered to the import capability - so it declares the same service dependency.
    uses build.jenesis.repository.format.RepositoryFormat;
    // WSPI.2 (b): the two publication hooks are one discovered seam - a PublishInterceptor IS a PublicationObserver,
    // so the screen fixtures register through the single PublicationObserver clause and Publication splits them into
    // the verdict chain by instanceof.
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.test.RecordingObserver,
                    build.jenesis.repository.test.MarkerInterceptor,
                    build.jenesis.repository.test.CountingInterceptor,
                    build.jenesis.repository.test.CensusObserver;
    // register a test ImportEdgeProvider so the running free server discovers it via ServiceLoader exactly as a
    // richer distribution would, proving the free import edge yields (its mapping is not registered) when a distribution
    // owns the edge - no WebMvcRegistrations suppression. Inert by default (a required-config gate), activated only by
    // the yield test, so every other import test still sees the free edge served.
    provides build.jenesis.repository.server.spi.ImportEdgeProvider
            with build.jenesis.repository.test.TestImportEdgeProvider;
}
