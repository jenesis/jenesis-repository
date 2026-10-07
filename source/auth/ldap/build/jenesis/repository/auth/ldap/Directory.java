package build.jenesis.repository.auth.ldap;

import module java.base;

/** Where a name and password are checked, and the groups the account belongs to read. */
public interface Directory {

    /** The account {@code username} names, when {@code password} is its password; empty when it is not, or when
     *  no account has that name - the two are deliberately not told apart.
     *
     *  @throws Unreachable when the directory could not be asked at all - no connection, a TLS handshake refused, a
     *          search the server would not answer - which is not an answer about the password and must not read as
     *          one */
    Optional<Account> authenticate(String username, String password);

    /** The directory could not be asked: the failure is the connection's, not the person's. */
    final class Unreachable extends RuntimeException {

        public Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A signed-in directory account: the name it signed in with, and its groups. */
    record Account(String username, Set<String> groups) {

        public Account {
            groups = Set.copyOf(groups);
        }
    }
}
