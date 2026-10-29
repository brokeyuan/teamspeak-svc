package com.svcb.tsbridge.ts3voice;

/**
 * TS3 语音协议异常（握手失败、协议错误等）。
 */
public class Ts3VoiceException extends Exception {

    public Ts3VoiceException(String message) {
        super(message);
    }

    public Ts3VoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
