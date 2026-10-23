# Fast ImageFrames

**Item frames and map walls, rendered up to 50 times faster. Same look as vanilla.**

Fast ImageFrames is a client-side Fabric mod. Big map art walls, ImageFrame servers and shops full of item frames stop
dragging your FPS down. Nothing to install on the server, nothing changes on screen: every feature is checked against
vanilla screenshots.

## What it does

### Item frames and maps

- **Map atlas**: map textures are packed into a few large textures, so a whole wall of maps is drawn in a handful of
  draw calls instead of one per map.
- **Baked map walls**: frames that don't change are drawn once and replayed like terrain, instead of being processed
  one by one every frame. Frames are checked several times per second and rebuilt as soon as something changes. Without
  a shader pack, baked walls stay in video memory and are drawn straight from there.
- **Animated images (ImageFrame GIFs)** stay baked while they play: only the texture coordinates are swapped.
- **Baked item frames** (1.21.4 to 1.21.11): frames holding any item (blocks, tools, enchanted items, banners,
  shields, compasses, clocks...) are baked too.
- **Lighter frame model**: the edges between two frames and the back of the frames are not drawn when they can't be
  seen. Resource packs that change the frame (invisible frames...) are respected.
- **Hidden frames are skipped**: frames behind blocks are not drawn, baked or not, using the game's (or Sodium's)
  visibility plus a block by block test.
- **Glow item frames too**: normal and glow item frames are optimized the same way, and a change of light (torches,
  dynamic lights) does not rebuild anything.
- **No useless map updates**: the game no longer rebuilds a whole map texture when a packet only moves markers.

### ImageFrame Client HD images

With [ImageFrame Client](https://modrinth.com/mod/imageframeclient) installed, Fast ImageFrames handles its HD images:

- **Level of detail**: each image is drawn at the resolution its size on screen needs. Far images use no video memory
  of their own, near ones get sharper.
- **Cache in RAM and on disk**, one folder per server: images show up instantly next time you join, without being
  downloaded again (only when the map still matches).
- **Smart downloads**: images are requested in the order they appear, a few at a time, refusals are retried.

## Measured gains

In-game test, 854x480 window, RTX 4060 laptop, Minecraft 1.21.8:

| Scene | Vanilla | Fast ImageFrames |
|---|---|---|
| Wall of 1600 maps (40x40) | 13 FPS | 725 FPS |
| Wall of 1600 HD maps with ImageFrame Client | 18 FPS | 656 FPS |
| Animated wall, 400 maps, one image per tick | 60 FPS | 687 FPS |
| 400 item frames holding 12 kinds of items | 94 FPS | 512 FPS |

On a real Paper server with a wall of 2160 HD images (4000 px each): about 30 FPS with ImageFrame Client alone,
about 800 FPS with Fast ImageFrames.

## Compatibility

- **Sodium**, **Iris** (with and without shader packs: baked frames keep their shader ids and their shadows),
  **Entity Culling**, **ImageFrame Client**, **ImmediatelyFast**: supported.
- Minecraft **1.21.4 to 1.21.11** and **26.1 to 26.3**, Fabric Loader and Fabric API required.
- Lots of player heads too? Its sister mod **Voltum** does the same for heads and skulls.

## Settings

Everything can be turned on and off from **Mod Menu**, one page per group of settings, every option explained in its
tooltip. English and French.

## License

LGPL-3.0. Source code: https://github.com/Palmine38/FastImageFrame
