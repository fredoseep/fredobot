package com.fredoseep.utils.prenether;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.RelevantDirectionHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BucketItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.CallbackI;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class NetherPortalBuilding {
    private static LinkedHashSet<BlockPos> twoByOneAreaClearPos = new LinkedHashSet<>();
    private static LinkedHashSet<BlockPos> missingObiPosList = new LinkedHashSet<>();
    private static BlockHitResult topHitPosHitResult = null;
    private static BlockPos magmaSideMiddleFragmentPos = null;
    private static BlockPos alignedSideMiddleFragmentPos = null;

    public static void resetState() {
        currentTBOState = TwoByOneBuildState.IDLE;
        topHitPosHitResult = null;
        magmaSideMiddleFragmentPos = null;
        alignedSideMiddleFragmentPos = null;
        twoByOneAreaClearPos.clear();
        missingObiPosList.clear();
    }

    private enum TwoByOneBuildState {
        IDLE, CLEARING_AREA, SETTING_UP_HIT_POINT, BUILDING, SEALING_SPACE,WATER_MOVING,CLEARING_SPACE,FORCING_LIGHTER;
    }

    private static TwoByOneBuildState currentTBOState = TwoByOneBuildState.IDLE;

    public static void twoByOneBuild() {
        MinecraftClient client = MinecraftClient.getInstance();
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), true);
        switch (currentTBOState) {
            case IDLE:
                if (PreNether.alignedMagmaPos == null) {
                    globalExecutor.resetWorld();
                    System.out.println("Fredodebug: reset because alignedMagmaPos is null somehow");
                } else System.out.println("Fredodebug: alignedMagmaPos : " + PreNether.alignedMagmaPos.toShortString());
                currentTBOState = TwoByOneBuildState.CLEARING_AREA;
                break;
            case CLEARING_AREA:
                if (areaNotClear()) {
                    if (twoByOneAreaClearPos.isEmpty()) {
                        System.out.println("Fredodebug: twoByOneAreaClearPos is empty ");
                        return;
                    }
                    System.out.println("Fredodebug: twoByOneAreaClearPos: "+ twoByOneAreaClearPos.toString());
                    BlockPos currentPos = twoByOneAreaClearPos.getFirst();
                    ToolsHelper.equipBestTool(client.player, currentPos, false);
                    System.out.println("Fredodubug: clearing area Pos: "+ currentPos.toShortString()+" state: "+ client.interactionManager.updateBlockBreakingProgress(currentPos, Direction.UP));
                    return;
                }
                currentTBOState = TwoByOneBuildState.SETTING_UP_HIT_POINT;
                break;
            case SETTING_UP_HIT_POINT:
                Direction topHitPosRelevantDirection = RelevantDirectionHelper.getIrrelevantDirections(PreNether.magmaPos, PreNether.alignedMagmaPos)[0];
                BlockPos topHitPos = PreNether.alignedMagmaPos.up().up().offset(topHitPosRelevantDirection);
                topHitPosHitResult = new BlockHitResult(Vec3d.ofCenter(topHitPos), topHitPosRelevantDirection.getOpposite(), topHitPos, false);
                boolean topHitPosHittable = RelevantDirectionHelper.isValidHitResult(client.player, client.world, topHitPosHitResult);
                if (topHitPosHittable) {
                    currentTBOState = TwoByOneBuildState.BUILDING;
                    return;
                }
                client.player.inventory.selectedSlot = 6;
                BlockHitResult downBlockHitResult = new BlockHitResult(Vec3d.ofCenter(topHitPos.down()), Direction.UP, topHitPos.down(), false);
                boolean downHitPosHittable = RelevantDirectionHelper.isValidHitResult(client.player, client.world, downBlockHitResult);
                if (downHitPosHittable) {
                    System.out.println("Fredodebug: top Block gap but Placeable trying to place the block: " + client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, downBlockHitResult));
                } else {
                    System.out.println("Fredodebug: downHitPosInvalid, trying to place the block pos: "+topHitPos.down(1).toShortString()+" state: " + client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(topHitPos.down(2)), Direction.UP, topHitPos.down(2), false)));
                }
                return;

            case BUILDING:
                InventoryHelper.moveItemToHotbar(client, client.player, BucketItem.class, 3);
                BlockPos missingObiPos = getMissingObiPos();
                if (missingObiPos == null) {
                    System.out.println("Fredodebug: no obi place is missing . Clearing Space");
                    currentTBOState = TwoByOneBuildState.CLEARING_SPACE;
                    return;
                }
                BlockPos lavaSourcePos = findLavaSource();
                if (lavaSourcePos == null) {
                    globalExecutor.resetWorld();
                    System.out.println("Fredodebug: reset because lava is not enough");
                    return;
                }
                if (client.player.inventory.getStack(3).getItem() == Items.BUCKET) {
                    System.out.println("Fredobotdebug: lava grab isSuccess: " + grabLava(client, client.player, lavaSourcePos));
                    return;
                }
                System.out.println("Fredodebug: currentMissingPos: "+ missingObiPos.toShortString());
                System.out.println("FREdodebug: magmaSideMiddleFragmentPos: "+magmaSideMiddleFragmentPos.toShortString()+" magmaPos: "+ PreNether.magmaPos.toShortString()+" alignedPos: "+ PreNether.alignedMagmaPos.toShortString()+" alignedMiddleMagmaPos: "+ alignedSideMiddleFragmentPos.toShortString());
                if (missingObiPos.equals(magmaSideMiddleFragmentPos.up())) {
                    System.out.println("Fredobotdebug: 倒岩浆1 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, magmaSideMiddleFragmentPos, Direction.UP));
                } else if (missingObiPos.equals(PreNether.alignedMagmaPos.up(2))) {
                    // 这个是用到了之前的 topHitPosHitResult 的方向
                    System.out.println("Fredobotdebug: 倒岩浆2 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, topHitPosHitResult.getBlockPos(), topHitPosHitResult.getSide()));
                }
                else if (missingObiPos.equals(PreNether.magmaPos.up(2))) {
                    Direction dir = RelevantDirectionHelper.getDirectionBetween(PreNether.alignedMagmaPos, PreNether.magmaPos);
                    System.out.println("Fredobotdebug: 倒岩浆3 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, PreNether.alignedMagmaPos.up(2), dir));
                }
                else if (missingObiPos.equals(alignedSideMiddleFragmentPos.up())) {
                    System.out.println("Fredobotdebug: 倒岩浆4 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, alignedSideMiddleFragmentPos, Direction.UP));
                }
                else if (missingObiPos.equals(magmaSideMiddleFragmentPos.down())) {
                    System.out.println("Fredobotdebug: 倒岩浆5 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, magmaSideMiddleFragmentPos, Direction.DOWN));
                }
                else if (missingObiPos.equals(alignedSideMiddleFragmentPos.down())) {
                    System.out.println("Fredobotdebug: 倒岩浆6 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, alignedSideMiddleFragmentPos, Direction.DOWN));
                }
                else if (missingObiPos.equals(PreNether.magmaPos.down(2))) {
                    System.out.println("Fredobotdebug: 倒岩浆7 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, PreNether.magmaPos.down(3), Direction.UP));
                }
                else if (missingObiPos.equals(PreNether.alignedMagmaPos.down(2))) {
                    System.out.println("Fredobotdebug: 倒岩浆8 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, PreNether.alignedMagmaPos.down(3), Direction.UP));
                }
                // 下面这两个包含挖掘动作的逻辑保留不变，倒岩浆的部分替换
                else if (missingObiPos.equals(alignedSideMiddleFragmentPos)) {
                    if (client.world.getBlockState(alignedSideMiddleFragmentPos).getBlock() == Blocks.MAGMA_BLOCK) {
                        client.player.inventory.selectedSlot = 1;
                        client.interactionManager.updateBlockBreakingProgress(alignedSideMiddleFragmentPos, Direction.UP);
                    } else {
                        System.out.println("Fredobotdebug: 倒岩浆 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, alignedSideMiddleFragmentPos.up(), Direction.DOWN));
                    }
                }
                else if (missingObiPos.equals(magmaSideMiddleFragmentPos)) {
                    if (client.world.getBlockState(magmaSideMiddleFragmentPos).getBlock() == Blocks.MAGMA_BLOCK) {
                        client.player.inventory.selectedSlot = 1;
                        client.interactionManager.updateBlockBreakingProgress(magmaSideMiddleFragmentPos, Direction.UP);
                    } else {
                        System.out.println("Fredobotdebug: 倒岩浆 -> " + missingObiPos.toShortString() + " 结果: " + placeLava(client, client.player, magmaSideMiddleFragmentPos.up(), Direction.DOWN));
                    }
                }
                break;
            case SEALING_SPACE:
                List<BlockPos> leakingPos = findLeakingPos();
                if(leakingPos.isEmpty()){
                    currentTBOState = TwoByOneBuildState.WATER_MOVING;
                    return;
                }
                client.player.inventory.selectedSlot = 6;
                for(BlockPos targetPos: leakingPos){
                    BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(targetPos.down()),Direction.UP,targetPos.down(),false);
                    System.out.println("Fredobotdebug: leaking pos: " + targetPos.toShortString() + " state: " + client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, hitResult));
                }
                break;
            case WATER_MOVING:
                if(client.world.getBlockState(PreNether.alignedMagmaPos).getBlock()==Blocks.MAGMA_BLOCK){
                    client.player.inventory.selectedSlot = 1;
                    client.interactionManager.updateBlockBreakingProgress(alignedSideMiddleFragmentPos,Direction.UP);
                    return;
                }
                client.player.inventory.selectedSlot = 3;
                if(client.player.inventory.getStack(3).getItem()==Items.WATER_BUCKET){
                    BlockHitResult blockHitResult = new BlockHitResult(Vec3d.ofCenter(alignedSideMiddleFragmentPos),RelevantDirectionHelper.getDirectionBetween(PreNether.alignedMagmaPos,PreNether.magmaPos),alignedSideMiddleFragmentPos,false);
                    System.out.println("Fredobotdebug: emptyBucket pos: " + PreNether.alignedMagmaPos.toShortString() + " state: " + client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, blockHitResult));
                    return;
                }
                if(client.world.getBlockState(PreNether.magmaPos.up()).getBlock()==Blocks.WATER){
                    System.out.println("Fredobotdebug: grab isSuccess: " + grabLava(client, client.player, PreNether.magmaPos.up()));
                    return;
                }
                else if(client.world.getBlockState(PreNether.alignedMagmaPos.up()).getBlock()==Blocks.WATER){
                    System.out.println("Fredobotdebug: grab isSuccess: " + grabLava(client, client.player, PreNether.alignedMagmaPos.up()));
                    return;
                }
                else{
                    currentTBOState = TwoByOneBuildState.CLEARING_SPACE;
                }
                break;
            case CLEARING_SPACE:
                if(client.world.getBlockState(PreNether.alignedMagmaPos.down()).getMaterial().isSolid()){
                    client.player.inventory.selectedSlot = 1;
                    client.interactionManager.updateBlockBreakingProgress(PreNether.alignedMagmaPos.down(),Direction.UP);
                    return;
                }
                else if(client.world.getBlockState(PreNether.magmaPos.down()).getMaterial().isSolid()){
                    client.player.inventory.selectedSlot = 1;
                    client.interactionManager.updateBlockBreakingProgress(PreNether.magmaPos.down(),Direction.UP);
                    return;
                }
                else if(client.world.getBlockState(PreNether.magmaPos).getBlock()==Blocks.MAGMA_BLOCK){
                    client.player.inventory.selectedSlot = 1;
                    client.interactionManager.updateBlockBreakingProgress(PreNether.magmaPos,Direction.UP);
                    return;
                }
                else if(client.world.getBlockState(PreNether.alignedMagmaPos).getBlock()==Blocks.WATER){
                    client.player.inventory.selectedSlot = 3;
                    System.out.println("Fredobotdebug: grab isSuccess: " + grabLava(client, client.player, PreNether.alignedMagmaPos));
                    return;
                }
                currentTBOState = TwoByOneBuildState.FORCING_LIGHTER;
                break;
            case FORCING_LIGHTER:
                System.out.println("Fredodebug: forcingLighter...");
        }


    }
    public static boolean placeLava(MinecraftClient client, PlayerEntity player, BlockPos foundationPos, Direction placeDirection) {
        if (client == null || player == null || foundationPos == null || client.getNetworkHandler() == null) return false;

        player.inventory.selectedSlot = 3;

        Vec3d realPos = player.getPos();
        float realYaw = player.yaw;
        float realPitch = player.pitch;

        // 这里的 0.55 意味着眼睛距离方块表面只有 0.05 格
        double eyeX = foundationPos.getX() + 0.5 + placeDirection.getOffsetX() * 0.55;
        double eyeY = foundationPos.getY() + 0.5 + placeDirection.getOffsetY() * 0.55;
        double eyeZ = foundationPos.getZ() + 0.5 + placeDirection.getOffsetZ() * 0.55;

        double ghostX = eyeX;
        double ghostY = eyeY - player.getEyeHeight(player.getPose());
        double ghostZ = eyeZ;

        float ghostYaw = realYaw;
        float ghostPitch = realPitch;

        if (placeDirection == Direction.UP) {
            ghostPitch = 90.0f;
        } else if (placeDirection == Direction.DOWN) {
            ghostPitch = -90.0f;
        } else {
            ghostPitch = 0.0f;
            if (placeDirection == Direction.NORTH) ghostYaw = 0.0f;   // 面朝南看北面
            if (placeDirection == Direction.SOUTH) ghostYaw = 180.0f; // 面朝北看南面
            if (placeDirection == Direction.WEST) ghostYaw = -90.0f;  // 面朝东看西面
            if (placeDirection == Direction.EAST) ghostYaw = 90.0f;   // 面朝西看东面
        }

        // ==========================================
        // 【调试核心】：在发包前，模拟服务端的射线检测！
        // ==========================================
        Vec3d rayStart = new Vec3d(ghostX, ghostY + player.getEyeHeight(player.getPose()), ghostZ);

        // 将 Yaw 和 Pitch 转换为视线向量 (Minecraft 原版算法)
        float f = net.minecraft.util.math.MathHelper.cos(-ghostYaw * 0.017453292F - (float)Math.PI);
        float g = net.minecraft.util.math.MathHelper.sin(-ghostYaw * 0.017453292F - (float)Math.PI);
        float h = -net.minecraft.util.math.MathHelper.cos(-ghostPitch * 0.017453292F);
        float i = net.minecraft.util.math.MathHelper.sin(-ghostPitch * 0.017453292F);
        Vec3d rayDir = new Vec3d((double)(g * h), (double)i, (double)(f * h));
        Vec3d rayEnd = rayStart.add(rayDir.x * 5.0, rayDir.y * 5.0, rayDir.z * 5.0);

        net.minecraft.world.RayTraceContext context = new net.minecraft.world.RayTraceContext(
                rayStart, rayEnd,
                net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,
                net.minecraft.world.RayTraceContext.FluidHandling.NONE,
                player
        );
        net.minecraft.util.hit.BlockHitResult hitResult = client.world.rayTrace(context);

        System.out.println("====== [Lava Debug] ======");
        System.out.println("欲放岩浆支撑面: " + foundationPos.toShortString() + " 侧: " + placeDirection);
        System.out.println("幽灵坐标: " + String.format("%.2f, %.2f, %.2f", ghostX, ghostY, ghostZ));
        System.out.println("幽灵视角: Yaw=" + ghostYaw + ", Pitch=" + ghostPitch);

        // ==========================================
        // 【新增】：生成游戏内可用的 TP 调试指令 (强制使用 US Locale 避免小数点变成逗号)
        // ==========================================
        String tpCommand = String.format(java.util.Locale.US, "/tp @s %.3f %.3f %.3f %.2f %.2f", ghostX, ghostY, ghostZ, ghostYaw, ghostPitch);
        System.out.println("【硬核物理排错】复制此指令到游戏内执行: " + tpCommand);

        if (hitResult.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            System.out.println("=> 射线预测命中: " + hitResult.getBlockPos().toShortString() + " 面: " + hitResult.getSide());
            if (!hitResult.getBlockPos().equals(foundationPos)) {
                System.out.println("🚨 致命警告：射线命中的根本不是我们要点的方块！");
            }
        } else {
            System.out.println("🚨 致命警告：射线打空了 (MISS)！");
        }
        System.out.println("==========================");

        // 1. 发送瞬移包
        client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.Both(
                ghostX, ghostY, ghostZ,
                ghostYaw, ghostPitch,
                player.isOnGround()
        ));

        // 2. 发送使用物品包
        client.interactionManager.interactItem(player, client.world, net.minecraft.util.Hand.MAIN_HAND);

        // 3. 瞬间拉回
        client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.Both(
                realPos.x, realPos.y, realPos.z,
                realYaw, realPitch,
                player.isOnGround()
        ));

        return true;
    }

    private static List<BlockPos> findLeakingPos() {
        BlockPos magmaPos = PreNether.magmaPos;
        BlockPos alignedMagmaPos = PreNether.alignedMagmaPos;
        List<BlockPos> result = new ArrayList<>();
        Direction[] directions = RelevantDirectionHelper.getIrrelevantDirections(magmaPos,alignedMagmaPos);
        for(Direction offset: directions){
            if(!MinecraftClient.getInstance().world.getBlockState(magmaPos.offset(offset)).getMaterial().isSolid())result.add(magmaPos.offset(offset));
            if(!MinecraftClient.getInstance().world.getBlockState(alignedMagmaPos.offset(offset)).getMaterial().isSolid())result.add(alignedMagmaPos.offset(offset));
        }
        return result;
    }

    private static BlockPos getMissingObiPos() {
        BlockPos magmaPos = PreNether.magmaPos;
        BlockPos alignedMagmaPos = PreNether.alignedMagmaPos;
        Direction relevantDirectionFromMagmaToAligned = RelevantDirectionHelper.getDirectionBetween(magmaPos, alignedMagmaPos);
        magmaSideMiddleFragmentPos = magmaPos.offset(relevantDirectionFromMagmaToAligned.getOpposite());
        alignedSideMiddleFragmentPos = alignedMagmaPos.offset(relevantDirectionFromMagmaToAligned);
        if (missingObiPosList.isEmpty()) {
            if (!isObiFragmentSettled(magmaSideMiddleFragmentPos.up())) {
                missingObiPosList.add(magmaSideMiddleFragmentPos.up());
            }
            if (!isObiFragmentSettled(alignedSideMiddleFragmentPos.up())) {
                missingObiPosList.add(alignedMagmaPos.up());
            }
            if (!isObiFragmentSettled(alignedMagmaPos.up(2))) {
                missingObiPosList.add(alignedMagmaPos.up(2));
            }
            if (!isObiFragmentSettled(magmaPos.up(2))) {
                missingObiPosList.add(magmaPos.up(2));
            }
            if (!isObiFragmentSettled(magmaPos.down(2))) {
                missingObiPosList.add(magmaPos.down(2));
            }
            if (!isObiFragmentSettled(alignedMagmaPos.down(2))) {
                missingObiPosList.add(alignedMagmaPos.down(2));
            }
            if (!isObiFragmentSettled(magmaSideMiddleFragmentPos.down())) {
                missingObiPosList.add(magmaSideMiddleFragmentPos.down());
            }
            if (!isObiFragmentSettled(alignedSideMiddleFragmentPos.down())) {
                missingObiPosList.add(alignedSideMiddleFragmentPos.down());
            }
            if (!isObiFragmentSettled(alignedSideMiddleFragmentPos)) {
                missingObiPosList.add(alignedSideMiddleFragmentPos);
            }
            if (!isObiFragmentSettled(magmaSideMiddleFragmentPos)) {
                missingObiPosList.add(magmaSideMiddleFragmentPos);
            }
        } else {
            missingObiPosList.removeIf(NetherPortalBuilding::isObiFragmentSettled);
        }
        if (missingObiPosList.isEmpty()) return null;
        return missingObiPosList.getFirst();
    }

    private static boolean isObiFragmentSettled(BlockPos checkPos) {
        BlockState blockState = MinecraftClient.getInstance().world.getBlockState(checkPos);
        return blockState.getBlock() == Blocks.OBSIDIAN || blockState.getBlock() == Blocks.LAVA;
    }

    private static BlockPos findLavaSource() {
        BlockPos magmaPos = PreNether.magmaPos;
        BlockPos alignedMagmaPos = PreNether.alignedMagmaPos;
        if (isLavaSource(magmaPos.down())) return magmaPos.down();
        if (isLavaSource(alignedMagmaPos.down())) return alignedMagmaPos.down();
        for (Direction offset : RelevantDirectionHelper.getIrrelevantDirections(magmaPos, alignedMagmaPos)) {
            if (isLavaSource(magmaPos.down().offset(offset))) return magmaPos.down().offset(offset);
            if (isLavaSource(alignedMagmaPos.down().offset(offset))) return alignedMagmaPos.down().offset(offset);

        }
        return null;
    }

    private static boolean isLavaSource(BlockPos pos) {
        return MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == Blocks.LAVA;
    }

    private static boolean areaNotClear() {
        boolean isNotClear = false;
        if (twoByOneAreaClearPos.isEmpty()) {
            BlockPos magmaPos = PreNether.magmaPos.up();
            BlockPos alignedMagmaPos = PreNether.alignedMagmaPos.up();
            if (isNotClear(magmaPos)) {
                isNotClear = true;
                twoByOneAreaClearPos.add(magmaPos);
            }
            if (isNotClear(alignedMagmaPos)) {
                isNotClear = true;
                twoByOneAreaClearPos.add(alignedMagmaPos);
            }
            if (isNotClear(magmaPos.offset(RelevantDirectionHelper.getDirectionBetween(alignedMagmaPos, magmaPos)))) {
                isNotClear = true;
                twoByOneAreaClearPos.add(magmaPos.offset(RelevantDirectionHelper.getDirectionBetween(alignedMagmaPos, magmaPos)));
            }
            if (isNotClear(alignedMagmaPos.offset((RelevantDirectionHelper.getDirectionBetween(magmaPos, alignedMagmaPos))))) {
                isNotClear = true;
                twoByOneAreaClearPos.add(alignedMagmaPos.offset((RelevantDirectionHelper.getDirectionBetween(magmaPos, alignedMagmaPos))));
            }
            if (isNotClear(PreNether.magmaPos.down(2))) {
                isNotClear = true;
                twoByOneAreaClearPos.add(PreNether.magmaPos.down(2));
            }
            if (isNotClear(PreNether.alignedMagmaPos.down(2))) {
                isNotClear = true;
                twoByOneAreaClearPos.add((PreNether.alignedMagmaPos.down(2)));
            }
        } else {
            twoByOneAreaClearPos.removeIf(currentPos -> !isNotClear(currentPos));
            isNotClear = !twoByOneAreaClearPos.isEmpty();
        }
        System.out.println("Fredodebug: twoByOneisNotClear: "+ isNotClear);
        System.out.println("Fredodebug: twoByOneAreaClearPosList: "+ twoByOneAreaClearPos.toString());
        return isNotClear;
    }

    private static boolean isNotClear(BlockPos pos) {
        AbstractBlock.AbstractBlockState blockState = MinecraftClient.getInstance().world.getBlockState(pos);
        return blockState.getMaterial().isSolid() || blockState.getBlock().is(Blocks.KELP) || blockState.getBlock().is(Blocks.KELP_PLANT) || blockState.getBlock().is(Blocks.SEAGRASS) || blockState.getBlock().is(Blocks.TALL_SEAGRASS) || blockState.getBlock().is(Blocks.SEA_PICKLE);
    }

    public static boolean grabLava(MinecraftClient client, PlayerEntity player, BlockPos lavaPos) {
        if (client == null || player == null || lavaPos == null) return false;
        if (client.getNetworkHandler() == null) return false;
        player.inventory.selectedSlot = 3;
        Vec3d realPos = player.getPos();
        float realYaw = player.yaw;
        float realPitch = player.pitch;
        double ghostX = lavaPos.getX() + 0.5;
        double ghostY = lavaPos.getY() + 0.5 - player.getEyeHeight(player.getPose());
        double ghostZ = lavaPos.getZ() + 0.5;

        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Both(
                ghostX, ghostY, ghostZ,
                realYaw, 90.0f,
                player.isOnGround()
        ));
        client.interactionManager.interactItem(player, client.world, Hand.MAIN_HAND);
        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Both(
                realPos.x, realPos.y, realPos.z,
                realYaw, realPitch,
                player.isOnGround()
        ));

        System.out.println("FredoBot 取水/岩浆执行完毕！目标坐标 -> " + lavaPos.toShortString());
        return true;
    }

}
