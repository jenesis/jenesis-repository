/**
 * The web console: a Spring Boot admin front for the repository, built on a mainstream Spring stack
 * (Spring Boot on embedded Jetty, Thymeleaf views, Spring Security with OAuth2/OIDC login) so a downstream distribution
 * extends this shell rather than forking it. It is an open module (Spring needs reflective access) and requires the
 * Spring modules its code compiles against plus the Spring Boot starters that root the runtime closure (embedded
 * Jetty, Thymeleaf, Jackson, Security, OAuth2 client). Built as an open shell with a panel-registration SPI
 * ({@code uses Panel}) discovered with ServiceLoader and bridged into Spring, so additional panels are registered by
 * adding modules to the graph, with no fork of the console. Login mechanisms plug in the same way through the
 * {@code LoginContributor} bean seam.
 *
 * <p>It requires the format SPI for one reason: the browse panel marks each published namespace with the mark of the
 * format that owns it, resolved through the shared {@code Marks} every contributing plug-in family renders through,
 * so a namespace no installed format claims is shown as the orphan it is rather than as an ordinary row. That is an
 * SPI dependency, not a plugin one - the console still requires no concrete format and discovers them all through
 * the SPI's own lookup.
 *
 * @jenesis.release 25
 * @jenesis.exclude spring.boot.starter.jetty org.apache.tomcat.embed/tomcat-embed-el
 * @jenesis.main build.jenesis.repository.ui.Application
 *
 * @jenesis.pin ch.qos.logback/logback-classic 1.5.34 SHA-256/b65e05076a5c1aadb659b4fe4bc5fee31cb26cd70390292eb03e4a7a24cff10f
 * @jenesis.pin ch.qos.logback/logback-core 1.5.34 SHA-256/42eda264c0c650c2bec59e66151a88b708a8663dc1b49d788202d53e78b8caae
 * @jenesis.pin com.fasterxml.jackson.annotation 2.22
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.github.stephenc.jcip/jcip-annotations 1.0-1 SHA-256/4fccff8382aafc589962c4edb262f6aa595e34f1e11e61057d1c6a96e8fc7323
 * @jenesis.pin com.nimbusds/content-type 2.3 SHA-256/60349793e006fba96b532cb0c21e10e969fe0db8d87f91c3b9eaf82ba2998895
 * @jenesis.pin com.nimbusds/lang-tag 1.7 SHA-256/e8c1c594e2425bdbea2d860de55c69b69fc5d59454452449a0f0913c2a5b8a31
 * @jenesis.pin com.nimbusds/nimbus-jose-jwt 10.9 SHA-256/64d613d91140bad0dab8f0c41960f919ec8705a9ced9418146598b4b3ae71349
 * @jenesis.pin com.nimbusds/oauth2-oidc-sdk 11.37.2 SHA-256/b66e74746dcf516d77f20344e6fbcbcffe1b483b5cf1ad41ea81cec83cb27b3c
 * @jenesis.pin commons-logging/commons-logging 1.3.5 SHA-256/6d7a744e4027649fbb50895df9497d109f98c766a637062fe8d2eabbb3140ba4
 * @jenesis.pin io.micrometer/micrometer-commons 1.17.0 SHA-256/03919dc71e2417ec4b5c254c4ba924963c972e124190f73cdcb68ed51c6eede6
 * @jenesis.pin io.micrometer/micrometer-core 1.17.0 SHA-256/73503e701a377fafeaf33b71b9b8910a8d7884cbba88ab27971b33b3753b65aa
 * @jenesis.pin io.micrometer/micrometer-jakarta9 1.17.0 SHA-256/4ae9dbc9072fea8c36684a745e0e944b9540fd15027dfe7af0a186f8df43272c
 * @jenesis.pin io.micrometer/micrometer-observation 1.17.0 SHA-256/2fc95a327578d3b2a81c3ff40e646a4a21e46b0153ccbbf91690142bf80d9661
 * @jenesis.pin jakarta.annotation/jakarta.annotation-api 3.0.0 SHA-256/b01f55552284cfb149411e64eabca75e942d26d2e1786b32914250e4330afaa2
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.cdi-api 4.1.0 SHA-256/c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699
 * @jenesis.pin jakarta.enterprise/jakarta.enterprise.lang-model 4.1.0 SHA-256/bb56f571f60d2862b2387d5468fe8f5540f8094727283ed991f89082708095ee
 * @jenesis.pin jakarta.inject/jakarta.inject-api 2.0.1 SHA-256/f7dc98062fccf14126abb751b64fab12c312566e8cbdc8483598bffcea93af7c
 * @jenesis.pin jakarta.interceptor/jakarta.interceptor-api 2.2.0 SHA-256/d240d72b4dd38a2e431c804079810010cb97903678fa5f987fb7434878b04398
 * @jenesis.pin jakarta.servlet 6.1.0
 * @jenesis.pin jakarta.servlet/jakarta.servlet-api 6.1.0 SHA-256/8a31f465f3593bf2351531a5c952014eb839da96a605b5825b93dd54714c48c4
 * @jenesis.pin jakarta.transaction/jakarta.transaction-api 2.0.1 SHA-256/50c0a7c760c13ae6c042acf182b28f0047413db95b4636fb8879bcffab5ba875
 * @jenesis.pin jakarta.websocket/jakarta.websocket-api 2.2.0 SHA-256/541d00436cbca0a5e1f6a457c9f70a64f00bd2f83e10ed89c2b372bc34843b7e
 * @jenesis.pin jakarta.websocket/jakarta.websocket-client-api 2.2.0 SHA-256/aa6fa9331a3f470daee0dbfcf084abfbd7a49507297575d5bb8bfbf3d62fe8c0
 * @jenesis.pin micrometer.observation 1.17.0
 * @jenesis.pin net.minidev/accessors-smart 2.6.0 SHA-256/222c9f547bb20a99fc486403a398352d1306fb671b38abd7ecab6401df170e61
 * @jenesis.pin net.minidev/json-smart 2.6.0 SHA-256/1ae4b561458afb540be8ec5c6dbb4f2e715a319a7ae64854998aaf924770d61b
 * @jenesis.pin org.apache.logging.log4j/log4j-api 2.25.4 SHA-256/c4b642a7f047275215de117e0e3847eb2c7711d84a0aa7433e7b3c096daf341d
 * @jenesis.pin org.apache.logging.log4j/log4j-to-slf4j 2.25.4 SHA-256/d7b78fc0aaaa5e8ada388b29d718b0ab187e512965bed0b259bb4ab299f13db2
 * @jenesis.pin org.apache.tomcat.embed/tomcat-embed-el 11.0.22 SHA-256/1b34c33b858c141df36c501b4d809e68036c406bca3671a86facae297917c7de
 * @jenesis.pin org.attoparser/attoparser 2.0.7.RELEASE SHA-256/75dd1c045492bff8e1963aabb28bfe903c2064e11e27fe2f0f0aff1ad3d84476
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-common 12.1.10 SHA-256/7c02e58d5589549a5e9b19a903134a0e868fdb9ee38459b2c4495a6a3b342ba0
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-gzip 12.1.10 SHA-256/8c112b9ebb3413626026ba6782afde5e22575eb6fb071c98ae3541cb8d2dc79e
 * @jenesis.pin org.eclipse.jetty.compression/jetty-compression-server 12.1.10 SHA-256/83197998a68192694870bacd65d8f5eb061bcb5d0de3fecff63f4bf5e26367fb
 * @jenesis.pin org.eclipse.jetty.ee/jetty-ee-webapp 12.1.10 SHA-256/a75c0f3ec4aff18295e8ebeba5de07e67c22892f511e7637e6e51ba7b01f35e1
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-client 12.1.10 SHA-256/65165fa83e24362c03a0617d70503acb05f4ae4141dbdbd39815b560c00a3695
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-common 12.1.10 SHA-256/f9c5b829fcd6bef1cb7594c1230d0b71333a4c541428c25c6bae9b5bf2a52c1b
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jakarta-server 12.1.10 SHA-256/0c8de85e674fdb81e430f6830fc6b2f88d4ca3e6127d1d4637d43055937e227b
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-jetty-server 12.1.10 SHA-256/71f1124a57daebceb0ca1f70a8f3e4c3909a37ff78fe3a141ea3d14110d342e3
 * @jenesis.pin org.eclipse.jetty.ee11.websocket/jetty-ee11-websocket-servlet 12.1.10 SHA-256/2473da8b3583d0b375690ea9030bd4e4ff2c7831d80400bc750989ed6ce06bc8
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-annotations 12.1.10 SHA-256/0f5ea662cf1835bf312e6935b3a935d47e616c0495fd07be546dfa442d167c00
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-plus 12.1.10 SHA-256/3a190d91396ee0fdf6f4fd72c5be155d99937a40b8b6e9f413543f5706a82e2b
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-servlet 12.1.10 SHA-256/668b12749f2043d15953beb0b9fbd2ba6f863b61de5b4682ff12778034c6895a
 * @jenesis.pin org.eclipse.jetty.ee11/jetty-ee11-webapp 12.1.10 SHA-256/a0e30b909c6653f94a2af93fff865413a52e86b82bd2c53f21e2388ebb7b0064
 * @jenesis.pin org.eclipse.jetty.jndi 12.1.10
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-client 12.1.10 SHA-256/10ee92d90fe1d112a6d6d612083de853f28935310902443bdbe5daea84e530f5
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-common 12.1.10 SHA-256/bc6397bf67feeb18d7851b5fbf817c4ed11016024481202e8db790bb5a8321e1
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-core-server 12.1.10 SHA-256/ca9e5cdecde06d21d0637f7b0c15e40b90463290fa8c75d2c66b4bad6910608d
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-api 12.1.10 SHA-256/f3b33c3c284c12917bef8a5af9b347dfecfbcb3b1a416a7d00041f5ad1b2b76f
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-common 12.1.10 SHA-256/b7b6766b0f5eec268b17e971cd8ac8058cffba350aed53e5bf120c00081be4cf
 * @jenesis.pin org.eclipse.jetty.websocket/jetty-websocket-jetty-server 12.1.10 SHA-256/e9fb2d66de7edd797f95089afad93717f24e8948097f238fb2bb94bbc1718584
 * @jenesis.pin org.eclipse.jetty/jetty-alpn-client 12.1.10 SHA-256/4eed96763ac18fe310ad162d3266cf64ec718d86f5ac87b19a91f3f62a2c4520
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
 * @jenesis.pin org.hdrhistogram/HdrHistogram 2.2.2 SHA-256/22d1d4316c4ec13a68b559e98c8256d69071593731da96136640f864fa14fad8
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.ow2.asm/asm 9.7.1 SHA-256/8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
 * @jenesis.pin org.ow2.asm/asm-commons 9.10 SHA-256/4282689aaa9a7023dd7a9ccd0bcca39aca49863e16d1ac709b392a6f36a50bfb
 * @jenesis.pin org.ow2.asm/asm-tree 9.10 SHA-256/02a58618b38c7748e9ce4adff00d1080ced507f309e50b8643b703532ef347e0
 * @jenesis.pin org.slf4j 2.0.18
 * @jenesis.pin org.slf4j/jul-to-slf4j 2.0.18 SHA-256/cbb7d1aaaa9e871eb1a06594abd911bf97027152976edf1edc315be75239204e
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 * @jenesis.pin org.springframework.boot/spring-boot 4.1.0 SHA-256/b23951a3a7f867e38db4729b8594e1b72374516f386b1dd9cf4d5317d6d3f91f
 * @jenesis.pin org.springframework.boot/spring-boot-actuator 4.1.0 SHA-256/10279d87ab47a9a41cb69c56fc69494a54a3b84f25c853141ed234d4832e85b4
 * @jenesis.pin org.springframework.boot/spring-boot-actuator-autoconfigure 4.1.0 SHA-256/1124c22bc848e5ed557fe4543698056ef7090f134f2ccbcafb3d6c18a5613b13
 * @jenesis.pin org.springframework.boot/spring-boot-autoconfigure 4.1.0 SHA-256/0fcaa3050ba835ca6b3879f81cd48dc590a6262f53bbf10d2a95c70bf7c048ac
 * @jenesis.pin org.springframework.boot/spring-boot-health 4.1.0 SHA-256/cbd92b42254fe264a5d4556538049139aabbfdc367f5d06fb5b4cf97aa70fc18
 * @jenesis.pin org.springframework.boot/spring-boot-http-converter 4.1.0 SHA-256/f82c8913ea17a60630a5d26fa006cf79f28c7d920dbbef8760f5bc7053706fea
 * @jenesis.pin org.springframework.boot/spring-boot-jackson 4.1.0 SHA-256/10b6e63a40b257168854093f413eaab8b8a9afb7e989fcc7f6732b42c3f173d1
 * @jenesis.pin org.springframework.boot/spring-boot-jetty 4.1.0 SHA-256/6b022ef267ddce9e596b4117e5f2f44e66cda8be7230990007f8e5ce53c57537
 * @jenesis.pin org.springframework.boot/spring-boot-micrometer-metrics 4.1.0 SHA-256/2030f79dbc59c9d84f1f4d6d2a423b46a8ba5cc277cbda75ac54a436b3ea96fb
 * @jenesis.pin org.springframework.boot/spring-boot-micrometer-observation 4.1.0 SHA-256/1f4a7a9755b38470157316dcec8a9f19b1b89864c5f742149b8db0c517f41853
 * @jenesis.pin org.springframework.boot/spring-boot-security 4.1.0 SHA-256/5e30a3ea1d62c5ef2af5a8bcf31237583f216f40dbb0b76ec2eda72981ea2bac
 * @jenesis.pin org.springframework.boot/spring-boot-security-oauth2-client 4.1.0 SHA-256/cdf7e36a52b80b0b139f82e05ea9db0e58c8711c207b25fb85dcc48bd891fae2
 * @jenesis.pin org.springframework.boot/spring-boot-servlet 4.1.0 SHA-256/5f694a7c6c357a87032bc92db0e7e0e03b64010f77892e5992c86b10f568a5ae
 * @jenesis.pin org.springframework.boot/spring-boot-starter 4.1.0 SHA-256/40352b3fc0834d5830f66d40100fc8afa8ac73e24a134c7779bd42b72f2d6506
 * @jenesis.pin org.springframework.boot/spring-boot-starter-actuator 4.1.0 SHA-256/f6f6c4166430953515336ecf3b25ea467e24c4e5705cbcc8cefa273a4bd6bde8
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jackson 4.1.0 SHA-256/4214c534cca5c7c7e1cf92db90f178a3dffdede503fb68ac3c0dd905f331431f
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jetty 4.1.0 SHA-256/59d5c50dfb5a2a56a57a6304899b7a7fe9dd317f72ad3b137578f44c16a5a87d
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jetty-runtime 4.1.0 SHA-256/d23dc1cb0f329bd20a1bb05a57fe205fbac92bc46f70bc2382fa8e38d0090d9f
 * @jenesis.pin org.springframework.boot/spring-boot-starter-logging 4.1.0 SHA-256/73a6a42d2b6a589bd722aa107800829d0b20b731d94135f53c4b744db8beafbf
 * @jenesis.pin org.springframework.boot/spring-boot-starter-micrometer-metrics 4.1.0 SHA-256/ad4a34ba880e6a8c811e90c1c034b937b8a78030eae60ec5b43826c42590c802
 * @jenesis.pin org.springframework.boot/spring-boot-starter-oauth2-client 4.1.0 SHA-256/bb4c0a7b44c1dfe4170f1122823cb37fc3c6e3975e5573d63712cb68b8a6c2cd
 * @jenesis.pin org.springframework.boot/spring-boot-starter-security 4.1.0 SHA-256/5370ad6bd847e85675ee81a2da98f5fabcfc8649197b0a873417a051aa435c41
 * @jenesis.pin org.springframework.boot/spring-boot-starter-thymeleaf 4.1.0 SHA-256/e9392e2da88700e1c52f4d6a154f48129f1a926f2dea8d9a568dcf9c29ad3d09
 * @jenesis.pin org.springframework.boot/spring-boot-starter-web 4.1.0 SHA-256/d2732bdc307d3628d680d32758b300972109f499ec8e023bd663cdad002c67c6
 * @jenesis.pin org.springframework.boot/spring-boot-thymeleaf 4.1.0 SHA-256/5df118e86f83b58a8a3f8e7f37d114b72ae175aa3e2074d008c6548c20d0f751
 * @jenesis.pin org.springframework.boot/spring-boot-web-server 4.1.0 SHA-256/a8541ccbd29f5a8db7e6092fa83463aa4d1c002fac07b8b5babe118ad6c4a3d3
 * @jenesis.pin org.springframework.boot/spring-boot-webmvc 4.1.0 SHA-256/ab21735a550cbfefaa4ad6ffbb1a891592580ef05ad729cd2025bc0245862b55
 * @jenesis.pin org.springframework.security/spring-security-config 7.1.0 SHA-256/3234035bb5ccd45a9367ce526723d6b8da501c5c3f725b54a98354f922c2e978
 * @jenesis.pin org.springframework.security/spring-security-core 7.1.0 SHA-256/f8cecce9e65db9fe9ea42ca92b04d6e4e4320ff9d492aa60b753716ea397262c
 * @jenesis.pin org.springframework.security/spring-security-crypto 7.1.0 SHA-256/6f6957548a28451712e53b94a3e77057735b2fcec04c99ca6dd555b574453a98
 * @jenesis.pin org.springframework.security/spring-security-oauth2-client 7.1.0 SHA-256/6a90451711b3623f7f705ae5d555131b18be1e8f7299d7fc423fcf2e7b87128b
 * @jenesis.pin org.springframework.security/spring-security-oauth2-core 7.1.0 SHA-256/68c6bfbace2a429cdd277ce848f8a1a6ea8e33bb386fa2ba19636821457c376f
 * @jenesis.pin org.springframework.security/spring-security-oauth2-jose 7.1.0 SHA-256/a1620a4424e40035dc33d3a53d98a9e978a96d98334a43aaef0bbd60268d0f8c
 * @jenesis.pin org.springframework.security/spring-security-web 7.1.0 SHA-256/1deee612104ce85ec815076b80578cd8e82c07067e122068f09fbfef860b3cb1
 * @jenesis.pin org.springframework/spring-aop 7.0.8 SHA-256/1178f039e087884174e2affc46e484f4a8bd7f2a4e011d33dd9137709f740f80
 * @jenesis.pin org.springframework/spring-beans 7.0.8 SHA-256/6ec2e361a8872a71d8b1ff66f1bcb8cfa29fcc437931998919da7cecfb59b45b
 * @jenesis.pin org.springframework/spring-context 7.0.8 SHA-256/1eb7d552414ebac00e30ab3e809138d810785f6d2c4271db77cdf0181f308f19
 * @jenesis.pin org.springframework/spring-core 7.0.8 SHA-256/726ba2a5130833644bdf267a55ff26e1f52e8dcc9aa1ffa06904ca9c14619f25
 * @jenesis.pin org.springframework/spring-expression 7.0.8 SHA-256/3c97c38ab59c77ee886e08ccf8096f6bb58a1245f68dfed7a40e93f41c435f9a
 * @jenesis.pin org.springframework/spring-web 7.0.8 SHA-256/4d4ed7ecb0453d25d735ea27d025ea36b003c3d29cb7d006bedd6d5188a2f5c0
 * @jenesis.pin org.springframework/spring-webmvc 7.0.8 SHA-256/48f7e1e2d0d46e98ed3fa30d5a64cb1f7ed2aa339a82edcd87289ed8ff216f04
 * @jenesis.pin org.thymeleaf/thymeleaf 3.1.5.RELEASE SHA-256/4011795f8494dd69e764b7709443dd13d3068ba8ac37624f61d7084f4429cbe2
 * @jenesis.pin org.thymeleaf/thymeleaf-spring6 3.1.5.RELEASE SHA-256/fd5d306052d7aa6769a8ec77778d328e6f7c83af5ac074df38035bbb1e9cd72b
 * @jenesis.pin org.unbescape/unbescape 1.1.6.RELEASE SHA-256/597cf87d5b1a4f385b9d1cec974b7b483abb3ee85fc5b3f8b62af8e4bec95c2c
 * @jenesis.pin org.yaml/snakeyaml 2.6 SHA-256/c8f7a98e7394adda02f6317249710e4d1b4c7a25aa8c7eace0c2eea52eb8bf85
 * @jenesis.pin spring.beans 7.0.8
 * @jenesis.pin spring.boot 4.1.0
 * @jenesis.pin spring.boot.actuator 4.1.0
 * @jenesis.pin spring.boot.autoconfigure 4.1.0
 * @jenesis.pin spring.boot.starter.actuator 4.1.0
 * @jenesis.pin spring.boot.starter.jetty 4.1.0
 * @jenesis.pin spring.boot.starter.oauth2.client 4.1.0
 * @jenesis.pin spring.boot.starter.security 4.1.0
 * @jenesis.pin spring.boot.starter.thymeleaf 4.1.0
 * @jenesis.pin spring.boot.starter.web 4.1.0
 * @jenesis.pin spring.boot.webmvc 4.1.0
 * @jenesis.pin spring.context 7.0.8
 * @jenesis.pin spring.core 7.0.8
 * @jenesis.pin spring.security.config 7.1.0
 * @jenesis.pin spring.security.core 7.1.0
 * @jenesis.pin spring.security.oauth2.client 7.1.0
 * @jenesis.pin spring.security.oauth2.core 7.1.0
 * @jenesis.pin spring.security.web 7.1.0
 * @jenesis.pin spring.web 7.0.8
 * @jenesis.pin tools.jackson.core 3.2.0
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.0 SHA-256/5e353ce53c6901105dfcbf183e3220c17072e334e552b818a4bb1b99decea596
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.0 SHA-256/3ef94a3dddeafc247c50230fad0315981b2ce4ae6e91cfb4368a86f328904e4f
 * @jenesis.pin tools.jackson.databind 3.2.0
 */
open module build.jenesis.repository.ui {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.posture;
    requires jakarta.servlet;
    requires micrometer.observation;
    requires org.slf4j;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.boot;
    requires spring.boot.actuator;
    requires spring.boot.starter.actuator;
    requires spring.boot.autoconfigure;
    requires spring.security.config;
    requires spring.security.core;
    requires spring.security.web;
    requires spring.security.oauth2.client;
    requires tools.jackson.databind;
    requires spring.security.oauth2.core;
    requires spring.boot.webmvc;
    requires spring.boot.starter.jetty;
    requires org.eclipse.jetty.jndi;
    requires spring.boot.starter.thymeleaf;
    requires spring.boot.starter.security;
    requires spring.boot.starter.oauth2.client;
    exports build.jenesis.repository.ui;
    uses build.jenesis.repository.ui.Panel;
    provides build.jenesis.repository.ui.Panel
            with build.jenesis.repository.ui.BrowsePanel,
                    build.jenesis.repository.ui.SpiCatalogPanel,
                    build.jenesis.repository.ui.ObservabilityPanel,
                    build.jenesis.repository.ui.LogPanel,
                    build.jenesis.repository.ui.ConsistencyPanel,
                    build.jenesis.repository.ui.CredentialsPanel;
}
