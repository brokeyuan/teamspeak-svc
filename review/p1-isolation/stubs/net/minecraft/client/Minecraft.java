package net.minecraft.client;

import java.util.ArrayList;
import java.util.List;

/** Recording stub: captures chat messages delivered through execute(). */
public class Minecraft {
    public static final List<Object> CHAT = new ArrayList<>();
    private static final Minecraft INSTANCE = new Minecraft();
    public Player player;

    public static Minecraft getInstance() { return INSTANCE; }
    public void execute(Runnable action) { action.run(); }
    public User getUser() { return new User(); }

    public static class User {
        public String getName() { return "Test"; }
    }

    public static class Player {
        public java.util.UUID getUUID() { return java.util.UUID.randomUUID(); }
        public void sendSystemMessage(Object msg) { CHAT.add(msg); }
    }
}
