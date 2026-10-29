package com.soundboard;

import javazoom.jl.decoder.*;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Audio engine with disk-based cache.
 *
 * Cache structure:
 *   ~/.soundboard-cache/<tab-uuid>/<button-uuid>.<ext>
 *   where <ext> is "wav" or "mp3" depending on AppSettings.cacheCodec.
 *
 * Pipeline (first play of a button):
 *   MP3 file  →  JLayer decode  →  raw PCM
 *             →  DSP (offset / volume / fade-in / fade-out)
 *             →  write to disk as WAV (stdlib) or MP3 (ffmpeg)
 *             →  play
 *
 * Subsequent plays:
 *   cache file  →  javax.sound.sampled decode  →  Clip  →  play
 *
 * WAV is the default codec: lossless, zero extra dependencies, handled
 * entirely by javax.sound.sampled (built into every JDK).
 * MP3 encoding delegates to ffmpeg and falls back to WAV if unavailable.
 */
public class AudioEngine {

    private static final int BUCKETS = 200;

    private final Map<String, float[]> waveformCache = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "audio-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile String      mainDeviceName    = "";
    private volatile String      previewDeviceName = "";
    private volatile AppSettings appSettings       = new AppSettings();

    /** The most recently started Clip; may be null or already stopped. */
    private volatile Clip activeClip = null;

    // ── device config ─────────────────────────────────────────────────────────

    public void setMainDevice(String name)    { mainDeviceName    = name == null ? "" : name; }
    public void setPreviewDevice(String name) { previewDeviceName = name == null ? "" : name; }
    public void setAppSettings(AppSettings s) { appSettings       = s; }
    public String getMainDeviceName()    { return mainDeviceName; }
    public String getPreviewDeviceName() { return previewDeviceName; }

