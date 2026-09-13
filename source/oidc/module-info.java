/**
 * The OIDC token exchange as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.spi.TokenExchangeProvider} answering to {@code oidc}, validating a workload's
 * id-token against the tenant's trust policy with Spring Security's per-issuer decoders (OIDC discovery, JWKS
 * signature verification, key rotation) and minting a short-lived credential on a match. The exchange endpoint
 * discovers it with {@code ServiceLoader}; a deployment without this module answers 501 there - and carries none
 * of the OAuth2/JOSE dependency stack, which lives here rather than in the server.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin com.nimbusds/nimbus-jose-jwt 10.9.1 SHA-256/33152ea83ec50d22706fdaf3b07acbcd716f9a68edcabdd7c4d02843cbdcdcf6
 * @jenesis.pin commons-logging/commons-logging 1.4.0 SHA-256/d175dbd751dd782a63bde28c7a039520e971f25e84b79c19b8435edc3603e0dc
 * @jenesis.pin io.micrometer/micrometer-commons 1.17.1 SHA-256/f2cee6ef046e72eec8128474c7f58c6217173b45ae373c30de0289f2a56ab0fd
 * @jenesis.pin io.micrometer/micrometer-observation 1.17.1 SHA-256/eb34f0cd84a879393ae5c21160fc06385ccdc74f36a4d1e31b199f59acfdaf71
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.springframework.security/spring-security-core 7.1.1 SHA-256/98a5011baa78df36fb184e6ce0e8e086ca9a64fcdd8f161c0a96475a3a0907bd
 * @jenesis.pin org.springframework.security/spring-security-crypto 7.1.1 SHA-256/6e8bb2337faccabd30626f917ed1d2bfa9dde982229b6b373b644d1a01635403
 * @jenesis.pin org.springframework.security/spring-security-oauth2-core 7.1.1 SHA-256/29140d71b37cf1246bf16e33619e88e7baaa1c66bf5574e3c3e9a9f225f0ef6e
 * @jenesis.pin org.springframework.security/spring-security-oauth2-jose 7.1.1 SHA-256/e07d47cac04de4f01be6a6c3f8be249472444b24fd4fad8ff030b45bfe6c5b9c
 * @jenesis.pin org.springframework/spring-aop 7.0.9 SHA-256/b8c5d6bfcb1f4993f2cc124f7ab7fac40edf5256b9e43c2f7250a733cb5510e9
 * @jenesis.pin org.springframework/spring-beans 7.0.9 SHA-256/ff218b827a25c9e8929b0cd56dfb56916cea9d5b669ed97dc7cb262508ff548b
 * @jenesis.pin org.springframework/spring-context 7.0.9 SHA-256/7552a2fcfa30cea53eb14d7a65a7a8e1b1dd82e832a7164fb7e6fb105f438858
 * @jenesis.pin org.springframework/spring-core 7.0.9 SHA-256/5195f4722699b39878d99a832549fe65df2890b159d063b88fff31b1ca65ae36
 * @jenesis.pin org.springframework/spring-expression 7.0.9 SHA-256/046434c40f43819729b9b1db0e6c659dfa68184c1c2c2efa5f7b3a5b27c4e2e2
 * @jenesis.pin org.springframework/spring-web 7.0.9 SHA-256/941ced476427bde2533872f293da535fd0258de3f3a650a7f1bfe01ed2927302
 * @jenesis.pin spring.security.oauth2.core 7.1.1 SHA-256/29140d71b37cf1246bf16e33619e88e7baaa1c66bf5574e3c3e9a9f225f0ef6e
 * @jenesis.pin spring.security.oauth2.jose 7.1.1 SHA-256/e07d47cac04de4f01be6a6c3f8be249472444b24fd4fad8ff030b45bfe6c5b9c
 */
module build.jenesis.repository.oidc {
    requires build.jenesis.repository.server.spi;
    requires spring.security.oauth2.core;
    requires spring.security.oauth2.jose;
    exports build.jenesis.repository.oidc to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e;
    provides build.jenesis.repository.server.spi.TokenExchangeProvider
            with build.jenesis.repository.oidc.OidcExchangeProvider;
}
