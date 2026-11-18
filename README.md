# Live Screen

Shows Moments Creator's Live program out on a TV, through a Fire TV or Google TV stick, by receiving
its NDI® feed. Delay is NDI's: a few frames. Audio is ignored; the room's sound system carries it.

- Opens straight to Moments Creator's feed ("Moments Live – Clean") as soon as it appears, and keeps
  showing the last one chosen from then on. While the feed is off it says so, and the picture comes back
  by itself when the Mac starts sending again.
- Finds every NDI source on the network by itself. With no Moments Creator feed there, the list opens
  so one can be picked; a hint at the bottom says OK changes it whenever the picture starts.
- **OK** on the remote: the list of NDI sources on the network, the picture's size and frame rate,
  and **Quality** (full, or lower for weak Wi-Fi). **Back** closes it.
- Opens by itself when the stick powers on (after the one-time grant below).

Full-quality 1080p NDI is roughly 100–150 Mbps, so a wired connection (Ethernet adapter) is best.

## Build and install

Needs, besides JDK 17 and the Android SDK in `~/android-sdk` (as for TV Time):

- **Android NDK:** `sdkmanager 'ndk;<version>'`
- **NDI SDK for Android**, free from https://ndi.video/for-developers/ndi-sdk/, unpacked to
  `~/NDI SDK for Android` (or set `NDI_SDK`). Its `libndi.so` ships inside the app.

On the stick: Settings › My Fire TV › About, press OK on the device name 7 times, then Developer
Options › ADB debugging **On** (Google TV: Settings › System › About › Android TV OS build, 7 times,
then Developer options › USB debugging). The first install asks to allow the Mac on the TV.

```sh
DEVICE=<stick's IP> ./build.sh install
```

To open by itself at power-on, allow it once to start from the background:

```sh
~/android-sdk/platform-tools/adb shell appops set com.moments.livescreen SYSTEM_ALERT_WINDOW allow
```

Android skips the power-on start for an app that was force-stopped (Settings › Applications › Force stop)
until it's opened once by hand. On Fire TV it takes about a minute after the home screen appears.

NDI® is a registered trademark of Vizrt NDI AB.
