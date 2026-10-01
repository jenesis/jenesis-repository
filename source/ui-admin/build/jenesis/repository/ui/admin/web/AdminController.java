package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.store.ConsoleActor;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.store.ScimTokens;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The per-tenant admin screen: the current tenant's members and groups. Every route requires admin in the selected
 * tenant. Binding names are explicit because the build compiles without {@code -parameters}.
 */
@Controller
@ConsoleScreen
@RequestMapping("/ui/admin")
public class AdminController {

    private final UserDirectory directory;

    private final KnownPrincipals known;
    /** The deployment root, scoped to the session's tenant at each use through {@link #tenantDocuments()}. */
    private final Documents storage;
    private final AuditTrail audit;
    private final CurrentTenant current;
    private final ConsoleActor actor;

    /** Reads and writes groups and grants; the console does not authorize through this. */
    private final Authorization authorization;

    public AdminController(UserDirectory directory, KnownPrincipals known,
                           @Qualifier("rootStorage") Documents storage, AuditTrail audit,
                           CurrentTenant current, ConsoleActor actor, Authorization authorization) {
        this.directory = directory;
        this.known = known;
        this.storage = storage;
        this.audit = audit;
        this.current = current;
        this.actor = actor;
        this.authorization = authorization;
    }

    /** Records a privileged tenant-admin mutation under the current tenant, attributed to the acting member;
     *  best-effort. */
    private void audit(String action, String target) {
        audit.record(current.name(), actor.name(), action, target);
    }

    /** How many members one page renders; the membership is paged. */
    private static final int MEMBERS_PAGE = 200;

    /** How many seen principals the id field suggests. */
    private static final int KNOWN_PAGE = 100;

    /** How many groups one page renders and how many members of each it previews, so the screen costs a constant
     *  number of reads. */
    private static final int GROUPS_PAGE = 50;

    private static final int MEMBER_PREVIEW = 8;

    @GetMapping
    public String admin(@RequestParam(name = "cursor", required = false) String cursor, Model model)
            throws IOException {
        UserDirectory.Page page = directory.page(cursor, MEMBERS_PAGE);
        model.addAttribute("users", page.users());
        model.addAttribute("nextCursor", page.nextCursor().orElse(null));
        model.addAttribute("groups", groups());
        // Everyone this deployment has seen sign in, offered on the id fields.
        model.addAttribute("knownPrincipals", known.page(null, KNOWN_PAGE));
        model.addAttribute("scimConfigured", new ScimTokens(tenantDocuments()).configured());
        return "admin";
    }

    /** One group as this screen renders it: what it grants, and enough of who is in it to recognise. */
    public record GroupRow(String name, Map<String, String> grants, List<String> members, boolean more) {
    }

    /**
     * This tenant's groups with a preview of each membership, read through the {@link Authorization} the API's group
     * routes use.
     */
    private List<GroupRow> groups() throws IOException {
        List<GroupRow> rows = new ArrayList<>();
        for (String name : authorization.subjects(current.name(), Authorization.Kind.GROUP, null, GROUPS_PAGE).ids()) {
            Authorization.SubjectPage members =
                    authorization.groups().members(current.name(), name, null, MEMBER_PREVIEW);
            rows.add(new GroupRow(name,
                    authorization.grants(current.name(), Authorization.Subject.group(name)),
                    members.ids(), members.next() != null));
        }
        return rows;
    }

    /** How many members one group page renders; the rest are a cursor away. */
    private static final int GROUP_MEMBERS_PAGE = 200;

    /**
     * One group's page: what it grants and who is in it, each with its own action, and its deletion; members are paged
     * by {@code cursor}.
     */
    @GetMapping("/group")
    public String group(@RequestParam("name") String name,
                        @RequestParam(name = "cursor", required = false) String cursor, Model model)
            throws IOException {
        Authorization.SubjectPage members =
                authorization.groups().members(current.name(), name, cursor, GROUP_MEMBERS_PAGE);
        model.addAttribute("name", name);
        model.addAttribute("grants", authorization.grants(current.name(), Authorization.Subject.group(name)));
        model.addAttribute("members", members.ids());
        model.addAttribute("nextCursor", members.next());
        model.addAttribute("crumbs", List.of(Map.of("href", "/ui/admin", "label", "Members"),
                Map.of("href", "", "label", name)));
        model.addAttribute("knownPrincipals", known.page(null, KNOWN_PAGE));
        return "admin-group";
    }

    /** Back to the group's page, the name as a query parameter so no typed name becomes part of a path. */
    private static String toGroup(String name, RedirectAttributes redirect) {
        redirect.addAttribute("name", name);
        return "redirect:/ui/admin/group";
    }

    /** Grants a group rights at a scope; every member holds them from the next request, since the write re-derives
     *  them. The audit action names are spelled as the API's group routes record them. */
    @PostMapping("/groups/grant")
    public String grantGroup(@RequestParam("name") String name,
                             @RequestParam("scope") String scope,
                             @RequestParam("tokens") String tokens,
                             RedirectAttributes redirect) throws IOException {
        authorization.setGrant(current.name(), Authorization.Subject.group(name), scope, tokens.trim());
        audit("group.grant.set", name + " " + scope);
        redirect.addFlashAttribute("message",
                "Granted " + tokens.trim() + " on " + scope + " to everyone in " + name + ".");
        return toGroup(name, redirect);
    }

