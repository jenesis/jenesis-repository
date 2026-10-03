/*
 * The console's shared behaviour: the confirmation guard, and the CSRF header htmx needs.
 *
 * Both consoles load this one file. The CSRF wiring lived in a downstream console shell's own <head> and the
 * confirmation guard was pasted into two screens, each with a comment claiming it matched the other - which is
 * how behaviour that is meant to be identical stops being identical.
 */

/*
 * The confirmation guard.
 *
 * Every destructive control carries `data-confirm="<question>"`; this asks it once, on the way out. It is
 * delegated from the document rather than bound per control so a control that arrives later - an htmx swap, a
 * lazily rendered row - is guarded on arrival instead of being the one that got away.
 *
 * The question is read from the submitting control first and the form second. That order matters: a form can
 * offer both a safe action and a destructive one, and reading the form alone would either prompt for both or,
 * worse, prompt for neither.
 */
(function () {
    'use strict';

    function question(event) {
        var submitter = event.submitter;
        if (submitter && submitter.dataset && submitter.dataset.confirm) {
            return submitter.dataset.confirm;
        }
        var form = event.target;
        return form && form.dataset ? form.dataset.confirm : null;
    }

    // Capture phase, so the answer is known before any other submit handler has begun acting on the event.
    document.addEventListener('submit', function (event) {
        var asked = question(event);
        if (asked && !window.confirm(asked)) {
            event.preventDefault();
            event.stopPropagation();
        }
    }, true);

    // htmx issues requests without a submit event, so the same attribute is honoured there too rather than
    // leaving a control guarded on one path and bare on the other.
    document.addEventListener('htmx:confirm', function (event) {
        var element = event.target;
        var asked = element && element.dataset ? element.dataset.confirm : null;
        if (asked) {
            event.preventDefault();
            if (window.confirm(asked)) {
                event.detail.issueRequest(true);
            }
        }
    });
})();

/*
 * The typed-phrase guard.
 *
 * A control that deletes something carries `data-delete="<name>"` and `data-delete-warning="<what is lost>"`
 * (the `deleteButton` fragment renders both). Pressing it opens a dialog that says what is lost and that it cannot
 * be undone, and its confirm button stays disabled until the reader has typed `delete <name>` - a click can be
 * reflexive, typing the name of the thing cannot. A control whose act reaches beyond one object carries
 * `data-phrase="<phrase>"` with `data-phrase-question` and `data-phrase-warning` instead (the `phraseButton`
 * fragment), and the same dialog asks its question and waits for that phrase. Confirming submits the form with the
 * phrase as `confirm`, so a server that checks it refuses a request that did not come through the dialog.
 *
 * Every string is set as text, never as markup: the name and the warning come from the repository.
 */
(function () {
    'use strict';

    var confirmed = null;

    function asks(node) {
        return node && node.dataset && (node.dataset.delete || node.dataset.phrase) ? node : null;
    }

    function guarded(event) {
        return asks(event.submitter) || asks(event.target);
    }

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text) {
            node.textContent = text;
        }
        return node;
    }

    function open(form, submitter, source) {
        var deletion = !source.dataset.phrase;
        var phrase = deletion ? 'delete ' + source.dataset.delete : source.dataset.phrase;
        var dialog = element('dialog', 'app-phrase-dialog');
        var article = element('article');
        var heading = element('h2', null,
                deletion ? 'Delete ' + source.dataset.delete + '?' : source.dataset.phraseQuestion);
        heading.id = 'app-phrase-dialog-title';
        dialog.setAttribute('aria-labelledby', heading.id);
        var warning = element('p', 'app-phrase-dialog__warning');
        var said = deletion ? source.dataset.deleteWarning : source.dataset.phraseWarning;
        if (said) {
            warning.appendChild(document.createTextNode(said + ' '));
        }
        if (deletion) {
            warning.appendChild(element('strong', null, 'This cannot be undone.'));
        }
        var label = element('label');
        label.appendChild(document.createTextNode('Type '));
        label.appendChild(element('code', null, phrase));
        label.appendChild(document.createTextNode(' to confirm'));
        var input = element('input');
        input.type = 'text';
        input.autocomplete = 'off';
        input.spellcheck = false;
        input.setAttribute('autocapitalize', 'off');
        label.appendChild(input);
        var footer = element('footer');
        var cancel = element('button', 'secondary outline app-secondary', 'Cancel');
        cancel.type = 'button';
        var confirm = element('button', 'app-danger',
                (submitter && submitter.textContent.trim()) || (deletion ? 'Delete' : 'Confirm'));
        confirm.type = 'button';
        confirm.disabled = true;
        footer.appendChild(cancel);
        footer.appendChild(confirm);
        article.appendChild(heading);
        article.appendChild(warning);
        article.appendChild(label);
        article.appendChild(footer);
        dialog.appendChild(article);
        document.body.appendChild(dialog);

        function close() {
            dialog.close();
            dialog.remove();
            (submitter || form).focus();
        }
        input.addEventListener('input', function () {
            confirm.disabled = input.value.trim() !== phrase;
        });
        input.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                if (!confirm.disabled) {
                    confirm.click();
                }
            }
        });
        cancel.addEventListener('click', close);
        dialog.addEventListener('cancel', function (event) {
            event.preventDefault();
            close();
        });
        confirm.addEventListener('click', function () {
            if (input.value.trim() !== phrase) {
                return;
            }
            var field = form.querySelector('input[name=confirm]') || element('input');
            field.type = 'hidden';
            field.name = 'confirm';
            field.value = phrase;
            form.appendChild(field);
            dialog.close();
            dialog.remove();
            confirmed = form;
            if (form.requestSubmit) {
                form.requestSubmit(submitter || undefined);
            } else {
                form.submit();
            }
        });
        dialog.showModal();
        input.focus();
    }

    // Capture phase, ahead of the confirmation guard above: a typed phrase is asked instead of that question.
    document.addEventListener('submit', function (event) {
        var source = guarded(event);
        if (!source) {
            return;
        }
        if (confirmed === event.target) {
            confirmed = null;
            return;
        }
        event.preventDefault();
        event.stopImmediatePropagation();
        open(event.target, event.submitter, source);
    }, true);
})();

