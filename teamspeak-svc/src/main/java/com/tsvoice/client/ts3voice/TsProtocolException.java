package com.tsvoice.client.ts3voice;

/**
 * 带结构化 TS3 错误上下文的协议异常（S0.7/R26）。
 *
 * <p>服务器以 {@code error id=<numeric> msg=<token> [extra_msg=...]} 拒绝时，
 * 数值 id 与原始 token 保留在异常上，上层分类不得再把终止性拒绝（认证/权限/
 * 封禁/服务器满）与可重试的网络超时混为一类。id 的语义映射须依真实协议资料
 * 核实后才可添加；本类只负责<b>传递</b>，不擅自解释数值。</p>
 */
public class TsProtocolException extends Ts3VoiceException {

    /** 服务器返回的原始数值错误 id；无法解析时为 -1。 */
    private final int errorCode;
    /** error 行的 msg token（如 "invalid_server_password"）；可能为 null。 */
    private final String errorMessage;
    /** error 行的 extra_msg（服务器补充文本）；可能为 null。 */
    private final String extraMessage;

    public TsProtocolException(String message, int errorCode, String errorMessage, String extraMessage) {
        super(message);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.extraMessage = extraMessage;
    }

    public int errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String extraMessage() {
        return extraMessage;
    }
}
