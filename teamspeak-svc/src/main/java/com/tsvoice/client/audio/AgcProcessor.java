//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.audio;


public class AgcProcessor {
    private static final float MIN_ENVELOPE = 1.0f;

    private final int sampleRate;
    private float currentGain = 1.0f;
    private float envelope = 0f;

    private final float attackAlpha;
    private final float releaseAlpha;
    private final float envAlpha;

    public AgcProcessor(int sampleRate) {
        this.sampleRate = sampleRate;
        this.attackAlpha = (float) (1.0 - Math.exp(-1.0 / (sampleRate * 0.005)));
        this.releaseAlpha = (float) (1.0 - Math.exp(-1.0 / (sampleRate * 0.100)));
        this.envAlpha = (float) (1.0 - Math.exp(-1.0 / (sampleRate * 0.010)));
    }

    public void process(short[] audio) {
        if (audio == null || audio.length == 0) return;
        // AGC 参数（SVC-Extra 默认值）
        float tgtRms = (float) (32768.0 * Math.pow(10.0, (-3.0) / 20.0));
        float maxGain = (float) Math.pow(10.0, Math.min(30.0, 40.0) / 20.0);

        for (int i = 0; i < audio.length; i++) {
            float absVal = Math.abs(audio[i]);
            envelope += envAlpha * (absVal - envelope);
            float env = Math.max(envelope, MIN_ENVELOPE);

            float desiredGain = tgtRms / env;
            desiredGain = Math.max(0.05f, Math.min(maxGain, desiredGain));

            float alpha = (desiredGain < currentGain) ? attackAlpha : releaseAlpha;
            currentGain += alpha * (desiredGain - currentGain);

            float sample = audio[i] * currentGain;
            sample = Math.max(-32768f, Math.min(32767f, sample));
            audio[i] = (short) sample;
        }
    }

    public void reset() {
        currentGain = 1.0f;
        envelope = 0f;
    }
}
