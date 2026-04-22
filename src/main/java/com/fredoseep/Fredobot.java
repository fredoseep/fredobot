package com.fredoseep;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.prenether.PreNether;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;

public class Fredobot implements ModInitializer {

	@Override
	public void onInitialize() {
		// 注册单人/服务端测试指令 /goto <x> <y> <z>
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
			dispatcher.register(CommandManager.literal("goto")
					.then(CommandManager.argument("x", IntegerArgumentType.integer())
							.then(CommandManager.argument("y", IntegerArgumentType.integer())
									.then(CommandManager.argument("z", IntegerArgumentType.integer())
											.executes(context -> {
												int x = IntegerArgumentType.getInteger(context, "x");
												int y = IntegerArgumentType.getInteger(context, "y");
												int z = IntegerArgumentType.getInteger(context, "z");
												BlockPos target = new BlockPos(x, y, z);

												BotEngine.getInstance().start();

												BotEngine.getInstance().getModule(PathExecutor.class).setGoal(target);

												context.getSource().sendFeedback(new LiteralText("§e[服务器侧] 接收到寻路请求: " + target.toShortString()), false);
												return 1;
											})))).then(CommandManager.literal("stop").executes(context -> {

						BotEngine.getInstance().stop();

						context.getSource().sendFeedback(new LiteralText("§c[服务器侧] 机器人已彻底停止"), false);
						return 1;
					})));
		});
		CommandRegistrationCallback.EVENT.register((commandDispatcher, b) -> {
			commandDispatcher.register((CommandManager.literal("speedrun")).executes(commandContext -> {
				BotEngine.getInstance().start();
				return 1;
			}).then(CommandManager.literal("stop").executes(commandContext -> {
				BotEngine.getInstance().stop();
				return 1;
			})).then(CommandManager.literal("reset").executes(commandContext -> {
				BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
				return 1;
					}))
			);
		});
		CommandRegistrationCallback.EVENT.register((commandDispatcher, b) -> {
			commandDispatcher.register((CommandManager.literal("findravine")).executes(commandContext -> {
				BlockPos result = PreNether.findOceanRavine(MinecraftClient.getInstance().player,MinecraftClient.getInstance().world, 100);
				if(result == null) System.out.println("Fredodebug: No ravine found");
				else System.out.println(result.toShortString());
				return 1;
			}));
		});

		System.out.println("SpeedrunBot 初始化成功！");
	}
}