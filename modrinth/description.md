# Holoscreen

Holoscreen lets you put a browser screen anywhere in your world. You can watch YouTube, Twitch, Kick or just browse, then move it around, resize it, or have it follow you. You can also make it public so everyone on the server watches the same thing.

It started as a "can I get YouTube running inside Minecraft" experiment and grew from there.

## What it does

- Press G to place a screen in front of you. A small menu on the screen lets you pick Browser (Google), YouTube, Twitch or Kick.
- Press J to edit it. You can resize it from the corners, move it, scale it with the scroll wheel, orbit it around you, or rotate it on any axis (hold Shift to snap to 15°). Ctrl+Z undoes your last change.
- It has three anchor modes: fixed in the world, following you, or locked to your view like a HUD.
- Press P while editing to make it public. Everyone sees it in the same spot, on the same page, at the same point in the video. Only you can control it. When you log off it disappears, and it comes back when you log in again.
- Audio is spatial by default. It gets quieter with distance and pans left and right. You can turn that off, and the video can pause on its own when you walk away.
- You can choose whether it draws over blocks, always on top, or hidden behind blocks and players like a real object.
- Your screen is saved per world and per server.

## Install

You need Fabric Loader and Fabric API for Minecraft 1.21.1. Drop the jar in your mods folder and you're done. MCEF (the Chromium library) is bundled. The first time you open a screen it downloads Chromium, which is about 100 MB.

Public screens only work if the server also has Holoscreen. Without it, screens stay private and everything else works as usual.

## Keys

| Key | |
|---|---|
| G | place a screen |
| I | use the page (Ctrl+L for the address bar) |
| J | edit |
| X | remove |
| K | settings |
| V | change how it shows through blocks |

While editing:

| Key | |
|---|---|
| 1–5 | resize / move / zoom / orbit / rotate |
| R | reset rotation and size |
| Shift+R | bring it back in front of you |
| F | fixed / follow / follow head |
| P | public / private |
| M | back to the menu |
| U | address bar |
| Ctrl+Z, Ctrl+Y | undo, redo |
| A | spatial audio on/off |

You can rebind all of these under Controls → Holoscreen.

## Building from source

You need JDK 21. Then run:

```
./gradlew build
```

The jar ends up in `build/libs/`. Use `./gradlew runClient` to start a dev client and `./gradlew test` to run the tests.

## Known issues

- Some Twitch and Kick streams might not play. MCEF's Chromium build doesn't ship every video codec.
- With an Iris shader pack on, the screen is always drawn on top of the world.
- It's a beta, so expect rough edges. Bug reports are welcome.

## Credits

The browser is [MCEF](https://github.com/CinemaMod/mcef) by CinemaMod, included unmodified under LGPL-2.1. The logo uses the [Inter](https://rsms.me/inter/) font (OFL).

Not affiliated with YouTube, Twitch or Kick.

MIT licensed.
