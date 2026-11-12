package org.example.spi;

/**
 * Process roles for the multi-process layout (Phase 0+).
 * Concrete SPI methods grow in later phases; names document the target split.
 */
public final class ServiceRoles {

    public static final String GATEWAY = "gateway";
    public static final String MATCH = "match";
    public static final String LOGIC = "logic";
    public static final String PHYSICS = "physics";

    private ServiceRoles() {
    }
}
