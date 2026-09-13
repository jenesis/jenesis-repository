/**
 * End-to-end test of the Spring Boot web console. It boots the real {@link build.jenesis.repository.ui.Application} on
 * an ephemeral port over a temporary filesystem store (supplied here, so the console module itself stays
 * store-agnostic) under the {@code dev} security profile, then drives it over HTTP: the Actuator health endpoint is up,
 * the login page is served anonymously, the console denies an anonymous request, and an authenticated user sees the
 * browse panel rendering the store's real published contents.
 *
 * @jenesis.release 25
 * @jenesis.exclude spring.security.oauth2.client com.nimbusds/oauth2-oidc-sdk
 * @jenesis.test build.jenesis.repository.ui
 * @jenesis.attach org.mockito
 * @jenesis.pin ch.qos.logback/logback-classic 1.6.3 SHA-256/beebede8db065fe1b72909ecc66bb49acda618901635d86c25fce14a5915e37e
 * @jenesis.pin ch.qos.logback/logback-core 1.6.3 SHA-256/a6967a28c8b086dee75a694d2f6fb8830c71153539866d7d8af065c9b6df91e0
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin com.nimbusds/nimbus-jose-jwt 10.9.1 SHA-256/33152ea83ec50d22706fdaf3b07acbcd716f9a68edcabdd7c4d02843cbdcdcf6
 * @jenesis.pin commons-logging/commons-logging 1.4.0 SHA-256/d175dbd751dd782a63bde28c7a039520e971f25e84b79c19b8435edc3603e0dc
 * @jenesis.pin io.micrometer/micrometer-commons 1.17.1 SHA-256/f2cee6ef046e72eec8128474c7f58c6217173b45ae373c30de0289f2a56ab0fd
 * @jenesis.pin io.micrometer/micrometer-core 1.17.1 SHA-256/9ca47dfa831409b40253c8dc98c17b9d3e050ebbde4d1543c5521dca2b95ad56
 * @jenesis.pin io.micrometer/micrometer-jakarta9 1.17.1 SHA-256/37fbb0a98ba92e185ae7ebe0804fe6c1394bf7fba7573b204fa6fc3998c2e246
 * @jenesis.pin io.micrometer/micrometer-observation 1.17.1 SHA-256/eb34f0cd84a879393ae5c21160fc06385ccdc74f36a4d1e31b199f59acfdaf71
 * @jenesis.pin jakarta.annotation/jakarta.annotation-api 3.0.0 SHA-256/b01f55552284cfb149411e64eabca75e942d26d2e1786b32914250e4330afaa2
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.cdi-api 4.1.0 SHA-256/c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.lang-model 4.1.0 SHA-256/bb56f571f60d2862b2387d5468fe8f5540f8094727283ed991f89082708095ee
 * @jenesis.pin jakarta.inject/jakarta.inject-api 2.0.1 SHA-256/f7dc98062fccf14126abb751b64fab12c312566e8cbdc8483598bffcea93af7c
 * @jenesis.pin jakarta.interceptor/jakarta.interceptor-api 2.2.0 SHA-256/d240d72b4dd38a2e431c804079810010cb97903678fa5f987fb7434878b04398
 * @jenesis.pin jakarta.servlet 6.1.0 SHA-256/8a31f465f3593bf2351531a5c952014eb839da96a605b5825b93dd54714c48c4
 * @jenesis.pin jakarta.servlet/jakarta.servlet-api 6.1.0 SHA-256/8a31f465f3593bf2351531a5c952014eb839da96a605b5825b93dd54714c48c4
 * @jenesis.pin jakarta.transaction/jakarta.transaction-api 2.0.1 SHA-256/50c0a7c760c13ae6c042acf182b28f0047413db95b4636fb8879bcffab5ba875
 * @jenesis.pin jakarta.websocket/jakarta.websocket-api 2.2.0 SHA-256/541d00436cbca0a5e1f6a457c9f70a64f00bd2f83e10ed89c2b372bc34843b7e
 * @jenesis.pin jakarta.websocket/jakarta.websocket-client-api 2.2.0 SHA-256/aa6fa9331a3f470daee0dbfcf084abfbd7a49507297575d5bb8bfbf3d62fe8c0
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.13 SHA-256/aa0013fbcb7e8086f4775f3dcb69a6c6833f7fd9023fe027d2d0241834cc51d5
 * @jenesis.pin net.bytebuddy/byte-buddy-agent 1.18.13 SHA-256/990c630297fb8c84fea5b16085a4b3035b81748084a94a98f0b9dc0c5d63d499
 * @jenesis.pin org.apache.logging.log4j/log4j-api 2.26.1 SHA-256/f1810a4704ccce019d02bba029dc02a4f2ac0c997f647b465e7d409c1927f822
 * @jenesis.pin org.apache.logging.log4j/log4j-to-slf4j 2.26.1 SHA-256/6b10bc838a3c773b3fd02979c7ef4e7a443371fa89bda4415d89099bbee224df
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
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
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.7 SHA-256/ff1cf9d62786d067fa19c56f13cd6ed1077d0f8782ff269824b7a2c586101bf0
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.ow2.asm/asm 9.10.1 SHA-256/ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb
 * @jenesis.pin org.ow2.asm/asm-commons 9.10.1 SHA-256/6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895
 * @jenesis.pin org.ow2.asm/asm-tree 9.10.1 SHA-256/3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b
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
 * @jenesis.pin spring.beans 7.0.9 SHA-256/ff218b827a25c9e8929b0cd56dfb56916cea9d5b669ed97dc7cb262508ff548b
 * @jenesis.pin spring.context 7.0.9 SHA-256/7552a2fcfa30cea53eb14d7a65a7a8e1b1dd82e832a7164fb7e6fb105f438858
 * @jenesis.pin spring.core 7.0.9 SHA-256/5195f4722699b39878d99a832549fe65df2890b159d063b88fff31b1ca65ae36
 * @jenesis.pin spring.security.core 7.1.1 SHA-256/98a5011baa78df36fb184e6ce0e8e086ca9a64fcdd8f161c0a96475a3a0907bd
 * @jenesis.pin spring.security.oauth2.client 7.1.1 SHA-256/d871b3571201c69e0142cca3a61fbcca4fd4ec9c0dedb21fdd4fe4a77ff9991d
 * @jenesis.pin spring.security.oauth2.core 7.1.1 SHA-256/29140d71b37cf1246bf16e33619e88e7baaa1c66bf5574e3c3e9a9f225f0ef6e
 * @jenesis.pin spring.security.web 7.1.1 SHA-256/dece134a2332c976a2c94ecf13f816c64e8ea7f2139795485afe655fba0408f3
 * @jenesis.pin spring.web 7.0.9 SHA-256/941ced476427bde2533872f293da535fd0258de3f3a650a7f1bfe01ed2927302
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.2 SHA-256/93d9090b8c505b2f942ad85dd5821e8c401bfbafafff11708a3fbe2ef9da18ca
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
 */
