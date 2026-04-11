package com.fredoseep.render;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.PathExecutor;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class PathRenderer {

    public static void renderPath(MatrixStack matrices, Camera camera) {
        PathExecutor executor = BotEngine.getInstance().getModule(PathExecutor.class);
        if (executor == null) return;

        List<SimplePathfinder.Node> path = executor.getCurrentPath();
        if (path == null || path.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Vec3d cameraPos = camera.getPos();

        RenderSystem.pushMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableTexture();
        RenderSystem.disableDepthTest(); // 关闭深度测试，后画的必然在最上层

        // 如果想让线粗一点可以取消下面这行的注释（部分 1.16 映射可能不支持）
        // RenderSystem.lineWidth(2.0F);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferBuilder = tessellator.getBuffer();

        int currentIndex = executor.getCurrentPathIndex();
        Matrix4f matrix = matrices.peek().getModel();

        // ==================================================
        // Pass 0：绘制点与点之间的连线 (GL_LINES = 1)
        // ==================================================
        bufferBuilder.begin(1, VertexFormats.POSITION_COLOR); // 1 代表绘制独立线段

        for (int i = 0; i < path.size() - 1; i++) {
            SimplePathfinder.Node nodeA = path.get(i);
            SimplePathfinder.Node nodeB = path.get(i + 1);

            // 获取起止点的绝对坐标中心相对于相机的偏移
            double cxA = nodeA.pos.getX() + 0.5D - cameraPos.x;
            double cyA = nodeA.pos.getY() + 0.5D - cameraPos.y;
            double czA = nodeA.pos.getZ() + 0.5D - cameraPos.z;

            double cxB = nodeB.pos.getX() + 0.5D - cameraPos.x;
            double cyB = nodeB.pos.getY() + 0.5D - cameraPos.y;
            double czB = nodeB.pos.getZ() + 0.5D - cameraPos.z;

            // 获取颜色
            float[] colorA = getNodeColor(nodeA, i, currentIndex);
            float[] colorB = getNodeColor(nodeB, i + 1, currentIndex);

            // 绘制顶点 A 到顶点 B 的连线 (OpenGL 会自动在两点颜色间平滑渐变)
            bufferBuilder.vertex(matrix, (float) cxA, (float) cyA, (float) czA)
                    .color(colorA[0], colorA[1], colorA[2], colorA[3]).next();
            bufferBuilder.vertex(matrix, (float) cxB, (float) cyB, (float) czB)
                    .color(colorB[0], colorB[1], colorB[2], colorB[3]).next();
        }
        tessellator.draw(); // 必须先 draw 掉连线，因为画线和画方块的模式(1 和 7)不同

        // ==================================================
        // Pass 1：绘制所有普通的下半身节点 (GL_QUADS = 7)
        // ==================================================
        bufferBuilder.begin(7, VertexFormats.POSITION_COLOR); // 7 代表绘制四边形
        for (int i = 0; i < path.size(); i++) {
            SimplePathfinder.Node node = path.get(i);
            BlockPos pos = node.pos;

            double cx = pos.getX() + 0.5D - cameraPos.x;
            double cy = pos.getY() + 0.5D - cameraPos.y;
            double cz = pos.getZ() + 0.5D - cameraPos.z;

            float s = 0.1f; // 普通节点大小
            float[] color = getNodeColor(node, i, currentIndex);

            drawCube(bufferBuilder, matrix, cx, cy, cz, s, color[0], color[1], color[2], color[3]);
        }
        tessellator.draw();

        // ==================================================
        // Pass 2：绘制所有的附加方块 (extraPos)
        // ==================================================
        bufferBuilder.begin(7, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < path.size(); i++) {
            SimplePathfinder.Node node = path.get(i);
            if (node.extraPos != null) {
                double cx = node.extraPos.getX() + 0.5D - cameraPos.x;
                double cy = node.extraPos.getY() + 0.5D - cameraPos.y;
                double cz = node.extraPos.getZ() + 0.5D - cameraPos.z;

                float s = 0.13f; // 稍微放大一点点

                // 默认亮紫色
                float r = 1.0f, g = 0.0f, b = 1.0f, a = 1.0f;
                // 如果走过了变半透明灰
                if (i < currentIndex) {
                    r = 0.5f; g = 0.5f; b = 0.5f; a = 0.3f;
                }

                drawCube(bufferBuilder, matrix, cx, cy, cz, s, r, g, b, a);
            }
        }
        tessellator.draw();

        RenderSystem.enableDepthTest();
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
        RenderSystem.popMatrix();
    }

    /**
     * 将颜色逻辑抽离，方便线和方块共用
     * 返回 float 数组: [R, G, B, Alpha]
     */
    private static float[] getNodeColor(SimplePathfinder.Node node, int nodeIndex, int currentIndex) {
        if (nodeIndex < currentIndex) {
            return new float[]{0.5f, 0.5f, 0.5f, 0.3f}; // 走过的灰色
        }

        return switch (node.state) {
            case WALKING -> new float[]{0.0f, 0.0f, 1.0f, 1.0f}; // 蓝
            case MINING -> new float[]{1.0f, 0.0f, 0.0f, 1.0f}; // 红
            case BUILDING_BRIDGE -> new float[]{0.5f, 1.0f, 0.5f, 1.0f}; // 浅绿
            case BUILDING_PILLAR -> new float[]{0.0f, 0.5f, 0.0f, 1.0f}; // 深绿
            case FALLING -> new float[]{0.0f, 1.0f, 1.0f, 1.0f}; // 青
            case JUMPING_UP -> new float[]{1.0f, 0.5f, 0.0f, 1.0f}; // 橙
            case JUMPING_AIR -> new float[]{1.0f, 1.0f, 0.0f, 1.0f}; // 黄
            case SWIMMING -> new float[]{0.0f, 0.5f, 1.0f, 1.0f}; // 浅蓝
            default -> new float[]{1.0f, 1.0f, 1.0f, 1.0f}; // 白
        };
    }

    /**
     * 将重复的画方块代码抽离出来，让主逻辑更清爽
     */
    private static void drawCube(BufferBuilder bufferBuilder, Matrix4f matrix, double cx, double cy, double cz, float s, float r, float g, float b, float a) {
        // 上面
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy + s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy + s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy + s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy + s), (float)(cz - s)).color(r, g, b, a).next();
        // 下面
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy - s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy - s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy - s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy - s), (float)(cz + s)).color(r, g, b, a).next();
        // 前面
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy + s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy + s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy - s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy - s), (float)(cz - s)).color(r, g, b, a).next();
        // 后面
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy + s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy - s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy - s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy + s), (float)(cz + s)).color(r, g, b, a).next();
        // 左面
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy + s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy - s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy - s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx - s), (float)(cy + s), (float)(cz + s)).color(r, g, b, a).next();
        // 右面
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy + s), (float)(cz - s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy + s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy - s), (float)(cz + s)).color(r, g, b, a).next();
        bufferBuilder.vertex(matrix, (float)(cx + s), (float)(cy - s), (float)(cz - s)).color(r, g, b, a).next();
    }
}