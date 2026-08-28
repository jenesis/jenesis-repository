/*
 * The behaviour of the overview cards that read live state: logs, consistency and credentials.
 *
 * Three things about this file are deliberate, and each replaces something the cards did before as Java strings.
 *
 * The markup is not here. Each card's body is a Thymeleaf fragment in `console/cards.html`; this file only finds
 * the controls by id and fills the output containers. Previously the whole body - markup, controls and this
 * script - was one Java text block per card, so a change to the layout was a change to a string literal that no
 * template engine, editor or linter ever looked at.
 *
 * There is no escape function. Every value that comes back from the API reaches the page through `textContent` or
 * through a created element, never through `innerHTML`, so the DOM escapes it by construction. Each card used to
 * carry a hand-written `esc()` and had to remember to call it at each interpolation; the one that forgot would
 * have been stored cross-site scripting on an admin page, and nothing would have said so.
 *
 * The handlers are bound here rather than written as `onclick` attributes. That keeps the fragments free of
 * behaviour, and it is what lets a deployment serve the console under a content-security policy that refuses
 * inline handlers.
 */
(function () {
    'use strict';

    /* The key box is per card: a reader's credential and an administrator's are not the same key. */
    function headers(keyField, json) {
        var key = keyField ? keyField.value.trim() : '';
        var head = key ? {'Jenesis-Repository-Key': key} : {};
        if (json) {
            head['Content-Type'] = 'application/json';
        }
        return head;
    }

    function json(response) {
        if (!response.ok) {
            throw new Error('status ' + response.status);
        }
        return response.json();
    }

    /* A row of cells, each filled as text. The one place the tables are built, so no card can forget. */
    function row(cells) {
        var tr = document.createElement('tr');
        cells.forEach(function (cell) {
            var td = document.createElement('td');
            if (cell && cell.code) {
                var code = document.createElement('code');
                code.textContent = cell.code;
                td.appendChild(code);
            } else if (cell && cell.node) {
                td.appendChild(cell.node);
            } else {
                td.textContent = cell === null || cell === undefined ? '-' : String(cell);
            }
            tr.appendChild(td);
        });
        return tr;
    }

    function table(headings) {
        var element = document.createElement('table');
        var head = document.createElement('thead');
        var heading = document.createElement('tr');
        headings.forEach(function (text) {
            var th = document.createElement('th');
            th.textContent = text;
            heading.appendChild(th);
        });
        head.appendChild(heading);
        element.appendChild(head);
        element.appendChild(document.createElement('tbody'));
        return element;
    }

    function replace(container, node) {
        container.textContent = '';
        if (node) {
            container.appendChild(node);
        }
    }

    function note(container, text) {
        var p = document.createElement('p');
        p.textContent = text;
        replace(container, p);
    }

    /* A toggle that starts and stops a poll, naming itself for whichever state it is in. */
    function poller(button, start, stop, load, interval) {
        var timer = null;
        button.textContent = start;
        button.addEventListener('click', function () {
            if (timer) {
                clearInterval(timer);
                timer = null;
                button.textContent = start;
            } else {
                load();
                timer = setInterval(load, interval);
                button.textContent = stop;
            }
        });
    }

    function logs(card) {
        var level = document.getElementById('jlogs-level');
        var query = document.getElementById('jlogs-q');
        var keyField = document.getElementById('jlogs-key');
        var status = document.getElementById('jlogs-status');
        var out = document.getElementById('jlogs-out');
        var cursor = 0;
        var rows = null;

        function load(reset) {
            if (reset) {
                cursor = 0;
                out.textContent = '';
                rows = null;
            }
            var params = ['limit=200'];
            if (level.value) {
                params.push('level=' + encodeURIComponent(level.value));
            }
            if (query.value.trim()) {
                params.push('q=' + encodeURIComponent(query.value.trim()));
            }
            if (!reset) {
                params.push('since=' + cursor);
            }
            fetch('/api/logs?' + params.join('&'), {headers: headers(keyField, false)})
                .then(json)
                .then(function (data) {
                    cursor = data.cursor;
                    if (reset && (!data.entries || !data.entries.length)) {
                        note(out, 'No entries.');
                    } else if (data.entries && data.entries.length) {
                        if (!rows) {
                            var built = table(['time', 'level', 'logger', 'message', 'tenant']);
                            replace(out, built);
                            rows = built.querySelector('tbody');
                        }
                        data.entries.forEach(function (entry) {
                            rows.appendChild(row([entry.timestamp, entry.level, entry.logger, entry.message,
                                entry.tenant || '-']));
                        });
                    }
                    status.textContent = data.count + ' shown, cursor ' + data.cursor;
                })
                .catch(function (failure) {
                    status.textContent = 'error: ' + failure.message;
                });
        }

        document.getElementById('jlogs-refresh').addEventListener('click', function () {
            load(true);
        });
        poller(document.getElementById('jlogs-tail'), 'Start tail', 'Stop tail', function () {
            load(false);
        }, 2000);
        card.dataset.jenesisBound = 'true';
    }

    function consistency(card) {
        var keyField = document.getElementById('jcon-key');
        var status = document.getElementById('jcon-status');
        var summary = document.getElementById('jcon-summary');
        var nodes = document.getElementById('jcon-nodes');
        var divergences = document.getElementById('jcon-divergences');

        /* "Converged" and "Diverged" are the answer, so they are the emphasised part of the sentence. */
        function verdict(word, rest, local) {
            var p = document.createElement('p');
            var strong = document.createElement('strong');
            strong.textContent = word;
            p.appendChild(strong);
            p.appendChild(document.createTextNode(rest + ' Local node '));
            var code = document.createElement('code');
            code.textContent = local;
            p.appendChild(code);
            p.appendChild(document.createTextNode('.'));
            return p;
        }

        function load() {
            fetch('/api/consistency', {headers: headers(keyField, false)})
                .then(json)
                .then(function (data) {
                    if (data.singleNode) {
                        replace(summary, verdict('Single node', ' - no divergence to check.', data.localNodeId));
                    } else if (data.converged) {
                        replace(summary, verdict('Converged',
                            ' - ' + data.liveCount + ' live nodes agree.', data.localNodeId));
                    } else {
                        replace(summary, verdict('Diverged',
                            ' - ' + data.divergences.length + ' finding(s) across ' + data.liveCount
                            + ' live nodes.', data.localNodeId));
                    }
                    if (!data.nodes || !data.nodes.length) {
                        note(nodes, 'No node fingerprints published yet.');
                    } else {
                        var built = table(['node', 'state', 'heartbeat age (ms)', 'index cursor', 'config gen',
                            'quota used']);
                        var body = built.querySelector('tbody');
                        data.nodes.forEach(function (node) {
                            var state = (node.live ? 'live' : 'dead') + (node.stale ? ', stale' : '')
                                + (node.local ? ' (this node)' : '');
                            body.appendChild(row([{code: node.nodeId}, state, node.heartbeatAgeMillis,
                                node.indexCursor, {code: node.configGeneration}, node.quotaUsed]));
                        });
                        replace(nodes, built);
                    }
                    divergences.textContent = '';
                    (data.divergences || []).forEach(function (divergence) {
                        var article = document.createElement('article');
                        article.className = 'app-card';
                        var head = document.createElement('header');
                        var kind = document.createElement('strong');
                        kind.textContent = divergence.kind;
                        head.appendChild(kind);
                        head.appendChild(document.createTextNode(' node '));
                        var id = document.createElement('code');
                        id.textContent = divergence.nodeId;
                        head.appendChild(id);
                        article.appendChild(head);
                        var detail = document.createElement('p');
                        detail.textContent = divergence.detail;
                        article.appendChild(detail);
                        divergences.appendChild(article);
                    });
                    status.textContent = data.liveCount + ' live of ' + data.nodeCount + ' node(s)'
                        + (data.truncated
                            ? ' (listing cut short: more fingerprints exist than the report reads)' : '');
                })
                .catch(function (failure) {
                    status.textContent = 'error: ' + failure.message;
                });
        }

        document.getElementById('jcon-refresh').addEventListener('click', load);
        poller(document.getElementById('jcon-tail'), 'Start auto-refresh', 'Stop auto-refresh', load, 5000);
        card.dataset.jenesisBound = 'true';
    }

    function credentials(card) {
        var keyField = document.getElementById('jcred-key');
        var label = document.getElementById('jcred-label');
        var status = document.getElementById('jcred-status');
        var minted = document.getElementById('jcred-minted');
        var list = document.getElementById('jcred-list');

        function load() {
            fetch('/api/credentials', {headers: headers(keyField, false)})
                .then(json)
                .then(function (rows) {
                    if (!rows.length) {
                        note(list, 'No credentials yet.');
                        return;
                    }
                    var built = table(['Label', 'Id', 'Expires', 'Used', 'Grants', '']);
                    var body = built.querySelector('tbody');
                    rows.forEach(function (credential) {
                        var revoke = document.createElement('button');
                        revoke.className = 'secondary';
                        revoke.textContent = 'Revoke';
                        revoke.addEventListener('click', function () {
                            fetch('/api/credentials/' + encodeURIComponent(credential.id),
                                {method: 'DELETE', headers: headers(keyField, false)})
                                .then(function (response) {
                                    if (!response.ok) {
                                        throw new Error('status ' + response.status);
                                    }
                                    status.textContent = 'revoked';
                                    load();
                                })
                                .catch(function (failure) {
                                    status.textContent = 'error: ' + failure.message;
                                });
                        });
                        var grants = Object.keys(credential.grants || {}).map(function (scope) {
                            return scope + '=' + credential.grants[scope];
                        }).join(', ');
                        body.appendChild(row([credential.label || '-', {code: credential.id.slice(0, 12)},
                            credential.expires || 'never', credential.useCount, grants, {node: revoke}]));
                    });
                    replace(list, built);
                })
                .catch(function (failure) {
                    status.textContent = 'error: ' + failure.message;
                });
        }

        document.getElementById('jcred-refresh').addEventListener('click', load);
        document.getElementById('jcred-mint').addEventListener('click', function () {
            fetch('/api/credentials', {
                method: 'POST',
                headers: headers(keyField, true),
                body: JSON.stringify({label: label.value.trim() || null})
            })
                .then(json)
                .then(function (issued) {
                    /* Shown once and never again, so it is stated as such rather than dropped into the table. */
                    var p = document.createElement('p');
                    var lead = document.createElement('strong');
                    lead.textContent = 'Issued - copy it now, it is not shown again:';
                    p.appendChild(lead);
                    p.appendChild(document.createElement('br'));
                    var secret = document.createElement('code');
                    secret.textContent = issued.key;
                    p.appendChild(secret);
                    p.appendChild(document.createElement('br'));
                    p.appendChild(document.createTextNode('id '));
                    var id = document.createElement('code');
                    id.textContent = issued.id;
                    p.appendChild(id);
                    p.appendChild(document.createTextNode(
                        issued.expires ? ' - expires ' + issued.expires : ' - does not expire'));
                    replace(minted, p);
                    status.textContent = 'issued';
                    load();
                })
                .catch(function (failure) {
                    status.textContent = 'error: ' + failure.message;
                });
        });
        card.dataset.jenesisBound = 'true';
    }

    /*
     * A card binds only when its fragment is on the page. The overview renders the cards a deployment installed,
     * and a card whose module is absent contributes nothing - so this looks for the marker each fragment carries
     * rather than assuming the three are always there.
     */
    var binders = {logs: logs, consistency: consistency, credentials: credentials};
    document.addEventListener('DOMContentLoaded', function () {
        Object.keys(binders).forEach(function (name) {
            var card = document.querySelector('[data-jenesis-card="' + name + '"]');
            if (card && !card.dataset.jenesisBound) {
                binders[name](card);
            }
        });
    });
})();
