/*
 * theme.js - the light/dark switch, working the way the Jenesis documentation's does.
 *
 * Until a reader chooses, the page follows the system's preference: Pico themes by the `data-theme` attribute and
 * falls back to prefers-color-scheme when it is absent. The switch flips between the two themes and remembers the
 * choice. The shell loads this without `defer`, so a stored choice is applied before the first paint and a dark
 * reader never sees a flash of the light page.
 *
 * The button's glyph never changes; its name does, because a glyph says nothing to a reader who cannot see it.
 */
(function () {
    var KEY = 'jenesis-theme';

    function stored() {
        try {
            var value = localStorage.getItem(KEY);
            return value === 'light' || value === 'dark' ? value : null;
        } catch (ignored) {
            // Storage can be unavailable (privacy mode); the page then simply follows the system.
            return null;
        }
    }

    function current() {
        var chosen = document.documentElement.getAttribute('data-theme');
        if (chosen === 'light' || chosen === 'dark') {
            return chosen;
        }
        return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }

    function describe(button) {
        var next = current() === 'dark' ? 'light' : 'dark';
        button.setAttribute('aria-label', 'Switch to the ' + next + ' theme');
        button.setAttribute('title', 'Switch to the ' + next + ' theme');
    }

    var initial = stored();
    if (initial) {
        document.documentElement.setAttribute('data-theme', initial);
    }

    document.addEventListener('DOMContentLoaded', function () {
        Array.prototype.forEach.call(document.querySelectorAll('[data-theme-toggle]'), function (button) {
            describe(button);
            button.addEventListener('click', function () {
                var next = current() === 'dark' ? 'light' : 'dark';
                document.documentElement.setAttribute('data-theme', next);
                try {
                    localStorage.setItem(KEY, next);
                } catch (ignored) {
                }
                describe(button);
            });
        });
    });
})();
