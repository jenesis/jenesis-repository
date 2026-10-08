package build.jenesis.repository.discovery.test;

import module java.base;
import build.jenesis.repository.discovery.RepositoryDiscovery;

/** A transport answering from tables - files and documents by address, {@code HEAD}s by address - and counting what
 *  was asked. */
final class Table implements RepositoryDiscovery.Transport {

    final Map<URI, String> files = new HashMap<>();
    final Map<URI, RepositoryDiscovery.Head> heads = new HashMap<>();
    final List<URI> asked = new ArrayList<>();

    Table file(String domain, String text) {
        files.put(URI.create("https://" + domain + "/.well-known/java-repository.properties"), text);
        return this;
    }

    Table document(String url, String text) {
        files.put(URI.create(url), text);
        return this;
    }

    Table redirect(String link, String target) {
        heads.put(URI.create(link), new RepositoryDiscovery.Head(302, Map.of("Location", target)));
        return this;
    }

    Table head(String link, int status, Map<String, String> headers) {
        heads.put(URI.create(link), new RepositoryDiscovery.Head(status, headers));
        return this;
    }

    @Override
    public Optional<String> read(URI url, int most) {
        asked.add(url);
        return Optional.ofNullable(files.get(url));
    }

    @Override
    public Optional<RepositoryDiscovery.Head> head(URI url) {
        asked.add(url);
        return Optional.ofNullable(heads.get(url)).or(() -> Optional.of(new RepositoryDiscovery.Head(404, Map.of())));
    }

    /** How often a domain's file was asked for. */
    long asked(String domain) {
        URI file = URI.create("https://" + domain + "/.well-known/java-repository.properties");
        return asked.stream().filter(file::equals).count();
    }
}