/*
 * htmx must send the CSRF token on every request it issues, since it bypasses the form post the server's own
 * hidden field rides on. The token and its header name are published as meta tags by the shared head; a page
 * without them (a console that does not use CSRF) simply wires nothing.
 */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var token = document.querySelector('meta[name=_csrf]');
        var header = document.querySelector('meta[name=_csrf_header]');
        if (!token || !header || !header.content) {
            return;
        }
        document.body.addEventListener('htmx:configRequest', function (event) {
            event.detail.headers[header.content] = token.content;
        });
    });
})();

/*
 * Keeping a screen current while work runs off the request path.
 *
 * The rule every operator surface follows is that a screen renders what is known at once and never waits: a screen
 * that waits fails on the largest store, which is the one where it is needed most. The half that was missing is
 * this one - five screens rendered "a rescan is running" and then told the reader to reload by hand, and one
 * reloaded the whole page from an inline setTimeout, which loses the scroll position and anything typed.
 *
 * So: a screen with work in flight renders the shared `running` fragment, which carries `data-app-poll` with an
 * interval in milliseconds. This re-fetches the page and swaps <main>, leaving the rest of the document - the
 * navigation, the focus outside main, the page's scroll offset - untouched. It stops by itself, because a screen
 * whose work has finished renders no marker for the next pass to find.
 *
 * A failed poll shows the last known state and tries again rather than becoming an error page. A poll that is
 * redirected has met a session that ended, and reloading is the only honest answer: swapping a sign-in page into
 * <main> would render a login form inside a console that still looks signed in.
 */
(function () {
    'use strict';

    var FALLBACK = 3000;
    var FLOOR = 500;
    var pending = null;

    function marker() {
        return document.querySelector('[data-app-poll]');
    }

    function schedule() {
        if (pending !== null) {
            return;
        }
        var found = marker();
        if (!found) {
            return;
        }
        var every = parseInt(found.getAttribute('data-app-poll'), 10);
        if (!(every >= FLOOR)) {
            every = FALLBACK;
        }
        pending = window.setTimeout(poll, every);
    }

    function swap(html) {
        var fresh = new DOMParser().parseFromString(html, 'text/html').querySelector('main');
        var current = document.querySelector('main');
        if (fresh && current) {
            current.replaceWith(fresh);
        }
    }

    function poll() {
        pending = null;
        window.fetch(window.location.href, {credentials: 'same-origin'}).then(function (answer) {
            if (answer.redirected) {
                window.location.reload();
                return null;
            }
            return answer.ok ? answer.text() : null;
        }).then(function (html) {
            if (html) {
                swap(html);
            }
            schedule();
        }).catch(function () {
            schedule();
        });
    }

    document.addEventListener('DOMContentLoaded', schedule);
})();

/*
 * The Menu button of a narrow screen.
 *
 * Below the documentation's breakpoint the header has no room for the groups, so the sidebar carries them and folds
 * away behind this button, as the documentation's chapter list does. On a wide screen the sidebar is always shown
 * and the button is hidden by the stylesheet. Without scripting the sidebar is simply always shown.
 */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var button = document.querySelector('[data-menu-toggle]');
        var sidebar = document.getElementById('app-sidebar');
        if (!button || !sidebar || !window.matchMedia) {
            return;
        }
        var narrow = window.matchMedia('(max-width: 48rem)');

        function sync() {
            sidebar.hidden = narrow.matches && button.getAttribute('aria-expanded') !== 'true';
        }

        button.addEventListener('click', function () {
            button.setAttribute('aria-expanded', String(button.getAttribute('aria-expanded') !== 'true'));
            sync();
        });
        narrow.addEventListener('change', sync);
        sync();
    });
})();

/*
 * A table wider than the screen scrolls inside its own <figure>, and a region that scrolls has to be reachable by
 * keyboard, or its right-hand columns are for mouse users only. So a figure whose content overflows it becomes a tab
 * stop, and one that fits does not - a focusable region with nothing to scroll is a stop that does nothing. Checked
 * when the page loads, when it is resized, and after htmx swaps content in, which is when a table can grow.
 */
