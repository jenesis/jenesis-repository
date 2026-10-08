package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.discovery.DiscoveryFile;
import build.jenesis.repository.discovery.RepositoryDiscovery;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repository discovery at the API: {@code POST /api/admin/discovery/check?name=<module or groupId>[&path=<path>]}
 * starts asking each domain {@code name} reverses into, afresh, what its {@code /.well-known/java-repository.properties}
 * holds - or that it has none, or why it is refused - which file answers each key, and, given a repository request
 * path such as {@code /maven/build/jenesis/build.jenesis/0.15.4/build.jenesis-0.15.4.jar}, where a {@code discovered}
 * leg would take it. It answers the check's state at once - {@code running}, or {@code done} with what was found -
 * and {@code GET} of the same address reads it back, so nothing waits on the domains. The console's Discovery screen
 * and the CLI's {@code discovery check} reach the same {@link RepositoryDiscovery#ask}. Without the reader on this
 * node the answer is {@code 501}.
 */
@RestController
public class DiscoveryAdminController {

    private final ObjectProvider<RepositoryDiscovery> discovery;

    public DiscoveryAdminController(ObjectProvider<RepositoryDiscovery> discovery) {
        this.discovery = discovery;
    }

    /** Starts a check of {@code name} (and {@code path}) unless one is running, and answers its state. */
    @PostMapping("/api/admin/discovery/check")
    public CheckingView ask(@RequestParam("name") String name,
                            @RequestParam(value = "path", required = false) String path,
                            HttpServletResponse response) {
        RepositoryDiscovery reader = reader(name, response);
        return reader == null ? null : CheckingView.of(reader.ask(name.strip(), blankToNull(path)));
    }

    /** The last check of {@code name} (and {@code path}) this node ran or is running; {@code not-checked} for none. */
    @GetMapping("/api/admin/discovery/check")
    public CheckingView checking(@RequestParam("name") String name,
                                 @RequestParam(value = "path", required = false) String path,
                                 HttpServletResponse response) {
        RepositoryDiscovery reader = reader(name, response);
        if (reader == null) {
            return null;
        }
        return reader.checking(name.strip(), blankToNull(path)).map(CheckingView::of)
                .orElse(new CheckingView(name.strip(), blankToNull(path), "not-checked", null, null, null));
    }

    private RepositoryDiscovery reader(String name, HttpServletResponse response) {
        RepositoryDiscovery reader = discovery.getIfAvailable();
        if (reader == null) {
            response.setStatus(501);
            return null;
        }
        if (name.isBlank() || name.length() > 255) {
            response.setStatus(400);
            return null;
        }
        return reader;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A check's state - {@code running}, {@code done} or {@code not-checked} - when it started and finished, and what
     *  it found once done. */
    public record CheckingView(String name, String path, String state, Instant started, Instant finished,
                               CheckView check) {

        static CheckingView of(RepositoryDiscovery.Checking checking) {
            return new CheckingView(checking.name(), checking.path(), checking.state(), checking.started(),
                    checking.finished(), checking.check() == null ? null : CheckView.of(checking.check()));
        }
    }

    /** What a check found: each domain asked, which domain answers each key, and where {@code path} goes. */
    public record CheckView(String name, List<DomainView> domains, Map<String, String> answering, String path,
                            LocatedView located, String refusal) {

        static CheckView of(RepositoryDiscovery.Check check) {
            Map<String, String> answering = new TreeMap<>();
            check.answering().forEach((key, answer) -> answering.put(key.spelled(), answer.domain()));
            return new CheckView(check.name(), check.domains().stream().map(DomainView::of).toList(), answering,
                    check.path(), check.located() == null ? null : LocatedView.of(check.located()), check.refusal());
        }
    }

    /** One domain as asked: {@code found}, {@code absent}, {@code refused} or {@code not-reached}, and what its file
     *  holds key by key, or why it is refused. */
    public record DomainView(String domain, String address, String state, Boolean delegate,
                             Map<String, EntryView> entries, String refusal) {

        static DomainView of(RepositoryDiscovery.Asked asked) {
            Map<String, EntryView> entries = new TreeMap<>();
            if (asked.file() != null) {
                asked.file().entries().forEach((key, entry) -> entries.put(key.spelled(), EntryView.of(entry)));
            }
            return new DomainView(asked.domain(), asked.address().toString(), asked.state(),
                    asked.file() == null ? null : asked.file().delegate(), entries, asked.refusal());
        }
    }

    /** One key's entry as the file states it. */
    public record EntryView(String value, String since, List<String> suffixes, String latest) {

        static EntryView of(DiscoveryFile.Entry entry) {
            return new EntryView(entry.value(), entry.since(), entry.suffixes(), entry.latest());
        }
    }

    /** Where a path's file is: {@code relayed} under a root, {@code fetched} at a filled template ({@code checked}
     *  against the checksum beside it), or {@code answered} - metadata a latest link names. */
    public record LocatedView(String kind, String url, Boolean checked) {

        static LocatedView of(RepositoryDiscovery.Located located) {
            return switch (located) {
                case RepositoryDiscovery.Located.Relayed relayed -> new LocatedView("relayed",
                        relayed.root() + relayed.path(), null);
                case RepositoryDiscovery.Located.Fetched fetched -> new LocatedView("fetched",
                        fetched.url().toString(), fetched.checked());
                case RepositoryDiscovery.Located.Answered answered -> new LocatedView("answered", null, null);
            };
        }
    }
}
