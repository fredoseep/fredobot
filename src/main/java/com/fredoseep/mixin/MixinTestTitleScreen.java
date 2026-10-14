package com.fredoseep.mixin;

import com.fredoseep.testing.CreativeWorldTestRunner;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class MixinTestTitleScreen extends Screen {
    protected MixinTestTitleScreen() { super(new LiteralText("")); }

    @Inject(method = "init", at = @At("TAIL"))
    private void fredobot$addWorldTestButton(CallbackInfo callback) {
        ButtonWidget button = new ButtonWidget(8, height - 32, 156, 20,
                new LiteralText(CreativeWorldTestRunner.isActive() ? "停止自动测试" : "宝藏测试（10轮）"),
                pressed -> {
                    if (CreativeWorldTestRunner.isActive()) CreativeWorldTestRunner.command("/fredotest stop");
                    else CreativeWorldTestRunner.start("treasure", 10, null);
                });
        addButton(button);
    }
}