(function () {
    'use strict';

    function reachable() {
        document.querySelectorAll('main figure').forEach(function (figure) {
            if (figure.scrollWidth > figure.clientWidth) {
                figure.setAttribute('tabindex', '0');
            } else if (figure.getAttribute('tabindex') === '0') {
                figure.removeAttribute('tabindex');
            }
        });
    }

    document.addEventListener('DOMContentLoaded', reachable);
    window.addEventListener('resize', reachable);
    document.addEventListener('htmx:afterSwap', reachable);
})();

/*
 * The settings filter.
 *
 * A settings screen carries `#setting-filter`; typing in it keeps the `.setting` rows whose `data-search` (key,
 * label, description and module) contains what was typed, so a setting is found by what it does and not only by its
 * name. It works over the rows already rendered and asks the server nothing. A group none of whose rows match steps
 * aside, and so does any other panel of the page, so the result is only what matched. The advanced settings stand
 * aside while `#setting-show-advanced` is off (the stylesheet hides them); when what was typed matches only advanced
 * settings, the switch turns itself on so the match is never hidden, and clearing the filter puts it back as the
 * reader left it. A `q` query parameter fills the filter, so a link from another screen lands on the setting it names.
 */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var input = document.getElementById('setting-filter');
        if (!input) {
            return;
        }
        var advanced = document.getElementById('setting-show-advanced');
        var settings = Array.prototype.slice.call(document.querySelectorAll('.setting'));
        if (advanced && !document.querySelector('.setting--advanced')) {
            // Nothing is folded away here, so there is nothing for the switch to show.
            advanced.disabled = true;
            advanced.closest('label').title = 'This page has no advanced settings.';
        }
        var groups = Array.prototype.slice.call(document.querySelectorAll('.setting-group'));
        var others = Array.prototype.slice.call(document.querySelectorAll('main > article:not(.setting-group)'));
        var noMatch = document.getElementById('setting-nomatch');
        var chosen = null;

        function apply() {
            var query = input.value.trim().toLowerCase();
            var plain = false;
            var any = false;
            settings.forEach(function (setting) {
                var haystack = (setting.getAttribute('data-search') || '').toLowerCase();
                var show = query === '' || haystack.indexOf(query) !== -1;
                setting.hidden = !show;
                if (show) {
                    any = true;
                    if (!setting.classList.contains('setting--advanced')) {
                        plain = true;
                    }
                }
            });
            if (advanced) {
                if (query !== '' && any && !plain) {
                    if (chosen === null) {
                        chosen = advanced.checked;
                    }
                    advanced.checked = true;
                } else if (query === '' && chosen !== null) {
                    advanced.checked = chosen;
                    chosen = null;
                }
            }
            groups.forEach(function (group) {
                group.hidden = !group.querySelector('.setting:not([hidden])');
            });
            others.forEach(function (panel) {
                panel.hidden = query !== '';
            });
            if (noMatch) {
                noMatch.hidden = query === '' || any;
            }
        }

        input.addEventListener('input', apply);
        if (advanced) {
            // A reader who sets the switch by hand has chosen; the filter no longer puts it back.
            advanced.addEventListener('change', function () {
                chosen = null;
            });
        }
        var named = new URLSearchParams(window.location.search).get('q');
        if (named) {
            input.value = named;
            apply();
        }
    });
})();

/*
 * A setting's switch, with a grace before it is written.
 *
 * A switch's form carries `data-grace="<seconds>"`. Pressing it flips the switch at once and counts the grace down
 * beside it ("Taking effect in 5 seconds..."); pressing it again inside the grace takes the change back and nothing is
 * written. When the count ends the form is posted in the background, so the page neither reloads nor jumps, and the
 * count disappears. A post that fails puts the switch back and says so. Without a script the press posts at once.
 *
 * One slot after the switch says where its value comes from, in one place and one size: "(default)" while it
 * inherits, "Reset to default" once a value is set here. The reset submits a form of its own (`reset-<key>`, which
 * posts the empty value); it too is posted in the background, after which the switch shows the default it inherits
 * (`data-default`) again, drawn muted.
 *
 * A high-impact switch carries `data-confirm` on its forms, and the confirmation guard above asks it first: a press it
 * cancels never reaches these handlers.
 */
