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
