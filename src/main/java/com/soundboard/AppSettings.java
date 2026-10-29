package com.soundboard;

/**
 * Application-wide settings (serialised as part of the layout JSON,
 * or saved separately). Held as a plain data class so Gson can handle it.
 */
public class AppSettings {

    /** javax.sound.sampled Mixer name for main playback ("" = system default). */
    public String mainDeviceName    = "";

    /** Mixer name used for the Preview button ("" = same as main). */
    public String previewDeviceName = "";

    /** Dot grid colour as #rrggbb hex. */
    public String gridDotColor      = "#d8d8d8";

    /** Default colour for new buttons. */
    public String defaultButtonColor = "#3a7ca5";

    /** Cache codec: "WAV" or "MP3". WAV is native Java; MP3 uses ffmpeg if available. */
    public String cacheCodec = "WAV";

    /** Directory to store cached audio files. If empty, defaults to ~/.soundboard-cache. */
    public String cacheDirectory = "";

    /**
     * When true, buttons with no DSP (offset=0, fades=0, volume=100%) are also
     * pre-cached to disk when properties are saved. Default is true so all
     * buttons benefit from instant cache playback.
     */
    public boolean cacheNoDspButtons = true;

    /** "Run Command" section of the settings dialog: command line and its two parameters. */
    public String execCommand = "";
    public String execParam1  = "";
    public String execParam2  = "";

    public AppSettings() {}

    /** Deep copy. */
    public AppSettings copy() {
        AppSettings s = new AppSettings();
        s.mainDeviceName     = mainDeviceName;
        s.previewDeviceName  = previewDeviceName;
        s.gridDotColor       = gridDotColor;
        s.defaultButtonColor = defaultButtonColor;
        s.cacheCodec         = cacheCodec;
        s.cacheDirectory     = cacheDirectory;
        s.cacheNoDspButtons  = cacheNoDspButtons;
        s.execCommand        = execCommand;
        s.execParam1         = execParam1;
        s.execParam2         = execParam2;
        return s;
    }
}