(function () {
    'use strict';

    function plural(seconds) {
        return seconds === 1 ? '1 second' : seconds + ' seconds';
    }

    function post(form, value) {
        var body = new FormData(form);
        body.set('value', value);
        return window.fetch(form.action, {method: 'POST', body: body, credentials: 'same-origin'})
            .then(function (answer) {
                if (!answer.ok) {
                    throw new Error('status ' + answer.status);
                }
            });
    }

    function wire(form) {
        var button = form.querySelector('.app-switch');
        var state = form.querySelector('.app-switch__state');
        var slot = form.querySelector('.app-switch__slot');
        var note = form.querySelector('.app-switch__pending');
        var field = form.querySelector('input[name=value]');
        var key = form.querySelector('input[name=key]').value;
        var reset = document.getElementById('reset-' + key);
        var grace = parseInt(form.getAttribute('data-grace'), 10) || 5;
        var inheritedValue = form.getAttribute('data-default') === 'true';
        var stored = button.getAttribute('aria-checked') === 'true';
        var inherited = button.classList.contains('app-switch--inherited');
        var wanted = stored;
        var timer = null;
        var left = 0;

        function show(on) {
            button.setAttribute('aria-checked', String(on));
            state.textContent = on ? 'On' : 'Off';
        }

        function mark() {
            button.classList.toggle('app-switch--inherited', inherited);
            slot.textContent = '';
            if (inherited) {
                var label = document.createElement('small');
                label.className = 'app-switch__default';
                label.textContent = '(default)';
                slot.appendChild(label);
            } else if (reset) {
                var undo = document.createElement('button');
                undo.type = 'submit';
                undo.className = 'app-quiet app-switch__reset';
                undo.setAttribute('form', reset.id);
                undo.textContent = 'Reset to default';
                slot.appendChild(undo);
            }
        }

        function stop() {
            if (timer !== null) {
                window.clearInterval(timer);
                timer = null;
            }
            note.textContent = '';
        }

        function commit() {
            stop();
            note.textContent = 'Saving...';
            post(form, String(wanted)).then(function () {
                stored = wanted;
                inherited = false;
                field.value = String(!stored);
                mark();
                note.textContent = '';
            }).catch(function () {
                wanted = stored;
                show(stored);
                note.textContent = 'Not saved - try again.';
            });
        }

        function tick() {
            left -= 1;
            if (left <= 0) {
                commit();
            } else {
                note.textContent = 'Taking effect in ' + plural(left) + '...';
            }
        }

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            wanted = !wanted;
            show(wanted);
            stop();
            if (wanted === stored) {
                return;
            }
            left = grace;
            note.textContent = 'Taking effect in ' + plural(left) + '...';
            timer = window.setInterval(tick, 1000);
        });

        if (reset) {
            reset.addEventListener('submit', function (event) {
                event.preventDefault();
                stop();
                post(reset, '').then(function () {
                    stored = inheritedValue;
                    wanted = stored;
                    inherited = true;
                    field.value = String(!stored);
                    show(stored);
                    mark();
                }).catch(function () {
                    note.textContent = 'Not reset - try again.';
                });
            });
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('form[data-grace]').forEach(wire);
    });
})();

/*
 * A list filter.
 *
 * An input carrying `data-filter` names, as a selector, the items it filters - table rows, cards - and typing in it
 * keeps the items whose `data-search` contains what was typed. It works over what is already rendered and asks the
 * server nothing. The element whose id `data-filter-empty` names shows while nothing matches, and a `q` query
 * parameter fills the filter, so a link from another screen lands on what it names.
 */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('input[data-filter]').forEach(function (input) {
            var items = Array.prototype.slice.call(document.querySelectorAll(input.getAttribute('data-filter')));
            var empty = input.hasAttribute('data-filter-empty')
                ? document.getElementById(input.getAttribute('data-filter-empty')) : null;

            function apply() {
                var query = input.value.trim().toLowerCase();
                var anyVisible = false;
                items.forEach(function (item) {
                    var show = query === ''
                        || (item.getAttribute('data-search') || '').toLowerCase().indexOf(query) !== -1;
                    item.hidden = !show;
                    if (show) {
                        anyVisible = true;
                    }
                });
                if (empty) {
                    empty.hidden = query === '' || anyVisible;
                }
            }

            input.addEventListener('input', apply);
            var named = new URLSearchParams(window.location.search).get('q');
            if (named) {
                input.value = named;
                apply();
            }
        });
    });
})();

/*
 * Checking a field as soon as it is left.
 *
 * Every console input is checked when focus leaves it, not only when its form is sent: its own constraints (a number,
 * an address, a required field) at once, and a setting's value (`data-check-key`) against the catalogue by asking
 * `/ui/check/setting`, which answers the refusal a save would make, or nothing. A refused field is marked invalid with
 * the reason beside it, and its form refuses to be sent until it is fixed; the server still judges what arrives, so
 * this only says sooner what it would say.
 */
