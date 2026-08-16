package me.criseda.autostopper.lifecycle;

import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ServerHoldRegistryTest {

    private ServerHoldRegistry registry;

    @BeforeEach
    public void setUp() {
        registry = new ServerHoldRegistry();
    }

    @Test
    public void testHold_AddNewHold() {
        ServerMapping mapping = new ServerMapping("survival", "survival-container");
        assertTrue(registry.hold(mapping));
        assertTrue(registry.isHeld("survival"));
        assertEquals(Set.of("survival"), registry.heldServers());
    }

    @Test
    public void testHold_DuplicateHoldReturnsFalse() {
        ServerMapping mapping = new ServerMapping("survival", "survival-container");
        assertTrue(registry.hold(mapping));
        assertFalse(registry.hold(mapping));
        assertTrue(registry.isHeld("survival"));
    }

    @Test
    public void testRelease_ExistingHold() {
        ServerMapping mapping = new ServerMapping("survival", "survival-container");
        registry.hold(mapping);
        assertTrue(registry.release("survival"));
        assertFalse(registry.isHeld("survival"));
        assertTrue(registry.heldServers().isEmpty());
    }

    @Test
    public void testRelease_NonExistentHold() {
        assertFalse(registry.release("survival"));
        assertFalse(registry.isHeld("survival"));
    }

    @Test
    public void testIsHeld_NullServerName() {
        assertFalse(registry.isHeld(null));
    }

    @Test
    public void testReconcileConfig_PreservesUnchangedMapping() {
        ServerMapping survivalMapping = new ServerMapping("survival", "survival-container");
        ServerMapping creativeMapping = new ServerMapping("creative", "creative-container");
        registry.hold(survivalMapping);
        registry.hold(creativeMapping);

        ConfigSnapshot previous = new ConfigSnapshot(300, List.of(survivalMapping, creativeMapping));
        ConfigSnapshot current = new ConfigSnapshot(300, List.of(survivalMapping, creativeMapping));

        registry.reconcileConfig(previous, current);

        assertTrue(registry.isHeld("survival"));
        assertTrue(registry.isHeld("creative"));
    }

    @Test
    public void testReconcileConfig_ClearsOnMappingRemoval() {
        ServerMapping survivalMapping = new ServerMapping("survival", "survival-container");
        ServerMapping creativeMapping = new ServerMapping("creative", "creative-container");
        registry.hold(survivalMapping);
        registry.hold(creativeMapping);

        ConfigSnapshot previous = new ConfigSnapshot(300, List.of(survivalMapping, creativeMapping));
        ConfigSnapshot current = new ConfigSnapshot(300, List.of(survivalMapping));

        registry.reconcileConfig(previous, current);

        assertTrue(registry.isHeld("survival"));
        assertFalse(registry.isHeld("creative"));
    }

    @Test
    public void testReconcileConfig_ClearsOnMappingReplacement() {
        ServerMapping survivalMapping = new ServerMapping("survival", "survival-container");
        ServerMapping newSurvivalMapping = new ServerMapping("survival", "survival-container-v2");
        registry.hold(survivalMapping);

        ConfigSnapshot previous = new ConfigSnapshot(300, List.of(survivalMapping));
        ConfigSnapshot current = new ConfigSnapshot(300, List.of(newSurvivalMapping));

        registry.reconcileConfig(previous, current);

        assertFalse(registry.isHeld("survival"));
    }

    @Test
    public void testClear() {
        ServerMapping mapping1 = new ServerMapping("s1", "c1");
        ServerMapping mapping2 = new ServerMapping("s2", "c2");
        registry.hold(mapping1);
        registry.hold(mapping2);

        registry.clear();

        assertFalse(registry.isHeld("s1"));
        assertFalse(registry.isHeld("s2"));
        assertTrue(registry.heldServers().isEmpty());
    }
}
