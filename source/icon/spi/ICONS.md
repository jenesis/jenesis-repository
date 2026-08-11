# Marks held by the icon SPI

The core is mark-agnostic: a brand mark lives in the module that contributes it, and its source and licence are
recorded next to *that* module. Only the two documents that belong to no contributor live here, and this file is
their provenance record.

## The neutral fallback — `Marks.neutral()`

An isometric package box drawn as three `currentColor` strokes on a `0 0 24 24` viewBox. Original line glyph,
released **CC0 1.0** (public domain dedication), drawn for this project; it is a generic shipping-box outline of the
kind every permissively-licensed icon set carries, with no set's proportions copied. It is the mark a console renders
where **nothing markable was identified at all** — an ecosystem no installed plug-in declares, a row whose subject
has no contributor — so a surface degrades to one uniform glyph rather than to a hole or a broken image.

It is deliberately byte-identical to the fallback the downstream console and the downstream format-icon endpoint used
to hold privately, so promoting it here changed no rendered pixel.

## The generated mark — `Marks.generated(name)` / `Marks.orphaned(name)`

Not an icon-set glyph at all: it is *computed*, so it has no upstream and no licence beyond this repository's own.
A rounded-square tile encloses a five-by-five, vertically-mirrored grid of cells, each cell empty, a filled rounded
square, or a dot, chosen by the base-three digits of the SHA-256 digest of the contributor's name. Every stroke and
fill is `currentColor`, so it inverts with the light/dark theme exactly like a declared mark, and the document
contains no text — the name is an *input* to the geometry and never appears in the output, so a mark can carry
nothing injectable into the page it is inlined in.

The tile is solid for an installed contributor that declares no mark of its own, and dashed for one that is **not
installed at all**, which is the non-colour cue that keeps those two states distinguishable without a palette.
