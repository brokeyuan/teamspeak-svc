package com.tsvoice.client.config;

import com.tsvoice.client.connection.VoiceTransport;

/**
 * S3：四模式传输配置迁移回归 —— 旧字符串与旧 boolean 语义到明确传输类型的
 * 映射，以及"旧 tcp 只迁移为隧道、不自动变 MC 通道"的硬约束。
 */
public class S3TransportMigrationTest {
    public static void main(String[] args) {
        class Ctx { int failures; void check(boolean ok, String name) {
            System.out.println((ok ? "PASS " : "FAIL ") + name);
            if (!ok) failures++;
        }}
        Ctx c = new Ctx();
        c.check(VoiceTransport.fromConfig(null) == VoiceTransport.AUTO, "null -> AUTO");
        c.check(VoiceTransport.fromConfig("") == VoiceTransport.AUTO, "empty -> AUTO");
        c.check(VoiceTransport.fromConfig("udp") == VoiceTransport.TS_UDP, "udp -> TS_UDP");
        c.check(VoiceTransport.fromConfig("UDP") == VoiceTransport.TS_UDP, "UDP case-insensitive");
        c.check(VoiceTransport.fromConfig("tcp") == VoiceTransport.TS_TUNNEL_V1,
                "tcp -> TS_TUNNEL_V1 (legacy tcp does NOT become MC channel)");
        c.check(VoiceTransport.fromConfig("tunnel") == VoiceTransport.TS_TUNNEL_V1, "tunnel alias");
        c.check(VoiceTransport.fromConfig("mc_channel") == VoiceTransport.TS_MC_CHANNEL,
                "mc_channel -> TS_MC_CHANNEL (explicit only)");
        c.check(VoiceTransport.fromConfig("relay") == VoiceTransport.TS_MC_CHANNEL, "relay alias");
        c.check(VoiceTransport.fromConfig("garbage") == VoiceTransport.AUTO,
                "unknown value falls back to AUTO (old semantic)");
        c.check(VoiceTransport.fromLegacyTcp(true) == VoiceTransport.TS_TUNNEL_V1,
                "legacy tcp=true -> TS_TUNNEL_V1");
        c.check(VoiceTransport.fromLegacyTcp(false) == VoiceTransport.TS_UDP,
                "legacy tcp=false -> TS_UDP");
        System.out.println(c.failures == 0 ? "S3 transport migration: all checks passed"
                : c.failures + " FAILURES");
        if (c.failures > 0) System.exit(1);
    }
}
