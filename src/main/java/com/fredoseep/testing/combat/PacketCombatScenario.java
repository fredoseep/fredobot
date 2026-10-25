package com.fredoseep.testing.combat;

import com.fredoseep.testing.CreativeTestContext;
import com.fredoseep.testing.CreativeWorldScenario;
import com.fredoseep.testing.TestBatch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real client attacks; the integrated server only observes health and received player state.
 * No target.attack()/damage() calls, reach overrides, or changes to combat rules.
 */
public final class PacketCombatScenario implements CreativeWorldScenario {
    private enum Phase { ARRIVE, ARENA_BUILD, ARENA, CASE_SETUP, BASELINE, CHARGE, RESULT, FINISHED }
    public record Trial(String group, double distance, float yaw, String cover, int chargeTicks, int repeatTicks) {}
    private record Observation(long tick, float health, double px, double py, double pz,
                               double tx, double ty, double tz, float yaw, float cooldown,
                               boolean holdingAxe, boolean targetAlive, boolean coverValid, double maxHealth, double knockbackResistance,
                               double vx, double vy, double vz, boolean onGround,
                               boolean aiDisabled, double movementSpeed, double followRange) {}
    private final boolean knockbackMode;
    private final List<Trial> trials;
    private final List<Map<String,Object>> motionSamples = new ArrayList<>();
    private long firstSampleTick=-1, lastSampleTick=-1, firstDamageTick=-1;
    private double peakAway, peakHorizontal, peakRise, peakSpeed;

    public PacketCombatScenario() { this(false); }
    private PacketCombatScenario(boolean knockbackMode) {
        this.knockbackMode=knockbackMode;
        this.trials=knockbackMode?knockbackTrials():trials();
    }
    public static PacketCombatScenario knockback() { return new PacketCombatScenario(true); }
    public static List<Trial> knockbackTrials() {
        List<Trial> cases=new ArrayList<>();
        for(float yaw:new float[]{-90,0,90})for(int charge:new int[]{0,5,15,25,35})
            cases.add(new Trial("knockback",2,yaw,"air",charge,0));
        cases.add(new Trial("resistant_control",2,-90,"air",35,0));
        cases.add(new Trial("no_attack_control",2,-90,"air",35,0));
        return List.copyOf(cases);
    }
    private final List<Map<String,Object>> results = new ArrayList<>();
    private Phase phase;
    private int index, enteredAge, attackAge, lastWaitAge;
    private boolean secondAttack, querying;
    private volatile UUID target;
    private volatile boolean closed;
    private Observation baseline;
    private volatile Observation reply;
    private volatile String queryError;
    private float clientCooldown;
    private int sent;

    public static List<Trial> trials() {
        List<Trial> list = new ArrayList<>();
        // Includes ordinary reach, likely packet boundaries, and a no-damage control.
        for (String cover : List.of("air", "stone")) {
            for (double d : new double[]{1.5,2.0,2.9,3.0,3.1,4.0,4.9,5.5,5.9,6.0,6.1,6.5}) {
                if (!cover.equals("stone") || d>=2) list.add(new Trial("distance",d,-90,cover,35,0));
            }
        }
        for (float yaw : new float[]{-90,0,90,180})
            list.add(new Trial("facing",2,yaw,"air",35,0));
        for (int ticks : new int[]{0,1,5,10,15,20,25,30,35})
            list.add(new Trial("axe_swap",2,-90,"air",ticks,0));
        for (int ticks : new int[]{1,5,10,15,20,25,30})
            list.add(new Trial("repeat",2,-90,"air",35,ticks));
        list.add(new Trial("far_control",9,-90,"air",35,0));
        return List.copyOf(list);
    }

    @Override public String name() { return knockbackMode?"knockback":"combat"; }