(function () {
    'use strict';

    function csrf() {
        var token = document.querySelector('meta[name=_csrf]');
        var header = document.querySelector('meta[name=_csrf_header]');
        return token && header && header.content ? {name: header.content, value: token.content} : null;
    }

    function holder(field) {
        return field.closest('.app-field, .setting, .app-duration, .app-routing') || field.parentElement;
    }

    function message(field) {
        var place = holder(field);
        var shown = place.querySelector(':scope > .app-field__error[data-checked]');
        if (!shown) {
            shown = document.createElement('small');
            shown.className = 'app-field__error';
            shown.setAttribute('data-checked', '');
            shown.setAttribute('role', 'alert');
            shown.id = 'check-' + Math.random().toString(36).slice(2);
            place.appendChild(shown);
        }
        return shown;
    }

    function mark(field, refusal) {
        var targets = field.type === 'hidden' ? holder(field).querySelectorAll('input:not([type=hidden]), select')
            : [field];
        var note = message(field);
        note.textContent = refusal || '';
        note.hidden = !refusal;
        Array.prototype.forEach.call(targets, function (target) {
            if (refusal) {
                target.setAttribute('aria-invalid', 'true');
                target.setAttribute('aria-describedby', note.id);
            } else if (target.getAttribute('aria-describedby') === note.id) {
                target.removeAttribute('aria-invalid');
                target.removeAttribute('aria-describedby');
            }
        });
        field.toggleAttribute('data-refused', Boolean(refusal));
    }

    function ask(field) {
        if (!field.checkValidity()) {
            mark(field, field.validationMessage);
            return;
        }
        var key = field.getAttribute('data-check-key');
        if (!key) {
            mark(field, null);
            return;
        }
        var body = new URLSearchParams();
        body.set('key', key);
        body.set('value', field.value);
        var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
        var token = csrf();
        if (token) {
            headers[token.name] = token.value;
        }
        window.fetch('/ui/check/setting', {method: 'POST', body: body, headers: headers, credentials: 'same-origin'})
            .then(function (answer) {
                return answer.ok ? answer.text() : '';
            }).then(function (refusal) {
                mark(field, refusal.trim() || null);
            }).catch(function () {
                // Unanswered, the form's own submit is the check.
            });
    }

    document.addEventListener('focusout', function (event) {
        var field = event.target;
        if (field.matches && field.matches('main :is(input, select, textarea)') && field.type !== 'hidden'
                && !field.closest('.app-duration, .app-routing, .app-values')) {
            ask(field);
        }
    });

    // A composed control (a duration, a routing) says it changed on its hidden field.
    document.addEventListener('change', function (event) {
        var field = event.target;
        if (field.type === 'hidden' && field.hasAttribute('data-check-key')) {
            ask(field);
        }
    });

    document.addEventListener('submit', function (event) {
        var refused = event.target.querySelector('[data-refused]');
        if (refused) {
            event.preventDefault();
            event.stopImmediatePropagation();
            var visible = refused.type === 'hidden'
                ? holder(refused).querySelector('input:not([type=hidden]), select') : refused;
            if (visible) {
                visible.focus();
            }
        }
    }, true);
})();

/*
 * A duration as an amount and a unit.
 *
 * A duration setting's field (`data-duration`) carries the machine form the API and the command line use - `P30D`,
 * `6h`, `none`. The console shows it as an amount and a unit instead, with "never" among the units where the rule may
 * be switched off (`data-duration="or-none"`), and writes the field back in the suffixed form ("30d") as either
 * changes. An amount left empty leaves the field empty, which inherits. Without a script the field is typed as is.
 */
(function () {
    'use strict';

    var UNITS = [['s', 'seconds', 1], ['m', 'minutes', 60], ['h', 'hours', 3600], ['d', 'days', 86400]];

    function seconds(text) {
        var value = (text || '').trim();
        var suffixed = /^(\d+)(ms|s|m|h|d)$/i.exec(value);
        if (suffixed) {
            var amount = parseInt(suffixed[1], 10);
            switch (suffixed[2].toLowerCase()) {
                case 'ms': return amount / 1000;
                case 's': return amount;
                case 'm': return amount * 60;
                case 'h': return amount * 3600;
                default: return amount * 86400;
            }
        }
        var iso = /^P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i.exec(value);
        if (iso && value.length > 1) {
            return (parseInt(iso[1] || '0', 10) * 7 + parseInt(iso[2] || '0', 10)) * 86400
                + parseInt(iso[3] || '0', 10) * 3600 + parseInt(iso[4] || '0', 10) * 60 + parseFloat(iso[5] || '0');
        }
        return null;
    }

    function split(total) {
        for (var index = UNITS.length - 1; index >= 0; index--) {
            if (total % UNITS[index][2] === 0) {
                return [total / UNITS[index][2], UNITS[index][0]];
            }
        }
        return [total, 's'];
    }

    function wire(field) {
        var orNone = field.getAttribute('data-duration') === 'or-none';
        var stored = field.value.trim();
        var parsed = seconds(stored);
        if (stored !== '' && stored !== 'none' && parsed === null) {
            return;   // a value this control cannot say; the field stays as typed
        }
        var box = document.createElement('span');
        box.className = 'app-duration';
        var amount = document.createElement('input');
        amount.type = 'number';
        amount.min = '0';
        amount.step = '1';
        amount.setAttribute('aria-label', (field.getAttribute('aria-label') || 'Duration') + ' amount');
        var unit = document.createElement('select');
        unit.setAttribute('aria-label', (field.getAttribute('aria-label') || 'Duration') + ' unit');
        UNITS.forEach(function (entry) {
            var option = document.createElement('option');
            option.value = entry[0];
            option.textContent = entry[1];
            unit.appendChild(option);
        });
        if (orNone) {
            var never = document.createElement('option');
            never.value = 'none';
            never.textContent = 'never';
            unit.appendChild(never);
        }
        var inherited = seconds(field.getAttribute('data-default'));
        if (stored === 'none') {
            unit.value = 'none';
            amount.disabled = true;
        } else if (parsed !== null) {
            var shown = split(parsed);
            amount.value = String(shown[0]);
            unit.value = shown[1];
        } else if (inherited !== null) {
            var fallback = split(inherited);
            amount.placeholder = fallback[0] + ' (default)';
            unit.value = fallback[1];
        } else {
            unit.value = 'd';
        }

        function write() {
            amount.disabled = unit.value === 'none';
            var next = unit.value === 'none' ? 'none' : amount.value.trim() === '' ? '' : amount.value.trim() + unit.value;
            if (next !== field.value) {
                field.value = next;
                field.dispatchEvent(new Event('change', {bubbles: true}));
            }
        }

        amount.addEventListener('change', write);
        unit.addEventListener('change', write);
        field.type = 'hidden';
        field.parentNode.insertBefore(box, field);
        box.appendChild(amount);
        box.appendChild(unit);
        box.appendChild(field);
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('input[data-duration]').forEach(wire);
    });
})();

