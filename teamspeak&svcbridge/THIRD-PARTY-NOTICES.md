# Third-party notices — tsbridge

## Bundled in the mod jar (META-INF/jars)

| Component | Version | License | Notes |
|---|---|---|---|
| `teamspeak3-api` (TheHolyWaffle) | 1.2.0 | MIT | ServerQuery client |
| `Java-WebSocket` | 1.5.6 | MIT | Deprecated Ts3AudioBotTransport fallback only |
| `fabric-permissions-api` | 0.7.0 | MIT | Permission checks |
| Bouncy Castle `bcprov-jdk18on` | 1.78.1 | Bouncy Castle License (MIT-style) | Native TS3 voice client crypto |
| `ts3voice-core` | 0.1.0 | MIT OR GPL-3.0-or-later (dual) | Shared protocol module of this project |

## Derived protocol code

The native TS3 voice client (`com.svcb.tsbridge.ts3voice`) is ported from
TS3AudioBot/TSLib protocol work. **The upstream license must be verified
against the MIT claim above before public distribution** — this is tracked as
an open release-engineering item in the project spec.

## Runtime-provided (not bundled)

- Minecraft / Fabric Loader / Fabric API / Simple Voice Chat (API) — provided
  by the game or mod loader at runtime.
