# Fast ImageFrames

Client-side Fabric mod that makes item frames and map walls (map art, ImageFrame) much cheaper to render. Nothing is
needed on the server, and everything looks like vanilla: map walls stay seamless and shader packs see the same
geometry. Every feature is checked by in-game tests that compare screenshots with and without the mod.

Works alone and with ImageFrame Client (the official mod, unmodified): Fast ImageFrames then downloads, caches and
draws its HD images. Works with Sodium, Iris (with and without shader packs), Entity Culling and ImmediatelyFast. For player heads, see
Voltum, its sister mod.

- Minecraft 1.21.4 to 1.21.11 and 26.1 to 26.3, Fabric Loader, Fabric API. Mod Menu is optional.
- MidnightLib is bundled in the jar.
- License: GNU LGPL v3 (`COPYING.LESSER`), see [License](#license).

## Install

Put the jar matching your Minecraft version in `mods/`, next to Fabric API:

| Jar | Minecraft | Java |
|---|---|---|
| `FastImageFrames-1.21.4-fabric-<version>.jar` | 1.21.4 | 21 |
| `FastImageFrames-1.21.5-fabric-<version>.jar` | 1.21.5 | 21 |
| `FastImageFrames-1.21.6-1.21.8-fabric-<version>.jar` | 1.21.6 to 1.21.8 | 21 |
| `FastImageFrames-1.21.9-1.21.10-fabric-<version>.jar` | 1.21.9, 1.21.10 | 21 |
| `FastImageFrames-1.21.11-fabric-<version>.jar` | 1.21.11 | 21 |
| `FastImageFrames-26.1-fabric-<version>.jar` | 26.1 to 26.1.2 | 25 |
| `FastImageFrames-26.2-fabric-<version>.jar` | 26.2 | 25 |
| `FastImageFrames-26.3-fabric-<version>.jar` | 26.3 | 25 |

## Settings

Mod Menu opens one screen: a switch for everything, then a page per group of settings (map frame rendering, HD
images quality, HD images download and cache), every setting explained in its tooltip. Nothing is saved before Apply
or Done; Cancel leaves the settings as they were.

## What it does

- **Map atlas**: the 128x128 map textures are copied into a few 4096x4096 pages. Every map of a page uses the same
  render type, so a wall of maps is drawn in one call per page instead of one call per map. Only vanilla map
  textures go to the atlas; other textures (ImageFrame Client HD images, other mods) are left alone.
- **Frame batching** (up to 1.21.8): the frame model render type is added to the fixed buffers of the entity buffer
  source, so all frames are drawn in one call. From 1.21.9 the game already groups draws by render type.
- **Reduced frame model**: same geometry and UVs as `item_frame_map`, but only the faces that can be seen. A frame in
  the middle of a wall of maps draws nothing but its map. When a resource pack replaces the map frame model
  (invisible frames packs for example), vanilla draws the pack model instead.
- **Baked map walls**: a frame is an entity, so every frame of every image the game redoes the same work for each
  one (visibility, light, map lookup, rotations, model). Map frames that do not change are handled like terrain: the
  frames of a chunk section are drawn once by the normal code into recorders (so every mod hook still applies), then
  the recorded vertices are replayed as a whole and the entities are skipped. Each frame is checked 5 times per
  second (map, rotation, light, invisibility, HD texture) and its section is rebuilt when it changed. A baked
  section is written to the game buffers as one block copy where only the positions move with the camera.
  - The backs of the frames (about 70 % of the vertices of a wall) are kept apart and only drawn when the camera is
    behind the wall.
  - Sections hidden behind blocks are skipped, with the same visibility the game uses for terrain (Sodium's when it
    is there).
  - Frames that glow, show their name, have a named marker or an HD texture of their own are drawn the normal way.
    Up to 1.21.8, baking is off while hitboxes (F3+B) are shown.
- **Animated images (GIFs of the ImageFrame plugin)**: the plugin turns every image of a GIF into a map of its own and
  swaps the map held by each frame on every image. Every image goes to the atlas once, and in a baked wall only the
  4 texture coordinates of the map of the frame are changed, so GIF walls stay baked while they play. A frame that
  changes another way twice within 2 seconds is drawn the normal way until it has been still for 5 seconds.
- **Baked item frames** (1.21.4 to 1.21.11): item frames holding any item (and empty ones) are baked like map walls,
  with every kind of item model (block items, flat items, enchanted items with their glint, shields, banners,
  chests, skulls...). Five times per second the item is drawn again into a fingerprint, so items whose look changes
  without a server update (compasses, clocks) are rebuilt. From 26.1, where item rendering was rewritten, these
  frames are drawn the normal way.
- **Cheaper per map work**: no item model resolved for frames that show a map, no render type lookup for maps drawn
  from the atlas, no walk over empty map decorations, no map instance lookup with a new lambda.
- **No redundant map texture rebuilds**: vanilla rebuilds and uploads the whole texture of a map for every map
  packet, even when the packet only moves markers. They are now only rebuilt when the colors change.
- **Shader packs (Iris)**: baked map walls and item frames stay baked with a shader pack. The Iris ids of the frame
  entity and of the item it holds are captured while the frame is recorded and set again when it is replayed, and
  frames still cast shadows (Iris's shadow pass replays the baked sections with the shadow view).

- **ImmediatelyFast**: ImmediatelyFast has its own map atlas, which replaces the texture of every map. Fast
  ImageFrames then fills its atlas from the map colors, and when it draws a map in the world it takes the map back
  from ImmediatelyFast's atlas (into its own atlas or its HD texture). Maps shown in screens and tooltips stay with
  ImmediatelyFast. With ImmediatelyFast up to 1.21.8, the entity buffers are ImmediatelyFast's, so frame batching is
  left to it.

### ImageFrame Client HD images

Fast ImageFrames takes over the HD images of the official ImageFrame Client, which stays in charge of the rest
(handshake with the server, tooltips). Its packet receivers are wrapped through Fabric API, and one optional mixin
keeps it from requesting and drawing the images itself. Only names that every ImageFrame Client version has are
used; if they change, ImageFrame Client simply keeps handling its HD images.

- **Download**: an image is requested when its map is first drawn, in the order the maps appear, a few at a time.
  Refusals of the plugin (before the player has seen the map) are asked again with a growing delay, multipart
  images are put back together, update signals download the image again while the old one stays on screen.
- **Cache**: the pngs stay in RAM (with a budget) and on disk, one folder per server in `fastimageframes/cache`. A
  cached image is named after the hash of the vanilla colors of its map, and is only used when the map the server
  sends still has these colors: then it is shown without being downloaded again.
- **LOD**: images are drawn at the resolution their size on screen needs (screen height, FOV and zoom mods included).
  Far maps are scaled to 128x128 into the map atlas and use no texture of their own; near maps get a 256, 512...
  texture. Sizes go down after 5 seconds, so turning around does not reload everything. Textures of maps not seen
  for a while are unloaded, the png is decoded again from the cache when needed.
- With the LOD off, ImageFrame Client draws its images at full resolution again, from the cache too.

## Measures

In-game test, 854x480 window, RTX 4060 laptop. Values move from run to run.

A 40x40 wall of maps (1600 item frames, with the frame marker servers add) and a 20x20 animated wall (GIF of 7
images, one image per tick):

| Minecraft | wall, vanilla | Fast ImageFrames | invisible frames, vanilla | Fast ImageFrames | animated wall, vanilla | Fast ImageFrames |
|---|---|---|---|---|---|---|
| 1.21.4 | 26.7 FPS | 797.7 FPS | 40.3 FPS | 1275.5 FPS | 111.8 FPS | 821.0 FPS |
| 1.21.5 | 9.5 FPS | 430.3 FPS | 16.0 FPS | 761.2 FPS | 61.5 FPS | 600.3 FPS |
| 1.21.8 | 12.7 FPS | 724.5 FPS | 20.8 FPS | 1006.2 FPS | 60.0 FPS | 686.5 FPS |
| 1.21.10 | 32.5 FPS | 762.8 FPS | 37.8 FPS | 1027.5 FPS | 117.3 FPS | 734.3 FPS |
| 1.21.11 | 30.8 FPS | 629.5 FPS | 35.8 FPS | 879.5 FPS | 104.3 FPS | 586.8 FPS |
| 26.1.2 | 27.3 FPS | 770.7 FPS | 37.7 FPS | 1050.7 FPS | 109.2 FPS | 776.2 FPS |
| 26.2 | 19.8 FPS | 728.8 FPS | 24.8 FPS | 1121.7 FPS | 99.8 FPS | 815.2 FPS |
| 26.3 | 29.8 FPS | 839.7 FPS | 41.0 FPS | 1226.2 FPS | 175.8 FPS | 909.5 FPS |

A 40x40 wall of HD maps with ImageFrame Client:

| Minecraft | ImageFrame Client alone | with Fast ImageFrames |
|---|---|---|
| 1.21.4 | 23.7 FPS | 672.2 FPS |
| 1.21.8 | 17.5 FPS | 656.0 FPS |
| 1.21.10 | 33.0 FPS | 799.7 FPS |
| 1.21.11 | 30.8 FPS | 669.0 FPS |
| 26.1.2 | 26.5 FPS | 746.8 FPS |
| 26.3 | 25.8 FPS | 925.3 FPS |

On a real Paper server (1.21.6, 1920x1009, a wall of 2160 copies of a 4000 px HD image): about 30 FPS with ImageFrame
Client alone, about 800 FPS with Fast ImageFrames.

A 20x20 wall of item frames holding 12 kinds of items, median of 3 rounds: 90 FPS vanilla, 115 FPS without baked item
frames, 315 FPS with them (1.21.8); 183 FPS vanilla, 483 FPS with them (1.21.4).

## Building

```bash
# one version
cd versions/26.3
./gradlew build

# every version, jars collected in dist/
JAVA25_HOME=/path/to/jdk-25 ./tools/build_all.sh

# compile some versions and only print the errors (-t: the in-game tests too)
./tools/compile_check.sh 1.21.4 26.3

# after build_all: check that every Minecraft name used by the mixins was remapped in the jars
./tools/check_jars.sh
```

The in-game tests run with Mojang names, so a mixin target that is not remapped (a target without its descriptor)
passes them but fails in the real game: run `check_jars.sh` before giving jars out.

## Project layout

- `src/main`, `src/gametest`: the only sources, written with Mojang names. Version specific parts use preprocessor
  blocks (`//#if MC >= 1.21.9`, `//#elif`, `//#else`, `//#endif`). Before 1.21.11, `Identifier` and `RenderTypes`
  are renamed back to `ResourceLocation` and `RenderType`.
- `gradle/preprocess.gradle`: the preprocessor, run before each compilation.
- `gradle/common.gradle`: build logic shared by all versions.
- `versions/<folder>`: one independent Gradle build per Minecraft version.
- `tools/generate_versions.py`: (re)creates `versions/` from the list of targets.

## Tests

The in-game client gametest (`./gradlew runClientGameTest`, `[FIF]` lines in the log):

1. renders the same scenes with Fast ImageFrames disabled and enabled and compares the screenshots: map frames from
   the front, at a grazing angle, from behind through glass, on the floor, a transparent map, an invisible frame,
   and a row of item frames holding 12 kinds of items; checks that they are baked;
2. checks that a resource pack replacing the map frame model (invisible frames) is drawn like vanilla;
3. when an ImageFrame Client jar is given (`test-jars/` has the official ones), runs a fake ImageFrame server and
   checks the HD cache (downloads, refusals asked again, multipart, update signal, RAM and disk copies, world opened
   again), the HD images drawn like ImageFrame Client and the LOD;
4. measures the FPS on a 40x40 wall of maps, an animated 20x20 wall, a 40x40 wall of HD maps, and a 20x20 wall of
   item frames with items;
5. opens the settings screen: pages, a tooltip, Cancel keeps the settings, the Custom preset, Done saves.

`FIF_VISUAL_ONLY=1`, `FIF_BENCH_ONLY=1`, `FIF_ITEMS_ONLY=1`, `FIF_SCREEN_ONLY=1` and `FIF_DIAG=1` narrow the run.
`SHADER_PACK=<zip>` turns on that Iris shader pack (with Sodium and Iris in `EXTRA_MODS_DIR`).

```bash
JAVA25_HOME=/path/to/jdk-25 ./tools/run_tests_watchdog.sh test-results/run.txt 1.21.6-1.21.8
```

On 26.x the Fabric gametest can hang while closing the world once every check has passed; the watchdog then kills
the game and the run counts as passed.

## Credits

ImageFrame and ImageFrame Client are made by LOOHP. Fast ImageFrames only talks to ImageFrame Client and does not
include it.

## License

Licensed under the GNU Lesser General Public License version 3 (`COPYING.LESSER`), which adds permissions to the GNU
General Public License version 3 (`COPYING`). Both texts are also inside every jar.