/*
 * Several values as removable entries.
 *
 * A field of several values (`data-values`) carries them as the API and the command line do, on one line separated by
 * commas. The console shows one entry per value, each with a button that removes it, and a field that adds one -
 * offering the values the setting knows from the field's list, by name - and writes the line back as they change.
 * Without a script the line is typed as is.
 */
(function () {
    'use strict';

    function wire(field) {
        var known = {};
        var list = field.list;
        if (list) {
            Array.prototype.forEach.call(list.options, function (option) {
                known[option.value] = option.label || option.value;
            });
        }
        var values = field.value.split(',').map(function (value) {
            return value.trim();
        }).filter(function (value) {
            return value !== '';
        });
        var label = field.getAttribute('aria-label') || 'Value';
        var box = document.createElement('div');
        box.className = 'app-values';
        var chosen = document.createElement('ul');
        chosen.className = 'app-values__chosen';
        chosen.setAttribute('aria-label', label);
        var add = document.createElement('input');
        add.type = 'text';
        add.placeholder = field.placeholder && values.length === 0 ? field.placeholder : 'Add…';
        add.setAttribute('aria-label', 'Add to ' + label);
        if (list) {
            add.setAttribute('list', list.id);
        }

        function write() {
            var next = values.join(', ');
            if (next !== field.value) {
                field.value = next;
                field.dispatchEvent(new Event('change', {bubbles: true}));
            }
        }

        function render() {
            chosen.textContent = '';
            values.forEach(function (value, index) {
                var entry = document.createElement('li');
                var name = document.createElement('span');
                name.textContent = known[value] || value;
                name.title = value;
                var remove = document.createElement('button');
                remove.type = 'button';
                remove.className = 'app-quiet';
                remove.textContent = '×';
                remove.setAttribute('aria-label', 'Remove ' + (known[value] || value));
                remove.addEventListener('click', function () {
                    values.splice(index, 1);
                    render();
                    write();
                    add.focus();
                });
                entry.appendChild(name);
                entry.appendChild(remove);
                chosen.appendChild(entry);
            });
            chosen.hidden = values.length === 0;
        }

        function take() {
            var value = add.value.trim();
            add.value = '';
            if (value === '') {
                return false;
            }
            var named = Object.keys(known).filter(function (each) {
                return known[each].toLowerCase() === value.toLowerCase();
            });
            value = named.length === 1 ? named[0] : value;
            if (values.indexOf(value) < 0) {
                values.push(value);
                render();
                write();
            }
            return true;
        }

        add.addEventListener('keydown', function (event) {
            if ((event.key === 'Enter' || event.key === ',') && add.value.trim() !== '') {
                event.preventDefault();
                take();
            } else if (event.key === 'Backspace' && add.value === '' && values.length > 0) {
                values.pop();
                render();
                write();
            }
        });
        // A pick from the list arrives as one input event carrying a whole known value.
        add.addEventListener('input', function () {
            if (Object.prototype.hasOwnProperty.call(known, add.value)) {
                take();
            }
        });
        // An entry typed but not yet taken is taken as the form leaves, so Save keeps it.
        var form = field.form;
        if (form) {
            form.addEventListener('submit', take, true);
        }
        field.type = 'hidden';
        field.removeAttribute('list');
        field.parentNode.insertBefore(box, field);
        box.appendChild(chosen);
        box.appendChild(add);
        box.appendChild(field);
        render();
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('input[data-values]').forEach(wire);
    });
})();

/*
 * A repository's routing as a form.
 *
 * The routing setting's field (`data-routing`) carries the clauses the API and the command line use: `writable`, then
 * `fallback <source>` clauses with their options. The console edits it as whether the repository accepts uploads and
 * an ordered list of fallbacks, each an upstream address - cached or passed through, screened as standard, hardened
 * or not at all - or another repository picked from the page's `#routing-repositories` list, and writes the clauses
 * back as it changes. An option the form has no control for (match=, redirect) is kept on its fallback as written.
 * The server parses and judges the clauses; this only writes them. Without a script the clauses are typed as is.
 */
