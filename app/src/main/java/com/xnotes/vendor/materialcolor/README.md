# Material Color Utilities

Source: https://github.com/material-foundation/material-color-utilities
Revision: 5b3618b16fdc3825e21d5679bafd144662088ea1
License: Apache-2.0 (bundled in assets/licenses/material-color-utilities.txt).

This directory contains the eight scheme variants used by xnotes and their
transitive Java source dependencies. Quantization, scoring, blending and the
legacy static scheme API are omitted. Nothing is fetched during the build.

Local changes are mechanical: packages/imports are prefixed with
com.xnotes.vendor.materialcolor, AndroidX and Error Prone annotations are removed,
and em dash punctuation in comments is replaced with ASCII hyphens.
The colour calculations are unchanged. Upstream comments and copyright headers
are retained. xnotes explicitly selects SPEC_2021 and PHONE in MaterialColors.

To update, start from a pinned upstream archive, select the same scheme classes
and their source dependencies, and repeat the mechanical changes above. Review
changes against the palette golden values and contrast tests before committing.
