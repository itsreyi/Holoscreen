# Holoscreen

A Fabric mod for Minecraft 1.21.1 that puts a browser screen in your world. It can open YouTube, Twitch, Kick or any website, and you can share it with other players on the server.

## Build

You need JDK 21.

```
./gradlew build
```

The jar is written to `build/libs/holoscreen-<version>.jar`. MCEF is bundled inside it, so Fabric API is the only other mod you need.

Other useful tasks:

```
./gradlew runClient   # dev client, uses ./run
./gradlew runServer   # dev server
./gradlew test        # unit tests
```

## Use

- G places a screen. Pick a site from the menu on it.
- I lets you use the page.
- J is edit mode. 1–5 switch tools, R resets, F changes the follow mode, P makes it public.
- X removes it. K opens the settings.

Public screens need Holoscreen on the server as well. The first launch downloads Chromium (about 100 MB).

## License

MIT. MCEF is LGPL-2.1 and included unmodified.
