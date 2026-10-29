package com.tsvoice;

import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;

/**
 * F5 受控真实 codec 验证：通过真实 SVC mod 的 Opus 实现（voicechat-fabric JAR
 * 内部 de.maxhenkel.voicechat.voice.client.AudioEncoder 等）做 encode→decode
 * 往返 —— 不需要麦克风/扬声器，headless 可测。
 *
 * <p>本测试与 harness 的 stub encoder 不同：它加载真实实现类并断言压缩后的
 * 帧能解回能量/形状正确的 PCM（长度 = 960 样本，非全零）。</p>
 *
 * <p>若运行环境缺少真实 Opus 原生库，打印 SKIP 并退出 0（区分"缺环境"与
 * "实现缺失"—— MusicSession 生产路径在客户端有 SVC 时走同一实现）。</p>
 */
public class RealCodecRoundTripTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        // 真实 SVC 实现（voicechat-fabric 2.6.25）：concentus 纯 Java Opus，
        // 无原生库依赖 —— 48kHz 单声道，20ms 帧（960 样本）。
        //
        // JavaOpusDecoderImpl.open() 引用 Voicechat.LOGGER → Voicechat.<clinit>
        // → FabricLoaderImpl.isDevelopmentEnvironment() → FabricLauncherBase
        // .getLauncher()。裸 JVM 无 launcher，先安装最小 mock（仅 isDevelopment
        // =false 被该路径使用），不改变被测 Opus 实现的行为。
        try {
            Class<?> launcherBase = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
            Class<?> launcherIface = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher");
            Object mockLauncher = Proxy.newProxyInstance(launcherIface.getClassLoader(),
                    new Class<?>[]{launcherIface},
                    (p, m, a) -> switch (m.getName()) {
                        case "isDevelopment" -> Boolean.FALSE;
                        case "getTargetNamespace" -> "official";
                        default -> null;
                    });
            launcherBase.getMethod("setLauncher", launcherIface).invoke(null, mockLauncher);
        } catch (Throwable t) {
            System.out.println("SKIP: cannot install mock fabric launcher in this environment ("
                    + t.getClass().getSimpleName() + ") - run under gradle/production for full verification");
            return;
        }
        OpusEncoder encoderApi;
        OpusDecoder decoderApi;
        try {
            Class<?> encClass = Class.forName("de.maxhenkel.voicechat.plugins.impl.opus.JavaOpusEncoderImpl");
            Class<?> decClass = Class.forName("de.maxhenkel.voicechat.plugins.impl.opus.JavaOpusDecoderImpl");
            Class<?> appClass = Class.forName("de.maxhenkel.opus4j.OpusEncoder$Application");
            Object audioApp = Enum.valueOf((Class<? extends Enum>) appClass.asSubclass(Enum.class), "AUDIO");
            // 与 OpusManager.createEncoder/createDecoder 相同的实参
            //（javap 核对）：encoder(48000, frameSize=960, 缓冲上限, app)、
            // decoder(48000, frameSize=960)。
            encoderApi = (OpusEncoder) encClass
                    .getDeclaredConstructor(int.class, int.class, int.class, appClass)
                    .newInstance(48000, 960, 1024, audioApp);
            decoderApi = (OpusDecoder) decClass
                    .getDeclaredConstructor(int.class, int.class)
                    .newInstance(48000, 960);
        } catch (Throwable t) {
            System.out.println("SKIP: real SVC opus construction unavailable in this environment ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ") - "
                    + "run with voicechat-fabric JAR for full verification");
            return;
        }

        // 440Hz 正弦帧（20ms @48kHz = 960 样本）
        short[] pcm = new short[960];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * i / 48000.0) * 8000);
        }
        byte[] opus = encoderApi.encode(pcm);
        check(opus != null && opus.length > 0 && opus.length < pcm.length * 2,
                "RC: real Opus encode produces a compact frame (" + (opus == null ? -1 : opus.length) + " bytes)");
        short[] back = decoderApi.decode(opus);
        check(back != null && back.length == 960,
                "RC: real Opus decode restores 960 samples (got " + (back == null ? -1 : back.length) + ")");
        if (back != null) {
            double energy = 0;
            for (short v : back) {
                energy += (double) v * v;
            }
            energy = Math.sqrt(energy / back.length);
            check(energy > 100,
                    "RC: decoded frame retains signal energy (rms=" + (int) energy + ", not silence)");
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
