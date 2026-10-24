package com.fredoseep.testing.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;

public class CommandUtils {
    public static void enableCheats() {
        MinecraftClient client = MinecraftClient.getInstance();
        IntegratedServer server = client.getServer();

        if (server == null) {
            return; // 当前没有本地集成服务器，例如连接多人服务器
        }

        server.execute(() -> {
            server.getPlayerManager().setCheatsAllowed(true);

            // 重新同步指令树，让客户端能补全和识别作弊指令
            for (ServerPlayerEntity player
                    : server.getPlayerManager().getPlayerList()) {
                server.getPlayerManager().sendCommandTree(player);
            }
        });
    }
    public static void getItems(Map<Item,Integer> itemsToAsk){
        MinecraftClient client = MinecraftClient.getInstance();
        if(client==null){
            System.out.println("CommandUtils.getItems Error: client is null");
            return;
        }
        IntegratedServer server  = client.getServer();
        if(server == null){
            System.out.println("CommandUtils.getItems Error: server is null");
            return;
        }
        if(!server.getPlayerManager().areCheatsAllowed()){
            enableCheats();
        }
        itemsToAsk.forEach((key,value)->{
            String command = "/give @s "+key.getName().asString()+" "+value;
            client.execute(()->{
                client.player.sendChatMessage(command);
            });
        });

    }
}
