# ChunkyFly

[中文](README.md) | **English**

> A **client-side only** Fabric mod in the spirit of Chunky: **no server plugin needed** — it flies the target area with an **elytra** so the server loads/generates chunks.
> Fireworks are fully automatic (hotbar → inventory → shulker boxes), it flies the fastest way it can, shows live progress in the top-left corner, and when it's done it **flies back and lands near the take-off point**.

- Target: **Minecraft 26.2** + Fabric (`fabricloader >= 0.19.5`)
- **Client-side only**: depends on neither Fabric API nor MixinExtras (plain Mixin only). With Fabric API installed it registers real **client commands** (tab completion, never sent to the server); without it, it falls back to chat interception.
- Requirements: a gliding chestplate (detected through the `glider` component, so "elytra + chestplate" plugins work) + non-explosive firework rockets in your inventory.
- A **1.21.11** build line (compiled against intermediary names) is kept as an archive; source lives in `versions/1.21.11`.

---

## Features

### Flight & coverage

| Feature | Notes |
|---|---|
| **Automatic take-off** | 3×3 column clearance check first; from the ground it uses a jump pulse, in mid-air it sends the vanilla `START_FALL_FLYING` packet directly — no gambling on key timing |
| **Climb to cruise altitude** | Y=1000 by default (configurable); if that altitude is unreachable it picks the best it can get, and a stalled climb is handled too |
| **Serpentine route** | Row spacing = 2× the coverage radius, so adjacent rows tile the area exactly with no re-flying |
| **Adaptive coverage radius** | Takes the smaller of "client render distance" and the **measured radius of chunks actually received**, minus one block of margin — no missing strips when the server view distance is short |
| **Gap-filling pass** | If any chunk is still uncovered after the main route (corner cutting while turning, being pushed off course), it flies an extra pass — at most 3 rounds, zero overhead when the flight was clean |
| **Predictive obstacle avoidance** | Uses current horizontal/vertical speed to predict "how high will I be when I reach each sampled column", and pulls up + banks + re-boosts when terrain would be higher (fixes "hits a wall while descending") |
| **Rectangular areas** | `/chunkyfly corner <x1> <z1> <x2> <z2>` — any rectangle between two corner points |

### Landing

| Feature | Notes |
|---|---|
| **Automatic return** | Flies back to the take-off coordinates when done; `/chunkyfly return off` changes it to descend **in place** |
| **Gentle descent** | Flies straight at the landing point while high (no big circles), only spirals in a small radius near the ground |
| **Forgiving success criterion** | Touching down **within 16 blocks of the landing point counts as done**, and a **height difference is allowed** (a nearby roof, a tree, a hillside — all fine) |
| **It will not "refuse to land"** | No avoidance inside 8 blocks of the ground; avoidance attempts are capped; a slow descent switches to **forced descent**, and control is never handed back in mid-air (if it ever is, you get an explicit warning) |

### Fireworks & supplies

| Feature | Notes |
|---|---|
| **Non-explosive rockets only** | A rocket counts only when its `explosions` component is empty; they are bucketed by 1/2/3-second duration with a **preference for the 3-second ones** |
| **Fully automatic restocking** | Hotbar → inventory → **shulker boxes** (picks a box by reading the `container` component offline, right-clicks to open it inside the inventory screen, moves items with `QUICK_MOVE`, closes the screen and respects a cooldown) |
| **Boosts timed to the flight window** | Re-boosts at `10 × duration + 12` ticks instead of wasting the first 0–12 ticks of every rocket |

### Stability & safety

