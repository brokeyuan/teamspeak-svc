package com.tsvoice.protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded-lifetime, atomic single-use ticket store for tunnel authentication. */
public final class TunnelTicketStore {
    private final Map<String, Long> consumed = new ConcurrentHashMap<>();
    private volatile long lastCleanupSeconds;

    public boolean verifyAndConsume(TunnelTicket ticket, byte[] secret, long nowEpochSeconds) {
        if (ticket == null || !ticket.verify(secret, nowEpochSeconds)) {
            return false;
        }
        if (nowEpochSeconds - lastCleanupSeconds >= 30) {
            consumed.values().removeIf(expiry -> expiry < nowEpochSeconds);
            lastCleanupSeconds = nowEpochSeconds;
        }
        String nonce = java.util.HexFormat.of().formatHex(ticket.nonce());
        return consumed.putIfAbsent(nonce, ticket.expiresAtEpochSeconds()) == null;
    }

    public int size() {
        return consumed.size();
    }

    public void clear() {
        consumed.clear();
        lastCleanupSeconds = 0;
    }
}
