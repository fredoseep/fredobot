package com.fredoseep.testing.combat;

import com.fredoseep.mixin.MotionClientAccess;
import com.fredoseep.testing.CreativeTestContext;
import com.fredoseep.testing.CreativeWorldScenario;
import com.fredoseep.testing.TestBatch;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test-only weapon switching and yaw packet ordering. All hits use normal client attackEntity. */
public final class WeaponYawScenario implements CreativeWorldScenario {
    public record Strike(String weapon,boolean crit) {}
    public record Trial(String name,List<Strike> strikes,String yawMode,boolean sprint,boolean attack) {
        boolean yawTest(){return !yawMode.equals("COMBO");}
    }
    public static List<Trial> trials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=2;repeat++){
            for(String material:List.of("stone","iron","diamond","netherite")){
                out.add(new Trial(material+"_axe_sword_r"+repeat,List.of(new Strike(material+"_axe",true),new Strike(material+"_sword",true)),"COMBO",false,true));
                out.add(new Trial(material+"_sword_axe_r"+repeat,List.of(new Strike(material+"_sword",true),new Strike(material+"_axe",true)),"COMBO",false,true));
            }
            out.add(new Trial("diamond_sword_twice_r"+repeat,List.of(new Strike("diamond_sword",true),new Strike("diamond_sword",true)),"COMBO",false,true));
            out.add(new Trial("stone_axe_diamond_sword_r"+repeat,List.of(new Strike("stone_axe",true),new Strike("diamond_sword",true)),"COMBO",false,true));
            out.add(new Trial("iron_axe_normal_finish_r"+repeat,List.of(new Strike("iron_axe",true),new Strike("iron_axe",false)),"COMBO",false,true));
            for(boolean sprint:List.of(false,true))for(String mode:List.of("FACE","SIDE","REVERSE","LOCAL_ONLY","BEFORE_RESTORE","LOOK_AFTER"))
                out.add(new Trial("yaw_"+mode+"_"+(sprint?"sprint_flag":"normal")+"_r"+repeat,List.of(new Strike("iron_axe",false)),mode,sprint,true));
        }
        out.add(new Trial("yaw_blank_control",List.of(new Strike("iron_axe",false)),"FACE",false,false));
        out.add(new Trial("combo_blank_control",List.of(new Strike("iron_axe",false)),"COMBO",false,false));
        return List.copyOf(out);
    }
    private enum Phase { ARRIVE,FLOOR,ARENA,SETUP,ARM,FALL,ACK,RESULT,FINISHED }
    private record Snapshot(long tick,int id,float health,double px,double py,double pz,boolean ground,float fall,float cooldown,double chargeTicks,
                            boolean weaponMatches,double tx,double ty,double tz,double vx,double vy,double vz,
                            boolean noAI,double resistance,double speed,double follow) {}
    private static final class TestInput extends Input {
        boolean pulse;
        @Override public void tick(boolean slow){movementForward=movementSideways=0;
            pressingForward=pressingBack=pressingLeft=pressingRight=sneaking=false;jumping=pulse;pulse=false;}
    }
    private final List<Trial> trials=trials();
    private final List<Map<String,Object>> results=new ArrayList<>(),attacks=new ArrayList<>(),motion=new ArrayList<>();
    private int index,step,age,waitAge;
    private Phase phase;
    private volatile UUID target;
    private volatile Snapshot reply;
    private Snapshot latest,baseline,sent;
    private volatile boolean querying,closed;
    private volatile String error;
    private AutoCloseable observation;
    private List<Map<String,Object>> probeEvents=List.of();
    private Input saved;
    private TestInput owned;
    private boolean autoJump;
    private long resultTick;
    private int recoveries;
    private Trial trial(){return trials.get(index);}
    @Override public String name(){return "weapon_yaw";}
    private void enter(CreativeTestContext c,Phase next){phase=next;age=c.client.player.age;c.phase("WEAPON/YAW "+(index+1)+"/"+trials.size()+" "+trial().name()+" "+next);}
    private void fail(CreativeTestContext c,String reason){c.complete(TestBatch.Outcome.ERROR,reason,Map.of("case",index+1,"phase",phase==null?"START":phase.name(),"completed",results.size()));}
    @Override public void start(CreativeTestContext c){
        if(c.client.getServer()==null){fail(c,"SINGLEPLAYER_REQUIRED");return;}
        c.event("weapon_yaw_plan",Map.of("cases",trials.size(),"target","adult unarmored zombie; combo 20 health, yaw 1000 health",
                "weapons","stone/iron/diamond/netherite axe+sword both orders; diamond sword twice; stone axe+diamond sword; iron axe normal finish baseline; two repeats",
                "yaw","normal and sprint-flag attacks; face,side,reverse,local-only,before-restore,look-after; same attack API",
                "sprintNote","explicit START_SPRINTING immediately before attack, no run-up; isolates server sprint flag, not real running technique",
                "controls","combo target stationary/resistance1; yaw target AI enabled with movement/follow 0, gravity enabled, resistance0",
                "scope","fixed target damage/timing and knockback mechanics only, not live defense integration"));
        c.event("attack_packet_fields",Map.of("fields",Arrays.stream(PlayerInteractEntityC2SPacket.class.getDeclaredFields()).map(f->f.getName()+":"+f.getType().getSimpleName()).toList()));
        c.command("/tp @s 0.5 4 0.5 -90 0");enter(c,Phase.ARRIVE);
    }
    private boolean chunks(MinecraftClient c){for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++)if(c.world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)==null)return false;return true;}
    @Override public void tick(CreativeTestContext c){
        if(c.done() || phase==Phase.FINISHED)return;
        if(error!=null){c.event("weapon_yaw_error",Map.of("error",error));fail(c,"WEAPON_YAW_SERVER_ERROR");return;}
        if(c.phaseMillis()>30000){fail(c,"WEAPON_YAW_PHASE_TIMEOUT");return;}
        if(c.client.player.age-waitAge>=40){waitAge=c.client.player.age;c.event("weapon_yaw_wait",Map.of("case",index+1,"phase",phase.name(),"step",step,"attacks",attacks.size()));}
        if(owned!=null){c.client.options.keySprint.setPressed(false);((MotionClientAccess)(Object)c.client.player).fredobot$setSprintWindow(0);}
        switch(phase){
            case ARRIVE:
                if(!chunks(c.client) || Math.hypot(c.client.player.getX()-.5,c.client.player.getZ()-.5)>.1)return;
                c.command("/fill -10 3 -4 12 3 4 minecraft:stone");c.command("/fill -10 4 -4 12 9 4 minecraft:air");
                c.command("/time set midnight");c.command("/gamerule doDaylightCycle false");c.command("/gamerule doMobSpawning false");c.command("/difficulty normal");
                c.command("/effect clear @s");c.command("/effect give @s minecraft:saturation 100000 0 true");enter(c,Phase.FLOOR);return;
            case FLOOR:
                for(int x=-10;x<=12;x++)for(int z=-4;z<=4;z++)if(c.client.world.getBlockState(new BlockPos(x,3,z)).getBlock()!=Blocks.STONE)return;
                c.command("/gamemode survival");enter(c,Phase.ARENA);return;
            case ARENA:
                if(c.client.player.abilities.creativeMode || c.client.player.age-age<20)return;setup(c);return;
            case SETUP:
                if(c.client.player.age-age<20 || c.client.player.inventory.getStack(1).getItem()!=item(trial().strikes().get(0).weapon()))return;
                if(!querying)query(c);if(reply==null)return;Snapshot ready=take();
                if(c.client.world.getEntityById(ready.id())==null)return;
                boolean valid=Math.abs(ready.health()-(trial().yawTest()?1000:20))<.05 && ready.ground() && Math.abs(ready.py()-4)<.05
                        && Math.hypot(ready.px()-.5,ready.pz()-.5)<.05 && Math.abs(ready.tx()-3)<.05 && Math.abs(ready.ty()-4)<.05 && Math.abs(ready.tz()-.5)<.05
                        && Math.abs(ready.resistance()-(trial().yawTest()?0:1))<.001 && (trial().yawTest()?!ready.noAI() && ready.speed()<.000001 && ready.follow()<.000001:ready.noAI());
                if(!valid){c.event("weapon_yaw_fixture_invalid",Map.of("snapshot",ready.toString(),"retry",recoveries));if(recoveries++>=2){fail(c,"WEAPON_YAW_FIXTURE_INVALID");return;}setupFixture(c);return;}
                baseline=ready;saved=c.client.player.input;owned=new TestInput();c.client.player.input=owned;autoJump=c.client.options.autoJump;c.client.options.autoJump=false;
                select(c,0);enter(c,Phase.ARM);return;
            case ARM:
                if(!querying)query(c);if(reply==null)return;Snapshot armed=take();
                if(!armed.weaponMatches() || !armed.ground())return;
                boolean wantsCrit=trial().strikes().get(step).crit();
                // Begin the jump while the remaining cooldown can expire before descent. Actual hit still requires >=.99.
                if(wantsCrit?Math.max(0,1-armed.cooldown())*armed.chargeTicks()>6:armed.cooldown()<.99)return;
                if(step==0 && c.client.player.age-age<35)return;
                if(!trial().attack()){sent=armed;resultTick=armed.tick();enter(c,Phase.RESULT);return;}
                if(armed.health()<=0){resultTick=armed.tick();enter(c,Phase.RESULT);return;}
                if(trial().strikes().get(step).crit()){owned.pulse=true;enter(c,Phase.FALL);}else send(c,armed);return;
            case FALL:
                if(c.client.player.age-age>35){fail(c,"WEAPON_YAW_JUMP_MISSED");return;}
                if(!querying)query(c);if(reply==null)return;Snapshot falling=take();
                if(!falling.ground() && falling.fall()>.05 && falling.py()>4.2 && falling.weaponMatches() && falling.cooldown()>=.99)send(c,falling);return;
            case ACK:
                if(!querying)query(c);if(reply==null)return;Snapshot ack=take();
                if(ack.tick()<=sent.tick() || ack.health()>=sent.health()){
                    if(ack.tick()-sent.tick()>20){fail(c,"WEAPON_YAW_HIT_NOT_CONFIRMED");}return;
                }
                step++;
                if(ack.health()<=0 || step>=trial().strikes().size()){resultTick=ack.tick();enter(c,Phase.RESULT);}else enter(c,Phase.ARM);return;
            case RESULT:
                if(!querying)query(c);if(reply==null)return;Snapshot result=take();
                if(result.tick()-resultTick<(trial().yawTest() || !trial().attack()?40:2) || !result.ground())return;
                finish(c,result);return;
            default:return;
        }
    }
    private void select(CreativeTestContext c,int strike){
        int slot=strike==0?1:2;
        // Same-weapon second hit keeps the same slot; do not reset its charge via a redundant item switch.
        if(strike>0 && trial().strikes().get(0).weapon().equals(trial().strikes().get(strike).weapon()))return;
        c.client.player.inventory.selectedSlot=slot;c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }
    private void look(CreativeTestContext c,float yaw){c.client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(yaw,0,c.client.player.isOnGround()));}
    private void sprint(CreativeTestContext c,boolean active){c.client.player.setSprinting(active);c.client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(c.client.player,active?ClientCommandC2SPacket.Mode.START_SPRINTING:ClientCommandC2SPacket.Mode.STOP_SPRINTING));}
    private void send(CreativeTestContext c,Snapshot o){
        Entity e=c.client.world.getEntityById(o.id());if(e==null){fail(c,"WEAPON_YAW_CLIENT_TARGET_MISSING");return;}
        String mode=trial().yawMode();float before=mode.equals("SIDE") || mode.equals("BEFORE_RESTORE")?0:mode.equals("REVERSE")?90:-90;
        c.client.player.yaw=before;c.client.player.pitch=0;look(c,before);
        if(mode.equals("LOCAL_ONLY"))c.client.player.yaw=0;
        sprint(c,trial().sprint());
        c.client.interactionManager.attackEntity(c.client.player,e);c.client.player.swingHand(Hand.MAIN_HAND);
        List<String> order=new ArrayList<>(List.of("LOOK "+before,"SPRINT_FLAG "+trial().sprint(),"ATTACK"));
        if(mode.equals("BEFORE_RESTORE")){look(c,-90);c.client.player.yaw=-90;order.add("LOOK -90 after attack");}
        if(mode.equals("LOOK_AFTER")){look(c,0);c.client.player.yaw=0;order.add("LOOK 0 after attack");}
        sprint(c,false);
        Map<String,Object> row=new LinkedHashMap<>();row.put("step",step+1);row.put("weapon",trial().strikes().get(step).weapon());row.put("requestedCrit",trial().strikes().get(step).crit());
        row.put("tick",o.tick());row.put("cooldown",o.cooldown());row.put("healthBefore",o.health());row.put("clientYaw",c.client.player.yaw);row.put("packetOrder",order);
        attacks.add(row);c.event("weapon_yaw_attack_sent",row);sent=o;
        if(step+1<trial().strikes().size())select(c,step+1);
        enter(c,Phase.ACK);
    }
    private void setup(CreativeTestContext c){recoveries=0;setupFixture(c);}
    private void setupFixture(CreativeTestContext c){
        restore(c);stopProbe();if(target!=null)c.command("/kill "+target);target=UUID.randomUUID();reply=latest=null;querying=false;step=0;attacks.clear();motion.clear();probeEvents=List.of();
        observation=CriticalCombatScenario.beginObservation(c.client.player.getUuid(),List.of(target),rows->probeEvents=rows);
        c.client.player.setVelocity(0,0,0);IntegratedServer server=c.client.getServer();UUID player=c.client.player.getUuid(),expected=target;
        server.execute(()->{if(closed || !expected.equals(target))return;ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);if(p!=null)p.setVelocity(0,0,0);});
        sprint(c,false);c.command("/tp @s 0.5 4 0.5 -90 0");c.command("/clear @s");
        c.command("/replaceitem entity @s hotbar.1 minecraft:"+trial().strikes().get(0).weapon());
        if(trial().strikes().size()>1)c.command("/replaceitem entity @s hotbar.2 minecraft:"+trial().strikes().get(1).weapon());
        c.command("/summon minecraft:zombie 3.0 4.0 0.5 {UUID:"+PacketCombatScenario.uuidNbt(target)+",NoAI:"+(trial().yawTest()?"0b":"1b")+",NoGravity:"+(trial().yawTest()?"0b":"1b")+",Silent:1b,IsBaby:0b,PersistenceRequired:1b}");
        c.command("/attribute "+target+" minecraft:generic.max_health base set "+(trial().yawTest()?1000:20));
        c.command("/attribute "+target+" minecraft:generic.knockback_resistance base set "+(trial().yawTest()?0:1));
        if(trial().yawTest()){c.command("/attribute "+target+" minecraft:generic.movement_speed base set 0");c.command("/attribute "+target+" minecraft:generic.follow_range base set 0");}
        c.command("/data merge entity "+target+" {Health:"+(trial().yawTest()?1000:20)+"f,CanPickUpLoot:0b,HandItems:[{},{}],ArmorItems:[{},{},{},{}]}");
        c.event("weapon_yaw_case_setup",Map.of("case",index+1,"name",trial().name(),"yawMode",trial().yawMode(),"sprintFlag",trial().sprint()));enter(c,Phase.SETUP);
    }
    private void query(CreativeTestContext c){
        querying=true;IntegratedServer server=c.client.getServer();UUID player=c.client.player.getUuid(),expected=target;
        Item desired=item(trial().strikes().get(Math.min(step,trial().strikes().size()-1)).weapon());Snapshot prior=latest;boolean preparing=phase==Phase.SETUP;
        server.execute(()->{try{
            if(closed || !expected.equals(target))return;ServerWorld w=server.getWorld(World.OVERWORLD);ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);Entity e=w.getEntity(expected);
            if(p==null)throw new IllegalStateException("player missing");
            if(!(e instanceof MobEntity m)){
                if(prior!=null && prior.health()<=0){reply=new Snapshot(w.getTime(),prior.id(),0,p.getX(),p.getY(),p.getZ(),p.isOnGround(),p.fallDistance,p.getAttackCooldownProgress(.5f),20/Math.max(.001,p.getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED)),false,prior.tx(),prior.ty(),prior.tz(),0,0,0,prior.noAI(),prior.resistance(),prior.speed(),prior.follow());return;}
                if(preparing){querying=false;return;}throw new IllegalStateException("target disappeared before observed death");
            }
            Snapshot value=new Snapshot(w.getTime(),e.getEntityId(),m.getHealth(),p.getX(),p.getY(),p.getZ(),p.isOnGround(),p.fallDistance,p.getAttackCooldownProgress(.5f),20/Math.max(.001,p.getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED)),p.getMainHandStack().getItem()==desired,
                    e.getX(),e.getY(),e.getZ(),e.getVelocity().x,e.getVelocity().y,e.getVelocity().z,m.isAiDisabled(),m.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE),m.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED),m.getAttributeValue(EntityAttributes.GENERIC_FOLLOW_RANGE));
            if(!closed && expected.equals(target))reply=value;
        }catch(Exception ex){if(!closed && expected.equals(target))error=ex.toString();}});
    }
    private Snapshot take(){Snapshot o=reply;reply=null;querying=false;latest=o;
        if(phase==Phase.ACK || phase==Phase.RESULT)motion.add(Map.of("tick",o.tick(),"health",o.health(),"pos",List.of(o.tx(),o.ty(),o.tz()),"velocity",List.of(o.vx(),o.vy(),o.vz())));return o;
    }
    private void finish(CreativeTestContext c,Snapshot after){
        stopProbe();restore(c);
        long heads=probeEvents.stream().filter(e->e.get("signal").equals("ATTACK_HEAD")).count(),returns=probeEvents.stream().filter(e->e.get("signal").equals("ATTACK_RETURN")).count(),crits=probeEvents.stream().filter(e->e.get("signal").equals("CRIT_PARTICLES")).count();
        List<Map<String,Object>> headRows=probeEvents.stream().filter(e->e.get("signal").equals("ATTACK_HEAD")).toList();
        long expectedCrits=trial().attack()?trial().strikes().stream().limit(attacks.size()).filter(Strike::crit).count():0;
        boolean fullCharge=headRows.stream().allMatch(e->((Number)e.get("cooldown")).doubleValue()>=.99);
        boolean sprintMatched=headRows.stream().allMatch(e->e.get("sprinting").equals(trial().sprint()));
        long attackSpan=headRows.size()<2?0:((Number)headRows.get(headRows.size()-1).get("playerAge")).longValue()-((Number)headRows.get(0).get("playerAge")).longValue();
        Map<String,Object> row=new LinkedHashMap<>();row.put("case",index+1);row.put("name",trial().name());row.put("yawTest",trial().yawTest());row.put("yawMode",trial().yawMode());row.put("sprintFlag",trial().sprint());
        row.put("packets",attacks.size());row.put("attackHeads",heads);row.put("attackReturns",returns);row.put("crits",crits);row.put("killed",after.health()<=0);row.put("healthRemaining",after.health());row.put("damage",baseline.health()-after.health());
        row.put("expectedCrits",expectedCrits);row.put("critRequestsMatched",crits==expectedCrits);row.put("fullChargeMatched",fullCharge);row.put("sprintFlagMatched",sprintMatched);
        row.put("firstToLastAttackTicks",attackSpan);row.put("horizontalDisplacement",List.of(after.tx()-baseline.tx(),after.tz()-baseline.tz()));
        row.put("attacks",List.copyOf(attacks));row.put("probeEvents",probeEvents);row.put("samples",List.copyOf(motion));results.add(row);c.event("weapon_yaw_case_result",row);
        index++;if(index<trials.size()){setup(c);return;}phase=Phase.FINISHED;c.command("/kill "+target);c.command("/gamemode creative");
        boolean observer=results.stream().allMatch(e->e.get("packets").equals(((Number)e.get("attackHeads")).intValue()) && e.get("packets").equals(((Number)e.get("attackReturns")).intValue()));
        boolean blanks=results.stream().filter(e->e.get("name").toString().contains("blank_control")).allMatch(e->((Number)e.get("damage")).doubleValue()==0 && ((Number)e.get("packets")).intValue()==0);
        boolean critControl=results.stream().anyMatch(e->!e.get("yawTest").equals(true) && ((Number)e.get("crits")).intValue()>0);
        boolean knockback=results.stream().anyMatch(e->e.get("yawTest").equals(true) && e.get("yawMode").equals("FACE") && !e.get("sprintFlag").equals(true) && Math.hypot(((List<Number>)e.get("horizontalDisplacement")).get(0).doubleValue(),((List<Number>)e.get("horizontalDisplacement")).get(1).doubleValue())>.1);
        boolean conditions=results.stream().allMatch(e->e.get("critRequestsMatched").equals(true) && e.get("fullChargeMatched").equals(true) && e.get("sprintFlagMatched").equals(true));
        c.complete(observer&&blanks&&critControl&&knockback&&conditions?TestBatch.Outcome.PASS:TestBatch.Outcome.ERROR,observer&&blanks&&critControl&&knockback&&conditions?"WEAPON_YAW_MEASUREMENT_COMPLETE":"WEAPON_YAW_CONTROLS_FAILED",Map.of("cases",results.size(),"observer",observer,"blankControls",blanks,"critControl",critControl,"knockbackControl",knockback,"attackConditions",conditions));
    }
    private static Item item(String name){return switch(name){
        case "stone_axe"->Items.STONE_AXE;case "stone_sword"->Items.STONE_SWORD;
        case "iron_axe"->Items.IRON_AXE;case "iron_sword"->Items.IRON_SWORD;
        case "diamond_axe"->Items.DIAMOND_AXE;case "diamond_sword"->Items.DIAMOND_SWORD;
        case "netherite_axe"->Items.NETHERITE_AXE;case "netherite_sword"->Items.NETHERITE_SWORD;
        default->throw new IllegalArgumentException("unsupported test weapon "+name);
    };}
    private void stopProbe(){if(observation!=null)try{observation.close();}catch(Exception ex){error=ex.toString();}finally{observation=null;}}
    private void restore(CreativeTestContext c){if(owned!=null){owned.pulse=false;owned.tick(false);if(c.client.player!=null && c.client.player.input==owned)c.client.player.input=saved;c.client.options.autoJump=autoJump;}owned=null;saved=null;}
    @Override public void close(CreativeTestContext c){closed=true;stopProbe();restore(c);if(c.client.player!=null && c.client.getNetworkHandler()!=null)sprint(c,false);}
}