| Feature | Notes |
|---|---|
| **Render distance guard** | Temporarily lowers the render distance to 8 while flying (configurable / can be disabled); restores it on pause, finish, cancel and world exit — and never overwrites a value you changed yourself |
| **FPS guard** | Automatically pauses the task after 15 seconds below 8 FPS |
| **Vanilla dynamic-buffer overflow guard** | On 1.21.9+ `DynamicUniformStorage` computes `blockSize × capacity` when growing, which can overflow an int and hard-crash the game; this mod refuses the growth and wraps the write pointer just before the overflow, and logs a categorised stack trace of the "runaway writer" (prefixed `[uniform-guard]`) |
| **Crash resume** | Every 5 seconds the region, waypoint and progress are written to `config/chunkyfly-task.json`; after a crash, rejoin and use `/chunkyfly recover` to **continue from the last waypoint** (`/chunkyfly forget` discards it) |
| **Safety notices** | A photosensitive-epilepsy warning and a crash notice before every task; a permanent notice line on the third HUD line while running; local messages prefer the chat window (26.2 keeps `Hud.chat` private, so an optional mixin is used — if it does not apply, messages fall back to the action bar plus a persistent HUD line) |

---

## Commands

With **Fabric API** installed these are real client commands (tab completion, never sent to the server). Without it, type `chunkyfly ...` in chat (a leading `/` is optional).

| Command | What it does |
|---|---|
| `/chunkyfly <radius>` | Take off from here and load every chunk within that radius in **blocks**, e.g. `/chunkyfly 1000` |
| `/chunkyfly radius <radius> [chunks]` | Same; append `chunks` to give the radius in **chunks** |
| `/chunkyfly chunks <radius>` | Radius in chunks, e.g. `/chunkyfly chunks 64` ≈ 1024 blocks |
| `/chunkyfly corner <x1> <z1> <x2> <z2>` | Load a **rectangle**: the four numbers are two corner points in block coordinates |
| `/chunkyfly pause` / `stop` | Pause the flight (progress is kept) |
| `/chunkyfly continue` | Resume the paused task |
| `/chunkyfly cancel` | Cancel the task (progress is dropped, the crash-resume file is cleared) |
| `/chunkyfly status` | Progress / phase / rockets used |
| `/chunkyfly recover` | **Crash resume**: continue from the last unfinished waypoint |
| `/chunkyfly forget` | Discard the saved task |
| `/chunkyfly hud <x> <y>` | Move the progress text (pixels; `hud reset` restores the default) |
| `/chunkyfly cruise <Y>` | Cruise altitude (default 1000) |
| `/chunkyfly rd <n\|off>` | Render distance to clamp to while flying (default 8) |
| `/chunkyfly return <on\|off>` | Whether to fly back to the start before landing (default on; off = land in place) |
| `/chunkyfly help` | Command list with a one-line description for each |

**Aliases**: `radius` = `start` = `fly`; `status` = `info`; `pause` = `stop`; `continue` = `resume`; `cruise` = `cruisey`; `rd` = `renderdistance`; `corner` = `rect`; `recover` = `restore`; `forget` = `discard`; `return` = `back` = `home`.

---

## Installation

1. Create a **Minecraft 26.2** instance with **Fabric Loader ≥ 0.19.5**
2. Drop `chunkyfly-<version>+26.2.jar` into `mods/`
3. (Optional, recommended) Install **Fabric API** for real client commands with tab completion
4. In game: `/chunkyfly help` for the command list; wear an elytra, carry non-explosive fireworks, then `/chunkyfly 1000`

> **Only one chunkyfly jar may be enabled at a time** — a duplicate mod id makes Fabric crash on start-up. Rename older files to `.disabled` to keep them around.

---

## Building

No Gradle / Loom: `javac` compiles straight against the Minecraft jars and `jar.exe` packages the result.

```powershell
# 26.2 line (default; JDK 25, class file version 69)
powershell -File scripts\build.ps1
powershell -File scripts\build.ps1 -Version 2.3.4+26.2   # build a new version
powershell -File scripts\build.ps1 -NoPackage            # compile only
powershell -File scripts\verify-rebuild.ps1              # rebuild check (entry-by-entry against dist)

# 1.21.11 archive line (intermediary names / JDK 21 / class file version 65)
powershell -File scripts\build.ps1 -Line 1.21.11
```

