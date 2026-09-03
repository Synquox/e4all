<div align="center">
  <h1>e4all</h1>
  <p><b>The e4mc fork that lets anyone join your LAN world from anywhere, even with offline accounts. Supports Simple Voice Chat, Android, and more!</b></p>

  <a href="https://modrinth.com/mod/e4all">
    <img src="https://img.shields.io/badge/Modrinth-Download-00AD5C?style=for-the-badge&logo=modrinth&logoColor=white" alt="Modrinth Download" />
  </a>
  <a href="https://discord.gg/mUYW9Rw2ae">
    <img src="https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord Support" />
  </a>
</div>

---

### What is e4all?
e4all is a lightweight Minecraft mod that lets you open your singleplayer LAN world to friends over the internet. No port forwarding, no router setup, and no third-party VPN apps like Hamachi or Radmin required.

It is a fork of **e4mc** packed with fixes and quality-of-life additions: **offline/cracked account support**, **Simple Voice Chat (SVC)** support over P2P (so it may not work for everybody, see details below), **Android** support, **Krypton** compatibility (tho Krypton being installed with e4all is useless). Also, please don't misunderstand, **this mod is built for Windows, Linux and Mac it just also supports Android!!**

---

### Quick Tutorial

#### As the Host:
1. Put "e4all" in your "mods" folder and launch Minecraft.
2. Load your singleplayer world.
3. Hit Esc and click Open to LAN.
4. Configure your game mode and cheats as usual.
5. If you or your friends use offline/cracked accounts, set the Online Mode button to OFF.
6. Click Start LAN World.
7. Look at your chat for the generated public address (e.g., `abcde.e4mc.link`). Click it to copy it to your clipboard.
8. Send the address to your friends!

#### As the Joining Friend:
1. Open Minecraft and go to Multiplayer -> Direct Connection (or add it to your server list).
2. Paste the host's address and click Join Server.
3. Vanilla clients work: Guests don't even need the mod installed to join normal game sessions! (Though having e4all installed on both sides enables direct P2P connections and voice chat).

#### Using Simple Voice Chat (SVC):
- Both the host and joining player need e4all and Simple Voice Chat installed.
- Voice runs **strictly over direct player-to-player (P2P)** connections.
- **Important note on voice:** Because voice is 100% P2P, it requires both players' internet providers / routers to support direct P2P connections. If either player is behind a restrictive firewall, strict symmetric NAT/CGNAT, or university/school network, voice chat may not be able to connect. Game traffic will still work fine through the relay.

---

### ALL Differences Between e4mc and e4all (state 7.09.2026)

| Feature | e4mc | e4all | Details |
| :--- | :---: | :---: | :--- |
| **Offline / Cracked Accounts** | ❌ | ✅ | e4all adds an **Online Mode** toggle right in the Open to LAN menu so offline accounts can join. |
| **Simple Voice Chat (SVC)** | ❌ | ✅ | Voice chat works. |
| **Krypton Compatibility** | ❌ | ✅ | Krypton works if used with e4all, this was just a incompatibility before and it isn't anymore. |
| **Android** | ❌ | ✅ | E4all works on Minecraft Java Launchers for Android. |
| **Xaero's Minimap & World Map** | ❌ | ✅ | Syncs a world ID so waypoints and map progress don't reset when the domain changes. |
| **No-Chat-Reports (NCR)** | ❌ | ✅ | Strips chat signatures and disables secure profile enforcement when Offline Mode is on. The e4all NCR feature was inspired by the NCR mod. |
Do note that I only added Krypton compatibility because people were accidentally having it installed alongside e4all and it was causing problems. Having Krypton installed alongside e4all is pretty much useless.
I only recommend using Krypton if you run an actual server, though I can't say much as I personally haven't really used Krypton before.
---

### Supported Versions & Mod Loaders

| Loader | Supported Minecraft Versions |
| :--- | :--- |
| **Fabric** | 1.18.x - 26.3-snapshot-9 |
| **NeoForge** | 1.18.x - 26.3-snapshot-9 |
| **Forge** | 1.18.x - 1.20.4 |
| **Android** | Android Java wrappers supported! (Windows, Linux and MacOS ofc too)|

### Device Compatibility
- The mod works with PC's and laptops just like e4mc. The thing is that e4mc and other mods I saw didn't support Android, so I added Android Support. My mod now has the default normal mod file and a file with "-android" in the name, the only difference is that the one with android in the name works on Android and the file is much bigger. I'm also pretty sure the Android version also works for PC but I haven't tested that because its unnecessary. And for anybody wondering why there even is two different files if the Android file also works on PC: This is just because the Android file is about 9x bigger and that just annoyed me.

---

### Contributing
Please contribute

---

## Building

Gradle is used to build e4all.

### System requirements

- Java JDK 17 minimum (JDK 21 recommended for building)
- Git

### Compiling from source

```
git clone https://github.com/Synquox/e4all
cd e4all/
./gradlew build
```

Since e4all is a multi-loader project, you'll find the compiled jars in each loader's own build folder, for example `fabric/build/libs`, `forge/build/libs`, and `neoforge/build/libs`.

## License

e4all is licensed under the MIT license. See `LICENSE` for more info.

### Credits & Links

* Forked from the original [e4mc](https://modrinth.com/mod/e4mc) by vgskye. I also use her relay servers.
* **Discord Community & Support:** [https://discord.gg/mUYW9Rw2ae](https://discord.gg/mUYW9Rw2ae)
* **GitHub Repository:** [https://github.com/Synquox/e4all](https://github.com/Synquox/e4all) (Obviously)
* **Modrinth Page:** [https://modrinth.com/mod/e4all](https://modrinth.com/mod/e4all)
I don't earn money from this on Modrinth as I can't fulfill the legal requirements to earn money in Germany, so this project is just for fun.
The NCR feature of this mod was inspired by the No Chat Reports mod.s
