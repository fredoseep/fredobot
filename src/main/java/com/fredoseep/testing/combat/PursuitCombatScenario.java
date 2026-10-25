package com.fredoseep.testing.combat;

import com.fredoseep.testing.CreativeTestContext;
import com.fredoseep.testing.CreativeWorldScenario;
import com.fredoseep.testing.TestBatch;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Controlled pursuit experiment. Only fixture setup/observation uses the server;
 * damage and knockback still come from ordinary client attack packets and vanilla mob AI. */
public final class PursuitCombatScenario implements CreativeWorldScenario {
    private final boolean continuous;
    private final boolean adaptive;
    public PursuitCombatScenario(){this(false);}
    private PursuitCombatScenario(boolean continuous){this(continuous,false);}
    private PursuitCombatScenario(boolean continuous,boolean adaptive){
        this.continuous=continuous;this.adaptive=adaptive;
        this.trials=continuous?defenseTrials():trials();
    }
    public static PursuitCombatScenario continuousDefense(){return new PursuitCombatScenario(true);}
    public static PursuitCombatScenario cooldownDefense(){return new PursuitCombatScenario(true,true);}
    public static List<Trial> defenseTrials(){
        return List.of(new Trial(4.5,true,true),new Trial(4.5,true,true),
                new Trial(5,true,true),new Trial(5,true,true),new Trial(4.5,true,false));
    }
    private int minimumGap(){return continuous&&!adaptive?new int[]{25,30,25,30,0}[index]:0;}
    private double targetMaxHealth(){return adaptive && (index==2 || index==3)?20:1000;}
    private double triggerDistance(){return adaptive?(index%2==0?4.5:5):trials.get(index).triggerDistance();}
    private int observationTicks(){return continuous?400:100;}
    private enum Phase { ARRIVE, FLOOR, ARENA, SETUP, PREPARE, PURSUIT, OBSERVE, FINISHED }
    public record Trial(double triggerDistance, boolean precharged, boolean attack) {}
    private record Snapshot(long tick, int id, double px, double py, double pz, float playerHealth,
                            double tx, double ty, double tz, float health, double vx, double vy, double vz,
                            boolean targetsPlayer, boolean noAI, boolean grounded, boolean alive,
                            boolean axe, float cooldown, double speed, double followRange, double resistance) {}
    public static List<Trial> trials() {
        List<Trial> out=new ArrayList<>();
        for(double distance:new double[]{5.5,4,3,2}) {
            out.add(new Trial(distance,true,true));
            out.add(new Trial(distance,false,true));
        }
        out.add(new Trial(3,true,false));
        return List.copyOf(out);
    }
    private final List<Trial> trials;
    private final List<Map<String,Object>> samples=new ArrayList<>(), results=new ArrayList<>();
    private final List<Map<String,Object>> attacks=new ArrayList<>();
    private long lastAttackTick=-1;
    private boolean awaitingAttackAck;
    private float lastAttackHealth;
    private double previousPlayerHealth, cumulativeObservedDamage;
    private Phase phase;
    private int index, enteredAge, waitAge;
    private volatile UUID target;
    private volatile Snapshot reply;
    private volatile String error;
    private volatile boolean closed;
    private volatile boolean querying;
    private boolean confirmed, retreated;
    private Snapshot baseline, atAttack;
    private long lastTick=-1, releaseTick=-1, damageTick=-1, returnTicks=-1;
    private double nearest, farthestAfterDamage, minHealth;
    private float clientCooldown;

