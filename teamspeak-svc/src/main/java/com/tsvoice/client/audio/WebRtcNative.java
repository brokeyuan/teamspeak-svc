//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.audio;

import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig.NoiseSuppression.Level;
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WebRtcNative {
    private static volatile boolean nativeLoaded = false;

    private final AudioProcessing apm;
    private final AudioProcessingStreamConfig cfg;

    public WebRtcNative(int nsLevel, boolean echoCancel) {
        loadNativeOnce();
        AudioProcessing created = new AudioProcessing();
        try {
            var config = new AudioProcessingConfig();
            config.noiseSuppression.enabled = true;
            config.noiseSuppression.level = switch (nsLevel) {
                case 0 -> Level.LOW;
                case 1 -> Level.MODERATE;
                case 2 -> Level.HIGH;
                default -> Level.VERY_HIGH;
            };
            config.gainControl.enabled = false;
            config.echoCanceller.enabled = echoCancel;
            config.echoCanceller.enforceHighPassFiltering = echoCancel;
            created.applyConfig(config);
        } catch (Throwable t) {
            try { created.dispose(); } catch (Throwable ignored) {}
            throw t;
        }
        apm = created;
        cfg = new AudioProcessingStreamConfig(48000, 1);
    }

    public void process(short[] audio, int sampleRate) {
        if (apm == null) return;
        int frameSize = sampleRate / 100;
        int total = (audio.length / frameSize) * frameSize;
        if (total == 0) return;
        var c = cfg;
        for (int off = 0; off < total; off += frameSize) {
            try {
                byte[] in = new byte[frameSize * 2];
                ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().put(audio, off, frameSize);
                byte[] out = new byte[in.length];
                apm.processStream(in, c, c, out);
                ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().get(audio, off, frameSize);
            } catch (Throwable e) {
                com.tsvoice.client.TeamspeakSvcClient.LOGGER.error("webrtc process failed at {}", off, e);
            }
        }
    }

    public void close() {
        if (apm != null) try { apm.dispose(); } catch (Throwable ignored) {}
    }

    private static synchronized void loadNativeOnce() {
        if (nativeLoaded) return;
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();
            String libName, platformDir;
            if (os.contains("win")) { libName = "webrtc-java-windows-x86_64.dll"; platformDir = "windows-x86_64"; }
            else if (os.contains("mac")) {
                if (arch.contains("aarch64") || arch.contains("arm64")) { libName = "libwebrtc-java-macos-aarch64.dylib"; platformDir = "macos-aarch64"; }
                else { libName = "libwebrtc-java-macos-x86_64.dylib"; platformDir = "macos-x86_64"; }
            } else {
                if (arch.contains("aarch64") || arch.contains("arm64")) { libName = "libwebrtc-java-linux-aarch64.so"; platformDir = "linux-aarch64"; }
                else { libName = "libwebrtc-java-linux-x86_64.so"; platformDir = "linux-x86_64"; }
            }
            // 固定缓存目录：跨启动复用，避免每次连接解包几十 MB 到临时目录
            Path cacheDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("teamspeak-svc").resolve("native").resolve("webrtc-0.14.0-" + platformDir);
            Files.createDirectories(cacheDir);
            Path libPath = cacheDir.resolve(libName);
            if (!Files.exists(libPath)) {
                String jarPath = "/META-INF/jars/webrtc-java-0.14.0-" + platformDir + ".jar";
                Path tmp = Files.createTempFile(cacheDir, libName, ".tmp");
                boolean found = false;
                try (InputStream jarIs = WebRtcNative.class.getResourceAsStream(jarPath)) {
                    if (jarIs == null) throw new RuntimeException("Classifier JAR not found: " + jarPath);
                    try (ZipInputStream zis = new ZipInputStream(jarIs)) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (!entry.getName().equals(libName) && !entry.getName().endsWith("/" + libName)) continue;
                            Files.copy(zis, tmp, StandardCopyOption.REPLACE_EXISTING);
                            found = true;
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Files.deleteIfExists(tmp);
                    throw t;
                }
                if (!found) {
                    Files.deleteIfExists(tmp);
                    throw new RuntimeException(libName + " not found");
                }
                try {
                    Files.move(tmp, libPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable t) {
                    Files.deleteIfExists(tmp);
                    throw t;
                }
            }
            System.load(libPath.toAbsolutePath().toString());
            nativeLoaded = true;
        } catch (Throwable e) {
            throw new RuntimeException("WebRTC native load failed", e);
        }
    }
}
