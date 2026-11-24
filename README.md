# Fast ImageFrames
Now available on [Modrinth](https://modrinth.com/mod/fastimageframe)!
The best mod to pair with [ImageFrame Client mod](https://modrinth.com/mod/imageframeclient), by [LOOHP](https://modrinth.com/user/LOOHP). Also fixes the incompatibility issue between ImageFrame Client and ImmediatelyFast.

DM me here on [Discord](https://discord.com/users/852481562288062504) if you encounter any issues with the mod, or if you have suggestions.

![Low and stuttering FPS (Fabulously Optimized base modpack) on a wall of image frames.](https://cdn.modrinth.com/data/cached_images/464e43e14fe40e5798838737e39eefcd8ce50ebd.png)
![Fabulously Optimized + Fast ImageFrames: the FPS nearly quadrupled while being much more stable.](https://cdn.modrinth.com/data/cached_images/52ff087cc205d94c6d1b328a562174bf1ce647f1.png)
**Item frames and map walls, rendered up to 50 times faster, same look as vanilla.**

Fast ImageFrames is a client-side Fabric mod. Big map art walls, ImageFrame servers and shops full of item frames stop
dragging your FPS down. Nothing to install on the server, nothing changes on screen: every feature is checked against
vanilla screenshots.

## What it does

### Item frames and maps

- **Map atlas**: map textures are packed into a few large textures, so a whole wall of maps is drawn in a handful of
  draw calls instead of one per map.
- **Baked map walls**: frames that don't change are drawn once and replayed like terrain, instead of being processed
  one by one every frame. Frames are checked several times per second and rebuilt as soon as something changes. Baked
  walls stay in video memory and are drawn straight from there, since 1.9.0 also with an Iris shader pack on 1.21.6
  and later (1.21.4 and 1.21.5 copy them each frame under shaders).
- **Shader packs** (1.9.0): on 1.21.4 to 1.21.8, Iris wraps the render types while a frame is recorded, so walls of
  maps were never baked with a shader pack; they are now. Baked walls are also drawn from video memory under shaders,
  shadow pass included. With Complementary Reimagined: on 1.21.6, 1600 maps about 155 FPS before, 375 FPS now; on
  26.3, 1600 maps 492 to 656 FPS and 400 item frames 303 to 492 FPS (copied each frame vs drawn from video memory).
- **Animated images (ImageFrame GIFs)** stay baked while they play: only the texture coordinates are swapped.
- **Baked item frames**: frames holding any item (blocks, tools, enchanted items, banners, shields, compasses,
  clocks...) are baked too. Since 1.8.0 an item taken out of a frame or replaced disappears at once (it stayed visible
  for up to 3 ticks). Since 1.9.0 also on 26.1 to 26.3, where Minecraft rewrote item rendering: 400 item frames on
  26.3, 165 FPS vanilla, 1306 FPS baked.
- **Lighter frame model**: the edges between two frames and the back of the frames are not drawn when they can't be
  seen. Resource packs that change the frame (invisible frames...) are respected.
- **Hidden frames are skipped**: frames behind blocks are not drawn, baked or not, using the game's (or Sodium's)
  visibility plus a block by block test. Since 1.7.3 its time budget (0.4 ms per frame) counts only the time spent
  testing: it was counted from the start of the frame, so the test often ran out before the frames were reached.
  Since 1.8.0 light updates no longer make the test start over (on a server the light around the player changes all
  the time, so it used its whole budget every frame): only block changes count, light does not change which blocks
  are solid. A section of baked frames now tests its whole box first instead of each frame one by one.
- **Baked frames leave the entity list** (1.7.2): the game (and Iris, which sorts every entity by type each frame)
  no longer looks at baked frames one by one. With Sodium and Iris, the entity pass of a scene with 1600 baked maps and
  400 changing item frames takes 21% less time with a still camera and 37% less while moving.
- **Baking spread over frames** (1.9.0): changed frames used to be baked during the game tick, with no time limit,
  so the frame that held a tick (the frames that make the 1% lows) also paid for the baking. They are now baked at the
  start of a frame, within about 2 ms per frame.
- **Works together with Voltum** (1.9.0): with Voltum 1.7.4 or later, the two mods share one baking budget per frame
  (about 2 ms in total instead of 2 ms each), and frames use Voltum's hide behind blocks test and its cache: one test
  budget instead of two, and a block change that does not change which blocks are solid (a redstone clock, a door)
  no longer makes the frames start their test over. Without Voltum, Fast ImageFrames works as before.
- **Faster hide behind blocks test** (1.9.0): the test walks the blocks between the camera and a frame, slab by
  slab. The outline of each slab is now built from four values prepared once per test axis, with two edges per slab
  instead of eight divisions: same result for every box (checked on 33 million random cases), each test about 1.7
  times faster. Voltum 1.7.5 has the same change for the shared test.
- **Glow item frames too**: normal and glow item frames are optimized the same way, and a change of light (torches,
  dynamic lights) does not rebuild anything.
- **No useless map updates**: the game no longer rebuilds a whole map texture when a packet only moves markers.
- **Smooth maps at an angle** (setting, off by default, strength 1 to 4): maps get mipmaps like block textures. Seen
  from the side or from far away they stay smooth instead of shimmering when you move (9 times less shimmer in the
  test at strength 4). When a map is bigger on screen than its image, it looks the same as before. No FPS cost on map
  walls; maps repainted all the time cost a little CPU.

### ImageFrame Client HD images

With [ImageFrame Client](https://modrinth.com/mod/imageframeclient) installed, Fast ImageFrames handles its HD images:

- **Level of detail**: each image is drawn at the resolution its size on screen needs. Far images use no video memory
  of their own, near ones get sharper.
- **Cache in RAM and on disk**, one folder per server: images show up instantly next time you join, without being
  downloaded again (only when the map still matches).
- **Smart downloads**: images are requested in the order they appear, a few at a time, refusals are retried.
- **HD after a server transfer** (1.9.0): when a lobby sends you to another server, the game can report the end of
  the old connection after the new one has started. Fast ImageFrames took it for the end of the new session: no HD
  image was asked for any more (maps kept their vanilla colors) until you joined again or turned the mod off and on.
  The end of a connection now only closes the session it opened, and a server is recognized as soon as it declares
  ImageFrame's HD channel, without waiting for its handshake.
- **Map previews in the inventory** (setting, on by default): the previews of maps and combined images in ImageFrame
  Client tooltips are drawn by Fast ImageFrames, with the HD image when there is one, shrunk smoothly to the exact
  pixels of your screen instead of a pixelated, shimmering copy, and as one picture for a whole combined image. Maps
  showing banner or explorer markers keep the ImageFrame Client preview.

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
- Minecraft **1.21.4 to 1.21.11** and **26.1 to 26.3** on Fabric.
- **Voltum** (heads, signs, banners, armor stands): made to be used together, see above.
- **Sodium**, **Lithium**, **Iris**, even with shader packs, **ImmediatelyFast**, **Entity Culling**, **FerriteCore**, **ETF**, **EMF**, etc. (it should be compatible with every mod).

## Known incompatibilities
- None

## Settings

Everything can be turned on and off from **Mod Menu**, one page per group of settings, every option explained in its
tooltip. English and French.

## License

LGPL-3.0.
