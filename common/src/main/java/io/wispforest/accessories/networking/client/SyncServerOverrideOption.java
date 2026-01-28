package io.wispforest.accessories.networking.client;

import io.netty.buffer.Unpooled;
import io.wispforest.accessories.Accessories;
import io.wispforest.accessories.mixin.owo.ConfigSynchronizerAccessor;
import io.wispforest.accessories.mixin.owo.OptionAccessor;
import io.wispforest.accessories.networking.AccessoriesNetworking;
import io.wispforest.accessories.utils.ServerInstanceHolder;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import io.wispforest.owo.config.ConfigWrapper;
import io.wispforest.owo.config.Option;
import io.wispforest.owo.serialization.endec.MinecraftEndecs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

import java.util.function.Consumer;

public record SyncServerOverrideOption(String configId, Option.Key optionKey, FriendlyByteBuf buf) {
    
    // Flag to prevent sending updates back to server when we're processing a server sync
    private static final ThreadLocal<Boolean> isProcessingServerSync = ThreadLocal.withInitial(() -> false);

    public static final StructEndec<SyncServerOverrideOption> ENDEC = StructEndecBuilder.of(
        Endec.STRING.fieldOf("config_id", SyncServerOverrideOption::configId),
        Endec.STRING.xmap(Option.Key::new, Option.Key::asString).fieldOf("option_key", SyncServerOverrideOption::optionKey),
        MinecraftEndecs.PACKET_BYTE_BUF.fieldOf("buf", SyncServerOverrideOption::buf),
        SyncServerOverrideOption::new
    );

    public static <T> void hookUpdate(Consumer<Consumer<T>> hook, ConfigWrapper<?> wrapper, Option.Key optionKey) {
        hook.accept(object -> sendUpdatePacket(wrapper, optionKey));
    }

    public static <T> void sendUpdatePacket(ConfigWrapper<?> wrapper, Option.Key optionKey) {
        var option = wrapper.optionForKey(optionKey);

        if (option == null) {
            Accessories.LOGGER.warn("Unable to send config value change to clients as the wrapper '{}' dose not contain the given option '{}'!", wrapper.name(), optionKey);

            return;
        }

        sendUpdatePacket(option);
    }

    public static <T> void sendUpdatePacket(Option<T> option) {
        // Don't send updates back to server if we're currently processing a server sync
        if (isProcessingServerSync.get()) {
            return;
        }
        
        var currentServer = ServerInstanceHolder.getInstance();

        if (currentServer == null) {
            // We're on a client connected to a dedicated server
            // Send the update request to the server, which will validate and broadcast back
            sendUpdatePacketToServer(option);
        } else {
            // We're on an integrated server (singleplayer/LAN) or dedicated server
            // Directly broadcast to all clients
            sendUpdatePacketDirect(currentServer, option);
        }
    }
    
    private static <T> void sendUpdatePacketToServer(Option<T> option) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        ((OptionAccessor) (Object) option).accessories$write(buf);

        var packet = new SyncServerOverrideOption(option.configName(), option.key(), buf);
        AccessoriesNetworking.sendToServer(packet);
    }

    private static <T> void sendUpdatePacketDirect(net.minecraft.server.MinecraftServer server, Option<T> option) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        ((OptionAccessor) (Object) option).accessories$write(buf);

        var packet = new SyncServerOverrideOption(option.configName(), option.key(), buf);
        AccessoriesNetworking.sendToAllPlayers(server, packet);
    }
    
    /**
     * Handles packets received on the server from clients.
     * Validates the change and broadcasts to all clients (including sender).
     */
    public static void handleServerPacket(SyncServerOverrideOption packet, Player player) {
        var wrapper = ConfigSynchronizerAccessor.KNOWN_CONFIGS().get(packet.configId);

        if (wrapper == null) {
            Accessories.LOGGER.warn("Unable to process config value change from client '{}' as the wrapper '{}' does not exist!", 
                player.getName().getString(), packet.configId());
            return;
        }

        var option = wrapper.optionForKey(packet.optionKey());

        if (option == null) {
            Accessories.LOGGER.warn("Unable to process config value change from client '{}' as the wrapper '{}' does not contain the given option '{}'!", 
                player.getName().getString(), packet.configId(), packet.optionKey());
            return;
        }

        if (!option.detached()) {
            Accessories.LOGGER.warn("Client '{}' attempted to change non-detached option '{}'", 
                player.getName().getString(), packet.optionKey());
            return;
        }

        // Apply the change server-side
        ((OptionAccessor) (Object) option).accessories$read(packet.buf());
        
        // Broadcast to all clients (including the sender)
        var server = player.getServer();
        if (server != null) {
            sendUpdatePacketDirect(server, option);
        }
    }

    /**
     * Handles packets received on the client from the server.
     * Applies the server's authoritative config value.
     */

    public static void handlePacket(SyncServerOverrideOption packet, Player player) {
        var wrapper = ConfigSynchronizerAccessor.KNOWN_CONFIGS().get(packet.configId);

        if (wrapper == null) {
            Accessories.LOGGER.warn("Unable to sync config value change to client as the wrapper '{}' dose not exists!", packet.configId());
            return;
        }

        var option = wrapper.optionForKey(packet.optionKey());

        if (option == null) {
            Accessories.LOGGER.warn("Unable to sync config value change to client as the wrapper '{}' dose not contain the given option '{}'!", packet.configId(), packet.optionKey());
            return;
        }

        if (!option.detached()) return;

        // Set flag to prevent sending updates back to server during sync
        isProcessingServerSync.set(true);
        try {
            ((OptionAccessor) (Object) option).accessories$read(packet.buf());
        } finally {
            // Always clear the flag, even if an exception occurs
            isProcessingServerSync.set(false);
        }
    }
}
