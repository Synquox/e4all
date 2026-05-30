# Changelog

### 1.5.0
* Added a built-in No-Chat-Reports feature so player signatures are stripped automatically when Offline Mode is active. No more annoying warnings.
* Automatically turns off secure profiles when opening a LAN server in offline mode.
* Cleaned up and simplified packet code across all supported versions from 1.18 to 1.20.

### 1.4.0
* Added an "Online Mode" toggle button to the "Open to LAN" screen for Forge, Fabric, and NeoForge (Minecraft 1.20.1/1.20.2+).
* Added red warning messages in chat if you start a LAN server with Offline Mode turned on.
* Fixed a Fabric crash at startup caused by a Mixin target issue.
* Fixed a bug on Forge where the Online Mode button silently failed to show up.
* Cleaned up unused and redundant mixins that were causing compiler and remapper warnings.
* Fixed some annoying Netty network errors (NPEs and double-read buffer issues) in Dialtone code.

### 1.3.0
* Added Simple Voice Chat support! Voice chat now works fine over e4all tunneled connections.
* Fixed memory leaks related to voice chat packet handling.
* Fixed accidental disconnections when a Dialtone connection failed.
* Fixed wrong icon paths in Forge and NeoForge mod files.
* Updated the repository links in the Fabric mod files.

### 1.2.0
* Fixed a DecoderException ("incorrect header check") on Dialtone connections by skipping unnecessary Minecraft packet compression.
* Better UI compatibility across versions by using reflection for the "Open to LAN" screen changes.
* Made sure networking tunnels clean up their resources properly.
* Prepped everything for wider release under the new e4all brand.

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