    @Override public void start(CreativeTestContext c) {
        if (c.client.getServer() == null) {
            c.complete(TestBatch.Outcome.ERROR,"SINGLEPLAYER_REQUIRED",Map.of());return;
        }
        c.event(knockbackMode?"knockback_plan":"combat_plan",Map.of("cases",trials.size(),"weapon","minecraft:iron_axe",
                "mode","survival","target",knockbackMode?"adult unarmored AI-enabled zombie; movement speed/follow range 0; gravity enabled; resistance 0 except control":"stationary unarmored zombie; knockback resistance 1",
                "distance_definition","entity position to entity position; both feet Y=4"));
        // Relocate while still creative. Do not build into unloaded chunks or fall from a high arena.
        c.command("/tp @s 0.5 4 0.5");
        enter(c,Phase.ARRIVE);
    }

    private void buildArena(CreativeTestContext c) {
        c.command("/fill -10 3 -4 12 3 4 minecraft:stone");
        c.command("/fill -10 4 -4 12 8 4 minecraft:air");
        c.command("/time set midnight");
        c.command("/gamerule doDaylightCycle false");
        c.command("/gamerule doMobSpawning false");
        c.command("/effect give @s minecraft:resistance 100000 4 true");
        c.command("/effect give @s minecraft:saturation 100000 0 true");
        enter(c,Phase.ARENA_BUILD);
    }

