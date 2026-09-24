package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The registry's own catalog, {@code GET /v2/_catalog}: every image of the tenant's container-image repositories,
 * named the way a client addresses it - {@code <tenant>/<repository>/<image>} - in lexicographic order and paged by
 * the Distribution API's {@code n} and {@code last}.
 *
 * <p>A client listing a registry asks the host's {@code /v2/_catalog} and then pulls each name it is given, so the
 * names have to be the whole path after {@code /v2/}. Each repository keeps its own catalog as a stored listing
 * its tag pushes maintain; this answer asks each repository for a window of it, through the format that holds it,
 * in repository order, and stops once the page is full. The repository names are paged too, and a request examines
 * at most {@link #MAX_EXAMINED} of them: one that runs out of that budget answers the images it found with a
 * {@code Link} resuming past the last repository it examined - {@code last=<tenant>/<repository>/~}, {@code ~} sorting
 * after every character an image name may hold - so a tenant of many repositories holding few images still pages
 * rather than hanging. A request never reads an image-name tree or the whole repository set.
 *
 * <p>The tenant is the one the request answers for ({@link RepositoryRouting#tenant}): the registry root names none.
 */
final class RegistryCatalog {

    /** The page a request that names no {@code n} is answered with; a client follows the {@code Link} past it. */
    static final int DEFAULT_PAGE = 1_000;

    /** The largest page served. */
    static final int MAX_PAGE = 10_000;

    /** The most repositories one request examines; the {@code Link} resumes past them. */
    static final int MAX_EXAMINED = 1_000;

    /** An image-name position after every image of a repository, so {@code last} can resume at the next one. */
    private static final String PAST = "~";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArtifactStore root;
    private final FormatDispatcher dispatcher;

    RegistryCatalog(ArtifactStore root, FormatDispatcher dispatcher) {
        this.root = root;
        this.dispatcher = dispatcher;
    }

    /** Whether {@code uri} is the registry's own catalog rather than a repository's. */
    static boolean addresses(String uri) {
        return uri.equals("/v2/_catalog");
    }

    void answer(String tenant, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!Scopes.valid(tenant)) {
            response.setStatus(400);
            return;
        }
        int limit;
        try {
            String n = request.getParameter("n");
            limit = n == null ? DEFAULT_PAGE : Integer.parseInt(n);
        } catch (NumberFormatException _) {
            response.setStatus(400);
            return;
        }
        if (limit <= 0) {
            response.setStatus(400);
            return;
        }
        limit = Math.min(limit, MAX_PAGE);
        String last = request.getParameter("last");
        String after = last == null || !last.startsWith(tenant + "/") ? "" : last.substring(tenant.length() + 1);
        int slash = after.indexOf('/');
        String afterRepository = slash < 0 ? after : after.substring(0, slash);
        String afterImage = slash < 0 ? "" : after.substring(slash + 1);

        List<String> names = new ArrayList<>();
        String resume = null;
        String cursor = afterRepository.isEmpty() ? "" : previous(afterRepository);
        int examined = 0;
        repositories:
        while (true) {
            List<String> window = new ArrayList<>();
            root.scope(tenant).page("", cursor, Math.min(REPOSITORY_PAGE, MAX_EXAMINED - examined), window::add);
            if (window.isEmpty()) {
                break;
            }
            for (String repository : window) {
                cursor = repository;
                examined++;
                if (!Scopes.valid(repository) || repository.compareTo(afterRepository) < 0) {
                    continue;                       // a product space, or a name before where the page resumes
                }
                Optional<RepositoryType> type = RepositoryDocument.cached(root, tenant, repository)
                        .flatMap(document -> RepositoryType.of(document.format(), dispatcher.formats()))
                        .filter(held -> held.mount().equals("/v2"));
                if (type.isEmpty()) {
                    continue;
                }
                if (names.size() == limit) {
                    resume = names.getLast();       // a further repository holds images past the full page
                    break repositories;
                }
                Page page = page(type.get(), root.scope(tenant).scope(repository), limit - names.size(),
                        repository.equals(afterRepository) ? afterImage : "");
                for (String image : page.names()) {
                    names.add(tenant + "/" + repository + "/" + image);
                }
                if (page.more()) {
                    resume = names.getLast();
                    break repositories;
                }
            }
            if (examined >= MAX_EXAMINED) {
                resume = names.size() == limit ? names.getLast() : tenant + "/" + cursor + "/" + PAST;
                break;
            }
        }
        if (resume != null) {
            response.setHeader("Link", "</v2/_catalog?n=" + limit + "&last="
                    + URLEncoder.encode(resume, StandardCharsets.UTF_8) + ">; rel=\"next\"");
        }
        response.setStatus(200);
        response.setContentType("application/json");
        response.getOutputStream().write(JSON.writeValueAsBytes(Map.of("repositories", names)));
    }

    /** How many repository names one page of the tenant's scope reads. */
    private static final int REPOSITORY_PAGE = 500;

    /** A position just before {@code repository}, so the paged read starts at it rather than after it. */
    private static String previous(String repository) {
        return repository.substring(0, repository.length() - 1);
    }

    /** One repository's catalog window, asked of the format that holds it. */
    private Page page(RepositoryType type, ArtifactStore store, int limit, String after) throws IOException {
        Listing exchange = new Listing(limit, after);
        if (!dispatcher.only(type.formats()).dispatch(exchange, store) || exchange.status != 200) {
            return new Page(List.of(), false);
        }
        List<String> names = new ArrayList<>();
        for (JsonNode name : JSON.readTree(exchange.body.toByteArray()).path("repositories")) {
            names.add(name.asString());
        }
        return new Page(names, exchange.next);
    }

    private record Page(List<String> names, boolean more) {
    }

    /** A {@code GET /v2/_catalog} with no request behind it, capturing the window and whether a next page exists. */
    private static final class Listing implements FormatExchange {

        private final int limit;
        private final String after;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status;
        private boolean next;

        private Listing(int limit, String after) {
            this.limit = limit;
            this.after = after;
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return "/v2/_catalog";
        }

        @Override
        public String queryParameter(String name) {
            return switch (name) {
                case "n" -> Integer.toString(limit);
                case "last" -> after.isEmpty() ? null : after;
                default -> null;
            };
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
            if (name.equalsIgnoreCase("Link") && value.contains("rel=\"next\"")) {
                next = true;
            }
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return body;
        }
    }
}
