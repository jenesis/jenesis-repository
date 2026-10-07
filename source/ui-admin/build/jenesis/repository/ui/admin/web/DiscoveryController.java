package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.discovery.RepositoryDiscovery;
import build.jenesis.repository.ui.ConsoleScreen;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What a domain's discovery file says, under Operations: a module name or Maven groupId, and a request path if the
 * operator wants to know where it would go, start a check of the domains the name reverses into - the same
 * {@link RepositoryDiscovery#ask} the API and the CLI reach - and the screen shows its state, polling while it runs:
 * each domain asked with what its {@code /.well-known/java-repository.properties} holds or why it is refused, which
 * file answers each key, and where the path's file is. A super-admin's screen: the reader is the deployment's.
 */
@Controller
@ConsoleScreen
public class DiscoveryController {

    private final ObjectProvider<RepositoryDiscovery> discovery;

    public DiscoveryController(ObjectProvider<RepositoryDiscovery> discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/ui/discovery")
    public String discovery(@RequestParam(value = "name", required = false) String name,
                            @RequestParam(value = "path", required = false) String path, Model model) {
        RepositoryDiscovery reader = discovery.getIfAvailable();
        String asked = blankToNull(name);
        model.addAttribute("installed", reader != null);
        model.addAttribute("name", asked == null ? "" : asked);
        model.addAttribute("path", blankToNull(path) == null ? "" : path.strip());
        RepositoryDiscovery.Checking checking = reader == null || asked == null ? null
                : reader.checking(asked, blankToNull(path)).orElse(null);
        model.addAttribute("checking", checking);
        model.addAttribute("located", checking == null || checking.check() == null
                || checking.check().located() == null ? null : Where.of(checking.check().located()));
        return "discovery";
    }

    /** Where a path's file is, as the screen says it: {@code fetched} from {@code url}, {@code relayed} under
     *  {@code url} at {@code path}, or {@code answered} here. */
    public record Where(String kind, String url, String path, boolean checked) {

        static Where of(RepositoryDiscovery.Located located) {
            return switch (located) {
                case RepositoryDiscovery.Located.Fetched fetched ->
                        new Where("fetched", fetched.url().toString(), null, fetched.checked());
                case RepositoryDiscovery.Located.Relayed relayed ->
                        new Where("relayed", relayed.root().toString(), relayed.path(), false);
                case RepositoryDiscovery.Located.Answered answered -> new Where("answered", null, null, false);
            };
        }
    }

    @PostMapping("/ui/discovery/check")
    public String check(@RequestParam("name") String name,
                        @RequestParam(value = "path", required = false) String path) {
        RepositoryDiscovery reader = discovery.getIfAvailable();
        String asked = blankToNull(name);
        if (reader != null && asked != null && asked.length() <= 255) {
            reader.ask(asked, blankToNull(path));
        }
        return "redirect:" + UriComponentsBuilder.fromPath("/ui/discovery").queryParam("name", asked)
                .queryParamIfPresent("path", Optional.ofNullable(blankToNull(path))).encode().toUriString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
