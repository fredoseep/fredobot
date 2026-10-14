package com.fredoseep.mixin;

import com.fredoseep.logging.WorldSnapshotDumper;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class MixinGameMenuScreen extends Screen {

    @Shadow
    @Final
    private boolean showMenu;

    protected MixinGameMenuScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void fredobot$addSnapshotButton(CallbackInfo ci) {
        // 仅在普通暂停页面 (showMenu == true) 右下角添加 Snapshot 按钮，排除 F3+Esc 暂停 (showMenu == false)
        if (!this.showMenu || this.buttons.isEmpty()) {
            return;
        }
        int buttonWidth = 80;
        int buttonHeight = 20;
        int x = Math.max(4, this.width - buttonWidth - 8);
        int y = Math.max(4, this.height - buttonHeight - 8);
        this.addButton(new ButtonWidget(
                x,
                y,
                buttonWidth,
                buttonHeight,
                new LiteralText("Snapshot"),
                button -> WorldSnapshotDumper.captureAndSave(this.client, button)
        ));
    }
}
