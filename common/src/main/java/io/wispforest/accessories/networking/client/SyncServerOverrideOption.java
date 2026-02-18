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

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;

public record SyncServerOverrideOption(String configId, Option.Key optionKey, FriendlyByteBuf buf) {
    
    // Flag to prevent sending updates back to server when we're processing a server sync
    private static final ThreadLocal<Boolean> isProcessingServerSync = ThreadLocal.withInitial(() -> false);
    
    // Queue for updates when server instance is temporarily unavailable (integrated server startup)
    private static final Queue<PendingUpdate> pendingUpdates = new LinkedList<>();
    private static final int MAX_PENDING_UPDATES = 10;
    private static final long PENDING_UPDATE_TIMEOUT_MS = 10000; // 10 seconds
    
    private record PendingUpdate(String configName, Option.Key optionKey, FriendlyByteBuf buf, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > PENDING_UPDATE_TIMEOUT_MS;
        }
    }

    public static final StructEndec<SyncServerOverrideOption> ENDEC = StructEndecBuilder.of(
        Endec.STRING.fieldOf("config_id", SyncServerOverrideOption::configId),
        Endec.STRING.xmap(Option.Key::new, Option.Key::asString).fieldOf("option_key", SyncServerOverrideOption::optionKey),
        MinecraftEndecs.FRIENDLY_BYTE_BUF.fieldOf("buf", SyncServerOverrideOption::buf),
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
            // Server instance not available - could be:
            // 1. Client connected to dedicated server (config is server-authoritative, ignore)
            // 2. Integrated server during initialization (queue and retry)
            
            // Queue the update for potential retry
            queuePendingUpdate(option);
            return;
        }

        // We're on an integrated server (singleplayer/LAN) or the actual dedicated server
        // Broadcast to all clients
        sendUpdatePacketDirect(currentServer, option);
        
        // Try to flush any pending updates now that server is available
        flushPendingUpdates(currentServer);
    }
    
    private static <T> void queuePendingUpdate(Option<T> option) {
        // Remove expired entries first
        cleanupExpiredUpdates();
        
        if (pendingUpdates.size() >= MAX_PENDING_UPDATES) {
            Accessories.LOGGER.debug("Pending update queue is full, discarding oldest update for option '{}'", option.key());
            pendingUpdates.poll();
        }
        
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        ((OptionAccessor) (Object) option).accessories$write(buf);
        
        pendingUpdates.offer(new PendingUpdate(option.configName(), option.key(), buf, System.currentTimeMillis()));
        
        Accessories.LOGGER.debug("Queued config update for option '{}' (queue size: {})", option.key(), pendingUpdates.size());
    }
    
    private static void cleanupExpiredUpdates() {
        Iterator<PendingUpdate> iterator = pendingUpdates.iterator();
        while (iterator.hasNext()) {
            PendingUpdate update = iterator.next();
            if (update.isExpired()) {
                iterator.remove();
                Accessories.LOGGER.debug("Removed expired pending update for option '{}'", update.optionKey);
            }
        }
    }
    
    /**
     * Attempts to flush pending updates when server becomes available.
     * Called automatically when sendUpdatePacket() detects a server instance.
     * Can also be called manually (e.g., on server start event).
     */
    public static void flushPendingUpdates(net.minecraft.server.MinecraftServer server) {
        if (server == null || pendingUpdates.isEmpty()) return;
        
        cleanupExpiredUpdates();
        
        int flushed = 0;
        while (!pendingUpdates.isEmpty()) {
            PendingUpdate pending = pendingUpdates.poll();
            
            try {
                var packet = new SyncServerOverrideOption(pending.configName, pending.optionKey, pending.buf);
                AccessoriesNetworking.sendToAllPlayers(server, packet);
                flushed++;
            } catch (Exception e) {
                Accessories.LOGGER.warn("Failed to flush pending config update for '{}': {}", pending.optionKey, e.getMessage());
            }
        }
        
        if (flushed > 0) {
            Accessories.LOGGER.debug("Flushed {} pending config update(s)", flushed);
        }
    }
    
    /**
     * Clears all pending updates. Useful for cleanup.
     */
    public static void clearPendingUpdates() {
        if (!pendingUpdates.isEmpty()) {
            Accessories.LOGGER.debug("Clearing {} pending config update(s)", pendingUpdates.size());
            pendingUpdates.clear();
        }
    }

    private static <T> void sendUpdatePacketDirect(net.minecraft.server.MinecraftServer server, Option<T> option) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        ((OptionAccessor) (Object) option).accessories$write(buf);

        var packet = new SyncServerOverrideOption(option.configName(), option.key(), buf);
        AccessoriesNetworking.sendToAllPlayers(server, packet);
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
