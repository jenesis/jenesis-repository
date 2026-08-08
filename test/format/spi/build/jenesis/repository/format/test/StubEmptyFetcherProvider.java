package build.jenesis.repository.format.test;

import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;

import module java.base;

/** A stub fetcher provider whose {@code create} always declines (empty): its config never enables it. Unselected and
 *  alone it is the absence that degrades to {@code Fetcher.NONE}; <em>selected</em> by name it is the §9 case that
 *  must fail loudly rather than degrade. Declared first in the service list, so a resolver that still preferred
 *  discovery order over an explicit policy would be caught by it. */
public final class StubEmptyFetcherProvider implements FetcherProvider {

    @Override
    public String name() {
        return "empty";
    }

    @Override
    public Optional<ProxyFormat.Fetcher> create(UnaryOperator<String> config) {
        return Optional.empty();
    }
}
