# Slides

The deck for "Practical MCP Security in Action" at Porto Tech Hub 2026 (Sofico track, Tue 10 November 2026, 11:10).

Open `index.html` in any browser. No build step, no internet, no dependencies. The QR code on the closing slide points at this repository.

## Controls

- Right arrow, space, or page down (presenter clickers): next slide. Mouse clicks do nothing, so switching windows cannot advance the deck.
- Left arrow: previous slide
- Home / End: first / last slide
- `N`: toggle presenter notes (timing budget, demo prompts and fallbacks)
- `#N` in the URL jumps to slide N
- Print to PDF at 1600 x 900 for a static copy

## Contents

27 slides, carried over from the jPrime 2026 deck and updated for Porto Tech Hub and the 2026-07-28 MCP spec:

- The venue, demo prompts against the Porto Tech Hub schedule, and the `porto` realm.
- A short aside on stateless MCP (slide 5).
- CIMD as the default and DCR as deprecated (slide 13).
- Token exchange instead of token passthrough, and why the MCP server has to vouch for the MFA level Keycloak drops on exchange (slide 20).
- An honest caveat that `acr` step-up is not part of MCP itself (slide 24).
- A "beyond the token" slide on prompt injection and tool poisoning (slide 25).

The presenter run-sheet lives in `RUNBOOK.md`.
