package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.audit.AuditActions;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.CredentialLifetimes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The group surface: how an operator says "everyone in <em>developers</em> may read <em>acme</em>".
 *
 * <p>It makes the primitive reachable: a group holds rights exactly as a person or a key does and a member holds them
 * through it, so an estate grants a team once rather than five hundred people one at a time - the joiner-mover-leaver
 * story groups exist for.
 *
 * <p>It is deliberately the same shape as {@link CredentialsController}, because a group is deliberately the same
 * kind of thing: the tenant is the one the routing answers for the request, a grant is a scope
 * and a set of rights in the one vocabulary, and every route sits under {@code /api/} and is therefore gated by
 * {@code manage:read} or {@code manage:write} by the security chain before it is reached. Nothing here re-decides
 * authorization.
 *
 * <p><b>A member is added by id, and the id is the provider-qualified one</b> - {@code oidc/<sub>},
 * {@code github/1024025} - which carries a slash and is therefore a query parameter rather than a path segment.
 * A path variable would have to be encoded by every caller and decoded here, and the one thing worse than an
 * awkward URL is a subject id that two clients encode differently.
 */
@RestController
public final class GroupsController {

    private final Authorization authorization;

    private final RepositoryRouting routing;

    private final CredentialContext context;

    public GroupsController(Authorization authorization, RepositoryRouting routing, CredentialContext context) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** The tenant's groups, one page per request, each with the rights it grants - the same paging contract every
     *  listing here has, because a directory pushed from an identity provider is a listing to page. */
    @GetMapping("/api/groups")
    @ResponseBody
    public List<GroupView> groups(HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = routing.tenant(http);
        String after = http.getParameter("after");
        Authorization.SubjectPage page = authorization.subjects(tenant, Authorization.Kind.GROUP,
                after == null || after.isBlank() ? null : after, pageSize(http.getParameter("limit")));
        List<GroupView> views = new ArrayList<>();
        for (String name : page.ids()) {
            views.add(new GroupView(name,
                    authorization.label(tenant, Authorization.Subject.group(name)).orElse(null),
                    authorization.grants(tenant, Authorization.Subject.group(name))));
        }
        if (page.next() != null) {
            response.setHeader("Jenesis-Next-Cursor", page.next());
        }
        return views;
    }

    /** One group's members, one page per request. */
    @GetMapping("/api/groups/{name}/members")
    @ResponseBody
    public List<String> members(@PathVariable("name") String name,
                                HttpServletRequest http, HttpServletResponse response) {
        String tenant = routing.tenant(http);
        String after = http.getParameter("after");
        Authorization.SubjectPage page = authorization.groups().members(tenant, group(name),
                after == null || after.isBlank() ? null : after, pageSize(http.getParameter("limit")));
        if (page.next() != null) {
            response.setHeader("Jenesis-Next-Cursor", page.next());
        }
        return page.ids();
    }

    /** Grant the group rights at a scope: a repository name, or {@code *} for every repository of the tenant.
     *  Every member holds them from the next request - the write re-derives them before it returns. */
    @PostMapping("/api/groups/{name}/grants")
    public void setGrant(@PathVariable("name") String name,
                         HttpServletRequest http,
                         @RequestBody CredentialsController.GrantRequest request,
                         HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String tenant = routing.tenant(http);
        authorization.setGrant(tenant, Authorization.Subject.group(group(name)),
                request.scope(), String.join(",", request.tokens()),
                CredentialLifetimes.expiry(request.expires()));
        context.audit(tenant, key, AuditActions.GROUP_GRANT_SET, name + " " + request.scope());
        response.setStatus(200);
    }

    @DeleteMapping("/api/groups/{name}/grants/{scope}")
    public void removeGrant(@PathVariable("name") String name, @PathVariable("scope") String scope,
                            HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String tenant = routing.tenant(http);
        authorization.removeGrant(tenant, Authorization.Subject.group(group(name)), scope);
        context.audit(tenant, key, AuditActions.GROUP_GRANT_REMOVE, name + " " + scope);
        response.setStatus(200);
    }

    /** Put a principal in the group. The group need not exist first: one with members and no grants confers
     *  nothing, so there is no state here in which an unmade decision reads as access. */
    @PostMapping("/api/groups/{name}/members")
    public void addMember(@PathVariable("name") String name,
                          HttpServletRequest http,
                          @RequestBody MemberRequest request,
                          HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String tenant = routing.tenant(http);
        authorization.groups().addMember(tenant, group(name), request.id());
        context.audit(tenant, key, AuditActions.GROUP_MEMBER_ADD, name + " " + request.id());
        response.setStatus(200);
    }

    /** Take a principal out of the group, by the {@code id} query parameter - a provider-qualified id carries a
     *  slash, so it is not a path segment. */
    @DeleteMapping("/api/groups/{name}/members")
    public void removeMember(@PathVariable("name") String name,
                             HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String tenant = routing.tenant(http);
        String id = http.getParameter("id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A member is removed by id: pass ?id=<provider-qualified id>");
        }
        authorization.groups().removeMember(tenant, group(name), id);
        context.audit(tenant, key, AuditActions.GROUP_MEMBER_REMOVE, name + " " + id);
        response.setStatus(200);
    }

    /** Delete the group: its grants, its metadata and its membership, and every member re-derived so nothing of
     *  it is left conferring rights. */
    @DeleteMapping("/api/groups/{name}")
    public void remove(@PathVariable("name") String name,
                       HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String tenant = routing.tenant(http);
        authorization.removeSubject(tenant, Authorization.Subject.group(group(name)));
        context.audit(tenant, key, AuditActions.GROUP_REMOVE, name);
        response.setStatus(200);
    }

    /** A group name is a subject id, screened by {@link Authorization.Subject} - no slash, no traversal segment -
     *  before it can reach a key. Constructing the subject IS the screen, so it is not restated here. */
    private static String group(String name) {
        return Authorization.Subject.group(name).id();
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

    /** One group as this surface reports it: its name, its label and the rights it grants per scope. */
    public record GroupView(String name, String label, Map<String, String> grants) {
    }

    /** The principal to put in or take out of a group. */
    public record MemberRequest(String id) {
    }
}
