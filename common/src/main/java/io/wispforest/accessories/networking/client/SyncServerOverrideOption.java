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

import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;

public record SyncServerOverrideOption(String configId, Option.Key optionKey, FriendlyByteBuf buf) {

    private static final Queue<PendingUpdate> pendingUpdates = new LinkedList<>();
    private static final int MAX_PENDING_UPDATES = 100;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    
    // Flag to prevent sending updates back to server when we're processing a server sync
    private static final ThreadLocal<Boolean> isProcessingServerSync = ThreadLocal.withInitial(() -> false);

    public static final StructEndec<SyncServerOverrideOption> ENDEC = StructEndecBuilder.of(
        Endec.STRING.fieldOf("config_id", SyncServerOverrideOption::configId),
        Endec.STRING.xmap(Option.Key::new, Option.Key::asString).fieldOf("option_key", SyncServerOverrideOption::optionKey),
        MinecraftEndecs.PACKET_BYTE_BUF.fieldOf("buf", SyncServerOverrideOption::buf),
        SyncServerOverrideOption::new
    );

    private record PendingUpdate(String configName, Option.Key optionKey, FriendlyByteBuf buf, int retryCount) {}

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
            // Queue the update for retry
            queuePendingUpdate(option);
            return;
        }

        sendUpdatePacketDirect(currentServer, option);
        
        // Try to flush any pending updates now that server is available
        flushPendingUpdates(currentServer);
    }

    private static <T> void queuePendingUpdate(Option<T> option) {
        if (pendingUpdates.size() >= MAX_PENDING_UPDATES) {
            Accessories.LOGGER.warn("Pending update queue is full, discarding oldest update for option '{}'", option.key());
            pendingUpdates.poll(); // Remove oldest
        }

        var buf = new FriendlyByteBuf(Unpooled.buffer());
        ((OptionAccessor) (Object) option).accessories$write(buf);

        pendingUpdates.offer(new PendingUpdate(option.configName(), option.key(), buf, 0));
        
        Accessories.LOGGER.debug("Queued config update for option '{}' (queue size: {})", option.key(), pendingUpdates.size());
    }

    private static <T> void sendUpdatePacketDirect(net.minecraft.server.MinecraftServer server, Option<T> option) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        ((OptionAccessor) (Object) option).accessories$write(buf);

        var packet = new SyncServerOverrideOption(option.configName(), option.key(), buf);
        AccessoriesNetworking.sendToAllPlayers(server, packet);
    }

    /**
     * Attempts to flush pending updates when server becomes available.
     * Called automatically when sendUpdatePacket() detects a server instance.
     * Can also be called manually to retry pending updates.
     */
    public static void flushPendingUpdates(net.minecraft.server.MinecraftServer server) {
        if (server == null || pendingUpdates.isEmpty()) return;

        int flushed = 0;

        // Process all pending updates
        while (!pendingUpdates.isEmpty()) {
            PendingUpdate pending = pendingUpdates.poll();

            try {
                var packet = new SyncServerOverrideOption(pending.configName, pending.optionKey, pending.buf);
                AccessoriesNetworking.sendToAllPlayers(server, packet);
                flushed++;
            } catch (Exception e) {
                if (pending.retryCount < MAX_RETRY_ATTEMPTS) {
                    // Re-queue with incremented retry count
                    pendingUpdates.offer(new PendingUpdate(
                        pending.configName,
                        pending.optionKey,
                        pending.buf,
                        pending.retryCount + 1
                    ));
                    
                    Accessories.LOGGER.warn("Failed to send pending config update for '{}', will retry (attempt {}/{})",
                        pending.optionKey, pending.retryCount + 1, MAX_RETRY_ATTEMPTS);
                } else {
                    Accessories.LOGGER.error("Failed to send config update for '{}' after {} attempts, discarding",
                        pending.optionKey, MAX_RETRY_ATTEMPTS, e);
                }
            }
        }

        if (flushed > 0) {
            Accessories.LOGGER.info("Flushed {} pending config update(s) to server", flushed);
        }
    }

    /**
     * Clears all pending updates. Useful for cleanup when disconnecting.
     */
    public static void clearPendingUpdates() {
        if (!pendingUpdates.isEmpty()) {
            Accessories.LOGGER.debug("Clearing {} pending config update(s)", pendingUpdates.size());
            pendingUpdates.clear();
        }
    }

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