- The JDK is auto-detected through `scripts\jdk-<major>.path` and the usual install locations; `-Jdk <home>` overrides it.
- Third-party jars you have to supply yourself (they cannot be redistributed) are listed in [`deps/README.md`](deps/README.md).
- The build script writes the source list and classpath into `build\javac-args.txt` (to stay under the Windows command-line limit) and verifies the jar's entry count and class count.

---

## Configuration

`config/chunkyfly.json` (commands write it back to disk automatically):

| Key | Default | Meaning |
|---|---|---|
| `hudX` / `hudY` | `4` / `4` | Pixel offset of the progress text (right / down) |
| `cruiseY` | `1000` | Cruise altitude (takes effect on the next take-off) |
| `flightRenderDistance` | `8` | Render distance clamped while flying; `0` = leave it alone |
| `returnToStart` | `true` | Fly back to the start before landing; `false` = land in place |

Task progress file: `config/chunkyfly-task.json` (used by crash resume; cleared automatically on a normal finish or `cancel`).

---

## Known caveats

- **Do not run a printer mod's auto-place** (`workingSwitch`) at the same time — both fight over the camera.
- At high altitude with nothing occluding the view, a single frame contains the most possible chunk sections: with **Voxy / Sodium / Iris** installed, consider turning Voxy's distant LOD off or down before flying. The mod already clamps the render distance to 8 and pauses itself when the frame rate stays low.
- Coverage is an approximation of "chunks the client has seen", not server-side data. If the server view distance is clearly smaller, prefer flying in several smaller passes (the coverage radius already adapts to the measured server-side radius).
- ⚠️ **Photosensitive epilepsy warning**: this mod rotates the camera automatically and quite abruptly (it sets yaw/pitch directly every tick), and high-altitude flight refreshes a lot of chunks on screen, so **flickering is possible**. If you have a relevant medical history, please be careful; `/chunkyfly cancel` stops everything immediately.
- ⚠️ Flying large areas **can crash the game** (render pressure / the vanilla dynamic-buffer issue mentioned above). Just rejoin — then `/chunkyfly recover` continues the unfinished task.

---

## Repository layout

```
chunkyfly/
├─ README.md                      Chinese
├─ README.en.md                   English (this file)
├─ LICENSE                        MIT
├─ versions/
│  ├─ 26.2/                       maintained line (Mojang names / JDK 25 / class 69)
│  │  ├─ src/com/chunkyfly/       18 .java files
│  │  └─ resources/               fabric.mod.json + 3 mixin configs
│  └─ 1.21.11/                    archive line (intermediary names / JDK 21 / class 65)
├─ scripts/                       build / install / rebuild check / script-encoding fix
├─ deps/README.md                 which third-party jars you must supply
└─ docs/
   ├─ BUILD-LOG.md                per-version changes and verification record (Chinese)
   └─ route-coverage-sim.py       route coverage simulation (regression tool for the missing-strip fix)
```

---

## License

[MIT](LICENSE) — use, modify and redistribute freely, keep the copyright and license notice.

Names and mechanics of vanilla behaviour referenced by the mod come from the public APIs of **Minecraft** (Mojang) and **Fabric** (FabricMC). "In the spirit of Chunky" refers to the functional idea behind Chunky-style pre-generators; this mod is an independent implementation and contains none of their code.

### Icon credits

The mod icon is a **derivative work**: the five-pointed star comes from the logo assets of **Chunky** (<https://github.com/pop4959/Chunky> by pop4959, **GPLv3**) and the elytra in the middle is the **Minecraft** vanilla item texture (© Mojang). Neither asset is original to this project — they are used purely as a personal/community identifier. If you want to use this project or its icon commercially or in any context that needs clean licensing, replace the icon with your own artwork (`versions/26.2/resources/assets/chunkyfly/icon.png`, 512×512 PNG). Everything except the icon — all code and documentation — is original and MIT-licensed.