    @PostMapping("/groups/revoke-grant")
    public String revokeGroupGrant(@RequestParam("name") String name,
                                   @RequestParam("scope") String scope,
                                   RedirectAttributes redirect) throws IOException {
        authorization.removeGrant(current.name(), Authorization.Subject.group(name), scope);
        audit("group.grant.remove", name + " " + scope);
        redirect.addFlashAttribute("message", "Removed the grant on " + scope + " from " + name + ".");
        return toGroup(name, redirect);
    }

    /** Put a principal in a group. The group need not exist first: one with members and no grants confers nothing,
     *  so there is no order here in which an unmade decision reads as access. */
    @PostMapping("/groups/members")
    public String addGroupMember(@RequestParam("name") String name,
                                 @RequestParam("id") String id,
                                 RedirectAttributes redirect) throws IOException {
        authorization.groups().addMember(current.name(), name, id.trim());
        audit("group.member.add", name + " " + id.trim());
        redirect.addFlashAttribute("message", "Added " + id.trim() + " to " + name + ".");
        return toGroup(name, redirect);
    }

    @PostMapping("/groups/members/remove")
    public String removeGroupMember(@RequestParam("name") String name,
                                    @RequestParam("id") String id,
                                    RedirectAttributes redirect) throws IOException {
        authorization.groups().removeMember(current.name(), name, id);
        audit("group.member.remove", name + " " + id);
        redirect.addFlashAttribute("message", "Removed " + id + " from " + name + ".");
        return toGroup(name, redirect);
    }

    /** Delete a group: its grants, its metadata and its membership, with every member re-derived so nothing of it
     *  is left conferring rights. */
    @PostMapping("/groups/remove")
    public String removeGroup(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        authorization.removeSubject(current.name(), Authorization.Subject.group(name));
        audit("group.remove", name);
        redirect.addFlashAttribute("message", "Removed group " + name + " and everything it granted.");
        return "redirect:/ui/admin";
    }

    /**
     * This session's tenant view of the console's documents, scoped explicitly per call: {@link Documents} has no
     * interface, so a request-scoped proxy would need its package opened to Spring.
     */
    private Documents tenantDocuments() {
        String tenant = current.name();
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("No tenant selected.");
        }
        return storage.scope(tenant);
    }

    @PostMapping("/scim-token")
    public String setScimToken(RedirectAttributes redirect) throws IOException {
        String token = new ScimTokens(tenantDocuments()).mint();
        audit("scim.token.set", "scim");
        redirect.addFlashAttribute("message",
                "SCIM token (shown once): " + token + " - set it on your identity provider's SCIM connector.");
        return "redirect:/ui/admin";
    }

    @PostMapping("/scim-token/clear")
    public String clearScimToken(RedirectAttributes redirect) throws IOException {
        new ScimTokens(tenantDocuments()).set(null);
        audit("scim.token.clear", "scim");
        redirect.addFlashAttribute("message", "Cleared the SCIM token; SCIM provisioning for this tenant is off.");
        return "redirect:/ui/admin";
    }

    @PostMapping("/users")
    public String addUser(@RequestParam("id") String id,
                          @RequestParam(name = "role", defaultValue = "viewer") String role,
                          @RequestParam(name = "login", required = false) String login,
                          Authentication authentication,
                          RedirectAttributes redirect) throws IOException {
        UserDirectory.Role parsed = UserDirectory.Role.parse(role);
        // An admin may not demote themselves when no other member holds admin, which would leave the tenant unable to
        // manage its own membership.
        if (authentication != null && id.equals(authentication.getName())
                && !parsed.atLeast(UserDirectory.Role.ADMIN) && lastAdmin(id)) {
            throw new IllegalArgumentException("You are the last admin of this tenant. Promote another member to admin "
                    + "before lowering your own role.");
        }
        // Create or update, recorded as SCIM provisioning records them.
        boolean existing = directory.find(id).isPresent();
        directory.put(id, parsed, login);
        audit(existing ? "member.update" : "member.provision", id + " " + parsed.label());
        redirect.addFlashAttribute("message", "Saved user " + id + " (" + parsed.label() + ").");
        return "redirect:/ui/admin";
    }

    /** Whether {@code id} is currently an admin of this tenant and no OTHER member holds admin - so demoting them would
     *  empty the tenant's own admin roster. */
    private boolean lastAdmin(String id) {
        if (directory.find(id).map(user -> !user.role().atLeast(UserDirectory.Role.ADMIN)).orElse(true)) {
            return false;
        }
        // Pages and stops at the first other admin.
        String cursor = null;
        do {
            UserDirectory.Page page = directory.page(cursor, MEMBERS_PAGE);
            for (UserDirectory.User user : page.users()) {
                if (!user.id().equals(id) && user.role().atLeast(UserDirectory.Role.ADMIN)) {
                    return false;
                }
            }
            cursor = page.nextCursor().orElse(null);
        } while (cursor != null);
        return true;
    }

    @PostMapping("/users/remove")
    public String removeUser(@RequestParam("id") String id,
                             Authentication authentication,
                             RedirectAttributes redirect) throws IOException {
        if (authentication != null && id.equals(authentication.getName())) {
            throw new IllegalArgumentException("You cannot remove your own access.");
        }
        directory.remove(id);
        audit("member.deprovision", id);
        redirect.addFlashAttribute("message", "Removed user " + id + ".");
        return "redirect:/ui/admin";
    }
}
