package com.fredoseep.testing.nether;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.testing.utils.CommandUtils;
import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.LiteralText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/** Tick-driven test preparation: command permissions -> synced equipment -> actual Nether entry. */
public final class NetherStateAuto {
    private static final Logger LOGGER = LogManager.getLogger("FredoBotNetherTest");
    private static final Map<Item,Integer> itemsToAsk = Map.of(
            Items.IRON_AXE,1,Items.IRON_PICKAXE,1,Items.IRON_SHOVEL,1,
            Items.BUCKET,1,Items.COOKED_SALMON,64,Items.DIRT,128,
            Items.ACACIA_BOAT,1,Items.FLINT_AND_STEEL,1,Items.ACACIA_PLANKS,30);
    private enum Phase { IDLE, WAIT_CHEATS, WAIT_ITEMS, WAIT_NETHER, DONE }
    private static Phase phase = Phase.IDLE;
    private static int phaseTicks;

    private NetherStateAuto() {}

    public static void reset() {
        phase = Phase.IDLE;
        phaseTicks = 0;
    }

    public static void autoNether() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.getNetworkHandler() == null) return;
        IntegratedServer server = client.getServer();
        if (server == null) {
            fail(client,"地狱自动测试只支持单机世界");
            return;
        }
        if (phase == Phase.DONE) return;
        if (++phaseTicks > 2400) {
            fail(client,"地狱测试准备超时：" + phase);
            return;
        }
        switch (phase) {
            case IDLE:
                CommandUtils.enableCheats();
                enter(Phase.WAIT_CHEATS);
                return;
            case WAIT_CHEATS:
                // enableCheats runs on the server thread; do not race its permission update.
                if (!server.getPlayerManager().areCheatsAllowed()) return;
                CommandUtils.getItems(itemsToAsk);
                enter(Phase.WAIT_ITEMS);
                return;
            case WAIT_ITEMS:
                // /give changes server inventory first. Do not sort an empty client inventory.
                if (!hasEquipment(client.player)) return;
                InventoryHelper.inventoryManagement();
                if (!client.world.getDimension().hasCeiling()) {
                    CommandUtils.command("/setblock ~ ~ ~ minecraft:nether_portal");
                }
                enter(Phase.WAIT_NETHER);
                return;
            case WAIT_NETHER:
                if (!client.world.getDimension().hasCeiling()) return;
                BotEngine.getInstance().getModule(GlobalExecutor.class).isAutoNetherFinished = true;
                enter(Phase.DONE);
                return;
            case DONE:
                return;
        }
    }

    private static boolean hasEquipment(PlayerEntity player) {
        for (Map.Entry<Item,Integer> requirement : itemsToAsk.entrySet()) {
            int count = 0;
            for (int slot = 0; slot < player.inventory.size(); slot++) {
                var stack = player.inventory.getStack(slot);
                if (stack.getItem() == requirement.getKey()) count += stack.getCount();
            }
            if (count < requirement.getValue()) return false;
        }
        return true;
    }

    private static void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
        LOGGER.info("[NetherAuto] {}", next);
    }

    private static void fail(MinecraftClient client,String reason) {
        LOGGER.error("[NetherAuto] {}", reason);
        BotEngine.getInstance().stop();
        client.inGameHud.getChatHud().addMessage(new LiteralText("[Fredo 地狱测试] " + reason));
    }
}
