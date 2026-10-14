package com.fredoseep.mixin;

import com.fredoseep.testing.CreativeWorldTestRunner;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class MixinTestInventory {
    @Inject(method = "onInventory", at = @At("RETURN"))
    private void fredobot$observeSynchronizedInventory(InventoryS2CPacket packet, CallbackInfo callback) {
        // RETURN occurs after vanilla's main-thread dispatch and handler update.
        CreativeWorldTestRunner.inventory(packet);
    }
}
