# Changelog

## 2.0.0-beta.3

### New Features & Compatibility
* Ported `e4all` to support Minecraft `26.3-snapshot-9` for Fabric and NeoForge.
* Fixed really bad bugs.

## 2.0.0-beta.2 (upstream port: e4mc 6.2.1)

### Upstream Port
 * Ported changes from upstream e4mc 6.1.1 through 6.2.1
 * Updated Gradle (8.14.4 → 9.6.1), Shadow plugin (8.3.6 → 9.6.1), and mod-publish-plugin (1.1.0 → 2.1.1)
 * Fixed Dialtone connection issues in Minecraft 1.19 by removing stale address caching (upstream 6.2.1 fix)
 * Also fixed some bugs in the mod

### 2.0.0-beta

### Fixes
 * SVC (Simple Voice Chat): Ongoing bug fixes (WIP).
 * Xaero's Mods: Fixed compatibility issues with Xaero's Minimap and WorldMap (WIP).

### 2.0.0-alpha
* Voice chat integration

### 1.6.4
* **Fixes:**
  * Improved offline mode compatibility by injecting into server authentication checks and adding missing yarn mapping redirects.

### 1.6.3
* **Fixes:**
  * Fixed "Invalid Session" connection issues for offline/cracked players joining LAN worlds.
* **Removals:**
  * Removed Simple Voice Chat (SVC) integration temporarily to resolve connection bugs.

### 1.6.2
* **Fixes:**
  * Fixed connection errors, invalid sessions, and voice chat bridge crashes.
  * Gated secure profile disabling and chat signature changes under offline mode.
* **Translations:**
  * Integrated new community translations from upstream e4mc pull requests:
    * @nunoguevara - Indonesian translation (new) [e4mc PR #293]
    * @NuruddinPlays - Updated Malay & Malay (Jawi) translations [e4mc PR #292]
    * @zelear - Added missing German & Russian translation keys [e4mc PR #280]


### 1.6.0
* https://github.com/Synquox/e4all/pull/7 Pull request from khoingt helped making this fix.
* Fixed offline mode connection issues on Dialtone.
* Voice Chat sadly doesn't work :( 
* Fixed errors not showing up when using commands.
* Fixed some crashes with other mods.
* Integrated community translations from the original e4mc repository. Huge thanks to the translators:
  * @zelear (Bashkir, German, Russian, Tatar)
  * @emi0x0 (Dutch)
  * @Fjuro & @Luky12568 (Czech)
  * @Axillux (Kazakh, Russian, Vietnamese)
  * @KiwiCabreaoo (Andalusian Spanish)
  * @kluas005 (Portuguese Brazil)
  * @Lucanoria (German)
  * @NuruddinPlays (Malay)
  * @Biquaternions (Spanish)
  * @Tarteroycc (Ukrainian)
  * @vazanoir (French)
  *(Note: English is 100% finished. Other languages are only ~30% finished as they lack translations for the newer e4all Offline Mode features).*

### 1.5.6
* Fixed voice chat. correction: doesn't work :(
* Fixed an issue that made 26.1.x for Fabric not work.

### 1.5.2
* **Major Netty & Voice Chat Overhaul:**
  * Fixed TCP timeout issues and Ghost Servers on Minecraft 26.1.x (Netty 4.2) by rewriting the `VoiceChatBridgeInitializer` to comply with Netty 4.2's `@Sharable` limitations.
  * Fixed Simple Voice Chat bridge for proxy connections! You can now use voice chat over the public proxy IP, not just peer-to-peer (Dialtone) connections.
  * Fixed a pipeline race condition that caused Voice Chat data to be silently dropped on all versions.
* **Offline Mode & UI Improvements (from 1.4.0/1.5.0):**
  * Added an "Online Mode" toggle button directly to the "Open to LAN" screen for Forge, Fabric, and NeoForge (Minecraft 1.20.1+). This already existed but glitched...
  * Built-in No-Chat-Reports: Player signatures are automatically stripped when Offline Mode is active, preventing annoying chat warnings. Secure profiles are automatically disabled.
  * Added red warning messages in chat when starting a LAN server with Offline Mode enabled.
* **Compatibility & Stability (from 1.4.0/1.5.1):**
  * Fixed some things.

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
