package com.fredoseep.testing;

import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.text.Text;

/** Reusable test contract. The runner owns normal creative-world creation and batch lifecycle. */
public interface CreativeWorldScenario {
    String name();
    void start(CreativeTestContext context);
    void tick(CreativeTestContext context);
    default void onChat(CreativeTestContext context, Text message) {}
    default void onInventory(CreativeTestContext context, InventoryS2CPacket packet) {}
    default void close(CreativeTestContext context) {}
}
