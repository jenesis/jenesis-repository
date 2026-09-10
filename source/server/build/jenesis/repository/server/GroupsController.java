package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;
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
 * <p>Without it the primitive is unreachable. A group holds rights exactly as a person or a key does and a member
 * holds them through it, but nothing could create one - so an estate provisioned five hundred people one at a
 * time, which is the operational pain groups exist to remove and the joiner-mover-leaver story a buyer asks about
 * first.
 *
 * <p>It is deliberately the same shape as {@link CredentialsController}, because a group is deliberately the same
 * kind of thing: the tenant comes from the managing key rather than from routing of its own, a grant is a scope
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

    private final CredentialContext context;

    public GroupsController(Authorization authorization, CredentialContext context) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** The tenant's groups, one page per request, each with the rights it grants - the same paging contract every
     *  listing here has, because a directory pushed from an identity provider is a listing to page. */
    @GetMapping("/api/groups")
    @ResponseBody
    public List<GroupView> groups(HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String tenant = context.tenant(key);
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
            response.setHeader("X-Next-Cursor", page.next());
        }
        return views;
    }

    /** One group's members, one page per request. */
    @GetMapping("/api/groups/{name}/members")
    @ResponseBody
    public List<String> members(@PathVariable("name") String name,
                                HttpServletRequest http, HttpServletResponse response) {
        String key = PresentedKey.from(http);
        String after = http.getParameter("after");
        Authorization.SubjectPage page = authorization.members(context.tenant(key), group(name),
                after == null || after.isBlank() ? null : after, pageSize(http.getParameter("limit")));
        if (page.next() != null) {
            response.setHeader("X-Next-Cursor", page.next());
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
        authorization.setGrant(context.tenant(key), Authorization.Subject.group(group(name)),
                request.scope(), String.join(",", request.tokens()));
        context.audit(key, "group.grant.set", name + " " + request.scope());
        response.setStatus(200);
    }

    @DeleteMapping("/api/groups/{name}/grants/{scope}")
    public void removeGrant(@PathVariable("name") String name, @PathVariable("scope") String scope,
                            HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        authorization.removeGrant(context.tenant(key), Authorization.Subject.group(group(name)), scope);
        context.audit(key, "group.grant.remove", name + " " + scope);
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
        authorization.addMember(context.tenant(key), group(name), request.id());
        context.audit(key, "group.member.add", name + " " + request.id());
        response.setStatus(200);
    }

    /** Take a principal out of the group, by the {@code id} query parameter - a provider-qualified id carries a
     *  slash, so it is not a path segment. */
    @DeleteMapping("/api/groups/{name}/members")
    public void removeMember(@PathVariable("name") String name,
                             HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String id = http.getParameter("id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A member is removed by id: pass ?id=<provider-qualified id>");
        }
        authorization.removeMember(context.tenant(key), group(name), id);
        context.audit(key, "group.member.remove", name + " " + id);
        response.setStatus(200);
    }

    /** Delete the group: its grants, its metadata and its membership, and every member re-derived so nothing of
     *  it is left conferring rights. */
    @DeleteMapping("/api/groups/{name}")
    public void remove(@PathVariable("name") String name,
                       HttpServletRequest http, HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        authorization.removeSubject(context.tenant(key), Authorization.Subject.group(group(name)));
        context.audit(key, "group.remove", name);
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
