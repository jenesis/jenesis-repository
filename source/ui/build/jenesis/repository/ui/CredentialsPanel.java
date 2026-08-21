package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The console's credential screen: issue, scope and revoke the keys this deployment authorizes with.
 *
 * <p>It exists because an enforcing deployment could not otherwise be operated from the console at all. Key auth is
 * on by default and a keyless caller is rejected, so before this the only way to obtain a credential was to turn
 * authentication off - which is not a bootstrap but a different deployment. The first key comes from
 * {@code jenreg.bootstrap-key}; this screen is where every key after it is issued.
 *
 * <p>It calls the key-gated {@code /api/credentials} JSON surface with the {@code Jenesis-Repository-Key} header,
 * exactly as the {@link ConsistencyPanel} and {@link LogPanel} do: the free console authenticates the human by
 * session, while the server's {@code /api} surfaces are key-gated like every other one. Those routes take the
 * {@code manage:} rights at deployment scope, so the key pasted here must carry {@code manage:write} - a key that
 * may publish an artifact deliberately cannot issue more keys.
 *
 * <p><b>A minted secret is shown once.</b> Only its hash is stored, so the panel keeps the value on screen until
 * the operator dismisses it and never re-reads it from anywhere; a lost key is re-issued, not recovered. All
 * dynamic text is API-derived and escaped before it reaches the DOM, so a label cannot inject markup.
 */
public final class CredentialsPanel implements Panel {

    @Override
    public String id() {
        return "credentials";
    }

    @Override
    public String title() {
        return "Credentials";
    }

    @Override
    public String render(ArtifactStore store) {
        // Reads nothing from the store: credentials are a live API read, and rendering them from here would
        // duplicate the authorization the API already applies.
        return """
                <p>Issue and revoke the keys this deployment authorizes with. The read and the mutations are
                key-gated at deployment scope, so paste a credential carrying <code>manage:write</code> - the
                bootstrap key (<code>jenreg.bootstrap-key</code>) carries it. A minted secret is shown
                <strong>once</strong>: only its hash is stored, so a lost key is re-issued rather than recovered.</p>
                <div class="grid">
                  <label>Key <input id="jcred-key" placeholder="jenk_tenant.secret" type="password"></label>
                  <label>Label <input id="jcred-label" placeholder="ci-publisher"></label>
                </div>
                <p>
                  <button class="secondary" onclick="jcredLoad()">Refresh</button>
                  <button onclick="jcredMint()">Issue a key</button>
                  <span id="jcred-status"></span>
                </p>
                <div id="jcred-minted"></div>
                <div id="jcred-list"></div>
                <script>
                (function(){
                  function esc(s){return (s==null?'':String(s)).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
                  function key(){return document.getElementById('jcred-key').value.trim();}
                  function headers(extra){
                    var h=key()?{'Jenesis-Repository-Key':key()}:{};
                    if(extra){h['Content-Type']='application/json';}
                    return h;
                  }
                  function status(text){document.getElementById('jcred-status').textContent=text;}
                  window.jcredLoad=function(){
                    fetch('/api/credentials',{headers:headers(false)}).then(function(r){
                      if(!r.ok)throw new Error('status '+r.status); return r.json();
                    }).then(render).catch(function(e){status('error: '+e.message);});
                  };
                  window.jcredMint=function(){
                    var body=JSON.stringify({label:document.getElementById('jcred-label').value.trim()||null});
                    fetch('/api/credentials',{method:'POST',headers:headers(true),body:body}).then(function(r){
                      if(!r.ok)throw new Error('status '+r.status); return r.json();
                    }).then(function(d){
                      document.getElementById('jcred-minted').innerHTML=
                        '<p><strong>Issued - copy it now, it is not shown again:</strong><br>'
                        + '<code>'+esc(d.key)+'</code><br>'
                        + 'id <code>'+esc(d.id)+'</code>'
                        + (d.expires?' - expires '+esc(d.expires):' - does not expire')+'</p>';
                      status('issued'); jcredLoad();
                    }).catch(function(e){status('error: '+e.message);});
                  };
                  window.jcredRevoke=function(id){
                    fetch('/api/credentials/'+encodeURIComponent(id),{method:'DELETE',headers:headers(false)})
                      .then(function(r){
                        if(!r.ok)throw new Error('status '+r.status);
                        status('revoked'); jcredLoad();
                      }).catch(function(e){status('error: '+e.message);});
                  };
                  function render(rows){
                    var list=document.getElementById('jcred-list');
                    if(!rows.length){list.innerHTML='<p>No credentials yet.</p>';return;}
                    var html='<table><thead><tr><th>Label</th><th>Id</th><th>Expires</th><th>Used</th>'
                           + '<th>Grants</th><th></th></tr></thead><tbody>';
                    rows.forEach(function(c){
                      var grants=Object.keys(c.grants||{}).map(function(s){
                        return esc(s)+'='+esc(c.grants[s]);}).join(', ');
                      html+='<tr><td>'+esc(c.label||'-')+'</td>'
                          + '<td><code>'+esc(c.id).slice(0,12)+'</code></td>'
                          + '<td>'+esc(c.expires||'never')+'</td>'
                          + '<td>'+esc(c.useCount)+'</td>'
                          + '<td>'+grants+'</td>'
                          + '<td><button class="secondary" onclick="jcredRevoke(\\''+esc(c.id)+'\\')">Revoke</button></td></tr>';
                    });
                    list.innerHTML=html+'</tbody></table>';
                  }
                })();
                </script>
                """;
    }
}
