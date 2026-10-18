# DonutVision v1.2.0 — Fabric Minecraft Java 26.1.2

**Important: this repository contains SOURCE, not a ready-to-install Minecraft mod.** Putting this ZIP inside `.minecraft/mods` does nothing. You must build the `.jar` first.

## Menu controls (when installed correctly)

- **Right Shift** or **F10** = open settings menu while playing in a world.
- **F6** = toggle Freecam; **F7** = toggle Block ESP.
- **F8** = switch ores / containers / both; **F9** = change ESP radius.
- **Escape** = close the menu.
- In the menu, choose Modules / Block filters and use sliders for range and movement speed. Settings save to `.minecraft/config/donutvision.properties`.
- After joining a world, a one-time chat message says: `[DonutVision] Loaded! Menu: Right Shift or F10...` If that message never appears, **the mod is not loading**.
- You can inspect or rebind the keys under **Options > Controls > Key Binds > DonutVision**.

## Build a real `.jar` using GitHub Actions (Windows / no coding)

1. Create a free repository on https://github.com/new (public or private is fine).
2. Unzip this project. Open the folder `DonutVision-26.1.2` and upload **its contents**, including the hidden `.github/` folder, to the root of your repository. (When using GitHub's web interface, hidden folders can be easy to omit.)
3. On GitHub, open **Actions** and allow workflows if prompted, then choose **Build DonutVision 26.1.2** > **Run workflow**.
4. Open the finished job. It must display **Compile Fabric mod** and **Verify built JAR** as successful. Download the artifact **donutvision-26.1.2-jar** at the bottom of the run page.
5. Unzip the artifact and place `donutvision-26.1.2-1.2.0.jar` in your Fabric 26.1.2 Minecraft `mods` folder. Also install Fabric API for 26.1.2.
6. Restart the Fabric **26.1.2** profile, join a singleplayer test world and confirm the on-join `DonutVision Loaded` chat message. Press F10 first, then Right Shift. Once the GUI works, test on any multiplayer server only if it permits these mods.

The workflow *only uploads a JAR after compilation and static JAR checks succeed*. If compilation fails, GitHub Actions displays the specific errors, and you will not get a misleading `.jar` artifact.

## Local build

Requires JDK **25**, Gradle **9.6** (or a compatible Gradle version), internet for Minecraft/Fabric dependencies.

```
gradle clean build
```

Use the distributable `.jar` at `build/libs/donutvision-26.1.2-1.2.0.jar`, **not** any `-sources.jar` or `-dev.jar`.

## Known limits and safety

- Freecam operates client-side; it does not move or teleport the real player on the server. See-through rendering is not guaranteed for Block ESP.
- Block ESP only scans already-loaded client chunks; it cannot reveal blocks withheld by the server.
- Large ESP radii can reduce FPS.
- DonutSMP may forbid these features. No bypass or anti-detection features are included.
- **Not tested in Minecraft here:** the environment lacks Java 25, Gradle, Minecraft game libraries, and a running client. The project may still require compile/runtime fixes. Don't describe this code as verified in-game.

Sources: https://docs.fabricmc.net/26.1.2/develop/key-mappings and https://docs.fabricmc.net/26.1.2/develop/rendering/gui/custom-screens