open module build.jenesis.repository.ui.test {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.posture;
    requires build.jenesis.repository.contract.testkit;
    requires java.net.http;
    requires spring.core;
    requires spring.beans;
    requires spring.context;
    requires spring.web;
    requires jakarta.servlet;
    requires spring.security.core;
    requires spring.security.web;
    requires jdk.httpserver;
    requires spring.security.oauth2.client;
    requires spring.security.oauth2.core;
    requires org.junit.jupiter;
    requires org.assertj.core;
    requires org.mockito;
    // The card census loads the service itself rather than only through UiConfig: the runtime discovery leg and the
    // rendered-set leg are separate assertions, and a `uses` clause is what lets this module make the first one.
    uses build.jenesis.repository.ui.ConsoleCard;
    // A panel that always throws, discovered exactly like a real one, so the booted console in ConsoleE2ETest serves a
    // page that really contains a contained failure - the shell's rendering of it is not provable any other way.
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.ui.test.NavigatingConsoleModule;
    provides build.jenesis.repository.ui.ConsoleCard with build.jenesis.repository.ui.test.FailingCard;
    // A format declaring a mark, discovered the same way, so the booted console resolves a namespace's mark through
    // the panel's own ServiceLoader path rather than only through a lookup a unit test hands in. No format module is
    // otherwise on the console's graph.
    provides build.jenesis.repository.format.RepositoryFormat with build.jenesis.repository.ui.test.MarkedFormat;
}
