<p align="center">
  <img src="docs/media/title.png" alt="Cinematic Interactions" width="680">
</p>

<p align="center"><strong>Put your hands to work.</strong><br>First-person workstation scenes for Minecraft.</p>

<p align="center">
  <a href="https://github.com/dcvke/CinematicInteractions/releases"><img alt="Downloads" src="https://img.shields.io/badge/Download-Releases-38c9e8?style=flat-square"></a>
  <a href="https://github.com/dcvke/CinematicInteractions/wiki"><img alt="Documentation" src="https://img.shields.io/badge/Read-the_guide-38c9e8?style=flat-square"></a>
  <a href="https://github.com/dcvke/CinematicInteractions/issues/new/choose"><img alt="Issue tracker" src="https://img.shields.io/badge/Report-an_issue-38c9e8?style=flat-square"></a>
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/Code-MIT-56616b?style=flat-square"></a>
</p>

## 🎬 Your workshop, in motion

Place ingredients on the crafting table, load a furnace by hand, and forge equipment with a hammer. Cinematic Interactions turns supported workstations into scenes you operate directly in the world, with your own skin on the animated arms and smooth camera movement.

![Crafting directly on the tabletop](https://raw.githubusercontent.com/dcvke/cinematic-media/main/banner-hq.gif)

## ✨ What you can do

- **Craft on the tabletop.** Ingredients stay on the table. Search the recipe guide, preview missing materials, and let your hands lay out the materials you have.
- **Work with your hands.** Forge, grind, cut stone, weave banners, brew potions, and enchant equipment through dedicated station animations.
- **Stay in the workshop.** Switch to a neighboring station without a full exit; return quickly to a station you just used.
- **Take a quiet moment.** Read books with animated page turns, insert music discs, or sit beside a campfire.
- **Make it comfortable.** Choose scenes per block and adjust camera lean, zoom, transition speed, hints, shake, flashes, and cinematic bars.

![Anvil forging, enchanting, and reading at a lectern](https://raw.githubusercontent.com/dcvke/cinematic-media/main/showcase-three-scenes.gif)

[Watch the station transition video](https://dcvke.github.io/cinematic-media/showcase-station-transition.html) · [View all showcase media](https://dcvke.github.io/cinematic-media/)

## 🧩 Supported stations

| Activity | Blocks |
| --- | --- |
| Crafting & smelting | Crafting table, furnace, smoker, blast furnace, crafter |
| Tools & equipment | Anvil, grindstone, smithing table, stonecutter |
| Enchanting & brewing | Enchanting table, brewing stand |
| Banners & maps | Loom, cartography table |
| Books & music | Lectern, chiseled bookshelf, jukebox |
| Rest & effects | Campfire, soul campfire, beacon |

Chests, barrels, and shulker boxes keep their normal interfaces. [Station guide →](docs/wiki/Stations.md)

## 📦 Get the right build

**Cinematic Interactions 0.18.28 is available as a beta.** Install the JAR that matches your Minecraft version and mod loader. For full workstation scenes and shared items, install the mod on both the client and the server.

| Minecraft | Loader | Beta download |
| --- | --- | --- |
| 1.21.1 | NeoForge | [0.18.28 beta](https://github.com/dcvke/CinematicInteractions/releases/tag/v0.18.28-neoforge-1.21.1-beta) |
| 1.21.11 | Fabric | [0.18.28 beta](https://github.com/dcvke/CinematicInteractions/releases/tag/v0.18.28-fabric-1.21.11-beta) |
| 26.1.1 | Fabric | [0.18.28 beta](https://github.com/dcvke/CinematicInteractions/releases/tag/v0.18.28-fabric-26.1.1-beta) |
| 26.1.2 | Fabric | [0.18.28 beta](https://github.com/dcvke/CinematicInteractions/releases/tag/v0.18.28-fabric-26.1.2-beta) |
| 26.2 | Fabric | [0.18.28 beta](https://github.com/dcvke/CinematicInteractions/releases/tag/v0.18.28-fabric-26.2-beta) |
| 26.3 | Fabric | [0.18.28 beta](https://github.com/dcvke/CinematicInteractions/releases/tag/v0.18.28-fabric-26.3-beta) |
| 1.20.1 | Forge | [0.18.28 beta](https://github.com/dcvke/CinematicInteractions/releases/tag/v0.18.28-forge-1.20.1-beta) |

Natural Reach arms may render incorrectly in some scenes; a fix is planned. Stretched and Floating Hands are available in the meantime. [See all releases, including older builds](https://github.com/dcvke/CinematicInteractions/releases) and [installation instructions](docs/wiki/Installation.md). Fabric builds require Fabric API.

## 🎮 Start playing

1. Right-click a supported station with room to stand beside it.
2. Use the inventory panel to pick items and interact with the highlighted areas.
3. Press **Esc**, your inventory key, or a movement key to leave the scene.

Press **H** while looking at a campfire to sit down. Hold **Left Alt** during a scene for an orbit view of your character. Both bindings can be changed in Minecraft's controls.

[Controls](docs/wiki/Controls.md) · [Settings](docs/wiki/Settings.md) · [Troubleshooting](docs/wiki/Troubleshooting.md)

## 🛠️ Build & explore

An experimental standalone **NeoForge 26.3** source target is available in [`ports/neoforge-26.3/`](ports/neoforge-26.3/). It requires Java 25 and NeoForge 26.3.0.95; see its README for build instructions and verification limits. It is not a published release.

The root project builds NeoForge 1.21.1, Forge 1.20.1 and Fabric 1.20.1 / 1.21.1 from one source tree with [Stonecutter](https://stonecutter.kikugie.dev/): `src/main/java` holds the NeoForge 1.21.1 code, and the other loaders and versions sit next to it in `//? if` comments. `./gradlew build` builds all four (JARs in `versions/<target>/build/libs/`), `./gradlew :1.20.1-forge:runClient` runs one, and `./gradlew "Set active project to 1.20.1-fabric"` shows another target's code as plain Java (run `./gradlew "Reset active project"` before committing). Fabric 1.21.11 and 26.x are built by a second Stonecutter project, [`multiversion/`](multiversion/README.md), split off where Minecraft's rendering API changed; the two hold separate copies of the code. Fabric 1.21–1.21.10 (0.12.x) are no longer built. Notes of the former separate ports are in [`docs/ports/`](docs/ports/). Start with the [developer guide](docs/wiki/Development.md) and the README for the version you want to work on.

## 🗺️ Roadmap

See what is already available and what may come next in the **[interactive roadmap checklist](https://github.com/dcvke/CinematicInteractions/issues/1)**. It covers released features, further testing, and ideas under consideration. Future ideas have no fixed release dates.

[How to follow and update the roadmap →](docs/wiki/Roadmap.md)

## 🤝 Help improve the mod

Found a problem? [Open a bug report](https://github.com/dcvke/CinematicInteractions/issues/new?template=bug_report.yml) with the Minecraft version, loader, mod version, and steps to reproduce it. Screenshots or short recordings are especially useful for camera and animation issues. English and Russian reports are welcome.

Have an idea? [Suggest a feature](https://github.com/dcvke/CinematicInteractions/issues/new?template=feature_request.yml). Code contributions are welcome too: see [CONTRIBUTING.md](CONTRIBUTING.md).

## 📜 Credits & license

Created and maintained by **[Southware](https://github.com/dcvke)** as a solo project. Original project code is available under the [MIT License](LICENSE). The imported **Just a Hammer** model by **RDiamonds** is licensed under **CC BY 4.0**; attribution, adaptation details, and development disclosures are in [CREDITS.md](CREDITS.md).

Minecraft is a trademark of Mojang Studios. This project is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.
