# Soundboard (Java / Swing)

A free-flow dotted-grid soundboard GUI for Linux Mint, written in Java 11+
with Swing. **No proprietary or native dependencies** — runs on any standard
OpenJDK 11 or later install.

## Features

| Feature | Details |
|---|---|
| Multiple pages | JTabbedPane; add / rename / delete tabs |
| Free-flow grid | Scrollable dotted canvas (2400 × 1600 px virtual), place buttons anywhere |
| Drag & resize | Drag any button freely; resize from the bottom-right handle |
| Right-click menu | Edit properties, play, delete — on any button |
| Double-click (empty grid) | Create a new button at that spot |
| Right-click (empty canvas) | "New button here" |
| Button properties | Caption, color picker, audio file (mp3/wav/ogg/flac), start offset, fade-in ms, fade-out ms, volume % |
| Waveform preview | Visual peak display in the properties dialog; click to set the start offset |
| Keyboard hotkeys | Record any Ctrl/Alt/Shift+key combination per button; fires from any tab |
| Instant replay cache | Processed clips cached in memory; repeat plays are zero-latency |
| Drag button between tabs | Drag a button up onto a different tab label to move it there |
| Save / Load | Full layout (all tabs, buttons, properties) saved as JSON |

## Requirements

| Tool | Minimum version |
|---|---|
| OpenJDK | 11 (tested on 11, 17, 21) |
| Apache Maven | 3.6+ |
| (no other native libs needed) |  |

Install on Linux Mint:
```bash
sudo apt update
sudo apt install openjdk-21-jdk maven
```

## Build

```bash
cd soundboard-java
mvn package -q
```

This produces `target/soundboard-1.0.0.jar` — a fat JAR with all
dependencies bundled (JLayer MP3 decoder + Gson).

## Run

```bash
java -jar target/soundboard-1.0.0.jar
```

Or add a desktop shortcut pointing to that command.

## Audio notes

- MP3 decoding uses **JLayer** (javazoom), a pure-Java MP3 decoder that
  ships inside the fat JAR — no `ffmpeg` or native library needed.
- WAV / AU files are read directly by `javax.sound.sampled` (built into
  every JDK).
- For OGG/FLAC, the file is decoded by JLayer's generic input stream;
  complex formats may require adding a TRITONUS or JAAD dependency to
  `pom.xml`.
- The first click on a button triggers decoding + DSP in a background
  thread. All subsequent clicks replay the cached `Clip` instantly.

## Save file format

Layouts are plain JSON, compatible with the Python version's format:
```json
{
  "tabs": [
    {
      "name": "Page 1",
      "buttons": [
{
          "x": 40, "y": 40, "w": 140, "h": 80,
          "caption": "Sound",
          "colorHex": "#3a7ca5",
          "mp3Path": "/home/user/sounds/intro.mp3",
          "offsetSecStart": 0.0,
          "offsetSecFinish": 0.0,
          "fadeInMs": 0,
          "fadeOutMs": 0,
          "volumePct": 100,
          "hotkey": "",
          "id": "611308e0-c3fe-487b-aaf3-a03b4c132f4b"
        }
      ]
    }
  ]
}
```

## Project structure

```
soundboard-java/
├── pom.xml
└── src/main/java/com/soundboard/
    ├── Main.java                  ← entry point
    ├── MainWindow.java            ← application frame, toolbar, tabs, hotkeys
    ├── GridPanel.java             ← scrollable dotted-grid canvas
    ├── SoundButton.java           ← per-button data + paint logic
    ├── ButtonData.java            ← serialisable properties model
    ├── ButtonPropertiesDialog.java← properties editor dialog
    ├── AudioEngine.java           ← MP3 decode, DSP, playback, cache
    ├── HotkeyUtil.java            ← KeyEvent → "Ctrl+F5" string
    └── LayoutData.java            ← JSON model for save/load
```
