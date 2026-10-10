package build.jenesis.repository.format.contract.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis module layout's leg of the shared contract - the smallest of the four, and honestly so: it serves
 * {@code /module/} and {@code /artifact/} pointers, carries the {@code ArtifactLayout} coordinate seam and pulls a
 * module's files through from a module service, but it publishes no listing and generates no document. The properties
 * it is excluded from name the absent protocol surface rather than an absent implementation. The pulled file is a
 * classified jar, since a module's own jar must declare the module and a generated body declares none.
 */
final class JenesisFormatFixture implements FormatFixture {

    private static final String MODULE = "contract.module";

    private static final URI ROOT = URI.create("https://modules.upstream.example/");

    private static final String PROXIED = "/module/" + MODULE + "/1.0.0/" + MODULE + "-sources.jar";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "jenesis";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("the module layout is a view over artifacts published through another format, which carries the "
                + "signature story for them - a module mirror points at the same blob as its coordinate, and "
                + "checking it twice would report one artifact under two names.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.jenesis.JenesisFormat";
    }

    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = FormatFixture.super.serving();
        }
        return serving;
    }

    @Override
    public List<String> namespaces() {
        return List.of("publish/module", "publish/artifact", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        String path = "/module/" + MODULE + "/1.0.0/" + MODULE + ".jar";
        ContractExchange put = ContractExchange.of("PUT", path, body);
        serving().handle(put, store);
        if (put.status() != 201) {
            throw new AssertionError("seeding " + path + " answered " + put.status() + " rather than 201");
        }
        try {
            return new Published(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String probe(String vector) {
        return "/module/" + vector;
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        String artifact = ROOT + PROXIED.substring(1);
        return Optional.of(new Upstream(PROXIED, ROOT, new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(artifact)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        }));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE,
                "a module service advertises no digest, so the leg has no integrity refusal to spell, and it has no "
                        + "elective path either: a module file's path is its identity, and a client asking for one "
                        + "that is not there is asking for a file it expects, so the miss is a loud answer",
                
                FormatContract.Property.WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION,
                "the module layout publishes no enumeration surface at all - no listing, no version index, no "
                        + "catalogue - so there is no name for a hold to leave. Its serve-side retraction (a held "
                        + "path answers 404) is the publish/-namespace screen the Maven and raw legs prove over the "
                        + "same Publication.located chain",
                FormatContract.Property.EMPTY_ENUMERATION_IS_A_MISS,
                "the module layout publishes no enumeration surface at all, so there is no listing to answer for an "
                        + "empty repository, and no proxy to send a miss on to",
                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY,
                "a module service is a plain file tree: it publishes no checksum sibling, no digest header and no "
                        + "content-addressed reference, so an upstream body carries nothing to hold it to, and the kit "
                        + "refuses to fabricate a check. A discovered location that does publish checksums beside its "
                        + "files has them checked by the discovered leg, and a module's own jar is held to the module "
                        + "it is asked for",
                FormatContract.Property.LISTING_MERGE_STREAMS,
                "the module layout keeps no generated document a publish merges into - every response is stored "
                        + "bytes streamed back",
                FormatContract.Property.GENERATED_INDEX_IS_REVALIDATABLE,
                "the module layout renders nothing on read - every /module/ and /artifact/ response is stored bytes "
                        + "streamed back - so it has no generated document to revalidate",
                FormatContract.Property.GENERATED_INDEX_CARRIES_THE_REQUEST_SCHEME,
                "the same reason: with no generated document there is no emitted URL to carry a scheme. A format "
                        + "that streams stored bytes composes no absolute address of its own, so the defect this "
                        + "property ratchets - a document telling a client to fetch over cleartext from a "
                        + "TLS-serving deployment - has nowhere to occur here");
    }
}
