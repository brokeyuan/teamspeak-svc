//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.audio;

import java.util.concurrent.ConcurrentLinkedQueue;

public class AecReferenceMixer {
    /** 10ms@48k 帧。 */
    private static final int FRAME_SIZE = 480;
    /** 最多保留 0.5s 参考，超出丢最旧（避免 AEC 用到过期参考）。 */
    private static final int MAX_FRAMES = 50;
    private static final ConcurrentLinkedQueue<short[]> queue = new ConcurrentLinkedQueue<>();

    /**
     * 播放侧帧入队（AlSpeakerMixin，每 speaker 每 20ms 推 2×10ms 帧）。
     * 多说话人同时播放时按到达顺序串联；AEC 参考在此场景下为次优，单说话人时精确。
     */
    public static void push(short[] audio) {
        for (int off = 0; off + FRAME_SIZE <= audio.length; off += FRAME_SIZE) {
            short[] frame = new short[FRAME_SIZE];
            System.arraycopy(audio, off, frame, 0, FRAME_SIZE);
            queue.offer(frame);
        }
        while (queue.size() > MAX_FRAMES) queue.poll();
    }

    public static short[] poll() {
        return queue.poll();
    }

    public static void clear() {
        queue.clear();
    }
}