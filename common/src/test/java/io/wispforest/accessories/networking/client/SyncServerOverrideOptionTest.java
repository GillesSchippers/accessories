package io.wispforest.accessories.networking.client;

import io.wispforest.accessories.utils.ServerInstanceHolder;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SyncServerOverrideOption retry mechanism.
 */
public class SyncServerOverrideOptionTest {

    private MinecraftServer mockServer;

    @BeforeEach
    public void setUp() {
        mockServer = Mockito.mock(MinecraftServer.class);
        ServerInstanceHolder.setInstance((MinecraftServer) null);
        SyncServerOverrideOption.clearPendingUpdates();
    }

    @AfterEach
    public void tearDown() {
        ServerInstanceHolder.setInstance((MinecraftServer) null);
        SyncServerOverrideOption.clearPendingUpdates();
    }

    @Test
    public void testClearPendingUpdates() {
        // Ensure clearing works without errors even when queue is empty
        SyncServerOverrideOption.clearPendingUpdates();
        
        // This should not throw an exception
        assertDoesNotThrow(() -> SyncServerOverrideOption.clearPendingUpdates());
    }

    @Test
    public void testFlushPendingUpdatesWithNullServer() {
        // Flushing with null server should not throw exception
        assertDoesNotThrow(() -> SyncServerOverrideOption.flushPendingUpdates(null));
    }

    @Test
    public void testFlushPendingUpdatesWithEmptyQueue() {
        // Flushing empty queue should not throw exception
        assertDoesNotThrow(() -> SyncServerOverrideOption.flushPendingUpdates(mockServer));
    }
}
