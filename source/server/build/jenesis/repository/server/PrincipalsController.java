package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The person surface: what a signed-in human holds, over the same grants a key and a group hold theirs in.
 *
 * <p>It is the one holder that had no API. A key has had {@code /api/credentials} from the start and a group now
 * has {@code /api/groups}, but a person's rights were reachable only through the console's own membership screen
 * and the SCIM connector an identity provider drives - so the surface a script or an agent would use did not
 * exist, and "manage this on all three surfaces" was true of two of the four holders. That is the standing rule's
 * failure mode seen from the other side: usually the console lags the API, and here it was the API that lagged the
 * console.
 *
 * <p><b>The id is never a path segment.</b> A person is named as the sign-in mechanism names them -
 * {@code oidc/<sub>}, {@code github/1024025} - so their id carries a slash. A path variable would have to be
 * encoded by every caller and decoded here, and two clients encoding a subject id differently is a worse problem
 * than an awkward URL. It rides in the body of a write and as a query parameter of a delete.
 *
 * <p>Every route sits under {@code /api/} and is therefore gated by {@code manage:read} or {@code manage:write} by
 * the security chain before it is reached; nothing here re-decides authorization. What a person holds through a
 * <em>group</em> is not editable here and deliberately so - it belongs to the group, and a surface that let it be
 * edited on the member would be editing a copy.
 */
@RestController
public final class PrincipalsController {

    private final Authorization authorization;

    private final CredentialContext context;

    public PrincipalsController(Authorization authorization, CredentialContext context) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** The tenant's principals, one page per request, each with the rights granted to them directly. */
    @GetMapping("/api/principals")
    @ResponseBody
    public List<PrincipalView> principals(HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String tenant = context.tenant(key);
        String after = http.getParameter("after");
        Authorization.SubjectPage page = authorization.subjects(tenant, Authorization.Kind.PRINCIPAL,
                after == null || after.isBlank() ? null : after, pageSize(http.getParameter("limit")));
        List<PrincipalView> views = new ArrayList<>();
        for (String id : page.ids()) {
            Authorization.Subject subject = Authorization.Subject.principal(id);
            views.add(new PrincipalView(id, authorization.label(tenant, subject).orElse(null),
                    authorization.grants(tenant, subject)));
        }
        if (page.next() != null) {
            response.setHeader("X-Next-Cursor", page.next());
        }
        return views;
    }

    /** Grant a person rights at a scope: a repository name, or {@code *} for every repository of the tenant. */
    @PostMapping("/api/principals/grants")
    public void setGrant(HttpServletRequest http,
                         @RequestBody PrincipalGrantRequest request,
                         HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        authorization.setGrant(context.tenant(key), Authorization.Subject.principal(request.id()),
                request.scope(), String.join(",", request.tokens()),
                Authorization.expiry(request.expires()));
        context.audit(key, "principal.grant.set", request.id() + " " + request.scope());
        response.setStatus(200);
    }

    @DeleteMapping("/api/principals/grants")
    public void removeGrant(HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String id = required(http, "id");
        String scope = required(http, "scope");
        authorization.removeGrant(context.tenant(key), Authorization.Subject.principal(id), scope);
        context.audit(key, "principal.grant.remove", id + " " + scope);
        response.setStatus(200);
    }

    /** Remove a person entirely: every right granted to them directly, and their metadata, so nothing of them is
     *  left to read back as a holder with no rights. It does not touch their group memberships - those are the
     *  groups', and removing them here would be editing a copy. */
    @DeleteMapping("/api/principals")
    public void remove(HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String id = required(http, "id");
        authorization.removeSubject(context.tenant(key), Authorization.Subject.principal(id));
        context.audit(key, "principal.remove", id);
        response.setStatus(200);
    }

    private static String required(HttpServletRequest http, String name) {
        String value = http.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + name);
        }
        return value;
    }

    private static int pageSize(String value) {
        if (value == null || value.isBlank()) {
            return CredentialsController.MAX_PAGE;
        }
        try {
            return Math.clamp(Integer.parseInt(value.trim()), 1, CredentialsController.MAX_PAGE);
        } catch (NumberFormatException _) {
            return CredentialsController.MAX_PAGE;
        }
    }

    /** One person as this surface reports them: their provider-qualified id, their display label and the rights
     *  granted to them directly. What they hold through a group is the group's, and is listed there. */
    public record PrincipalView(String id, String label, Map<String, String> grants) {
    }

    /** A grant to a person: who, at what scope, of what, and until when. The id is in the body because it carries
     *  a slash; {@code expires} is optional and a blank one is a grant that does not lapse. */
    public record PrincipalGrantRequest(String id, String scope, List<String> tokens, String expires) {
    }
}
