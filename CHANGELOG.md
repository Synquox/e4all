# Changelog

## 1.4.1

- Fixed the "Online Mode" toggle button not appearing on the Open to LAN screen on Forge runtimes by making the screen mixin fully mapping-agnostic: the `init` injection now matches across Mojang/intermediary/SRG names, and the button construction (`Button.builder` / `bounds` / `build`), `Screen.addRenderableWidget`, `Button.setMessage`, and the `Button.OnPress` SAM dispatch are all resolved by signature via reflection instead of relying on compile-time references that bake in Mojang-only names.
- Fixed the `DataFormatException: incorrect header check` crash that affected hosted worlds on Forge 1.20.1 with heavy modpacks: the compression-skip in `ConnectionMixin#killDoubleCompression` is now restricted to peer-to-peer Dialtone channels and no longer applies to relay-tunneled `QuicStreamChannel` connections, where the host was writing raw frames while the client kept zlib-decompressing them on the other side of the relay.
- Fixed the "Encrypting..." infinite hang for clients joining offline-mode hosted worlds: the `ServerLoginPacketListenerImpl.handleKey` / `handleHello` redirects, the `ClientHandshakePacketListenerImpl.handleHello` redirects, and the `OfflineModeMixin` auth-skip now use cross-mapping method regexes so the offline-mode bypass actually applies on Forge runtimes whose intermediate names differ from Mojang.
- Fixed silent mixin no-ops on Forge runtimes for `MinecraftServer.usesAuthentication`, `ServerConnectionListener.startTcpServerListener` / `stop`, and `ServerNameResolver.resolveAddress` by switching their `@Inject` / `@ModifyArg` / `@Redirect` method targets to the same cross-mapping regex pattern.
- Forge: explicitly registered the `e4all.mixins.json` config in `mods.toml` as a belt-and-suspenders to the manifest entry so mixin loading does not depend on Forge's mixin service reading the JAR manifest.

## 1.4.0

- Fixed memory and background thread leaks in Dialtone event loop dispatchers.
- Fixed asynchronous Netty connection/socket leaks during rapid server shutdowns or restarts.
- Improved robustness of reflection-based utility methods to prevent startup, command, and login crashes.
- Restored Simple Voice Chat bridge compatibility on Minecraft 1.20.2+ by implementing fallback DiscardedPayload record serialization.

## 1.3.0

- Added Simple Voice Chat bridge: voice chat now works over e4all tunneled connections.
- Fixed memory leaks in voice chat packet handling.
- Fixed spurious disconnection events on failed Dialtone connections.
- Fixed incorrect icon references in Forge and NeoForge mod descriptors.
- Updated repository URLs in Fabric mod descriptors.

## 1.2.0

- Fixed a `DecoderException` ("incorrect header check") occurring on Dialtone connections by bypassing redundant Minecraft packet compression.
- Improved cross-version UI compatibility using robust reflection for "Open to LAN" screen modifications.
- Reinforced resource cleanup for networking tunnels.
- Prepared for wider distribution under the `e4all` brand.

## 6.1.0

- Support for 26.1 added. Note that Fabric requires the use of a separate modern jar for 26.1!

## 6.0.6

- Slightly improved mod compatibility. Should now work with the next version of ViaFabricPlus.

## 6.0.5

- Fixed connecting to servers not working on Forge due to a Loom bug.
- Fixed Dialtone connections not working on Forge due to a Mixin limitation.

## 6.0.4

- Fixed an erroneous warning message. oops!

## 6.0.3

- Fixed a crash with certain Minecraft versions. Again.

## 6.0.2

- Fixed Dialtone being broken for 1.18-1.20.

## 6.0.1

- Fixed a crash with certain Minecraft versions.

## 6.0.0

- Changed mod ID to `e4mc` from `e4mc_minecraft`.
- Introduced Dialtone! When installed on *both sides*, e4mc will now establish a direct connection.

## 5.5.4

- Fixed a regression on 1.21.11 and above due to Minecraft using Netty 4.2.

## 5.5.3

- Mildly improved performance and footprint by reusing Minecraft's `EventLoopGroup`.
- Fixed the inability to join using certain Forge versions with the message "Disconnected".
- Fixed an issue where e4mc would keep an unjoinable ghost session running after closing the world.

## 5.5.2

- Fixed some errors by updating dependencies.

## 5.5.1

- Fixed a crash on some NeoForge versions

## 5.5.0

- Added `/e4mc doctor`, which prints e4mc-related diagnostics for troubleshooting

## 5.4.2

- Now supports 1.21.9, 1.21.10, 1.21.11

## 5.4.1

- You can no longer lock yourself out of a world by banning yourself or forgetting to whitelist yourself

## 5.4.0

- Now supports 1.21.6
- Now restores basic administration commands such as /ban and /whitelist for LAN servers.

## 5.3.1

- Now supports 1.21.5

## 5.3.0

- Added Malay and Malay (Jawi) translations (by  NuruddinPlays)
- Added Spanish translation (by Biquaternions)
- Fixed constant redownloads of natives

## 5.2.1

- Removed accidentally added debug message

## 5.2.0

- Added NeoForge support
- Added French translation (by vazanoir)
- Added Ukrainian translation (by Tarteroycc)
