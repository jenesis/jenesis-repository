/*
 * theme.js — the shared light/dark theme switch for both consoles.
 *
 * Pico already themes by the `data-theme` attribute on <html> and falls back to the OS preference
 * (prefers-color-scheme) when the attribute is absent, and app.css defines its status tokens for both. This script
 * only adds the user's explicit choice on top: it applies a stored override at parse time (the shell loads it
 * without `defer`, so the first paint is already in the chosen theme - no flash), and wires any control marked
 * [data-theme-toggle] to cycle and persist it. "auto" removes the override, handing the decision back to the OS.
 *
 * The control is an icon button rather than a three-option select, and which glyph it shows is decided in CSS from
 * the `data-theme` attribute this script sets - so the button is already right at first paint and this script never
 * touches its appearance. What it does own is the button's *name*: a glyph says nothing to a reader who cannot see
 * it, so the label carries the current state and the title says what the next click does.
 */
(function () {
    var STATES = ['auto', 'light', 'dark'];
    var KEY = 'jenesis-theme';

    function stored() {
        try {
            var value = localStorage.getItem(KEY);
            return value === 'light' || value === 'dark' ? value : 'auto';
        } catch (ignored) {
            // Storage can be unavailable (privacy mode); the console then simply follows the OS preference.
            return 'auto';
        }
    }

    function apply(choice) {
        if (choice === 'light' || choice === 'dark') {
            document.documentElement.setAttribute('data-theme', choice);
        } else {
            document.documentElement.removeAttribute('data-theme');
        }
    }

    function persist(choice) {
        try {
            if (choice === 'light' || choice === 'dark') {
                localStorage.setItem(KEY, choice);
            } else {
                localStorage.removeItem(KEY);
            }
        } catch (ignored) {
        }
    }

    function describe(button, choice) {
        var next = STATES[(STATES.indexOf(choice) + 1) % STATES.length];
        button.setAttribute('aria-label', 'Color theme: ' + choice);
        button.setAttribute('title', 'Color theme: ' + choice + ' — switch to ' + next);
    }

    apply(stored());
    document.addEventListener('DOMContentLoaded', function () {
        Array.prototype.forEach.call(document.querySelectorAll('[data-theme-toggle]'), function (button) {
            describe(button, stored());
            button.addEventListener('click', function () {
                var choice = STATES[(STATES.indexOf(stored()) + 1) % STATES.length];
                persist(choice);
                apply(choice);
                describe(button, choice);
            });
        });
    });
})();