(function () {
    'use strict';

    var SCREENING = [['', 'Standard screening'], ['harden', 'Hardened'], ['unscreened', 'Unscreened']];

    function parse(text) {
        var tokens = (text || '').trim().split(/\s+/).filter(Boolean);
        // An unset routing is hosted: it accepts uploads and fetches from nowhere.
        var routing = {writable: tokens.length === 0, fallbacks: []};
        var current = null;
        for (var index = 0; index < tokens.length; index++) {
            var token = tokens[index];
            if (token === 'writable') {
                routing.writable = true;
            } else if (token === 'fallback' && index + 1 < tokens.length) {
                current = {source: tokens[++index], cache: true, screening: '', more: []};
                routing.fallbacks.push(current);
            } else if (current && token === 'nocache') {
                current.cache = false;
            } else if (current && (token === 'harden' || token === 'unscreened')) {
                current.screening = token;
            } else if (current) {
                current.more.push(token);
            } else {
                return null;   // not clauses this form can show
            }
        }
        return routing;
    }

    function upstream(source) {
        return /^[a-z][a-z0-9+.-]*:\/\//i.test(source);
    }

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text) {
            node.textContent = text;
        }
        return node;
    }

    function wire(field) {
        var routing = parse(field.value);
        if (routing === null) {
            return;
        }
        var names = Array.prototype.map.call(
            document.querySelectorAll('#routing-repositories > option'), function (option) { return option.value; });
        var box = element('div', 'app-routing');
        // Whether it accepts uploads is a state, so it is the console's switch, not a checkbox.
        var writableRow = element('div', 'app-routing__writable setting-switch');
        var writable = element('button', 'app-switch');
        writable.type = 'button';
        writable.setAttribute('role', 'switch');
        writable.setAttribute('aria-label', 'Accepts uploads');
        var writableTrack = element('span', 'app-switch__track');
        writableTrack.setAttribute('aria-hidden', 'true');
        writable.appendChild(writableTrack);
        writable.appendChild(element('span', 'app-switch__state', 'Accepts uploads'));
        writable.setAttribute('aria-checked', String(routing.writable));
        writableRow.appendChild(writable);
        box.appendChild(writableRow);
        var list = element('ol', 'app-routing__fallbacks');
        box.appendChild(list);
        var add = element('button', 'app-quiet', '+ Add a source');
        add.type = 'button';
        box.appendChild(add);

        function write() {
            var clauses = writable.getAttribute('aria-checked') === 'true' ? ['writable'] : [];
            Array.prototype.forEach.call(list.children, function (row) {
                var kind = row.querySelector('.app-routing__kind').value;
                var source = kind === 'repository' ? row.querySelector('.app-routing__repository').value
                    : row.querySelector('.app-routing__url').value.trim();
                if (!source) {
                    return;
                }
                var clause = ['fallback', source];
                if (kind === 'upstream') {
                    if (!row.querySelector('.app-routing__cache').checked) {
                        clause.push('nocache');
                    }
                    var screening = row.querySelector('.app-routing__screening').value;
                    if (screening) {
                        clause.push(screening);
                    }
                }
                clause = clause.concat(row.more);
                clauses.push(clause.join(' '));
            });
            var next = clauses.join(' ');
            if (next !== field.value) {
                field.value = next;
                field.dispatchEvent(new Event('change', {bubbles: true}));
            }
        }

        function row(fallback) {
            var item = element('li', 'app-routing__fallback');
            item.more = fallback.more || [];
            var kind = element('select', 'app-routing__kind');
            kind.setAttribute('aria-label', 'Fetch from');
            [['upstream', 'An upstream address'], ['repository', 'Another repository']].forEach(function (entry) {
                var option = element('option', null, entry[1]);
                option.value = entry[0];
                kind.appendChild(option);
            });
            var url = element('input', 'app-routing__url');
            url.type = 'url';
            url.placeholder = 'https://repo1.maven.org/maven2/';
            url.setAttribute('aria-label', 'Upstream address');
            var repository = element('select', 'app-routing__repository');
            repository.setAttribute('aria-label', 'Repository');
            names.concat(upstream(fallback.source) || !fallback.source || names.indexOf(fallback.source) >= 0
                ? [] : [fallback.source]).forEach(function (name) {
                var option = element('option', null, name);
                option.value = name;
                repository.appendChild(option);
            });
            var cacheLabel = element('label', 'app-routing__option');
            var cache = element('input', 'app-routing__cache');
            cache.type = 'checkbox';
            cache.checked = fallback.cache !== false;
            cacheLabel.appendChild(cache);
            cacheLabel.appendChild(document.createTextNode(' Keep a copy'));
            var screening = element('select', 'app-routing__screening');
            screening.setAttribute('aria-label', 'Screening');
            SCREENING.forEach(function (entry) {
                var option = element('option', null, entry[1]);
                option.value = entry[0];
                screening.appendChild(option);
            });
            screening.value = fallback.screening || '';
            var remove = element('button', 'app-quiet', 'Remove');
            remove.type = 'button';
            var isRepository = fallback.source && !upstream(fallback.source);
            kind.value = isRepository ? 'repository' : 'upstream';
            if (isRepository) {
                repository.value = fallback.source;
            } else {
                url.value = fallback.source || '';
            }

            function show() {
                var up = kind.value === 'upstream';
                url.hidden = !up;
                cacheLabel.hidden = !up;
                screening.hidden = !up;
                repository.hidden = up;
            }

            [kind, url, repository, cache, screening].forEach(function (control) {
                control.addEventListener('change', function () {
                    show();
                    write();
                });
            });
            remove.addEventListener('click', function () {
                item.remove();
                write();
            });
            [kind, url, repository, cacheLabel, screening, remove].forEach(function (part) {
                item.appendChild(part);
            });
            show();
            return item;
        }

        routing.fallbacks.forEach(function (fallback) {
            list.appendChild(row(fallback));
        });
        add.addEventListener('click', function () {
            list.appendChild(row({source: '', cache: true, screening: '', more: []}));
        });
        writable.addEventListener('click', function () {
            writable.setAttribute('aria-checked', String(writable.getAttribute('aria-checked') !== 'true'));
            write();
        });
        field.type = 'hidden';
        field.parentNode.insertBefore(box, field);
        box.appendChild(field);
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('input[data-routing]').forEach(wire);
    });
})();

