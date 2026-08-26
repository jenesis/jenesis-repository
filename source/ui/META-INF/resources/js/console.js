/*
 * The console's shared behaviour: the confirmation guard, and the CSRF header htmx needs.
 *
 * Both consoles load this one file. The CSRF wiring lived in the enterprise shell's own <head> and the
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