    public static List<String> availableOutputDevices() {
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(info);
                DataLine.Info test = new DataLine.Info(Clip.class,
                    new AudioFormat(44100, 16, 2, true, false));
                if (m.isLineSupported(test)) names.add(info.getName());
            } catch (Exception ignored) {}
        }
        return names;
    }

    // ── cache paths ───────────────────────────────────────────────────────────

    public Path getCacheRoot() {
        String dir = appSettings.cacheDirectory;
        if (dir == null || dir.isBlank())
            dir = System.getProperty("user.home") + "/.soundboard-cache";
        return Paths.get(dir);
    }

    public Path getCachePath(String tabId, String buttonId) throws IOException {
        Path tabDir = getCacheRoot().resolve(tabId);
        Files.createDirectories(tabDir);
        String codec = resolvedCodec();
        String ext   = codec.equals("MP3") ? "mp3" : "wav";
        return tabDir.resolve(buttonId + "." + ext);
    }

    /** "WAV" unless the setting is explicitly "MP3". */
    private String resolvedCodec() {
        String c = appSettings.cacheCodec;
        return (c != null && c.equalsIgnoreCase("MP3")) ? "MP3" : "WAV";
    }

    /**
     * A cache file is valid only if it is newer than the source file AND was
     * produced with the button's current DSP settings (start/finish offset,
     * fades, volume). The settings used are stored in a small sidecar file
     * next to the cache file, so editing a button's properties invalidates
     * its stale cache automatically.
     */
    private boolean isCacheValid(Path cachePath, File sourceFile, ButtonData d) {
        if (!Files.exists(cachePath)) return false;
        try {
            if (Files.getLastModifiedTime(cachePath).toMillis() < sourceFile.lastModified())
                return false;
            Path sig = sigPath(cachePath);
            return Files.exists(sig)
                && dspSignature(d).equals(Files.readString(sig).trim());
        } catch (Exception e) { return false; }
    }

    private static Path sigPath(Path cachePath) {
        return cachePath.resolveSibling(cachePath.getFileName() + ".dsp");
    }

    private static String dspSignature(ButtonData d) {
        return d.offsetSecStart + "|" + d.offsetSecFinish + "|"
             + d.fadeInMs + "|" + d.fadeOutMs + "|" + d.volumePct;
    }

    private void writeSignature(Path cachePath, ButtonData d) {
        try { Files.writeString(sigPath(cachePath), dspSignature(d)); }
        catch (Exception ignored) {}
    }

    // ── public playback API ───────────────────────────────────────────────────

    public void play(ButtonData d, String tabId, Consumer<String> onError) {
        playOn(d, tabId, mainDeviceName, onError);
    }

    public void preview(ButtonData d, String tabId, Consumer<String> onError) {
        String dev = previewDeviceName.isBlank() ? mainDeviceName : previewDeviceName;
        playOn(d, tabId, dev, onError);
    }

    /**
     * Encode and write the cache file without playing.
     * Silently skips if no file is assigned, the button has no ID, or the
     * cache is already valid. Also skips buttons with no DSP applied — those
     * play directly from source via {@link #playFromMP3}.
     * Errors are reported via onError.
     */
    public void preCache(ButtonData d, String tabId, Consumer<String> onError) {
        AppLogger.info("preCache method is called");
        if (d.mp3Path == null || d.mp3Path.isBlank()) return;
        File f = new File(d.mp3Path);
        if (!f.isFile() || d.id == null || d.id.isBlank()) return;
        // No DSP → source file is always used directly; nothing to cache
        if (isDspNoOp(d)) return;
        preCacheInternal(d, tabId, f, onError);
    }

    /**
     * Like {@link #preCache} but also caches buttons with no DSP applied.
     * Use this when the user wants all buttons pre-cached regardless of DSP.
     */
    public void preCacheAll(ButtonData d, String tabId, Consumer<String> onError) {
        AppLogger.info("preCacheAll method is called");
        if (d.mp3Path == null || d.mp3Path.isBlank()) return;
        File f = new File(d.mp3Path);
        if (!f.isFile() || d.id == null || d.id.isBlank()) return;
        preCacheInternal(d, tabId, f, onError);
    }

    private void preCacheInternal(ButtonData d, String tabId, File f,
                                  Consumer<String> onError) {
        pool.submit(() -> {
            try {
                Path cachePath = getCachePath(tabId, d.id);
                if (isCacheValid(cachePath, f, d)) return;
                byte[]      pcm = decodeMp3(d.mp3Path);
                AudioFormat fmt = detectFormat(d.mp3Path);
                pcm = applyDsp(pcm, fmt, d);
                if (pcm.length == 0) return;
                encodeAndCache(pcm, fmt, cachePath);
                writeSignature(cachePath, d);
            } catch (Exception ex) {
                onError.accept("Pre-cache failed for \"" + d.caption + "\": " + ex.getMessage());
            }
        });
    }

    /** Stop the active clip immediately. */
    public void stopAll() {
        Clip c = activeClip;
        if (c != null && c.isOpen()) {
            c.stop();
            c.close();
            activeClip = null;
        }
    }

    /** Pause the active clip (position is preserved). */
    public void pause() {
        Clip c = activeClip;
        if (c != null && c.isOpen() && c.isRunning()) c.stop();
    }

    /** Resume a paused clip from where it stopped. */
    public void resume() {
        Clip c = activeClip;
        if (c != null && c.isOpen() && !c.isRunning()) c.start();
    }

    /** 0.0–1.0 progress of the active clip, or -1 if nothing is playing. */
    public double getProgress() {
        Clip c = activeClip;
        if (c == null || !c.isOpen() || c.getFrameLength() <= 0) return -1;
        return (double) c.getFramePosition() / c.getFrameLength();
    }

    /**
     * Returns [elapsedSeconds, totalSeconds] for the active clip, or null if
     * nothing is loaded. Use this for time-based display (e.g. "0:12 / 1:07").
     */
    public double[] getProgressSeconds() {
        Clip c = activeClip;
        if (c == null || !c.isOpen() || c.getFrameLength() <= 0) return null;
        float rate    = c.getFormat().getFrameRate();
        double elapsed = c.getFramePosition() / rate;
        double total   = c.getFrameLength()   / rate;
        return new double[]{ elapsed, total };
    }

    /** True if a clip is currently playing (not paused, not stopped). */
    public boolean isPlaying() {
        Clip c = activeClip;
        return c != null && c.isOpen() && c.isRunning();
    }

    /** True if a clip is loaded but paused. */
    public boolean isPaused() {
        Clip c = activeClip;
        return c != null && c.isOpen() && !c.isRunning()
               && c.getFramePosition() > 0
               && c.getFramePosition() < c.getFrameLength();
    }
    public void clearCache() {
        try {
            Path root = getCacheRoot();
            if (Files.exists(root))
                Files.walk(root)
                     .sorted(Comparator.reverseOrder())
                     .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
        waveformCache.clear();
    }
    public void shutdown() { pool.shutdownNow(); }

    // ── internal playback ─────────────────────────────────────────────────────

    /**
     * True when the button's DSP settings are all at their neutral values:
     * no offset, no fades, volume at 100 %.
     * In that case the source file can be streamed directly without any
     * encode/decode round-trip through the cache.
     */
    private static boolean isDspNoOp(ButtonData d) {
        return d.offsetSecStart == 0.0 
            && d.offsetSecFinish == 0.0
            && d.fadeInMs  == 0
            && d.fadeOutMs == 0
            && d.volumePct == 100;
    }

    private void playOn(ButtonData d, String tabId, String deviceName,
                        Consumer<String> onError) {
        if (d.mp3Path == null || d.mp3Path.isBlank()) {
            onError.accept("No audio file assigned to this button."); return;
        }
        File f = new File(d.mp3Path);
        if (!f.isFile()) { onError.accept("File not found:\n" + d.mp3Path); return; }

		stopAll();

        Path cachePath;
        try { cachePath = getCachePath(tabId, d.id); }
        catch (IOException ex) {
            onError.accept("Could not create cache directory: " + ex.getMessage()); return;
        }


        
        // ── Cache path: DSP is active ─────────────────────────────────────────
        if (d.id == null || d.id.isBlank()) {
            onError.accept("Button has no ID (internal error)."); return;
        }



        if (isCacheValid(cachePath, f, d)) {
            pool.submit(() -> {
                try { playFromCache(cachePath, deviceName, onError); }
                catch (Exception ex) { onError.accept(ex.getMessage()); }
            });
            return;
        }
        else if (isDspNoOp(d)) {
            playFromMP3(d.mp3Path, deviceName, onError);
            return;
            // ── Direct path: no DSP needed — play the source file via JLayer ────────
			// AudioSystem cannot decode MP3 natively (no SPI registered), so we
			// always route through JLayer and skip the cache entirely.

        }


        // Slow path: decode → DSP → write cache → play
        pool.submit(() -> {
            try {
                byte[]      pcm = decodeMp3(d.mp3Path);
                AudioFormat fmt = detectFormat(d.mp3Path);
                pcm = applyDsp(pcm, fmt, d);
                if (pcm.length == 0) {
                    onError.accept("Start/Finish offset leaves no audio. Check that "
                        + "Start is before Finish and inside the file length."); return;
                }
                encodeAndCache(pcm, fmt, cachePath);
                writeSignature(cachePath, d);
                playFromCache(cachePath, deviceName, onError);
            } catch (Exception ex) { onError.accept(ex.getMessage()); }
        });
    }

    // ── encode & cache ────────────────────────────────────────────────────────

    private void encodeAndCache(byte[] pcm, AudioFormat fmt, Path cachePath)
            throws Exception {
		AppLogger.info("encodeAndCache method is called");
        if (resolvedCodec().equals("MP3")) {
            if (!encodeToMp3(pcm, fmt, cachePath)) {
                // ffmpeg unavailable — fall back to WAV at the .wav path
                Path wavPath = cachePath.resolveSibling(
                    cachePath.getFileName().toString().replaceFirst("\\.mp3$", ".wav"));
                writePcmAsWav(pcm, fmt, wavPath);
            }
        } else {
            writePcmAsWav(pcm, fmt, cachePath);
        }
    }

    /**
     * Write raw signed 16-bit LE PCM bytes as a proper WAV file using
     * javax.sound.sampled — no extra dependencies required.
     */
    private static void writePcmAsWav(byte[] pcm, AudioFormat fmt, Path dest)
            throws Exception {
        AudioInputStream ais = new AudioInputStream(
            new ByteArrayInputStream(pcm), fmt, pcm.length / fmt.getFrameSize());
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, dest.toFile());
    }

    /**
     * Encode PCM to MP3 via ffmpeg subprocess.
     * Returns true on success; false if ffmpeg is absent or fails (caller falls
     * back to WAV).
     */
    private static boolean encodeToMp3(byte[] pcm, AudioFormat fmt, Path dest) {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp.wav");
        try {
            writePcmAsWav(pcm, fmt, tmp);
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", tmp.toString(), "-q:a", "5", "-y", dest.toString());
            pb.redirectErrorStream(true);
            int exit = pb.start().waitFor();
            Files.deleteIfExists(tmp);
            return exit == 0 && Files.exists(dest);
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            return false;
        }
    }

    // ── play directly from MP3 file ──────────────────────────────────────────

    /**
     * Decode an MP3 file via JLayer and play it immediately, bypassing the
     * disk cache entirely.  Mirrors {@link #playFromCache} but accepts a raw
     * file path instead of a pre-encoded cache file.
     *
     * <p>Use this when no DSP processing is needed and you want to avoid the
     * encode/cache round-trip (e.g. offset=0, fades=0, volume=100 %).
     *
     * @param mp3Path    absolute path to the MP3 (or any format JLayer decodes)
     * @param deviceName output mixer name, or blank for the system default
     * @param onError    called on the calling thread with a human-readable message
     */
    public void playFromMP3(String mp3Path, String deviceName,
                            Consumer<String> onError) {
        if (mp3Path == null || mp3Path.isBlank()) {
            onError.accept("No audio file path provided."); return;
        }
        File f = new File(mp3Path);
        if (!f.isFile()) { onError.accept("File not found:\n" + mp3Path); return; }

        pool.submit(() -> {
            try {
                playStreamingMp3(mp3Path, deviceName, onError);
            } catch (Exception ex) {
                onError.accept("Playback error: " + ex.getMessage());
            }
        });
    }

    /**
     * Stream-decode an MP3 file and open it as a Clip without pre-buffering
     * the full PCM. Clip.open(AudioInputStream) reads ahead just enough to
     * start, so the first sound is heard almost immediately.
     */
    private void playStreamingMp3(String mp3Path, String deviceName,
                                   Consumer<String> onError) throws Exception {
        AudioFormat fmt = detectFormat(mp3Path);
        // AudioSystem.NOT_SPECIFIED tells the Clip the length is unknown —
        // it will read from the stream as it plays.
        Mp3InputStream mp3In = new Mp3InputStream(
            new BufferedInputStream(new FileInputStream(mp3Path)));
        AudioInputStream ais = new AudioInputStream(mp3In, fmt,
            AudioSystem.NOT_SPECIFIED);

        Clip clip = openClip(fmt, deviceName, onError);
        if (clip == null) { mp3In.close(); return; }
        clip.open(ais);          // buffers only what it needs to start
        clip.setFramePosition(0);
        activeClip = clip;
        clip.start();
        clip.addLineListener(ev -> {
            if (ev.getType() == LineEvent.Type.STOP) {
                if (activeClip == clip) activeClip = null;
                try { clip.close(); } catch (Exception ignored) {}
                try { mp3In.close(); } catch (Exception ignored) {}
            }
        });
    }

    // ── play PCM directly (no cache) ─────────────────────────────────────────

    /**
     * Open a Clip directly from raw signed 16-bit LE PCM bytes and start it.
     * Used by the no-DSP direct path so AudioSystem never sees an MP3 file.
     */
    private void playPcm(byte[] pcm, AudioFormat fmt, String deviceName,
                         Consumer<String> onError) throws LineUnavailableException {
        Clip clip = openClip(fmt, deviceName, onError);
        if (clip == null) return;
        try {
            clip.open(fmt, pcm, 0, pcm.length);
        } catch (LineUnavailableException ex) {
            onError.accept("Could not open audio line: " + ex.getMessage());
            try { clip.close(); } catch (Exception ignored) {}
            return;
        }
        clip.setFramePosition(0);
        activeClip = clip;
        clip.start();
        clip.addLineListener(ev -> {
            if (ev.getType() == LineEvent.Type.STOP) {
                if (activeClip == clip) activeClip = null;
                try { clip.close(); } catch (Exception ignored) {}
            }
        });
    }

    // ── play from cache ───────────────────────────────────────────────────────

    private void playFromCache(Path cachePath, String deviceName,
                               Consumer<String> onError) throws Exception {
        AppLogger.info("playFromCache method is called");
        // If the requested file doesn't exist but the WAV fallback does, use that
        if (!Files.exists(cachePath)) {
            Path wavFallback = cachePath.resolveSibling(
                cachePath.getFileName().toString().replaceFirst("\\.[^.]+$", ".wav"));
            if (Files.exists(wavFallback)) cachePath = wavFallback;
            else { onError.accept("Cache file missing: " + cachePath); return; }
        }

        try (AudioInputStream raw = AudioSystem.getAudioInputStream(cachePath.toFile())) {
            // Ensure we have a PCM format the Clip can handle
            AudioFormat srcFmt = raw.getFormat();
            AudioFormat pcmFmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                srcFmt.getSampleRate(), 16,
                srcFmt.getChannels(),
                srcFmt.getChannels() * 2,
                srcFmt.getSampleRate(), false);

            AudioInputStream pcmStream = AudioSystem.isConversionSupported(pcmFmt, srcFmt)
                ? AudioSystem.getAudioInputStream(pcmFmt, raw)
                : raw;

            Clip clip = openClip(pcmFmt, deviceName, onError);
            if (clip == null) return;
            clip.open(pcmStream);
            clip.setFramePosition(0);
            activeClip = clip;
            clip.start();
            clip.addLineListener(ev -> {
                if (ev.getType() == LineEvent.Type.STOP) {
                    if (activeClip == clip) activeClip = null;
                    try { clip.close(); } catch (Exception ignored) {}
                }
            });
        }
    }

    private static Clip openClip(AudioFormat fmt, String deviceName,
                                  Consumer<String> onError) {
        try {
            DataLine.Info info = new DataLine.Info(Clip.class, fmt);
            if (deviceName == null || deviceName.isBlank()) {
                if (!AudioSystem.isLineSupported(info)) {
                    onError.accept("Default audio output is not supported."); return null;
                }
                return (Clip) AudioSystem.getLine(info);
            }
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(deviceName)) {
                    Mixer m = AudioSystem.getMixer(mi);
                    if (m.isLineSupported(info)) return (Clip) m.getLine(info);
                }
            }
            // Named device not found — fall back to default
            onError.accept("Device \"" + deviceName + "\" not found — using default.");
            return (Clip) AudioSystem.getLine(info);
        } catch (LineUnavailableException ex) {
            onError.accept("Could not open audio line: " + ex.getMessage());
            return null;
        }
    }

    // ── DSP ───────────────────────────────────────────────────────────────────

    private static byte[] applyDsp(byte[] pcm, AudioFormat fmt, ButtonData d) {
        pcm = applyOffset (pcm, fmt, d.offsetSecStart, d.offsetSecFinish);
        pcm = applyVolume (pcm, d.volumePct);
        pcm = applyFadeIn (pcm, fmt, d.fadeInMs);
        pcm = applyFadeOut(pcm, fmt, d.fadeOutMs);
        return pcm;
    }

    /**
     * Extracts the part of the audio between the start and finish offsets.
     * secStart  : seconds from the beginning (0 = from the beginning).
     * secFinish : absolute end position in seconds (0 = to the end of the file).
     * Both are clamped to the file length. Returns an empty array if the
     * resulting range contains no audio.
     */
    private static byte[] applyOffset(byte[] pcm, AudioFormat fmt,
                                      double secStart, double secFinish) {
        int  frameSize   = fmt.getFrameSize();
        long totalFrames = pcm.length / frameSize;
        long startF = secStart  > 0 ? Math.min(totalFrames,
                          Math.round(secStart  * fmt.getSampleRate())) : 0;
        long endF   = secFinish > 0 ? Math.min(totalFrames,
                          Math.round(secFinish * fmt.getSampleRate())) : totalFrames;
        if (startF == 0 && endF == totalFrames) return pcm;
        if (startF >= endF) return new byte[0];
        return Arrays.copyOfRange(pcm, (int) (startF * frameSize), (int) (endF * frameSize));
    }

    private static byte[] applyVolume(byte[] pcm, int pct) {
        if (pct == 100) return pcm;
        double gain = pct / 100.0;
        byte[] out  = new byte[pcm.length];
        for (int i = 0; i+1 < pcm.length; i += 2) {
            short s = (short)(((pcm[i+1] & 0xFF) << 8) | (pcm[i] & 0xFF));
            s = clamp((long)(s * gain));
            out[i] = (byte)(s & 0xFF); out[i+1] = (byte)((s >> 8) & 0xFF);
        }
        return out;
    }

    private static byte[] applyFadeIn(byte[] pcm, AudioFormat fmt, int ms) {
        if (ms <= 0) return pcm;
        byte[] out    = pcm.clone();
        int    frames = Math.min((int)(ms / 1000.0 * fmt.getSampleRate()),
                                 pcm.length / fmt.getFrameSize());
        for (int i = 0; i < frames; i++) {
            double gain = (double) i / frames;
            for (int ch = 0; ch < fmt.getChannels(); ch++) {
                int idx = i * fmt.getFrameSize() + ch * 2;
                if (idx+1 >= out.length) break;
                short s = (short)(((out[idx+1] & 0xFF) << 8) | (out[idx] & 0xFF));
                s = clamp((long)(s * gain));
                out[idx] = (byte)(s & 0xFF); out[idx+1] = (byte)((s >> 8) & 0xFF);
            }
        }
        return out;
    }

    private static byte[] applyFadeOut(byte[] pcm, AudioFormat fmt, int ms) {
        if (ms <= 0) return pcm;
        byte[] out = pcm.clone();
        int totalF = pcm.length / fmt.getFrameSize();
        int fadeF  = Math.min((int)(ms / 1000.0 * fmt.getSampleRate()), totalF);
        int startF = totalF - fadeF;
        for (int i = startF; i < totalF; i++) {
            double gain = (double)(totalF - i) / fadeF;
            for (int ch = 0; ch < fmt.getChannels(); ch++) {
                int idx = i * fmt.getFrameSize() + ch * 2;
                if (idx+1 >= out.length) break;
                short s = (short)(((out[idx+1] & 0xFF) << 8) | (out[idx] & 0xFF));
                s = clamp((long)(s * gain));
                out[idx] = (byte)(s & 0xFF); out[idx+1] = (byte)((s >> 8) & 0xFF);
            }
        }
        return out;
    }

    private static short clamp(long v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    // ── waveform ──────────────────────────────────────────────────────────────

    public float[] getWaveform(String path, Runnable onReady) {
        float[] cached = waveformCache.get(path);
        if (cached != null) return cached;
        pool.submit(() -> {
            try {
                float[] peaks = buildPeaks(decodeMp3(path));
                waveformCache.put(path, peaks);
                onReady.run();
            } catch (Exception ignored) {}
        });
        return null;
    }

    private static float[] buildPeaks(byte[] pcm) {
        int samples = pcm.length / 2;
        int bsize   = Math.max(1, samples / BUCKETS);
        List<Float> peaks = new ArrayList<>();
        for (int i = 0; i < samples; i += bsize) {
            int   end  = Math.min(i + bsize, samples);
            short peak = 0;
            for (int j = i; j < end; j++) {
                short s = (short)(((pcm[j*2+1] & 0xFF) << 8) | (pcm[j*2] & 0xFF));
                if (Math.abs(s) > Math.abs(peak)) peak = s;
            }
            peaks.add(Math.abs(peak) / 32768f);
        }
        float[] arr = new float[peaks.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = peaks.get(i);
        return arr;
    }

    // ── MP3 decode (JLayer) ───────────────────────────────────────────────────

    private static byte[] decodeMp3(String path) throws Exception {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(path))) {
            Bitstream bs  = new Bitstream(fis);
            Decoder   dec = new Decoder();
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            Header h;
            while ((h = bs.readFrame()) != null) {
                SampleBuffer buf   = (SampleBuffer) dec.decodeFrame(h, bs);
                short[]      samp  = buf.getBuffer();
                int          count = buf.getBufferLength();
                byte[]       bytes = new byte[count * 2];
                for (int i = 0; i < count; i++) {
                    bytes[i*2]   = (byte) (samp[i] & 0xFF);
                    bytes[i*2+1] = (byte)((samp[i] >> 8) & 0xFF);
                }
                out.write(bytes, 0, bytes.length);
                bs.closeFrame();
            }
            return out.toByteArray();
        }
    }

    private static AudioFormat detectFormat(String path) throws Exception {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(path))) {
            Bitstream bs = new Bitstream(fis);
            Header    h  = bs.readFrame();
            if (h == null) throw new Exception("Could not read MP3 header");
            int sampleRate = (int) h.frequency();
            int channels   = h.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
            bs.closeFrame();
            return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, 16, channels, channels * 2, sampleRate, false);
        }
    }

    // ── streaming MP3 → PCM InputStream ──────────────────────────────────────

    /**
     * An InputStream that decodes MP3 frames on-demand via JLayer.
     * This lets us open a Clip without pre-decoding the entire file — the
     * first bytes are available almost instantly, so playback starts with
     * no perceptible lag.
     */
    private static final class Mp3InputStream extends InputStream {

        private final Bitstream            bitstream;
        private final Decoder              decoder;
        private       byte[]               frameBuf = new byte[0];
        private       int                  framePos = 0;
        private       boolean              done     = false;

        Mp3InputStream(InputStream source) throws BitstreamException {
            this.bitstream = new Bitstream(source);
            this.decoder   = new Decoder();
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return (read(b, 0, 1) == -1) ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (done) return -1;
            int written = 0;
            while (written < len) {
                // Drain the current frame buffer first
                if (framePos < frameBuf.length) {
                    int copy = Math.min(len - written, frameBuf.length - framePos);
                    System.arraycopy(frameBuf, framePos, buf, off + written, copy);
                    framePos += copy;
                    written  += copy;
                    continue;
                }
                // Decode the next frame
                try {
                    Header h = bitstream.readFrame();
                    if (h == null) { done = true; break; }
                    SampleBuffer sb    = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                    short[]      samp  = sb.getBuffer();
                    int          count = sb.getBufferLength();
                    frameBuf = new byte[count * 2];
                    for (int i = 0; i < count; i++) {
                        frameBuf[i*2]   = (byte) (samp[i] & 0xFF);
                        frameBuf[i*2+1] = (byte)((samp[i] >> 8) & 0xFF);
                    }
                    framePos = 0;
                    bitstream.closeFrame();
                } catch (Exception ex) {
                    done = true;
                    break;
                }
            }
            return (written == 0 && done) ? -1 : written;
        }

        @Override
        public void close() {
            try { bitstream.close(); } catch (Exception ignored) {}
        }
    }
}
