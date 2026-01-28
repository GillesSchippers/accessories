package io.wispforest.accessories.utils;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServerInstanceHolder to ensure proper null handling.
 */
public class ServerInstanceHolderTest {

    private MinecraftServer mockServer;

    @BeforeEach
    public void setUp() {
        // Create a mock MinecraftServer for testing
        mockServer = Mockito.mock(MinecraftServer.class);
    }

    @AfterEach
    public void tearDown() {
        // Reset the instance to null after each test
        ServerInstanceHolder.setInstance((MinecraftServer) null);
    }

    @Test
    public void testGetInstanceWhenNull() {
        // Ensure instance is null at the start
        ServerInstanceHolder.setInstance((MinecraftServer) null);

        // Getting instance when null should return null and not throw exception
        MinecraftServer result = ServerInstanceHolder.getInstance();
        
        assertNull(result, "getInstance() should return null when server instance is not set");
    }

    @Test
    public void testGetInstanceWhenSet() {
        // Set the instance
        ServerInstanceHolder.setInstance(mockServer);

        // Getting instance should return the set instance
        MinecraftServer result = ServerInstanceHolder.getInstance();
        
        assertNotNull(result, "getInstance() should return the server instance when set");
        assertEquals(mockServer, result, "getInstance() should return the exact instance that was set");
    }

    @Test
    public void testSetInstanceWithDirectServer() {
        // Test setting instance directly with a MinecraftServer object
        ServerInstanceHolder.setInstance(mockServer);

        MinecraftServer result = ServerInstanceHolder.getInstance();
        
        assertEquals(mockServer, result, "setInstance() should correctly set the server instance");
    }

    @Test
    public void testSetInstanceWithSupplier() {
        // Test setting instance with a Supplier
        ServerInstanceHolder.setInstance(() -> mockServer);

        MinecraftServer result = ServerInstanceHolder.getInstance();
        
        assertEquals(mockServer, result, "setInstance() with Supplier should correctly set the server instance");
    }

    @Test
    public void testSetInstanceToNullAfterBeingSet() {
        // First set the instance
        ServerInstanceHolder.setInstance(mockServer);
        assertNotNull(ServerInstanceHolder.getInstance());

        // Then set it to null
        ServerInstanceHolder.setInstance((MinecraftServer) null);
        
        MinecraftServer result = ServerInstanceHolder.getInstance();
        assertNull(result, "getInstance() should return null after being set to null");
    }

    @Test
    public void testMultipleSetAndGetCalls() {
        // Test multiple set and get operations
        ServerInstanceHolder.setInstance((MinecraftServer) null);
        assertNull(ServerInstanceHolder.getInstance());

        ServerInstanceHolder.setInstance(mockServer);
        assertNotNull(ServerInstanceHolder.getInstance());

        MinecraftServer anotherMockServer = Mockito.mock(MinecraftServer.class);
        ServerInstanceHolder.setInstance(anotherMockServer);
        assertEquals(anotherMockServer, ServerInstanceHolder.getInstance());

        ServerInstanceHolder.setInstance((MinecraftServer) null);
        assertNull(ServerInstanceHolder.getInstance());
    }
}
