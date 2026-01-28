package io.wispforest.accessories.utils;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.function.Supplier;

public class ServerInstanceHolder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static Supplier<@Nullable MinecraftServer> instance = () -> null;

    @Nullable
    public static MinecraftServer getInstance() {
        var server = instance.get();

        if (server == null) {
            LOGGER.warn("Unable to get current MinecraftServer instance as it has not been set yet!");
        }

        return server;
    }

    public static void setInstance(@Nullable MinecraftServer server) {
        setInstance(() -> server);
    }

    public static void setInstance(Supplier<MinecraftServer> server) {
        instance = server;
    }
}
