# Security

## What this project exposes

The TL Pro mod opens a TCP server on **127.0.0.1:38656** inside the game process; the companion app talks to it. There is no
authentication: any app on the same device that can open a loopback socket (every app with the INTERNET permission) can read the
open single-player character's inventory, equipment and map and issue the same commands as the companion (use / move items,
chat, pause, ...). Nothing is reachable from other devices or the network, and the mod serves only while a **single-player**
world is open.

Mitigations in place:

- the server binds to loopback only and stops when the game leaves the world;
- diagnostic operations (raw sprite dumps, renders, class probes) are disabled in released builds (`DEBUG` in `main.js`);
- mutating requests carry what the app believes is in the slot and are refused when the game disagrees;
- requests are limited to 8 KB.

Known limitation: a per-session token would need a channel only the companion can read; Android 11+ keeps the other app's
folders private, so this is not solved yet. Treat other apps that you install as able to see your single-player character.

## Release signing

Releases are signed with a **development key** (`tools/dev.keystore`, not in the repository) so that updates install over each
other. Verify downloads against `SHA256SUMS.txt` attached to each release. APKs from anywhere else are not ours.

## Game files

The project never modifies, re-signs or redistributes Terraria and does not bypass any licence check. The installer decodes
sprites on your PC from the copy you installed and copies them to your own device only.

## Reporting a problem

Open a private security advisory on GitHub (Security tab of this repository) or an issue without exploit details. Please do not
post working exploits publicly before there is a fix.
