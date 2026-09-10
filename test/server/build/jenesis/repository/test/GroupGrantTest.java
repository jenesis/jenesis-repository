package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A group holds rights exactly as a person or a key does, and a member holds them through it.
 *
 * <p>This is the primitive that was missing, and its absence had a shape: "everyone in <em>developers</em> may
 * read <em>acme</em>" was inexpressible, so an estate of five hundred engineers was five hundred grants changed
 * one person at a time. The only blanket instrument in the product was a console-admin-or-nothing wildcard.
 *
 * <p>The constraint that decides the design is the read path. Effective rights are a union over the caller's own
 * grants and every group's, which is a fan-out on the hottest path in the product and forbidden by the
 * bounded-read rule - so the union is computed by the writes that change it and read back as one document.
 * Everything asserted here is about that trade being honest: the union is right, it is in force on the next
 * request rather than the next repair, the two halves of what a person holds stay separable, and the drift the
 * design admits is really repaired.
 */
class GroupGrantTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    private Authorization authorization;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
    }

    @Test
    void a_member_holds_what_the_group_holds() throws IOException {
        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*",
                Authorization.REPOSITORY_READ);
        authorization.addMember("acme", "developers", "oidc/ada");

        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("through the group, having been granted nothing of their own").isTrue();
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_WRITE))
                .as("and only what the group holds").isFalse();
        // Grace is in a group of her own, so she HAS a derived document - which is what makes this assertion
        // about the membership test rather than about her never having been derived. Without the second group it
        // passes over a derivation that ignores membership entirely and hands every group's rights to everyone,
        // which is exactly what a planted mutation showed.
        authorization.addMember("acme", "auditors", "oidc/grace");
        assertThat(allowed("oidc/grace", Authorization.REPOSITORY_READ))
                .as("someone who is not a member of THAT group holds nothing of it").isFalse();
    }

    @Test
    void a_grant_given_to_a_group_reaches_the_people_already_in_it() throws IOException {
        // The order an operator actually works in: put people in the group, then decide what it may do. If this
        // needed a repair to take effect the grant would be real in the store and absent from every decision,
        // which is the failure this whole model exists to remove.
        authorization.addMember("acme", "developers", "oidc/ada");
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ)).isFalse();

        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*",
                Authorization.REPOSITORY_READ);

        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("in force on the next request, not at the next repair").isTrue();
    }

    @Test
    void leaving_the_group_takes_its_rights_away() throws IOException {
        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*",
                Authorization.REPOSITORY_READ);
        authorization.addMember("acme", "developers", "oidc/ada");
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ)).isTrue();

        authorization.removeMember("acme", "developers", "oidc/ada");

        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("the offboarding half - a membership that could not be taken back is not a membership")
                .isFalse();
    }

    @Test
    void deleting_the_group_takes_its_rights_off_everyone_who_held_them_through_it() throws IOException {
        authorization.setGrant("acme", Authorization.Subject.group("contractors"), "*",
                Authorization.REPOSITORY_WRITE);
        authorization.addMember("acme", "contractors", "oidc/ada");
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_WRITE)).isTrue();

        authorization.removeSubject("acme", Authorization.Subject.group("contractors"));

        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_WRITE))
                .as("a group that no longer exists confers nothing - and the member rows went with it")
                .isFalse();
        assertThat(authorization.members("acme", "contractors", null, 10).ids()).isEmpty();
    }

    @Test
    void two_groups_granting_the_same_scope_union_rather_than_one_winning() throws IOException {
        // Whichever is read first must not decide the answer. A union is the only rule that does not make a
        // person's rights depend on the order two unrelated groups happen to sit in the store.
        authorization.setGrant("acme", Authorization.Subject.group("readers"), "*",
                Authorization.REPOSITORY_READ);
        authorization.setGrant("acme", Authorization.Subject.group("publishers"), "*",
                Authorization.REPOSITORY_WRITE);
        authorization.addMember("acme", "readers", "oidc/ada");
        authorization.addMember("acme", "publishers", "oidc/ada");

        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ)).isTrue();
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_WRITE)).isTrue();
    }

    @Test
    void a_direct_grant_and_a_group_grant_are_separate_facts_about_one_person() throws IOException {
        // They are stored apart on purpose. Revoking what a person was given personally must not reach into what
        // they hold as a member of a team, and a repair that recomputed one document holding both would erase the
        // half it does not own.
        Authorization.Subject ada = Authorization.Subject.principal("oidc/ada");
        authorization.setGrant("acme", ada, "*", Authorization.MANAGE_WRITE);
        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*",
                Authorization.REPOSITORY_READ);
        authorization.addMember("acme", "developers", "oidc/ada");

        authorization.removeSubject("acme", ada);

        assertThat(allowed("oidc/ada", Authorization.MANAGE_WRITE))
                .as("what they held personally is gone").isFalse();
        authorization.rederive("acme", "oidc/ada");
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("what they hold as a member is still theirs").isTrue();
    }

    @Test
    void the_repair_recomputes_what_a_half_finished_write_left_behind() throws IOException {
        // The drift this design admits, made real: a node that dies between writing a group's grants and
        // re-deriving the last of its members leaves that member holding what the group USED to grant. Nothing
        // detects it on a read, because a stale derived document is a well-formed one - so it is recomputed on a
        // cadence rather than checked, and this is that cadence doing its job.
        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*",
                Authorization.MANAGE_WRITE);
        authorization.addMember("acme", "developers", "oidc/ada");
        assertThat(allowed("oidc/ada", Authorization.MANAGE_WRITE)).isTrue();

        // What a node dying mid-derivation leaves: the group's grants are current and one member's derived
        // document is not. That is the whole hazard, and it is invisible on a read - a stale derived document is
        // a well-formed one, and the group it disagrees with is never consulted. The write goes to the store and
        // is followed by forget(), which is what a peer's epoch bump does for a node that did not make the write.
        store.write(".system/auth/acme/principal/oidc%2Fada/derived",
                new ByteArrayInputStream(("*=" + Authorization.REPOSITORY_READ + "\n")
                        .getBytes(StandardCharsets.UTF_8)));
        authorization.forget();

        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("stale: holding what the group used to grant").isTrue();
        assertThat(allowed("oidc/ada", Authorization.MANAGE_WRITE))
                .as("and not holding what it grants now").isFalse();

        authorization.rederive("acme");

        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("the repair took away what the group stopped granting").isFalse();
        assertThat(allowed("oidc/ada", Authorization.MANAGE_WRITE))
                .as("and gave back what it does grant").isTrue();
    }

    @Test
    void the_boot_repair_reaches_every_tenant_and_never_costs_the_deployment_its_start() throws IOException {
        // The repair runs at start-up because start-up is when the drift has just happened: the only way a derived
        // document goes stale is a node dying between writing a group's grants and re-deriving the last of its
        // members, and a process that died is a process that comes back. It takes its tenants from the auth space
        // rather than from the tenancy SPI, so a second tenant is repaired without anything being told about it.
        for (String tenant : List.of("acme", "globex")) {
            authorization.setGrant(tenant, Authorization.Subject.group("developers"), "*",
                    Authorization.MANAGE_WRITE);
            authorization.addMember(tenant, "developers", "oidc/ada");
            store.write(".system/auth/" + tenant + "/principal/oidc%2Fada/derived",
                    new ByteArrayInputStream(("*=" + Authorization.REPOSITORY_READ + "\n")
                            .getBytes(StandardCharsets.UTF_8)));
        }
        authorization.forget();

        authorization.repairDerivedGrants();

        for (String tenant : List.of("acme", "globex")) {
            assertThat(authorization.authorize(tenant, Authorization.Subject.principal("oidc/ada"), null,
                    Authorization.MANAGE_WRITE))
                    .as("%s was repaired without this being told it exists", tenant)
                    .isEqualTo(Authorization.Decision.ALLOWED);
        }
    }

    @Test
    void a_store_that_refuses_the_repair_still_lets_the_deployment_boot() throws IOException {
        // A read-only deployment cannot write a derived document and must still start. The cost of the failure is
        // the repair, not the node - so this is best-effort by construction, and says so in the log rather than in
        // an exception nobody can act on while a process is coming up.
        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*",
                Authorization.REPOSITORY_READ);
        authorization.addMember("acme", "developers", "oidc/ada");

        Authorization readOnly = Authorization.enforcing(new ReadOnlyArtifactStore(store));
        assertThatCode(readOnly::repairDerivedGrants).doesNotThrowAnyException();
    }

    @Test
    void the_keyless_caller_is_still_refused_a_grant() throws IOException {
        // The rule that governed this the whole way through: a kind authorize does not resolve may not be granted
        // rights, because the row would read as access and confer none. A group is resolved now; anonymous is not.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> authorization.setGrant("acme", Authorization.Subject.ANONYMOUS, "*",
                        Authorization.REPOSITORY_READ))
                .withMessageContaining("anonymous");
    }

    @Test
    void a_member_is_not_a_group_and_cannot_be_named_with_a_traversal() throws IOException {
        assertThatIllegalArgumentException()
                .as("a member id is a subject id, screened before it can reach a key")
                .isThrownBy(() -> authorization.addMember("acme", "developers", "../../elsewhere"));
    }

    private boolean allowed(String principal, String right) throws IOException {
        return authorization.authorize("acme", Authorization.Subject.principal(principal), null, right)
                == Authorization.Decision.ALLOWED;
    }
}