/*
 * A true/false choice as a switch.
 *
 * A form that asks a true/false setting without saving it at once (a wizard step) carries it as a choice of the
 * default, true or false (`select[data-switch="<default>"]`). The console shows it as the same switch the settings
 * screens use: muted while it is left at its default, at full strength once it is set either way, and one slot after
 * it - "(default)" while it is left, "Reset to default" once it is set - in one place and one size. The choice stays
 * the field the form sends; without a script it is chosen as is.
 */
(function () {
    'use strict';

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text) {
            node.textContent = text;
        }
        return node;
    }

    function wire(select) {
        var inherited = select.getAttribute('data-switch') === 'true';
        var box = element('span', 'app-switch-field');
        var button = element('button', 'app-switch');
        button.type = 'button';
        button.setAttribute('role', 'switch');
        var label = document.querySelector('label[for="' + select.id + '"]');
        button.setAttribute('aria-label', label ? label.textContent.trim() : select.name);
        var track = element('span', 'app-switch__track');
        track.setAttribute('aria-hidden', 'true');
        var state = element('span', 'app-switch__state');
        button.appendChild(track);
        button.appendChild(state);
        var slot = element('span', 'app-switch__slot');
        var mark = element('small', 'app-switch__default', '(default)');
        var reset = element('button', 'app-quiet app-switch__reset', 'Reset to default');
        reset.type = 'button';
        slot.appendChild(mark);
        slot.appendChild(reset);

        function show() {
            var set = select.value !== '';
            var on = set ? select.value === 'true' : inherited;
            button.setAttribute('aria-checked', String(on));
            button.classList.toggle('app-switch--inherited', !set);
            state.textContent = on ? 'On' : 'Off';
            mark.hidden = set;
            reset.hidden = !set;
        }

        button.addEventListener('click', function () {
            var on = button.getAttribute('aria-checked') === 'true';
            select.value = String(!on);
            show();
        });
        reset.addEventListener('click', function () {
            select.value = '';
            show();
        });
        select.hidden = true;
        select.parentNode.insertBefore(box, select);
        box.appendChild(button);
        box.appendChild(slot);
        box.appendChild(select);
        show();
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('select[data-switch]').forEach(wire);
    });
})();

/*
 * What a choice does, under its drop-down.
 *
 * A drop-down of named choices (`select[data-describe="<id>"]`) has room for the names only; each option carries its
 * short description as its title, and the element the select names shows "Name: description" for the one chosen, as
 * the choice changes.
 */
(function () {
    'use strict';

    function describe(select) {
        var target = document.getElementById(select.getAttribute('data-describe'));
        var option = select.options[select.selectedIndex];
        if (!target || !option) {
            return;
        }
        var name = option.textContent.replace(/ \(default\)$/, '');
        target.textContent = option.title ? name + ': ' + option.title : '';
    }

    document.addEventListener('change', function (event) {
        if (event.target.matches && event.target.matches('select[data-describe]')) {
            describe(event.target);
        }
    });
})();

/*
 * Times in the reader's own timezone.
 *
 * The server draws every instant as a <time> element whose datetime is the instant and whose text is that instant to
 * the second in UTC, because it cannot know where its reader is. This shows each such time - on load and in whatever a
 * refresh swaps in - in the browser's timezone, keeping the UTC text as the element's title. Without a script the UTC
 * text stands, which is still correct.
 */
(function () {
    'use strict';

    var OPTIONS = {year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
        second: '2-digit'};

    function convert(root) {
        if (!root || !root.querySelectorAll) {
            return;
        }
        var times = Array.prototype.slice.call(root.querySelectorAll('time[datetime]'));
        if (root.matches && root.matches('time[datetime]')) {
            times.push(root);
        }
        times.forEach(function (time) {
            var at = new Date(time.getAttribute('datetime'));
            if (isNaN(at.getTime()) || time.hasAttribute('data-local')) {
                return;
            }
            time.title = time.textContent.trim();
            time.textContent = at.toLocaleString(undefined, OPTIONS);
            time.setAttribute('data-local', '');
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        convert(document.body);
    });
    document.addEventListener('htmx:afterSwap', function (event) {
        convert(event.target);
    });
})();
