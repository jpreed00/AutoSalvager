# AutoSalvager

A mod for [Sector Space](https://store.steampowered.com/app/3978250/Sector_Space/)
that adds tiered, station-only Auto-Salvager modules (I–V). Installed on a
station, they automatically salvage nearby wrecks and deposit the recovered items
into station storage — the salvage counterpart of the game's Auto-Miner.

It is an [SSFML](https://github.com/) / Fabric-style mixin mod that hooks into
the game at runtime, so no game files are modified permanently.

Source: [github.com/jpreed00/AutoSalvager](https://github.com/jpreed00/AutoSalvager)

## What it does

- Registers five **[Unique]** station-only modules, **Auto-Salvager I–V**, and
  stocks them at NPC **Industrial** markets (same place as the Auto-Miner).
- Each installed module launches a formation of salvage drones at a nearby wreck.
  Drones salvage on arrival and carry loot home; the next formation launches only
  once every drone from the previous one is back.
- Multiple modules run in parallel on distinct wrecks (they will not pile onto the
  same target). Range, drone count, and items-per-trip scale with module tier.
- Yield uses the same `(tier+1) / wreck hardness` gate as the salvager weapon.

| Module              | Salvage max tier | Drones | Range | Items / trip |
| ------------------- | ---------------- | ------ | ----- | ------------ |
| Auto-Salvager I     | 1                | 2      | 2500  | 1            |
| Auto-Salvager II    | 2                | 3      | 3000  | 1            |
| Auto-Salvager III   | 3                | 4      | 3500  | 2            |
| Auto-Salvager IV    | 4                | 5      | 4000  | 2            |
| Auto-Salvager V     | 5                | 6      | 4500  | 3            |

## How it works (and why it's light on performance)

- **Item registration** hooks `_database.ItemDatabase.loadDatabase()` (tail) and
  writes the five modules with the game's own `ModuleList.write(...)`.
- **Market stock** hooks `game.markets.MarketDatabase.loadDatabase()` (tail) and
  adds market ids `99006–99010` (base id + 90000) to industrial markets.
- **Behaviour** hooks `crafting.SalvageSystem.runSalvagers` — the game's own
  per-tick salvager runner — only to launch/relaunch formations when a unit is
  idle. Wreck wear-down, yield, and cargo deposit happen on the drones themselves.

There is no window and no hotkey. Debug chat logging is a compile-time switch:
`make DEBUG=1` (see below).

## Requirements

- **Sector Space** `>= 0.6.0.2` (may work with older versions; only tested on this one).
- **SSFML** (the Sector Space Fabric Mod Loader) with **Fabric Loader** `>= 0.18.4`.
- **JDK 25+** (only needed to build from source, not to play).

## Installation (players)

1. Download `autosalvager.jar` from
   [GitHub Releases](https://github.com/jpreed00/AutoSalvager/releases) or
   [build from source](#building-from-source).
2. Copy it into the `mods\` folder inside your Sector Space install directory, e.g.:
   ```
   ...\Steam\steamapps\common\Sector Space\mods\autosalvager.jar
   ```
3. Launch Sector Space (with SSFML installed). The mod loads automatically.

## Building from source

The mod compiles against the game's own jars, so the project folder is expected
to live **inside your Sector Space install directory** (alongside
`Sector Space.jar`, `SSFML.jar`, and `libs\`):

```
Sector Space\
├─ Sector Space.jar
├─ SSFML.jar
├─ libs\*.jar
├─ mods\
└─ AutoSalvager\        <- this repo
   ├─ autosalvager\mixin\*.java
   ├─ autosalvager\fx\*.java
   ├─ fabric.mod.json
   └─ autosalvager.mixins.json
```

```
git clone https://github.com/jpreed00/AutoSalvager.git
```

### 1. Point to your JDK

Make sure a JDK 25+ `javac`/`jar` is on your `PATH`, or note the path to its
`bin` folder (the bundled `Makefile` defaults to `D:\Java\jdk-25.0.4.1`).

### 2. Build

From the `AutoSalvager` folder:

```
make
```

That compiles the sources against the game jars one directory up and packages
`..\mods\autosalvager.jar`, ready to load. `make run` will also launch the game.

`make DEBUG=1` compiles debug chat logging in (what modules are doing, launches,
errors). A plain `make` strips it.

From the Sector Space directory, `make` builds every mod that has a Makefile
(`make DEBUG=1` forwards the flag to mods that support it).

To build by hand instead:

```
javac -encoding UTF-8 -cp "../Sector Space.jar;../SSFML.jar;../libs/*" -d build/classes autosalvager/mixin/*.java autosalvager/fx/*.java
jar --create --file ../mods/autosalvager.jar -C build/classes . -C . fabric.mod.json -C . autosalvager.mixins.json
```

> On Linux/macOS, use `:` instead of `;` as the classpath separator.

## Project layout

| Path                     | Purpose                                                  |
| ------------------------ | -------------------------------------------------------- |
| `autosalvager/mixin/`     | Mixins: item registry, market stock, salvage loop       |
| `autosalvager/fx/`       | Salvage drones, per-module units, debug logging          |
| `fabric.mod.json`        | Mod metadata, dependencies, and mixin registration       |
| `autosalvager.mixins.json` | Mixin configuration                                       |

## License

Released under the [MIT License](LICENSE).
