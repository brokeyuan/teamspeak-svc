package com.tsvoice.capabilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.tsvoice.protocol.capability.CapabilityWire;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理员配置：候选 TS/隧道 endpoint 与 TTL。全部字段严格验证
 * （host ≤253、端口 ≤0xFFFF、TTL ≤60、候选 ≤8），非法配置在加载时报错并
 * 降级为"无候选"（客户端回退原版 SVC），绝不带病运行。
 */
public final class CapabilityConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Entry(String kind, String host, int port) {
    }

    private List<CapabilityWire.Candidate> candidates = List.of();
    private int ttlSeconds = 30;
    private String serviceInstanceId = "voice-capabilities";

    public List<CapabilityWire.Candidate> candidates() {
        return candidates;
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }

    public String serviceInstanceId() {
        return serviceInstanceId;
    }

    public static CapabilityConfig load() {
        CapabilityConfig config = new CapabilityConfig();
        try {
            Path path = FabricLoader.getInstance().getConfigDir()
                    .resolve("voice-capabilities.json");
            if (!Files.exists(path)) {
                Files.writeString(path, DEFAULT_JSON);
                return config;
            }
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.has("ttlSeconds")) {
                int ttl = root.get("ttlSeconds").getAsInt();
                if (ttl < 1 || ttl > CapabilityWire.MAX_TTL_SECONDS) {
                    throw new IllegalArgumentException("ttlSeconds out of range: " + ttl);
                }
                config.ttlSeconds = ttl;
            }
            if (root.has("serviceInstanceId")) {
                config.serviceInstanceId = root.get("serviceInstanceId").getAsString();
            }
            List<CapabilityWire.Candidate> parsed = new ArrayList<>();
            if (root.has("candidates")) {
                for (var element : root.getAsJsonArray("candidates")) {
                    var entry = GSON.fromJson(element, Entry.class);
                    CapabilityWire.CandidateKind kind = switch (entry.kind() == null ? "" : entry.kind()) {
                        case "ts_endpoint" -> CapabilityWire.CandidateKind.TS_ENDPOINT;
                        case "tunnel" -> CapabilityWire.CandidateKind.TUNNEL;
                        case "svc_mc_channel" -> CapabilityWire.CandidateKind.SVC_MC_CHANNEL;
                        default -> throw new IllegalArgumentException("unknown candidate kind: " + entry.kind());
                    };
                    if (entry.host() == null || entry.host().isBlank()
                            || entry.host().length() > CapabilityWire.MAX_HOST_LENGTH) {
                        throw new IllegalArgumentException("invalid host: " + entry.host());
                    }
                    if (entry.port() < 0 || entry.port() > 0xFFFF) {
                        throw new IllegalArgumentException("invalid port: " + entry.port());
                    }
                    parsed.add(new CapabilityWire.Candidate(kind, entry.host(), entry.port(), List.of()));
                }
            }
            if (parsed.size() > CapabilityWire.MAX_CANDIDATES) {
                throw new IllegalArgumentException("too many candidates: " + parsed.size());
            }
            config.candidates = List.copyOf(parsed);
        } catch (Exception e) {
            CapabilityMod.LOGGER.error("[voice-capabilities] invalid config — serving no candidates: {}", e.getMessage());
            config.candidates = List.of();
        }
        return config;
    }

    private static final String DEFAULT_JSON = """
            {
              "ttlSeconds": 30,
              "serviceInstanceId": "voice-capabilities",
              "candidates": [
                { "kind": "ts_endpoint", "host": "voice.example.org", "port": 9987 }
              ]
            }
            """;
}
