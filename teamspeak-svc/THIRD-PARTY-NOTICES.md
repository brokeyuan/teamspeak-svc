# Third-party notices — teamspeak-svc

## Bundled in the mod jar (META-INF/jars)

| Component | Version | License | Notes |
|---|---|---|---|
| Bouncy Castle `bcprov-jdk18on` | 1.78.1 | Bouncy Castle License (MIT-style) | TS3 voice crypto (AES-EAX, ECDH/ECDSA) |
| `webrtc-java` + platform natives (windows-x86_64, linux-x86_64, linux-aarch64, macos-aarch64) | 0.14.0 | Apache-2.0 | Optional AEC / noise suppression; disabled by default |
| `ts3voice-core` | 0.1.0 | MIT OR GPL-3.0-or-later (dual) | Shared protocol module of this project |

## Ported source (GPLv3)

- `com.tsvoice.client.audio.*` and the marked mixins are ported from
  [SVC-Extra](https://github.com/liuyuexiaoyu1/SVC-Extra) (GPL-3.0), which
  incorporates code from [Channel](https://github.com/USS-Shenzhou/Channel).
- The TS3 protocol implementation references public TSLib implementations.

## Runtime-provided (not bundled)

- Minecraft / Fabric Loader / Fabric API / JNA 5.17.0 — provided by the game
  or mod loader at runtime.

## Not distributed

- NVIDIA NVAudioEffects.dll and its models are **not** distributed with this
  mod (NVIDIA license terms). The user installs NVIDIA Broadcast themselves;
  without it the mod falls back to SVC-native processing.
