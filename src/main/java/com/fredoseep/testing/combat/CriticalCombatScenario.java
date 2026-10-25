package com.fredoseep.testing.combat;

import com.fredoseep.testing.CreativeTestContext;
import com.fredoseep.testing.CreativeWorldScenario;
import com.fredoseep.testing.TestBatch;
import com.fredoseep.mixin.MotionClientAccess;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Real jumps and ordinary client attack packets. Probe only observes server attack/crit callbacks. */
public final class CriticalCombatScenario implements CreativeWorldScenario {
    public record Trial(String phase,boolean partial,boolean blind,boolean away,boolean attack) {}
    private enum Phase { ARRIVE, FLOOR, ARENA, SETUP, PREPARE, TRIGGER, RESULT, FINISHED }
    private record Snapshot(long tick,int id,float health,double x,double y,double z,float fall,boolean ground,
                            boolean axe,float cooldown,boolean blind) {}
    private static final class Probe {
        final UUID player;
        final List<UUID> targets;
        final ConcurrentLinkedQueue<Map<String,Object>> events=new ConcurrentLinkedQueue<>();
        Probe(UUID player,UUID target){this(player,List.of(target));}
        Probe(UUID player,List<UUID> targets){this.player=player;this.targets=List.copyOf(targets);}
    }
    private static volatile Probe active;
    /** Invoked exclusively on the server thread; immutable values cross to the scenario. */
    public static void observe(ServerPlayerEntity p,Entity target,boolean critical){observeSignal(p,target,critical?"CRIT_PARTICLES":"ATTACK_HEAD");}
    public static void observeReturn(ServerPlayerEntity p,Entity target){observeSignal(p,target,"ATTACK_RETURN");}
    private static void observeSignal(ServerPlayerEntity p,Entity target,String signal){
        Probe probe=active;if(probe==null || !probe.player.equals(p.getUuid()) || !probe.targets.contains(target.getUuid()))return;
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("target",target.getUuid().toString());
        row.put("signal",signal);row.put("playerAge",p.age);row.put("yaw",p.yaw);row.put("pitch",p.pitch);
        row.put("weapon",p.getMainHandStack().getItem().toString());
        row.put("targetPos",List.of(target.getX(),target.getY(),target.getZ()));
        row.put("targetVelocity",List.of(target.getVelocity().x,target.getVelocity().y,target.getVelocity().z));
        if(target instanceof MobEntity m)row.put("targetHealth",m.getHealth());row.put("cooldown",p.getAttackCooldownProgress(.5f));
        row.put("pos",List.of(p.getX(),p.getY(),p.getZ()));row.put("velocity",List.of(p.getVelocity().x,p.getVelocity().y,p.getVelocity().z));
        row.put("fallDistance",p.fallDistance);row.put("onGround",p.isOnGround());row.put("sprinting",p.isSprinting());
        row.put("blind",p.hasStatusEffect(StatusEffects.BLINDNESS));row.put("water",p.isTouchingWater());
        row.put("climbing",p.isClimbing());row.put("vehicle",p.hasVehicle());row.put("holdingAxe",p.getMainHandStack().getItem()==Items.IRON_AXE);
        probe.events.add(Map.copyOf(row));
    }
    /** Shared observational lease for the pursuit experiment; closing an old lease cannot clear a new one. */
    static AutoCloseable beginObservation(UUID player,List<UUID> targets,Consumer<List<Map<String,Object>>> drain){
        Probe probe=new Probe(player,targets);active=probe;
        return ()->{if(active==probe)active=null;drain.accept(List.copyOf(probe.events));};
    }
    public static List<Trial> trials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=0;repeat<2;repeat++){
            out.add(new Trial("STANDING",false,false,false,true));
            out.add(new Trial("RISING",false,false,false,true));
            out.add(new Trial("FALLING",false,false,false,true));
            out.add(new Trial("FALLING",false,false,true,true));
            out.add(new Trial("FALLING",true,false,false,true));
            out.add(new Trial("FALLING",false,true,false,true));
        }
        out.add(new Trial("STANDING",false,false,false,false));return List.copyOf(out);
    }
    private static final class JumpInput extends Input {
        int pulses;
        @Override public void tick(boolean slowDown){
            movementForward=movementSideways=0;pressingForward=pressingBack=pressingLeft=pressingRight=sneaking=false;
            jumping=pulses>0;if(pulses>0)pulses--;
        }
    }
    private final List<Trial> trials=trials();
    private final List<Map<String,Object>> results=new ArrayList<>();
    private Phase phase;
    private int index,age,waitAge;
    private volatile UUID target;
    private volatile Snapshot reply;
    private volatile boolean querying,closed;
    private volatile String error;
    private Probe probe;
    private Snapshot baseline,sent;
    private Input saved;
    private JumpInput input;
    private boolean oldAutoJump;
    @Override public String name(){return "critical";}
    private void enter(CreativeTestContext c,Phase p){phase=p;age=c.client.player.age;c.phase("CRITICAL "+(index+1)+"/"+trials.size()+" "+p);}
    private void fail(CreativeTestContext c,String reason){c.complete(TestBatch.Outcome.ERROR,reason,Map.of("case",index+1,"phase",phase.name(),"completed",results.size()));}
    @Override public void start(CreativeTestContext c){
        if(c.client.getServer()==null){c.complete(TestBatch.Outcome.ERROR,"SINGLEPLAYER_REQUIRED",Map.of());return;}
        c.event("critical_plan",Map.of("cases",trials.size(),"weapon","iron axe","target","stationary unarmored adult zombie, 1000 health, knockback resistance 1",
                "variants","standing/rising/falling/away-facing falling/partial-charge falling/blind falling; two repeats and no-attack control",
                "probe","ServerPlayerEntity.attack HEAD and addCritParticles HEAD; observational only; no server damage calls",
                "note","phase chosen using server snapshots; actual attack entry state and crit callback are authoritative"));
        c.command("/tp @s 0.5 4 0.5 -90 0");enter(c,Phase.ARRIVE);
    }
    private boolean chunks(MinecraftClient c){for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++)if(c.world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)==null)return false;return true;}
    @Override public void tick(CreativeTestContext c){
        if(c.done() || phase==Phase.FINISHED)return;
        if(error!=null){c.complete(TestBatch.Outcome.ERROR,"CRIT_SERVER_ERROR",Map.of("error",error));return;}
        if(c.phaseMillis()>30000){fail(c,"CRIT_PHASE_TIMEOUT");return;}
        if(c.client.player.age-waitAge>=40){waitAge=c.client.player.age;c.event("critical_wait",Map.of("case",index+1,"phase",phase.name(),"pos",List.of(c.client.player.getX(),c.client.player.getY(),c.client.player.getZ())));}
        switch(phase){
            case ARRIVE:
                if(!chunks(c.client) || Math.hypot(c.client.player.getX()-.5,c.client.player.getZ()-.5)>.1)return;
                c.command("/fill -10 3 -4 12 3 4 minecraft:stone");c.command("/fill -10 4 -4 12 8 4 minecraft:air");
                c.command("/time set midnight");c.command("/gamerule doDaylightCycle false");c.command("/gamerule doMobSpawning false");
                c.command("/effect clear @s");c.command("/effect give @s minecraft:saturation 100000 0 true");enter(c,Phase.FLOOR);return;
            case FLOOR:
                for(int x=-10;x<=12;x++)for(int z=-4;z<=4;z++)if(c.client.world.getBlockState(new BlockPos(x,3,z)).getBlock()!=Blocks.STONE)return;
                c.command("/gamemode survival");enter(c,Phase.ARENA);return;
            case ARENA:
                if(c.client.player.abilities.creativeMode || c.client.player.age-age<20)return;setup(c);return;
            case SETUP:
                if(c.client.player.age-age<15 || c.client.player.inventory.getStack(1).getItem()!=Items.IRON_AXE)return;
                if(!querying)query(c);if(reply==null)return;
                baseline=take();if(c.client.world.getEntityById(baseline.id())==null)return;
                if(baseline.health()<999 || Math.abs(baseline.y()-4)>.05 || !baseline.ground()
                        || Math.hypot(baseline.x()-.5,baseline.z()-.5)>.05 || baseline.blind()!=trials.get(index).blind()){
                    c.event("critical_fixture_invalid",Map.of("snapshot",baseline.toString()));fail(c,"CRIT_FIXTURE_INVALID");return;
                }
                saved=c.client.player.input;input=new JumpInput();c.client.player.input=input;
                oldAutoJump=c.client.options.autoJump;c.client.options.autoJump=false;c.client.options.keySprint.setPressed(false);
                c.client.player.setSprinting(false);((MotionClientAccess)(Object)c.client.player).fredobot$setSprintWindow(0);
                if(!trials.get(index).partial())selectAxe(c);
                enter(c,Phase.PREPARE);return;
            case PREPARE:
                if(c.client.player.age-age<35)return;
                if(!trials.get(index).phase().equals("STANDING"))input.pulses=1;
                if(trials.get(index).partial())selectAxe(c);
                enter(c,Phase.TRIGGER);return;
            case TRIGGER:
                if(c.client.player.age-age>30){fail(c,"CRIT_JUMP_WINDOW_MISSED");return;}
                if(!querying)query(c);if(reply==null)return;
                Snapshot o=take();Trial t=trials.get(index);
                boolean phaseReady=t.phase().equals("STANDING")?o.ground():t.phase().equals("RISING")?
                        !o.ground() && o.y()>4.15 && o.fall()==0 && c.client.player.getVelocity().y>0:
                        !o.ground() && o.fall()>.05 && o.y()>4.2;
                if(!phaseReady || !o.axe() || (!t.partial() && o.cooldown()<.99))return;
                sent=o;
                c.client.player.yaw=t.away()?90:-90;c.client.player.pitch=0;
                c.client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(c.client.player.yaw,0,c.client.player.isOnGround()));
                Entity entity=c.client.world.getEntityById(o.id());if(entity==null){fail(c,"CRIT_CLIENT_TARGET_MISSING");return;}
                if(t.attack()){c.client.interactionManager.attackEntity(c.client.player,entity);c.client.player.swingHand(Hand.MAIN_HAND);}
                c.event("critical_attack_sent",Map.of("case",index+1,"requestedPhase",t.phase(),"snapshot",o.toString(),"packets",t.attack()?1:0));
                enter(c,Phase.RESULT);return;
            case RESULT:
                if(!querying)query(c);if(reply==null)return;
                Snapshot after=take();if(after.tick()-sent.tick()<20 || !after.ground())return;
                finishCase(c,after);return;
            default:return;
        }
    }
    private void selectAxe(CreativeTestContext c){c.client.player.inventory.selectedSlot=1;c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(1));}
    private void restore(CreativeTestContext c){if(input!=null){input.pulses=0;input.tick(false);if(c.client.player!=null && c.client.player.input==input)c.client.player.input=saved;c.client.options.autoJump=oldAutoJump;}input=null;saved=null;}
    private void setup(CreativeTestContext c){
        restore(c);if(target!=null)c.command("/kill "+target);target=UUID.randomUUID();reply=null;querying=false;
        probe=new Probe(c.client.player.getUuid(),target);active=probe;
        c.client.player.setVelocity(0,0,0);IntegratedServer server=c.client.getServer();UUID player=c.client.player.getUuid();
        server.execute(()->{ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);if(!closed && p!=null)p.setVelocity(0,0,0);});
        c.command("/effect clear @s minecraft:blindness");if(trials.get(index).blind())c.command("/effect give @s minecraft:blindness 1000 0 true");
        c.command("/tp @s 0.5 4 0.5 -90 0");c.command("/clear @s");c.command("/replaceitem entity @s hotbar.0 minecraft:stick");
        c.command("/replaceitem entity @s hotbar.1 minecraft:iron_axe");c.client.player.inventory.selectedSlot=0;c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(0));
        c.command("/summon minecraft:zombie 3 4 0.5 {UUID:"+PacketCombatScenario.uuidNbt(target)+",NoAI:1b,NoGravity:1b,Silent:1b,IsBaby:0b}");
        c.command("/attribute "+target+" minecraft:generic.max_health base set 1000");c.command("/attribute "+target+" minecraft:generic.knockback_resistance base set 1");
        c.command("/data merge entity "+target+" {Health:1000f,CanPickUpLoot:0b,HandItems:[{},{}],ArmorItems:[{},{},{},{}]}");enter(c,Phase.SETUP);
    }
    private void query(CreativeTestContext c){
        querying=true;IntegratedServer server=c.client.getServer();UUID expected=target,player=c.client.player.getUuid();
        server.execute(()->{try{
            ServerWorld world=server.getWorld(World.OVERWORLD);ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);Entity e=world.getEntity(expected);
            if(p==null || !(e instanceof MobEntity m)){if(!closed && expected.equals(target))querying=false;return;}
            Snapshot o=new Snapshot(world.getTime(),e.getEntityId(),m.getHealth(),p.getX(),p.getY(),p.getZ(),p.fallDistance,p.isOnGround(),
                    p.getMainHandStack().getItem()==Items.IRON_AXE,p.getAttackCooldownProgress(.5f),p.hasStatusEffect(StatusEffects.BLINDNESS));
            if(!closed && expected.equals(target))reply=o;
        }catch(Exception ex){if(!closed && expected.equals(target))error=ex.toString();}});
    }
    private Snapshot take(){Snapshot o=reply;reply=null;querying=false;return o;}
    private void finishCase(CreativeTestContext c,Snapshot after){
        Trial t=trials.get(index);List<Map<String,Object>> events=List.copyOf(probe.events);
        Map<String,Object> row=new LinkedHashMap<>();row.put("case",index+1);row.put("requestedPhase",t.phase());row.put("partial",t.partial());row.put("blind",t.blind());row.put("away",t.away());
        row.put("packets",t.attack()?1:0);row.put("damage",baseline.health()-after.health());row.put("probeEvents",events);
        Map<String,Object> head=events.stream().filter(e->e.get("signal").equals("ATTACK_HEAD")).findFirst().orElse(Map.of());
        String actual=head.isEmpty()?"NO_ATTACK":head.get("onGround").equals(true)?"STANDING":
                ((Number)head.get("fallDistance")).doubleValue()>0?"FALLING":"AIRBORNE_NO_FALL_DISTANCE";
        row.put("actualPhaseAtAttack",actual);
        row.put("requestedPhaseMatched",!t.attack() || (t.phase().equals("RISING")?actual.equals("AIRBORNE_NO_FALL_DISTANCE"):actual.equals(t.phase())));
        row.put("attackHeadCount",events.stream().filter(e->e.get("signal").equals("ATTACK_HEAD")).count());
        row.put("critSignalCount",events.stream().filter(e->e.get("signal").equals("CRIT_PARTICLES")).count());results.add(row);c.event("critical_case_result",row);
        restore(c);index++;if(index<trials.size()){setup(c);return;}
        phase=Phase.FINISHED;c.command("/kill "+target);c.command("/effect clear @s minecraft:blindness");c.command("/gamemode creative");
        boolean observed=results.stream().allMatch(r->((Number)r.get("attackHeadCount")).intValue()==((Number)r.get("packets")).intValue());
        boolean grounded=results.stream().anyMatch(r->r.get("requestedPhase").equals("STANDING") && ((Number)r.get("damage")).doubleValue()>0 && ((Number)r.get("critSignalCount")).intValue()==0);
        boolean critical=results.stream().anyMatch(r->r.get("requestedPhase").equals("FALLING") && ((Number)r.get("critSignalCount")).intValue()>0);
        boolean blank=results.stream().anyMatch(r->((Number)r.get("packets")).intValue()==0 && ((Number)r.get("damage")).doubleValue()==0);
        boolean phaseValid=results.stream().allMatch(r->r.get("requestedPhaseMatched").equals(true));
        c.complete(observed&&grounded&&critical&&blank&&phaseValid?TestBatch.Outcome.PASS:TestBatch.Outcome.ERROR,
                observed&&grounded&&critical&&blank&&phaseValid?"CRITICAL_MEASUREMENT_COMPLETE":"CRITICAL_CONTROLS_FAILED",
                Map.of("cases",results.size(),"attackObserver",observed,"standingControl",grounded,"critSignalObserved",critical,"blankControl",blank,"phaseMatched",phaseValid));
    }
    @Override public void close(CreativeTestContext c){closed=true;if(active==probe)active=null;restore(c);}
}
