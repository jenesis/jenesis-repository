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
 * Docker daemon and <em>fail</em> under the strict lane's {@code -Djenesis.test.required}, where the environment is
 * declared complete and a skip would be a broken lane reported as green.
 *
 * <p>This module deliberately requires all four backend implementations and reaches them only through
 * {@code ArtifactStoreProvider.resolve} - the way a deployment does - so it is simultaneously the runtime-discovery
 * graph the census needs: a backend module omitted here disappears from {@code ServiceLoader}, and the census fails
 * because the source {@code provides} scan still declares it.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.testkit
 * @jenesis.alias org.containers org.containers/containers
 * @jenesis.pin com.azure.storage.blob 12.35.0
 * @jenesis.pin com.azure/azure-core 1.58.1 SHA-256/7b339126e92af79b07fcf96fe16fa5ba2a2854bb8ce7e03ac4776b9474fe7df5
 * @jenesis.pin com.azure/azure-core-http-netty 1.16.5 SHA-256/61091ba5634e711e396721edfcca5c6782be1c1e86f2ecf856eb57aa20260c0c
 * @jenesis.pin com.azure/azure-json 1.5.1 SHA-256/bad21d5eb306d82b85951b58a1d9e501a9b09970e452bee6d4d445fd5a91c519
 * @jenesis.pin com.azure/azure-storage-blob 12.35.0 SHA-256/c1f7dac599b0c057e406db76e7684bf2a5aae8f960f58bcecc18233298092eb8
 * @jenesis.pin com.azure/azure-storage-common 12.34.0 SHA-256/9ddbf4a4e7680e6d062995928b3933e496353d1e62449f2ce5662f9db0820325
 * @jenesis.pin com.azure/azure-storage-internal-avro 12.20.0 SHA-256/b80addb78cdc7ea6af99b8e76ac91c9a553e1a088850391bf2d7b3f7e2bc8dab
 * @jenesis.pin com.azure/azure-xml 1.2.1 SHA-256/08b458481b656554605215ab0b165f68e6025359e52bea4736d032328d40ba3b
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.18.7 SHA-256/4c992ecef3569e73f19cd6b3be027108fb73139bb67d55d1218ac72e92219ebc
 * @jenesis.pin com.fasterxml.jackson.core/jackson-core 2.18.7 SHA-256/e1c578d374f519aa9aa74cbdc251c6705ffa08ac78faea5fa36bad213de30dc8
 * @jenesis.pin com.fasterxml.jackson.core/jackson-databind 2.18.7 SHA-256/aa3c034534fce966b6dbd706b1f466b8a15c266127e5a15f96522091093dbd9b
 * @jenesis.pin com.fasterxml.jackson.datatype/jackson-datatype-jsr310 2.18.7 SHA-256/29b8f1f8e055653297b07c3844a056541bdbf5c8199517598d9fa6edbefcc82e
 * @jenesis.pin com.github.docker-java/docker-java-api 3.7.1 SHA-256/dad153d484b1f4ef009e2fdbad27e07aeb3191122da52b8985507ac504300081
 * @jenesis.pin com.github.docker-java/docker-java-transport 3.7.1 SHA-256/d15eec8034bf0f92c2a48ca9172691804048115c96dc853272f9486fa2695c3c
 * @jenesis.pin com.github.docker-java/docker-java-transport-zerodep 3.7.1 SHA-256/b89bdb1754160323597f9ea32a7fe7a4a3aa8f5b3b43b88e8d71fff3b267ab21
 * @jenesis.pin com.google.code.findbugs/jsr305 3.0.2 SHA-256/766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7
 * @jenesis.pin com.google.code.gson/gson 2.8.9 SHA-256/d3999291855de495c94c743761b8ab5176cfeabe281a5ab0d8e8d45326fd703e
 * @jenesis.pin commons-codec/commons-codec 1.19.0 SHA-256/5c3881e4f556855e9c532927ee0c9dfde94cc66760d5805c031a59887070af5f
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
 * @jenesis.pin main/maven/io.netty/netty-resolver-dns-native-macos/jar/osx-x86_64 4.1.135.Final SHA-256/0c86fa27317c4172fff03a0c20286e2c62ef9d60ad78f389a83ede48a5bb54cd
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-aarch_64 2.0.78.Final SHA-256/85f6e25942df7308c9a6e66015a5ba87589d6f239231fb5b175138afe451b592
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-x86_64 2.0.78.Final SHA-256/bb830d661dc70fac2df8d147ffb64d61566211455272bb75d09d1662ec843aae
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-aarch_64 2.0.78.Final SHA-256/29019bf2e3045acaf4fd17b9e4033536141c8971939cd78cc82a12fe74fe24c1
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-x86_64 2.0.78.Final SHA-256/6c6c574bf9ee85b53f176d7de1101d348cf4374014df2ea26b691e7f335d69ba
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/windows-x86_64 2.0.78.Final SHA-256/c720390d4733fa4997f4648327fcb63a688a72afd3ddd05d368759c6c65aef6b
 * @jenesis.pin main/maven/io.netty/netty-transport-native-epoll/jar/linux-x86_64 4.1.135.Final SHA-256/18a40063da3364cffff81c6c2097fb6ebcb45c62264dabcce45aade4fdac3125
 * @jenesis.pin main/maven/io.netty/netty-transport-native-kqueue/jar/osx-x86_64 4.1.135.Final SHA-256/412e10daef5aa4647984397fa6728acf88dffd0d4c53ad91f486ea6492f8f08f
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.3 SHA-256/d78396e3c5bce3f2865c9186647481e5589d34cacc632484715b686108d17c66
 * @jenesis.pin net.bytebuddy/byte-buddy-agent 1.12.10 SHA-256/5e8606d14a844c1ec70d2eb8f50c4009fb16138905dee8ca50a328116c041257
 * @jenesis.pin net.java.dev.jna/jna 5.18.1 SHA-256/260c4b1e22b1db9e110ee441c4f13ce115f841fa48c41d78750986214b395557
 * @jenesis.pin org.apache.commons/commons-compress 1.28.0 SHA-256/e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca
 * @jenesis.pin org.apache.commons/commons-lang3 3.18.0 SHA-256/4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.1 SHA-256/1e3d8444c3c27772e4b9d42a790f06b3345a8ece4fd16d00981f2f2460e1e772
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.2 SHA-256/7c34a25506e7207b6748cef9e91163ed03081bee805cef930d82e1d8761d62f1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4 SHA-256/2e0f4ace15db2d1609c2b06eca6012e7582afe4a99ad8d15073f62dd8edb3460
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
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
 * @jenesis.pin org.mockito/mockito-junit-jupiter 4.6.0 SHA-256/a773c0a51530291b72d03aca35191928bb18967700a8ceb53694e9bc8a1cff15
 * @jenesis.pin org.objenesis/objenesis 3.2 SHA-256/03d960bd5aef03c653eb000413ada15eb77cdd2b8e4448886edf5692805e35f3
 * @jenesis.pin org.opentest4j 1.3.0
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.4
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.reflections/reflections 0.10.2 SHA-256/938a2d08fe54050d7610b944d8ddc3a09355710d9e6be0aac838dbc04e9a2825
 * @jenesis.pin org.rnorth.duct-tape/duct-tape 1.0.8 SHA-256/31cef12ddec979d1f86d7cf708c41a17da523d05c685fd6642e9d0b2addb7240
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 * @jenesis.pin org.containers/containers 2.0.5 SHA-256/0466f481343d5f350a91274cd7bf984308cbaf90d706247fd1cf4b1a8010c2e1
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
    requires org.containers;

    // Discovery is the thing under test here, so this module loads the SPI itself rather than through a resolver
    // static: the census has to enumerate what ServiceLoader really sees in this graph and compare it against the
    // source `provides` scan. The same `uses`-in-a-test-module shape the importer census already uses in test/server.
    uses build.jenesis.repository.store.ArtifactStoreProvider;
}
