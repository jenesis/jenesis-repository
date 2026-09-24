package build.jenesis.repository.auth.ldap;

import module java.base;

/** Where a name and password are checked, and the groups the account belongs to read. */
public interface Directory {

    /** The account {@code username} names, when {@code password} is its password; empty when it is not, or when
     *  no account has that name - the two are deliberately not told apart. */
    Optional<Account> authenticate(String username, String password);

    /** A signed-in directory account: the name it signed in with, and its groups. */
    record Account(String username, Set<String> groups) {

        public Account {
            groups = Set.copyOf(groups);
        }
    }
}
