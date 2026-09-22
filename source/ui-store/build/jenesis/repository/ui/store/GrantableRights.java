package build.jenesis.repository.ui.store;

import module java.base;

/**
 * A grantable rights surface, contributed to the console as a bean. Each surface names a label (the heading
 * a role picker groups its tokens under) and the {@code <surface>:<verb>} tokens a credential can carry for it. The
 * set a credential may be granted is therefore discovered from the beans on the context, not hard-coded: a new
 * surface (or a new verb on one) is just another bean, and {@link CredentialService} validates a role against the
 * union of every bean's tokens (plus the all-privileges {@code *}). Three surfaces ship - the cache, the artifact
 * repository and the management API - so one credential can be granted a mix of rights over the unified store. The
 * shipped surfaces are wired as beans by the web layer's {@code DomainConfig}; this Spring-free module names none.
 */
public interface GrantableRights {

    String surface();

    List<String> tokens();

    final class CacheRights implements GrantableRights {

        @Override
        public String surface() {
            return "Cache";
        }

        @Override
        public List<String> tokens() {
            return List.of("cache:read", "cache:write", "cache:*");
        }
    }

    final class RepositoryRights implements GrantableRights {

        @Override
        public String surface() {
            return "Repository";
        }

        @Override
        public List<String> tokens() {
            return List.of("repository:read", "repository:write", "repository:*");
        }
    }

    final class ManagementRights implements GrantableRights {

        @Override
        public String surface() {
            return "Management";
        }

        @Override
        public List<String> tokens() {
            return List.of("manage:read", "manage:write", "manage:*");
        }
    }
}
