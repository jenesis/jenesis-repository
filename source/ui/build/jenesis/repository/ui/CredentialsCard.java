package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The console's credential card: issue, scope and revoke the keys this deployment authorizes with.
 *
 * <p>It exists because an enforcing deployment could not otherwise be operated from the console at all. Key auth is
 * on by default and a keyless caller is rejected, so before this the only way to obtain a credential was to turn
 * authentication off - which is not a bootstrap but a different deployment. The first key comes from
 * {@code jenreg.bootstrap-key}; this card is where every key after it is issued.
 *
 * <p>It calls the key-gated {@code /api/credentials} JSON surface with the {@code Jenesis-Repository-Key} header,
 * exactly as the {@link ConsistencyCard} and {@link LogCard} do: the free console authenticates the human by
 * session, while the server's {@code /api} surfaces are key-gated like every other one. Those routes take the
 * {@code manage:} rights at deployment scope, so the key pasted here must carry {@code manage:write} - a key that
 * may publish an artifact deliberately cannot issue more keys.
 *
 * <p><b>A minted secret is shown once.</b> Only its hash is stored, so the card keeps the value on screen until the
 * operator navigates away and never re-reads it from anywhere; a lost key is re-issued, not recovered.
 *
 * <p>Nothing is read here (contract clause 7): rendering keys from this side would duplicate the authorization the
 * API already applies, so the fragment ships the controls and {@code /js/cards.js} does the reading and the minting
 * against the key the operator pasted.
 */
public final class CredentialsCard implements ConsoleCard {

    @Override
    public String id() {
        return "credentials";
    }

    @Override
    public String title() {
        return "Credentials";
    }

    @Override
    public String fragment() {
        return "console/cards :: credentials";
    }

    @Override
    public Object model(ArtifactStore store) {
        return null;
    }
}