    @Override public String name(){return adaptive?"defense_ready":continuous?"defense":"pursuit";}
    @Override public void start(CreativeTestContext c) {
        if(c.client.getServer()==null){fail(c,"SINGLEPLAYER_REQUIRED");return;}
        c.event("pursuit_plan",Map.of("cases",trials.size(),"triggerDistances",continuous?List.of(4.5,5):List.of(5.5,4,3,2),
                "weapon","minecraft:iron_axe","windowServerTicks",observationTicks(),
                "fixture","survival, normal zombie AI/attributes; player max health 1000, no armor/resistance; natural regeneration disabled",
                "charge","precharged: equip 35 client ticks before release; immediate swap: equip at trigger",
                "hitDefinition","observed player health loss, not all attempted enemy attacks",
                "distanceDefinition","3D entity positions; trigger uses server snapshot, actual packet processing may occur later"));
        if(continuous)c.event("defense_plan",Map.of("minimumAttackGaps",adaptive?List.of(0):List.of(25,30),"requiredServerCooldown",.99,
                "rule","stay in place; attack only confirmed hostile within trigger distance, axe equipped, cooldown ready and minimum gap elapsed",
                "note","no chase or emergency low-charge attacks; no-attack control included"));
        if(adaptive)c.event("cooldown_defense_plan",Map.of("targetHealthByCase",List.of(1000,1000,20,20,1000),
                "triggerDistanceByCase",List.of(4.5,5,4.5,5,4.5),"acknowledgement","observe health decrease or cooldown reset before another packet; timeout is an explicit error",
                "normalHealthCases","finish on confirmed death; otherwise observe up to 400 server ticks"));
        c.command("/tp @s 0.5 4 0.5");enter(c,Phase.ARRIVE);
    }
    private void enter(CreativeTestContext c,Phase next) {
        phase=next;enteredAge=c.client.player.age;
        c.phase((adaptive?"COOLDOWN DEFENSE ":continuous?"DEFENSE ":"PURSUIT ")+(index+1)+"/"+trials.size()+" "+next);
    }
    private boolean chunks(MinecraftClient c) {
        for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++)
            if(c.world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)==null)return false;
        return true;
    }
    @Override public void onChat(CreativeTestContext c,Text text){c.event("pursuit_command_feedback",Map.of("message",text.getString()));}
    @Override public void tick(CreativeTestContext c) {
        if(c.done() || phase==Phase.FINISHED)return;
        if(error!=null){c.complete(TestBatch.Outcome.ERROR,"PURSUIT_SERVER_ERROR",Map.of("error",error));return;}
        if(c.phaseMillis()>30000){fail(c,"PURSUIT_PHASE_TIMEOUT");return;}
        if(c.client.player.age-waitAge>=40) {
            waitAge=c.client.player.age;
            c.event("pursuit_wait",Map.of("phase",phase.name(),"case",index+1,"confirmedTarget",confirmed,"samples",samples.size(),
                    "attacks",attacks.size(),"cumulativeObservedPlayerDamage",cumulativeObservedDamage));
        }
        switch(phase) {
            case ARRIVE:
                if(!chunks(c.client) || Math.hypot(c.client.player.getX()-.5,c.client.player.getZ()-.5)>.1)return;
                c.command("/fill -10 3 -4 12 3 4 minecraft:stone");
                c.command("/fill -10 4 -4 12 8 4 minecraft:air");
                c.command("/time set midnight");c.command("/gamerule doDaylightCycle false");
                c.command("/gamerule doMobSpawning false");c.command("/difficulty normal");
                c.command("/gamerule naturalRegeneration false");
                c.command("/effect clear @s");c.command("/effect give @s minecraft:saturation 100000 0 true");
                c.command("/attribute @s minecraft:generic.max_health base set 1000");
                enter(c,Phase.FLOOR);return;
            case FLOOR:
                if(!chunks(c.client))return;
                for(int x=-10;x<=12;x++)for(int z=-4;z<=4;z++)
                    if(c.client.world.getBlockState(new BlockPos(x,3,z)).getBlock()!=Blocks.STONE)return;
                c.command("/gamemode survival");enter(c,Phase.ARENA);return;
            case ARENA:
                if(c.client.player.abilities.creativeMode || c.client.player.age-enteredAge<20)return;
                setup(c);return;
            case SETUP:
                if(c.client.player.age-enteredAge<15 || c.client.player.inventory.getStack(1).getItem()!=Items.IRON_AXE)return;
                if(!querying)query(c);if(reply==null)return;
                Snapshot ready=take();
                if(c.client.world.getEntityById(ready.id())==null)return;
                baseline=ready;
                previousPlayerHealth=ready.playerHealth();
                if(!ready.noAI() || Math.abs(ready.health()-targetMaxHealth())>.05 || ready.playerHealth()<999 || ready.axe()
                        || Math.abs(distance(ready)-10)>.05 || Math.abs(ready.py()-4)>.05
                        || ready.speed()<=0 || ready.followRange()<10) {
                    fail(c,"PURSUIT_FIXTURE_INVALID");return;
                }
                c.event("pursuit_fixture",Map.of("movementSpeed",ready.speed(),"followRange",ready.followRange(),
                        "knockbackResistance",ready.resistance(),"playerHealth",ready.playerHealth(),"targetHealth",ready.health()));
                if(trials.get(index).precharged())selectAxe(c);
                enter(c,Phase.PREPARE);return;
            case PREPARE:
                if(c.client.player.age-enteredAge<35)return;
                release(c);enter(c,Phase.PURSUIT);return;
            case PURSUIT:
            case OBSERVE:
                if(!querying)query(c);if(reply==null)return;
                Snapshot o=take();if(o.tick()<=lastTick)return;lastTick=o.tick();
                if(!confirmed) {
                    if(!o.targetsPlayer() || o.noAI())return;
                    confirmed=true;releaseTick=o.tick();
                    c.event("pursuit_target_confirmed",Map.of("serverTick",o.tick(),"distance",distance(o)));
                }
                if(adaptive && atAttack!=null && targetMaxHealth()==20 && !o.alive() && o.health()<=0) {
                    recordSample(o);finishCase(c,o);return;
                }
                if(!o.alive() || o.noAI() || !o.targetsPlayer()){fail(c,"PURSUIT_TARGET_LOST");return;}
                if(adaptive && awaitingAttackAck && o.tick()>lastAttackTick) {
                    if(o.health()<lastAttackHealth || o.cooldown()<.99)awaitingAttackAck=false;
                    else if(o.tick()-lastAttackTick>20){fail(c,"DEFENSE_ATTACK_ACK_TIMEOUT");return;}
                }
                recordSample(o);
                if(phase==Phase.OBSERVE && o.tick()-atAttack.tick()>=observationTicks()){finishCase(c,o);return;}
                if(phase==Phase.PURSUIT && distance(o)<=triggerDistance()) {
                    atAttack=o;
                    if(!trials.get(index).precharged())selectAxe(c);
                    Entity entity=c.client.world.getEntityById(o.id());
                    if(entity==null){fail(c,"PURSUIT_CLIENT_TARGET_MISSING");return;}
                    clientCooldown=c.client.player.getAttackCooldownProgress(.5F);
                    if(trials.get(index).attack()) {
                        if(continuous && (!o.axe() || o.cooldown()<.99))return;
                        c.client.interactionManager.attackEntity(c.client.player,entity);
                        c.client.player.swingHand(Hand.MAIN_HAND);
                        recordAttack(c,o);
                    }
                    c.event("pursuit_attack_sent",Map.of("case",index+1,"packets",trials.get(index).attack()?1:0,
                            "serverSnapshotTick",o.tick(),"distance",distance(o),"velocity",List.of(o.vx(),o.vy(),o.vz()),
                            "serverCooldownBeforePacket",o.cooldown(),"clientCooldown",clientCooldown,
                            "clientCooldownReliableAfterSwap",trials.get(index).precharged()));
                    enter(c,Phase.OBSERVE);return;
                }
                if(continuous && phase==Phase.OBSERVE && trials.get(index).attack()
                        && (!adaptive || !awaitingAttackAck)
                        && distance(o)<=triggerDistance() && o.axe() && o.cooldown()>=.99
                        && o.tick()-lastAttackTick>=minimumGap()) {
                    Entity entity=c.client.world.getEntityById(o.id());
                    if(entity==null){fail(c,"PURSUIT_CLIENT_TARGET_MISSING");return;}
                    c.client.interactionManager.attackEntity(c.client.player,entity);
                    c.client.player.swingHand(Hand.MAIN_HAND);recordAttack(c,o);
                }
                if(phase==Phase.PURSUIT && o.tick()-releaseTick>200){fail(c,"PURSUIT_DID_NOT_APPROACH");return;}
                return;
            default:return;
        }
    }
    private void setup(CreativeTestContext c) {
        if(target!=null)c.command("/kill "+target);
        target=UUID.randomUUID();reply=null;querying=false;confirmed=retreated=false;
        baseline=atAttack=null;samples.clear();lastTick=releaseTick=damageTick=returnTicks=-1;
        attacks.clear();lastAttackTick=-1;awaitingAttackAck=false;cumulativeObservedDamage=0;previousPlayerHealth=1000;
        nearest=Double.POSITIVE_INFINITY;farthestAfterDamage=0;minHealth=1000;
        c.command("/tp @s 0.5 4 0.5 -90 0");c.command("/clear @s");
        c.command("/effect give @s minecraft:instant_health 1 10 true");
        c.command("/replaceitem entity @s hotbar.0 minecraft:stick");
        c.command("/replaceitem entity @s hotbar.1 minecraft:iron_axe");
        c.client.player.inventory.selectedSlot=0;
        c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(0));
        c.client.player.yaw=-90;c.client.player.pitch=0;
        c.client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(-90,0,c.client.player.isOnGround()));
        c.command("/summon minecraft:zombie 10.5 4 0.5 {UUID:"+PacketCombatScenario.uuidNbt(target)
                +",NoAI:1b,Silent:1b,PersistenceRequired:1b,IsBaby:0b}");
        c.command("/attribute "+target+" minecraft:generic.max_health base set "+targetMaxHealth());
        c.command("/data merge entity "+target+" {Health:"+targetMaxHealth()+"f,CanPickUpLoot:0b,HandItems:[{},{}],ArmorItems:[{},{},{},{}]}");
        enter(c,Phase.SETUP);
    }
    private void selectAxe(CreativeTestContext c) {
        c.client.player.inventory.selectedSlot=1;
        c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(1));
    }
    private void release(CreativeTestContext c) {
        IntegratedServer server=c.client.getServer();UUID expected=target,playerId=c.client.player.getUuid();
        server.execute(()->{
            if(closed || !expected.equals(target))return;
            Entity e=server.getWorld(World.OVERWORLD).getEntity(expected);
            ServerPlayerEntity p=server.getPlayerManager().getPlayer(playerId);
            if(!(e instanceof MobEntity mob) || p==null){error="release fixture missing";return;}
            mob.setAiDisabled(false);mob.setTarget(p);
        });
    }
    private void query(CreativeTestContext c) {
        querying=true;IntegratedServer server=c.client.getServer();UUID expected=target,playerId=c.client.player.getUuid();
        server.execute(()->{
            try {
                ServerWorld world=server.getWorld(World.OVERWORLD);
                Entity e=world.getEntity(expected);ServerPlayerEntity p=server.getPlayerManager().getPlayer(playerId);
                if(!(e instanceof MobEntity mob) || p==null) {
                    if(!closed && expected.equals(target))querying=false;
                    return; // summon may still be in the packet queue
                }
                Snapshot o=new Snapshot(world.getTime(),e.getEntityId(),p.getX(),p.getY(),p.getZ(),p.getHealth(),
                        e.getX(),e.getY(),e.getZ(),mob.getHealth(),e.getVelocity().x,e.getVelocity().y,e.getVelocity().z,
                        mob.getTarget()==p,mob.isAiDisabled(),e.isOnGround(),mob.isAlive(),
                        p.getMainHandStack().getItem()==Items.IRON_AXE,p.getAttackCooldownProgress(.5F),
                        mob.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED),mob.getAttributeValue(EntityAttributes.GENERIC_FOLLOW_RANGE),
                        mob.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE));
                if(!closed && expected.equals(target))reply=o;
            } catch(Exception ex){if(!closed && expected.equals(target))error=ex.toString();}
        });
    }
    private Snapshot take(){Snapshot o=reply;reply=null;querying=false;return o;}
    private static double distance(Snapshot o){return Math.sqrt(sq(o.px()-o.tx())+sq(o.py()-o.ty())+sq(o.pz()-o.tz()));}
    private static double sq(double n){return n*n;}
    private void recordSample(Snapshot o) {
        double d=distance(o);nearest=Math.min(nearest,d);minHealth=Math.min(minHealth,o.playerHealth());
        cumulativeObservedDamage+=Math.max(0,previousPlayerHealth-o.playerHealth());previousPlayerHealth=o.playerHealth();
        if(atAttack!=null && o.health()<atAttack.health()) {
            if(damageTick<0)damageTick=o.tick();
            farthestAfterDamage=Math.max(farthestAfterDamage,d);
            if(d>distance(atAttack)+.15)retreated=true;
            if(retreated && returnTicks<0 && d<=distance(atAttack))returnTicks=o.tick()-damageTick;
        }
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("serverTick",o.tick());row.put("distance",d);row.put("playerPos",List.of(o.px(),o.py(),o.pz()));
        row.put("targetPos",List.of(o.tx(),o.ty(),o.tz()));row.put("velocity",List.of(o.vx(),o.vy(),o.vz()));
        row.put("playerHealth",o.playerHealth());row.put("targetHealth",o.health());row.put("targetsPlayer",o.targetsPlayer());
        row.put("onGround",o.grounded());samples.add(row);
    }
    private void recordAttack(CreativeTestContext c,Snapshot o) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("case",index+1);row.put("triggerDistance",triggerDistance());row.put("minimumGapTicks",minimumGap());
        row.put("number",attacks.size()+1);row.put("serverSnapshotTick",o.tick());row.put("distance",distance(o));
        row.put("sincePreviousAttackSnapshot",lastAttackTick<0?-1:o.tick()-lastAttackTick);
        row.put("serverCooldown",o.cooldown());row.put("serverHoldingAxe",o.axe());
        row.put("incomingVelocity",List.of(o.vx(),o.vy(),o.vz()));
        row.put("targetHealthBefore",o.health());row.put("playerHealthBefore",o.playerHealth());
        attacks.add(row);lastAttackTick=o.tick();lastAttackHealth=o.health();awaitingAttackAck=adaptive;
        if(continuous)c.event("defense_attack_sent",row);
    }
    private void finishCase(CreativeTestContext c,Snapshot o) {
        Trial t=trials.get(index);Map<String,Object> row=new LinkedHashMap<>();
        row.put("case",index+1);row.put("triggerDistance",triggerDistance());row.put("precharged",t.precharged());
        row.put("targetInitialHealth",baseline.health());row.put("targetKilled",!o.alive() && o.health()<=0);
        row.put("packets",attacks.size());row.put("confirmedTarget",confirmed);row.put("triggerSnapshotDistance",distance(atAttack));
        row.put("triggerSnapshotTick",atAttack.tick());row.put("incomingVelocity",List.of(atAttack.vx(),atAttack.vy(),atAttack.vz()));
        row.put("clientCooldownAtAttack",clientCooldown);row.put("damageToZombie",atAttack.health()-o.health());
        row.put("clientCooldownReliableAfterSwap",t.precharged());
        row.put("cumulativeObservedPlayerDamage",cumulativeObservedDamage);
        row.put("observedPlayerHealthLoss",baseline.playerHealth()-minHealth);row.put("playerWasHurt",minHealth<baseline.playerHealth());
        row.put("nearestDistance",nearest);row.put("farthestDistanceAfterDamage",farthestAfterDamage);
        row.put("returnedToTriggerDistanceTicks",returnTicks);row.put("firstDamageTick",damageTick);
        row.put("observedServerTicks",o.tick()-atAttack.tick());row.put("samples",List.copyOf(samples));
        if(continuous){row.put("minimumGapTicks",minimumGap());row.put("attacks",List.copyOf(attacks));}
        results.add(row);c.event("pursuit_case_result",row);index++;
        if(index<trials.size()){setup(c);return;}
        phase=Phase.FINISHED;
        c.command("/kill "+target);c.command("/gamemode creative");
        boolean hurtControl=results.stream().anyMatch(r->((Number)r.get("packets")).intValue()==0 && r.get("playerWasHurt").equals(true));
        boolean hitControl=results.stream().anyMatch(r->r.get("precharged").equals(true) && ((Number)r.get("packets")).intValue()>=1
                && ((Number)r.get("damageToZombie")).doubleValue()>0);
        if(continuous)c.event("defense_summary",Map.of("noDamageCases",results.stream().filter(r->((Number)r.get("packets")).intValue()>0
                        && !r.get("playerWasHurt").equals(true)).count(),"attackCases",4,
                "note","PASS validates controls; inspect each case for sustained no-damage defense"));
        if(adaptive)c.event("cooldown_defense_summary",Map.of("sustainedNoDamageCases",results.stream().filter(r->
                        ((Number)r.get("targetInitialHealth")).doubleValue()==1000 && ((Number)r.get("packets")).intValue()>0 && !r.get("playerWasHurt").equals(true)).count(),
                "normalHealthKills",results.stream().filter(r->((Number)r.get("targetInitialHealth")).doubleValue()==20 && r.get("targetKilled").equals(true)).count(),
                "normalHealthNoDamageKills",results.stream().filter(r->((Number)r.get("targetInitialHealth")).doubleValue()==20 && r.get("targetKilled").equals(true) && !r.get("playerWasHurt").equals(true)).count()));
        c.complete(hurtControl&&hitControl?TestBatch.Outcome.PASS:TestBatch.Outcome.ERROR,
                hurtControl&&hitControl?(adaptive?"COOLDOWN_DEFENSE_MEASUREMENT_COMPLETE":continuous?"DEFENSE_MEASUREMENT_COMPLETE":"PURSUIT_MEASUREMENT_COMPLETE"):"PURSUIT_CONTROLS_FAILED",
                Map.of("cases",results.size(),"unopposedZombieHurtPlayer",hurtControl,"chargedAttackDamagedZombie",hitControl,
                        "note","health loss detects damage, not every attempted hit; natural regeneration disabled; inspect per-case packets and damage; -1 return time means not observed"));
    }
    private void fail(CreativeTestContext c,String reason) {
        c.complete(TestBatch.Outcome.ERROR,reason,Map.of("phase",phase==null?"START":phase.name(),"case",index+1,"completed",results.size()));
    }
    @Override public void close(CreativeTestContext c){closed=true;}
}
