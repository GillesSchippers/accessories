package io.wispforest.accessories.utils;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class ServerInstanceHolder {

    private static Supplier<@Nullable MinecraftServer> instance = () -> null;

    @Nullable
    public static MinecraftServer getInstance() {
        return instance.get();
    }

    public static void setInstance(@Nullable MinecraftServer server) {
        setInstance(() -> server);
    }

    public static void setInstance(Supplier<MinecraftServer> server) {
        instance = server;
    }
}