    private boolean arenaChunksLoaded(MinecraftClient client) {
        for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++) {
            if(client.world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)==null)return false;
        }
        return true;
    }

    private boolean arenaFloorReady(MinecraftClient client) {
        for(int x=-10;x<=12;x++)for(int z=-4;z<=4;z++) {
            if(client.world.getBlockState(new BlockPos(x,3,z)).getBlock()!=Blocks.STONE)return false;
        }
        return true;
    }

    @Override public void onChat(CreativeTestContext c,Text text) {
        c.event("combat_command_feedback",Map.of("message",text.getString()));
    }

    @Override public void tick(CreativeTestContext c) {
        if (phase == Phase.FINISHED || c.done()) return;
        if (c.phaseMillis() > 30000) {
            c.complete(TestBatch.Outcome.ERROR,"COMBAT_PHASE_TIMEOUT",Map.of(
                    "phase",phase.name(),"case",index,"completed",results.size()));return;
        }
        if (queryError != null) {
            c.complete(TestBatch.Outcome.ERROR,"COMBAT_OBSERVATION_ERROR",Map.of("error",queryError));return;
        }
        MinecraftClient client=c.client;
        if (client.player.age-lastWaitAge>=40) {
            lastWaitAge=client.player.age;
            c.event(knockbackMode?"knockback_wait":"combat_wait",Map.of("phase",phase.name(),"case",index+1,
                    "playerY",client.player.getY(),"creative",client.player.abilities.creativeMode,
                    "arenaChunksLoaded",arenaChunksLoaded(client),"entityId",entityId));
        }
        switch (phase) {
            case ARRIVE:
                if(Math.abs(client.player.getX()-.5)>.1 || Math.abs(client.player.getZ()-.5)>.1
                        || !arenaChunksLoaded(client))return;
                buildArena(c);return;
            case ARENA_BUILD:
                if(!arenaChunksLoaded(client) || !arenaFloorReady(client))return;
                c.command("/tp @s 0.5 4 0.5");
                c.command("/gamemode survival");
                enter(c,Phase.ARENA);return;
            case ARENA:
                if (client.player.abilities.creativeMode || Math.abs(client.player.getY()-4)>.05) return;
                if (client.player.age-enteredAge<20) return;
                setup(c);return;
            case CASE_SETUP:
                if (client.world.getEntityById(entityId) == null) {
                    if ((client.player.age-enteredAge)%5==0) requestId(c,target);
                    return;
                }
                if (client.player.inventory.getStack(0).getItem()!=Items.STICK
                        || client.player.inventory.getStack(1).getItem()!=Items.IRON_AXE) return;
                if (client.player.age-enteredAge<10) return;
                query(c);enter(c,Phase.BASELINE);return;
            case BASELINE:
                if (reply==null)return;
                baseline=reply;reply=null;querying=false;
                if (!validBaseline(c,baseline))return;
                client.player.yaw=trials.get(index).yaw();client.player.pitch=0;
                // Rotation is part of the actual client packet stream, including back-facing cases.
                client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(
                        client.player.yaw,client.player.pitch,client.player.isOnGround()));
                client.player.inventory.selectedSlot=1;
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(1));
                enter(c,Phase.CHARGE);
                if(trials.get(index).chargeTicks()==0)attack(c);
                return;
            case CHARGE:
                if(client.player.age-enteredAge>=trials.get(index).chargeTicks())attack(c);
                return;
            case RESULT:
                if(knockbackMode){tickKnockbackResult(c);return;}
                Trial trial=trials.get(index);
                int since=client.player.age-attackAge;
                if(trial.repeatTicks()>0 && !secondAttack && since>=trial.repeatTicks()) {
                    Entity again=client.world.getEntityById(entityId);
                    if(again==null){c.complete(TestBatch.Outcome.ERROR,"CLIENT_TARGET_MISSING",Map.of("case",index));return;}
                    client.interactionManager.attackEntity(client.player,again);
                    client.player.swingHand(Hand.MAIN_HAND);sent++;secondAttack=true;
                }
                if(since<Math.max(15,trial.repeatTicks()+15))return;
                if(!querying)query(c);
                if(reply==null)return;
                Observation after=reply;reply=null;querying=false;
                // Tasks scheduled on the server can run ahead of inbound packets: require actual server time to advance.
                if(after.tick()<=baseline.tick()+Math.max(5,trial.repeatTicks()))return;
                record(c,trial,after);
                if(c.done())return;
                index++;
                if(index==trials.size()){finish(c);return;}
                setup(c);return;
            default: return;
        }
    }

    private volatile int entityId=-1;

    private void setup(CreativeTestContext c) {
        if(target!=null)c.command("/kill "+target);
        target=UUID.randomUUID();entityId=-1;reply=null;queryError=null;querying=false;secondAttack=false;sent=0;
        motionSamples.clear();firstSampleTick=lastSampleTick=firstDamageTick=-1;
        peakAway=peakHorizontal=peakRise=peakSpeed=0;
        Trial t=trials.get(index);
        c.command("/fill 1 4 -1 1 6 1 minecraft:"+t.cover());
        c.command("/tp @s 0.5 4 0.5 "+t.yaw()+" 0");
        c.command("/clear @s");
        c.command("/replaceitem entity @s hotbar.0 minecraft:stick");
        c.command("/replaceitem entity @s hotbar.1 minecraft:iron_axe");
        c.client.player.inventory.selectedSlot=0;
        c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(0));
        // Chat commands are capped at 256 characters. Configure the fixture in ordered short commands.
        c.command("/summon minecraft:zombie "+(.5+t.distance())+" 4 0.5 {UUID:"+uuidNbt(target)
                +",NoAI:"+(knockbackMode?"0b":"1b")+",NoGravity:"+(knockbackMode?"0b":"1b")+",Silent:1b,PersistenceRequired:1b,IsBaby:0b}");
        if(knockbackMode) {
            // NoAI suppresses normal travel in this version, even when damage assigns a velocity.
            // Keep vanilla physics active; suppress autonomous walking/detection through attributes instead.
            c.command("/attribute "+target+" minecraft:generic.movement_speed base set 0");
            c.command("/attribute "+target+" minecraft:generic.follow_range base set 0");
        }
        c.command("/attribute "+target+" minecraft:generic.max_health base set 1000");
        c.command("/attribute "+target+" minecraft:generic.knockback_resistance base set "+expectedResistance());
        c.command("/data merge entity "+target
                +" {Health:1000f,CanPickUpLoot:0b,HandItems:[{},{}],ArmorItems:[{},{},{},{}]}");
        enter(c,Phase.CASE_SETUP);
        // Discover only the server entity id; absence while summon is in flight is not an error.
        requestId(c,target);
    }

    private void requestId(CreativeTestContext c,UUID expected) {
        IntegratedServer server=c.client.getServer();
        server.execute(()->{
            ServerWorld world=server.getWorld(World.OVERWORLD);
            Entity entity=world.getEntity(expected);
            if(!closed && expected.equals(target) && entity!=null)entityId=entity.getEntityId();
        });
    }

    private boolean validBaseline(CreativeTestContext c,Observation o) {
        Trial t=trials.get(index);
        double actual=Math.sqrt(square(o.px()-o.tx())+square(o.py()-o.ty())+square(o.pz()-o.tz()));
        if(!o.targetAlive() || Math.abs(actual-t.distance())>.03 || Math.abs(o.py()-4)>.03 || o.holdingAxe() || !o.coverValid() || o.health()<999
                || o.maxHealth()<1000 || Math.abs(o.knockbackResistance()-expectedResistance())>.001
                || !validMotionFixture(o,true)) {
            c.event("combat_motion_fixture",Map.of("aiDisabled",o.aiDisabled(),"onGround",o.onGround(),
                    "movementSpeed",o.movementSpeed(),"followRange",o.followRange()));
            c.complete(TestBatch.Outcome.ERROR,"COMBAT_FIXTURE_NOT_READY",Map.of(
                    "case",index,"requestedDistance",t.distance(),"actualDistance",actual,"holdingAxe",o.holdingAxe(),"coverValid",o.coverValid(),
                    "health",o.health(),"maxHealth",o.maxHealth(),"knockbackResistance",o.knockbackResistance()));
            return false;
        }
        return true;
    }

    private void attack(CreativeTestContext c) {
        Entity entity=c.client.world.getEntityById(entityId);
        if(entity==null){c.complete(TestBatch.Outcome.ERROR,"CLIENT_TARGET_MISSING",Map.of("case",index));return;}
        clientCooldown=c.client.player.getAttackCooldownProgress(.5F);
        if(!trials.get(index).group().equals("no_attack_control")) {
            c.client.interactionManager.attackEntity(c.client.player,entity);
            c.client.player.swingHand(Hand.MAIN_HAND);
            sent=1;
        }
        attackAge=c.client.player.age;
        enter(c,Phase.RESULT);
    }

    private void query(CreativeTestContext c) {
        querying=true;
        IntegratedServer server=c.client.getServer();UUID playerId=c.client.player.getUuid(),expected=target;
        Trial trial=trials.get(index);
        server.execute(()->{
            try {
                ServerWorld world=server.getWorld(World.OVERWORLD);
                ServerPlayerEntity player=server.getPlayerManager().getPlayer(playerId);
                Entity entity=world.getEntity(expected);
                if(player==null || !(entity instanceof LivingEntity living))throw new IllegalStateException("server fixture missing");
                Observation observation=new Observation(world.getTime(),living.getHealth(),player.getX(),player.getY(),player.getZ(),
                        entity.getX(),entity.getY(),entity.getZ(),player.yaw,player.getAttackCooldownProgress(.5F),
                        player.getMainHandStack().getItem()==Items.IRON_AXE,living.isAlive(),
                        world.getBlockState(new BlockPos(1,5,0)).getBlock()==(trial.cover().equals("stone")?Blocks.STONE:Blocks.AIR),
                        living.getAttributeValue(EntityAttributes.GENERIC_MAX_HEALTH),
                        living.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE),
                        entity.getVelocity().x,entity.getVelocity().y,entity.getVelocity().z,entity.isOnGround(),
                        entity instanceof MobEntity mob && mob.isAiDisabled(),
                        living.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED),
                        living.getAttributeValue(EntityAttributes.GENERIC_FOLLOW_RANGE));
                if(!closed && expected.equals(target))reply=observation; // Immutable snapshot only; never expose server entities to the client thread.
            } catch(Exception e){if(!closed && expected.equals(target))queryError=e.toString();}
        });
    }

    private void record(CreativeTestContext c,Trial t,Observation after) {
        if (!after.holdingAxe() || !after.coverValid()
                || square(after.px()-baseline.px())+square(after.py()-baseline.py())+square(after.pz()-baseline.pz())>.0009
                || square(after.tx()-baseline.tx())+square(after.ty()-baseline.ty())+square(after.tz()-baseline.tz())>.0009
                || Math.abs(Math.IEEEremainder(after.yaw()-t.yaw(),360))>.1) {
            c.complete(TestBatch.Outcome.ERROR,"COMBAT_FIXTURE_CHANGED",Map.of(
                    "case",index,"serverHoldingAxe",after.holdingAxe(),"serverYaw",after.yaw(),"expectedYaw",t.yaw()));
            return;
        }
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("case",index+1);row.put("group",t.group());row.put("distance",t.distance());row.put("yaw",t.yaw());
        row.put("cover",t.cover());row.put("chargeTicks",t.chargeTicks());row.put("repeatTicks",t.repeatTicks());
        row.put("maxHealth",baseline.maxHealth());row.put("knockbackResistance",baseline.knockbackResistance());
        row.put("packets",sent);row.put("healthBefore",baseline.health());row.put("healthAfter",after.health());
        row.put("damage",baseline.health()-after.health());row.put("clientCooldownAtAttack",clientCooldown);
        row.put("serverHoldingAxeAfter",after.holdingAxe());row.put("serverYawAfter",after.yaw());
        row.put("actualDistanceBefore",Math.sqrt(square(baseline.px()-baseline.tx())+square(baseline.py()-baseline.ty())+square(baseline.pz()-baseline.tz())));
        row.put("targetDisplacement",Math.sqrt(square(after.tx()-baseline.tx())+square(after.ty()-baseline.ty())+square(after.tz()-baseline.tz())));
        results.add(row);c.event("combat_case_result",row);
    }

    private double expectedResistance() {
        return !knockbackMode || trials.get(index).group().equals("resistant_control")?1:0;
    }

    private boolean validMotionFixture(Observation o,boolean requireGround) {
        return !knockbackMode || (!o.aiDisabled() && Math.abs(o.movementSpeed())<.000001
                && Math.abs(o.followRange())<.000001 && (!requireGround || o.onGround()));
    }

    private void tickKnockbackResult(CreativeTestContext c) {
        if(reply!=null) {
            Observation o=reply;reply=null;querying=false;
            if(o.tick()>lastSampleTick) {
                if(firstSampleTick<0)firstSampleTick=o.tick();
                lastSampleTick=o.tick();
                Trial t=trials.get(index);
                if(!o.holdingAxe() || !o.coverValid() || !validMotionFixture(o,false) || Math.abs(o.knockbackResistance()-expectedResistance())>.001
                        || square(o.px()-baseline.px())+square(o.py()-baseline.py())+square(o.pz()-baseline.pz())>.0009
                        || Math.abs(Math.IEEEremainder(o.yaw()-t.yaw(),360))>.1) {
                    c.complete(TestBatch.Outcome.ERROR,"KNOCKBACK_FIXTURE_CHANGED",Map.of("case",index+1));return;
                }
                if(firstDamageTick<0 && o.health()<baseline.health())firstDamageTick=o.tick();
                double dx=o.tx()-baseline.tx(),dz=o.tz()-baseline.tz();
                double ux=baseline.tx()-baseline.px(),uz=baseline.tz()-baseline.pz(),length=Math.hypot(ux,uz);
                double away=length==0?0:(dx*ux+dz*uz)/length;
                peakAway=Math.max(peakAway,away);peakHorizontal=Math.max(peakHorizontal,Math.hypot(dx,dz));
                peakRise=Math.max(peakRise,o.ty()-baseline.ty());peakSpeed=Math.max(peakSpeed,Math.hypot(o.vx(),o.vz()));
                Map<String,Object> sample=new LinkedHashMap<>();
                sample.put("serverTick",o.tick());sample.put("sinceBaseline",o.tick()-baseline.tick());
                sample.put("damage",baseline.health()-o.health());sample.put("pos",List.of(o.tx(),o.ty(),o.tz()));
                sample.put("velocity",List.of(o.vx(),o.vy(),o.vz()));sample.put("onGround",o.onGround());sample.put("away",away);
                motionSamples.add(sample);
                if(lastSampleTick-firstSampleTick>=40) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("case",index+1);row.put("group",t.group());row.put("yaw",t.yaw());row.put("chargeTicks",t.chargeTicks());
                    row.put("aiDisabled",baseline.aiDisabled());row.put("movementSpeed",baseline.movementSpeed());
                    row.put("followRange",baseline.followRange());row.put("groundedBeforeAttack",baseline.onGround());
                    row.put("packets",sent);row.put("damage",baseline.health()-o.health());row.put("resistance",expectedResistance());
                    row.put("peakAway",peakAway);row.put("peakHorizontal",peakHorizontal);row.put("peakRise",peakRise);
                    row.put("peakHorizontalSpeed",peakSpeed);row.put("finalAway",away);row.put("firstDamageTick",firstDamageTick);
                    row.put("sampleCount",motionSamples.size());row.put("observedServerTicks",lastSampleTick-firstSampleTick);
                    row.put("samples",List.copyOf(motionSamples));results.add(row);c.event("knockback_case_result",row);
                    index++;
                    if(index==trials.size()){finishKnockback(c);return;}
                    setup(c);return;
                }
            }
        }
        if(!querying)query(c);
    }

    private void finishKnockback(CreativeTestContext c) {
        phase=Phase.FINISHED;
        boolean free=results.stream().anyMatch(r->r.get("group").equals("knockback")
                && ((Number)r.get("chargeTicks")).intValue()==35 && ((Number)r.get("yaw")).floatValue()==-90
                && ((Number)r.get("damage")).doubleValue()>0 && ((Number)r.get("peakAway")).doubleValue()>.03);
        boolean resistant=results.stream().anyMatch(r->r.get("group").equals("resistant_control")
                && ((Number)r.get("damage")).doubleValue()>0 && ((Number)r.get("peakHorizontal")).doubleValue()<.03);
        boolean idle=results.stream().anyMatch(r->r.get("group").equals("no_attack_control")
                && ((Number)r.get("damage")).doubleValue()==0 && ((Number)r.get("peakHorizontal")).doubleValue()<.03);
        c.complete(free&&resistant&&idle?TestBatch.Outcome.PASS:TestBatch.Outcome.ERROR,
                free&&resistant&&idle?"KNOCKBACK_MEASUREMENT_COMPLETE":"KNOCKBACK_CONTROLS_FAILED",
                Map.of("cases",results.size(),"freeKnockback",free,"resistantControl",resistant,"idleControl",idle,
                        "note","AI enabled with movement speed/follow range 0; vanilla physics; pursuit and projectile defense not measured"));
    }

    private void finish(CreativeTestContext c) {
        phase=Phase.FINISHED;
        boolean positive=results.stream().anyMatch(r->r.get("group").equals("distance")
                && r.get("cover").equals("air") && ((Number)r.get("distance")).doubleValue()==2
                && ((Number)r.get("damage")).doubleValue()>0);
        boolean negative=results.stream().anyMatch(r->r.get("group").equals("far_control")
                && ((Number)r.get("damage")).doubleValue()==0);
        for (String cover : List.of("air","stone")) {
            List<Object> hitDistances=new ArrayList<>(),missDistances=new ArrayList<>();
            for (Map<String,Object> row : results) {
                if (!row.get("group").equals("distance") || !row.get("cover").equals(cover)) continue;
                (((Number)row.get("damage")).doubleValue()>0?hitDistances:missDistances).add(row.get("distance"));
            }
            c.event("combat_distance_summary",Map.of("cover",cover,"damagedAt",hitDistances,
                    "noDamageAt",missDistances,"note","sampled distances only; do not extrapolate an exact boundary"));
        }
        c.complete(positive&&negative?TestBatch.Outcome.PASS:TestBatch.Outcome.ERROR,
                positive&&negative?"COMBAT_MEASUREMENT_COMPLETE":"COMBAT_CONTROLS_FAILED",
                Map.of("cases",results.size(),"positiveControl",positive,"negativeControl",negative,
                        "note","PASS validates measurement controls, not an assumed reach limit; inspect combat_case_result"));
    }

    @Override public void close(CreativeTestContext c) { closed=true; }

    private void enter(CreativeTestContext c,Phase next) {
        phase=next;enteredAge=c.client.player.age;
        c.phase((knockbackMode?"KNOCKBACK ":"COMBAT ")+(index+1)+"/"+trials.size()+" "+next);
    }
    private static double square(double n){return n*n;}
    public static String uuidNbt(UUID uuid) {
        long hi=uuid.getMostSignificantBits(),lo=uuid.getLeastSignificantBits();
        return "[I;"+(int)(hi>>32)+","+(int)hi+","+(int)(lo>>32)+","+(int)lo+"]";
    }
}
