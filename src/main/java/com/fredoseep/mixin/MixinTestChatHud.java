package com.fredoseep.mixin;

import com.fredoseep.testing.CreativeWorldTestRunner;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public class MixinTestChatHud {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void fredobot$observeCommandReply(Text message, CallbackInfo callback) {
        CreativeWorldTestRunner.chat(message);
    }
}
