package build.jenesis.repository.ui.store;

import module java.base;

/**
 * A grantable rights surface, contributed as a bean: a label for the role picker and the {@code <surface>:<verb>}
 * tokens a credential can carry for it. {@link CredentialService} validates a role against the union of every bean's
 * tokens plus {@code *}.
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
