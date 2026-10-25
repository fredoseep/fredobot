package com.fredoseep.testing.combat;

import com.fredoseep.mixin.MotionClientAccess;
import com.fredoseep.testing.CreativeTestContext;
import com.fredoseep.testing.CreativeWorldScenario;
import com.fredoseep.testing.TestBatch;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Controlled tactical experiments, separate from live bot defense. */
public final class TacticalKnockbackScenario implements CreativeWorldScenario {
    public record Trial(String group,String tactic,int repeat,boolean attack,boolean chase,boolean contact,boolean servo,String layout,ContactJumpFollow.Mode jumpMode){
        public Trial(String group,String tactic,int repeat,boolean attack,boolean chase,boolean contact,boolean servo,String layout){this(group,tactic,repeat,attack,chase,contact,servo,layout,ContactJumpFollow.Mode.NONE);}
        public Trial(String group,String tactic,int repeat,boolean attack,boolean chase,boolean contact,boolean servo){this(group,tactic,repeat,attack,chase,contact,servo,"standard");}
        public Trial(String group,String tactic,int repeat,boolean attack){this(group,tactic,repeat,attack,false,false,false);}
        public Trial(String group,String tactic,int repeat,boolean attack,boolean chase){this(group,tactic,repeat,attack,chase,false,false);}
        public Trial(String group,String tactic,int repeat,boolean attack,boolean chase,boolean contact){this(group,tactic,repeat,attack,chase,contact,false);}
        boolean ranged(){return group.equals("skeleton");}}
    public static List<Trial> trials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++){
            List<String> melee=repeat%2==1?List.of("OUTWARD","SIDE_POS_X","SIDE_NEG_X"):List.of("SIDE_NEG_X","SIDE_POS_X","OUTWARD");
            for(String tactic:melee)out.add(new Trial("zombie",tactic,repeat,true));
            for(String tactic:repeat%2==1?List.of("OUTWARD","PULL"):List.of("PULL","OUTWARD"))out.add(new Trial("skeleton",tactic,repeat,true));
        }
        out.add(new Trial("zombie","OUTWARD",0,false));out.add(new Trial("skeleton","OUTWARD",0,false));return List.copyOf(out);
    }
    private enum Phase { ARRIVE,FLOOR,ARENA,TERRAIN,SETUP,PREPARE,APPROACH,ACK,OBSERVE,FINISHED }
    private record Snapshot(long tick,int id,double px,double py,double pz,float playerHealth,boolean ground,float cooldown,boolean axe,
                            double tx,double ty,double tz,float health,boolean noAI,boolean targeting,boolean bow,boolean overlap,List<Double> playerBox,List<Double> targetBox) {}
    private static final class EscapeInput extends Input {
        final ClientPlayerEntity player;boolean escape;int validUntil;double dx,dz=1;ContactFollowServo servo;ContactJumpFollow jumper;boolean headroom;final List<Map<String,Object>> jumpInputs=new ArrayList<>();
        ArrowEvasionExperiment evasion;MinecraftClient context;double targetX,targetZ;java.util.function.Consumer<Map<String,Object>> evasionLog;
        EscapeInput(ClientPlayerEntity p){player=p;}
        @Override public void tick(boolean slow){
            ArrowEvasionExperiment.Control evade=evasion!=null && escape && player.age<=validUntil?evasion.update(context,targetX,targetZ,evasionLog):null;
            if(evade!=null){dx=evade.x();dz=evade.z();}
            if(evade==null && servo!=null && escape && player.age<=validUntil){
                ContactFollowServo.Command command=servo.command(player.age,player.getX(),player.getZ(),player.getVelocity().x,player.getVelocity().z,player.isOnGround());dx=command.x();dz=command.z();
            }
            double move=escape && player.age<=validUntil?1:0,yaw=Math.toRadians(player.yaw);
            movementForward=(float)(move*(-dx*Math.sin(yaw)+dz*Math.cos(yaw)));movementSideways=(float)(move*(dx*Math.cos(yaw)+dz*Math.sin(yaw)));
            pressingForward=movementForward>.01;pressingBack=movementForward<-.01;
            pressingLeft=movementSideways>.01;pressingRight=movementSideways<-.01;sneaking=false;
            jumping=evade!=null?evade.jump():jumper!=null && jumper.input(player.age,player.isOnGround(),escape && player.age<=validUntil,headroom);
            if(jumping)jumpInputs.add(Map.of("age",player.age,"pos",List.of(player.getX(),player.getY(),player.getZ()),"velocity",List.of(player.getVelocity().x,player.getVelocity().y,player.getVelocity().z)));
            player.setSprinting(false);((MotionClientAccess)(Object)player).fredobot$setSprintWindow(0);
            if(slow){movementForward*=.3f;movementSideways*=.3f;}
        }
    }
    private final List<Trial> trials;
    private final boolean rangedExperiment,overlapExperiment,trackingExperiment,impulseExperiment,robustnessExperiment,jumpExperiment,projectileExperiment;
    public TacticalKnockbackScenario(){this(false);}
    private TacticalKnockbackScenario(boolean rangedExperiment){this(rangedExperiment,false);}
    private TacticalKnockbackScenario(boolean rangedExperiment,boolean overlapExperiment){this(rangedExperiment,overlapExperiment,false);}
    private TacticalKnockbackScenario(boolean rangedExperiment,boolean overlapExperiment,boolean trackingExperiment){this(rangedExperiment,overlapExperiment,trackingExperiment,false);}
    private TacticalKnockbackScenario(boolean rangedExperiment,boolean overlapExperiment,boolean trackingExperiment,boolean impulseExperiment){this(rangedExperiment,overlapExperiment,trackingExperiment,impulseExperiment,false);}
    private TacticalKnockbackScenario(boolean rangedExperiment,boolean overlapExperiment,boolean trackingExperiment,boolean impulseExperiment,boolean robustnessExperiment){this(rangedExperiment,overlapExperiment,trackingExperiment,impulseExperiment,robustnessExperiment,false);}
    private TacticalKnockbackScenario(boolean rangedExperiment,boolean overlapExperiment,boolean trackingExperiment,boolean impulseExperiment,boolean robustnessExperiment,boolean jumpExperiment){this(rangedExperiment,overlapExperiment,trackingExperiment,impulseExperiment,robustnessExperiment,jumpExperiment,false);}
    private TacticalKnockbackScenario(boolean rangedExperiment,boolean overlapExperiment,boolean trackingExperiment,boolean impulseExperiment,boolean robustnessExperiment,boolean jumpExperiment,boolean projectileExperiment){this.rangedExperiment=rangedExperiment;this.overlapExperiment=overlapExperiment;this.trackingExperiment=trackingExperiment;this.impulseExperiment=impulseExperiment;this.robustnessExperiment=robustnessExperiment;this.jumpExperiment=jumpExperiment;this.projectileExperiment=projectileExperiment;trials=projectileExperiment?projectileTrials():jumpExperiment?jumpTrials():robustnessExperiment?robustnessTrials():impulseExperiment?impulseTrials():trackingExperiment?trackingTrials():overlapExperiment?overlapTrials():rangedExperiment?rangedTrials():trials();}
    public static TacticalKnockbackScenario projectileEvasion(){return new TacticalKnockbackScenario(true,true,true,true,true,true,true);}
    public static List<Trial> projectileTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++)for(String layout:List.of("near","far","diagonal","wall"))
            for(boolean predict:repeat%2==1?new boolean[]{false,true}:new boolean[]{true,false})
                out.add(new Trial("skeleton",predict?"EVASION_CONTACT":"IMPULSE_CONTACT",repeat,true,true,true,true,layout,ContactJumpFollow.Mode.IMMEDIATE));
        out.add(new Trial("skeleton","PULL",0,false,true,false,false));return List.copyOf(out);
    }
    private boolean evasionControl(){return trial().tactic().equals("EVASION_CONTACT");}
    public static TacticalKnockbackScenario contactJump(){return new TacticalKnockbackScenario(true,true,true,true,true,true);}
    public static List<Trial> jumpTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++)for(String layout:List.of("near","far","diagonal","wall"))
            for(ContactJumpFollow.Mode mode:repeat%2==1?List.of(ContactJumpFollow.Mode.NONE,ContactJumpFollow.Mode.IMMEDIATE,ContactJumpFollow.Mode.CONFIRMED):List.of(ContactJumpFollow.Mode.CONFIRMED,ContactJumpFollow.Mode.IMMEDIATE,ContactJumpFollow.Mode.NONE))
                out.add(new Trial("skeleton","IMPULSE_CONTACT",repeat,true,true,true,true,layout,mode));
        out.add(new Trial("skeleton","PULL",0,false,true,false,false));return List.copyOf(out);
    }
    public static TacticalKnockbackScenario contactRobustness(){return new TacticalKnockbackScenario(true,true,true,true,true);}
    public static List<Trial> robustnessTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++)for(String layout:List.of("near","far","diagonal","wall"))
            for(boolean impulse:repeat%2==1?new boolean[]{false,true}:new boolean[]{true,false})
                out.add(new Trial("skeleton",impulse?"IMPULSE_CONTACT":"PULL_CONTACT",repeat,true,true,true,true,layout));
        out.add(new Trial("skeleton","PULL",0,false,true,false,false));return List.copyOf(out);
    }
    private double spawnX(){return switch(trial().layout()){case "diagonal"->3.5;case "wall"->2.5;default->.5;};}
    private double spawnZ(){return switch(trial().layout()){case "near"->2.5;case "far"->8.5;case "diagonal"->4.5;default->trial().ranged()?5.0:6.5;};}
    public static TacticalKnockbackScenario contactImpulse(){return new TacticalKnockbackScenario(true,true,true,true);}
    public static List<Trial> impulseTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++)for(boolean attack:List.of(true,false))for(boolean impulse:repeat%2==1?new boolean[]{false,true}:new boolean[]{true,false})
            out.add(new Trial("skeleton",impulse?"IMPULSE_CONTACT":"PULL_CONTACT",repeat,attack,true,true,true));
        out.add(new Trial("skeleton","PULL",0,false,true,false,false));return List.copyOf(out);
    }
    private boolean impulseControl(){return trial().tactic().equals("IMPULSE_CONTACT") || evasionControl();}
    private String controller(){return evasionControl()?"evasion":impulseControl()?"impulse":trial().servo()?"servo":trial().contact()?"legacy":"gap";}
    public static TacticalKnockbackScenario contactTracking(){return new TacticalKnockbackScenario(true,true,true);}
    public static List<Trial> trackingTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++)for(boolean attack:List.of(true,false))for(boolean servo:repeat%2==1?new boolean[]{false,true}:new boolean[]{true,false})
            out.add(new Trial("skeleton","PULL_CONTACT",repeat,attack,true,true,servo));
        out.add(new Trial("skeleton","PULL",0,false,true,false,false));return List.copyOf(out);
    }
    public static TacticalKnockbackScenario rangedContact(){return new TacticalKnockbackScenario(true,true);}
    public static List<Trial> overlapTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++)for(String tactic:repeat%2==1?List.of("PULL","PULL_CHAIN","PULL_CONTACT"):List.of("PULL_CONTACT","PULL_CHAIN","PULL"))
            out.add(new Trial("skeleton",tactic,repeat,true,true,tactic.equals("PULL_CONTACT")));
        out.add(new Trial("skeleton","PULL",0,false,true,false));out.add(new Trial("skeleton","PULL_CONTACT",0,false,true,true));return List.copyOf(out);
    }
    private RangedArrowProbe arrowProbe;
    private ClientArrowTimingProbe clientArrowClock;
    private List<Map<String,Object>> arrowShots=List.of();
    private Snapshot previousExposure;
    private CreativeTestContext exposureContext;
    private long overlapObservedTicks;
    private int overlapSamples;
    private double overlapObservedHealthLoss;

    public static TacticalKnockbackScenario rangedPursuit(){return new TacticalKnockbackScenario(true);}
    public static List<Trial> rangedTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++)for(boolean chase:repeat%2==1?new boolean[]{false,true}:new boolean[]{true,false})
            for(String tactic:repeat%2==1?List.of("OUTWARD","PULL"):List.of("PULL","OUTWARD"))out.add(new Trial("skeleton",tactic,repeat,true,chase));
        out.add(new Trial("skeleton","OUTWARD",0,false,false));out.add(new Trial("skeleton","OUTWARD",0,false,true));return List.copyOf(out);
    }
    private long approachTick=-1;
    private boolean chasing,haveTravelSample;
    private double measuredTravel,travelX,travelZ;
    private int chaseUpdates;
    private final List<Map<String,Object>> results=new ArrayList<>(),samples=new ArrayList<>(),attacks=new ArrayList<>();
    private Phase phase;
    private int index,age,waitAge,recoveries;
    private volatile UUID target;
    private volatile Snapshot reply;
    private Snapshot latest,sent;
    private volatile boolean querying,closed;
    private volatile String error;
    private Input saved;private EscapeInput owned;private boolean autoJump;
    private AutoCloseable observation;private List<Map<String,Object>> probe=List.of();
    private long firstTick=-1,lastTick=-1,lastAttack=-1,exitTick=-1;
    private float previousHealth=1000,pendingHealth;
    private double damage,minimum=1000;
    private boolean pending,confirmed;
    private Trial trial(){return trials.get(index);}
    @Override public String name(){return projectileExperiment?"projectile_evasion":jumpExperiment?"contact_jump":robustnessExperiment?"contact_robustness":impulseExperiment?"contact_impulse":trackingExperiment?"contact_tracking":overlapExperiment?"ranged_contact":rangedExperiment?"ranged_pursuit":"tactical_knockback";}
    private void enter(CreativeTestContext c,Phase next){phase=next;age=c.client.player.age;c.phase((projectileExperiment?"PROJECTILE EVASION ":jumpExperiment?"CONTACT JUMP ":robustnessExperiment?"CONTACT ROBUSTNESS ":impulseExperiment?"CONTACT IMPULSE ":trackingExperiment?"CONTACT TRACKING ":overlapExperiment?"RANGED CONTACT ":rangedExperiment?"RANGED PURSUIT ":"TACTICAL KB ")+(index+1)+"/"+trials.size()+" "+trial().group()+" "+trial().tactic()+" "+trial().layout()+" "+trial().jumpMode()+" "+next);}
    private void fail(CreativeTestContext c,String reason){c.complete(TestBatch.Outcome.ERROR,reason,Map.of("case",index+1,"phase",phase==null?"START":phase.name(),"completed",results.size()));}
    @Override public void start(CreativeTestContext c){
        exposureContext=c;
        if(c.client.getServer()==null){fail(c,"SINGLEPLAYER_REQUIRED");return;}
        c.event("tactical_knockback_plan",Map.of("cases",trials.size(),"weapon","iron axe only; no crit request; isolate direction",
                "melee",rangedExperiment?"not included in ranged-only profile":"one sprint-flag hit at distance <=3, then walk world +Z to z=8.5; compare outward and two side directions, 100-tick limit",
                "ranged",overlapExperiment?"active chase with first-only pull, repeat pull and contact pursuit; see ranged_contact_plan":rangedExperiment?"stationary vs active walking pursuit; outward vs pull; see ranged_pursuit_plan":"bow skeleton starts 4.5 blocks away; first outward vs reverse sprint-flag hit; then ordinary charged attacks within 5, no chase; 160-tick limit",
                "fixture","player 1000 health, enemies 20; real AI targeting player, natural regeneration off; three interleaved repeats and two blank controls",
                "scope","controlled corridor/flat experiments; arrows remain dangerous, no production defense integration"));
        if(rangedExperiment && !overlapExperiment)c.event("ranged_pursuit_plan",Map.of("cases",trials.size(),
                "comparison","stationary outward/pull vs walking pursuit outward/pull; three interleaved repeats and two blank controls",
                "startAttackDistance",3.0,"followAttackDistance",3.25,"stopDistance",2.5,"resumeDistance",3.0,
                "movement","walk directly toward latest target position on this flat test arena; renew only on fresh observations; no jumps or real sprint run-up",
                "timing","approach and post-first-hit durations both recorded; damage includes approach; projectile evasion not implemented"));
        if(overlapExperiment && !projectileExperiment)c.event("ranged_contact_plan",Map.of("cases",trials.size(),
                "comparison","first-pull only vs repeat nonlethal pull vs repeat pull plus collision-box contact pursuit; three repeats",
                "repeatPull","nonlethal followup above .8 horizontal distance also uses sprint-flag reverse; closer or killable target uses ordinary hit",
                "contactBand","stop .15 / resume .4 horizontal distance, no teleport or collision changes",
                "measurement","actual server AABB overlap and health loss; read-only arrow spawn probe records overlap at emission; two no-attack controls"));
        if(projectileExperiment)c.event("projectile_evasion_plan",Map.of("cases",trials.size(),"comparison","immediate follow-jump vs same pursuit plus observed-arrow evasion","player","GroundAgent vanilla tickMovement; candidate paths 12 ticks, execute 2 then renew","arrow","dry-air position then .99 velocity drag and .05 gravity; block raycast; relative swept-body slab test with .3 conservative padding","scope","client observations only; no bow timing or unknown future arrows; dynamic mob pushing is omitted and errors measured; unsupported rollouts fall back visibly"));
        if(jumpExperiment && !projectileExperiment)c.event("contact_jump_plan",Map.of("cases",trials.size(),"comparison","same v137 horizontal controller; NONE vs IMMEDIATE vs CONFIRMED; three interleaved repeats per geometry","confirmed","health decreased after sent pull; target feet >.2 above player; horizontal separation <1.3; grounded and clear overhead","input","one jump pulse per nonlethal pull; request expires after 12 client ticks; stale movement disables pulse","limits","jump may change crit and follow-up timing; inspect actual server attack probe, not assumed damage"));
        if(robustnessExperiment && !projectileExperiment)c.event("contact_robustness_plan",Map.of("cases",trials.size(),"layouts",List.of("near: 2 blocks","far: 8 blocks","diagonal: +3 X / +4 Z","wall: target at 2.5,4,5; wall X=3"),"comparison","v136 servo vs v137 attack-window interception; three interleaved repeats; identical attacks; final no-attack gap control","limits","flat floor plus one straight side wall, no obstacle pathfinding or projectile dodge; PASS validates measurement, not superiority"));
        if(trackingExperiment && !projectileExperiment)c.event("contact_tracking_plan",Map.of("cases",trials.size(),"comparison",impulseExperiment?"v136 servo vs attack-window interception; identical attacks and three interleaved repeats":"v135 threshold pursuit vs per-client-tick input servo, with and without attacks; three interleaved repeats",
                "servo","observed target displacement predicts horizontal movement; proportional position error plus relative velocity braking; ordinary analog input only",
                "limits",".098 ground/.02 air acceleration estimates, max desired speed .215; flat unbuffed walking test only; four-tick observation TTL",
                "evidence","actual AABB overlap, arrow emission and health loss; no immunity assumption"));
        c.command("/tp @s 0.5 4.0 0.5 0 0");enter(c,Phase.ARRIVE);
    }
    private boolean chunks(MinecraftClient c){for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++)if(c.world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)==null)return false;return true;}
    private boolean terrainReady(MinecraftClient c){
        if(!chunks(c))return false;
        for(int x=-2;x<=3;x++)for(int z=-2;z<=10;z++)for(int y=4;y<=6;y++){
            boolean wall=(!trial().ranged() && (x==-2 || x==3)) || (trial().layout().equals("wall") && x==3);
            if(c.world.getBlockState(new BlockPos(x,y,z)).getBlock()!=(wall?Blocks.STONE:Blocks.AIR))return false;
        }
        return true;
    }
    @Override public void tick(CreativeTestContext c){
        if(c.done() || phase==Phase.FINISHED)return;
        if(error!=null){c.event("tactical_observation_error",Map.of("error",error));fail(c,"TACTICAL_SERVER_ERROR");return;}
        if(c.phaseMillis()>30000){fail(c,"TACTICAL_PHASE_TIMEOUT");return;}
        if(c.client.player.age-waitAge>=40){waitAge=c.client.player.age;c.event("tactical_wait",Map.of("case",index+1,"phase",phase.name(),"attacks",attacks.size(),"damage",damage));}
        switch(phase){
            case ARRIVE:
                if(!chunks(c.client) || Math.hypot(c.client.player.getX()-.5,c.client.player.getZ()-.5)>.1)return;
                c.command("/fill -12 3 -12 12 3 12 minecraft:stone");c.command("/fill -12 4 -12 12 9 12 minecraft:air");
                c.command("/time set midnight");c.command("/gamerule doDaylightCycle false");c.command("/gamerule doMobSpawning false");c.command("/gamerule naturalRegeneration false");
                c.command("/difficulty normal");c.command("/effect clear @s");c.command("/effect give @s minecraft:saturation 100000 0 true");
                c.command("/attribute @s minecraft:generic.max_health base set 1000");enter(c,Phase.FLOOR);return;
            case FLOOR:
                for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)if(c.client.world.getBlockState(new BlockPos(x,3,z)).getBlock()!=Blocks.STONE)return;
                c.command("/gamemode survival");enter(c,Phase.ARENA);return;
            case ARENA:
                if(c.client.player.abilities.creativeMode || c.client.player.age-age<20)return;setup(c,true);return;
            case TERRAIN:
                if(!terrainReady(c.client))return;enter(c,Phase.SETUP);return;
            case SETUP:
                if(c.client.player.age-age<20 || c.client.player.inventory.getStack(1).getItem()!=Items.IRON_AXE)return;
                if(!querying)query(c);if(reply==null)return;Snapshot ready=take();
                if(c.client.world.getEntityById(ready.id())==null)return;
                if(ready.playerHealth()<999 || !ready.ground() || Math.abs(ready.py()-4)>.05 || Math.hypot(ready.px()-.5,ready.pz()-.5)>.05 || !ready.noAI()
                        || Math.abs(ready.tx()-spawnX())>.05 || Math.abs(ready.tz()-spawnZ())>.05 || Math.abs(ready.health()-20)>.05 || trial().ranged()!=ready.bow()){
                    c.event("tactical_fixture_invalid",Map.of("snapshot",ready.toString(),"retry",recoveries));if(recoveries++>=2){fail(c,"TACTICAL_FIXTURE_INVALID");return;}setup(c,false);return;
                }
                saved=c.client.player.input;owned=new EscapeInput(c.client.player);if(jumpExperiment)owned.jumper=new ContactJumpFollow(trial().jumpMode());
                if(evasionControl()){owned.evasion=new ArrowEvasionExperiment(target);owned.context=c.client;int caseNumber=index+1;owned.evasionLog=data->{Map<String,Object> row=new LinkedHashMap<>(data);row.put("case",caseNumber);c.event("projectile_evasion_update",row);};}
                c.client.player.input=owned;autoJump=c.client.options.autoJump;c.client.options.autoJump=false;c.client.options.keySprint.setPressed(false);
                c.client.player.inventory.selectedSlot=1;c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(1));enter(c,Phase.PREPARE);return;
            case PREPARE:
                if(c.client.player.age-age<35)return;release(c);enter(c,Phase.APPROACH);return;
            case APPROACH:
                if(!querying)query(c);if(reply==null)return;Snapshot approaching=take();
                if(approaching.noAI() || !approaching.targeting())return;confirmed=true;
                if(approachTick<0)approachTick=approaching.tick();
                if(trial().chase())follow(c,approaching);
                if(!approaching.axe() || approaching.cooldown()<.99 || distance(approaching)>(trial().ranged() && !trial().chase()?5:3))return;
                firstTick=approaching.tick();sent=approaching;
                if(trial().attack()){send(c,approaching,true);enter(c,Phase.ACK);}else {if(!trial().ranged())walk(c);enter(c,Phase.OBSERVE);}return;
            case ACK:
                observe(c,true);return;
            case OBSERVE:
                observe(c,false);return;
            default:return;
        }
    }
    private static double distance(Snapshot s){return Math.sqrt(sq(s.tx()-s.px())+sq(s.ty()-s.py())+sq(s.tz()-s.pz()));}
    private static double sq(double x){return x*x;}
    private void walk(CreativeTestContext c){owned.escape=true;owned.validUntil=c.client.player.age+4;}
    private void follow(CreativeTestContext c,Snapshot s){
        double dx=s.tx()-s.px(),dz=s.tz()-s.pz(),d=Math.hypot(dx,dz);boolean previous=chasing;
        owned.targetX=s.tx();owned.targetZ=s.tz();
        if(owned.jumper!=null){
            owned.jumper.observe(s.tick(),s.health(),s.ty()-s.py(),d);
            BlockPos feet=c.client.player.getBlockPos();
            owned.headroom=c.client.world.getBlockState(feet.up(2)).getBlock()==Blocks.AIR && c.client.world.getBlockState(feet.up(3)).getBlock()==Blocks.AIR;
        }
        if(trial().servo()){
            if(owned.servo==null)owned.servo=new ContactFollowServo(impulseControl());
            owned.servo.observe(s.tick(),c.client.player.age,s.tx(),s.tz());
            chasing=s.health()>0;owned.escape=chasing;owned.validUntil=owned.servo.validUntilAge();
            if(s.tick()%10<2)c.event("contact_servo_update",Map.of("case",index+1,"tick",s.tick(),"distance",d,"overlap",s.overlap(),"servo",owned.servo.summary()));
        }else{
            double stop=trial().contact()?.15:2.5,resume=trial().contact()?.4:3;
            if(s.health()<=0 || d<=stop)chasing=false;else if(d>resume)chasing=true;
            owned.dx=d<.001?0:dx/d;owned.dz=d<.001?0:dz/d;owned.escape=chasing;owned.validUntil=c.client.player.age+4;
        }
        if(chasing)chaseUpdates++;
        if(previous!=chasing)c.event("ranged_follow_update",Map.of("case",index+1,"tick",s.tick(),"chasing",chasing,"distance",d,
                "playerPos",List.of(s.px(),s.py(),s.pz()),"targetPos",List.of(s.tx(),s.ty(),s.tz())));
    }
    private void observe(CreativeTestContext c,boolean firstAck){
        // Do not renew movement while waiting for a stalled server observation: the four-tick TTL must expire.
        if(!querying)query(c);if(reply==null)return;Snapshot s=take();if(s.tick()<=lastTick)return;lastTick=s.tick();
        if(trial().chase())follow(c,s);
        damage+=Math.max(0,previousHealth-s.playerHealth());previousHealth=s.playerHealth();minimum=Math.min(minimum,distance(s));
        samples.add(Map.of("tick",s.tick(),"playerPos",List.of(s.px(),s.py(),s.pz()),"playerHealth",s.playerHealth(),"targetPos",List.of(s.tx(),s.ty(),s.tz()),
                "targetHealth",s.health(),"distance",distance(s),"cooldown",s.cooldown(),"targetsPlayer",s.targeting(),"movementActive",owned.escape,"laneBlocked",Math.abs(s.tx()-s.px())<.9 && s.tz()>s.pz() && s.tz()-s.pz()<2.5));
        if(overlapExperiment){Map<String,Object> sample=samples.get(samples.size()-1);Map<String,Object> detail=new LinkedHashMap<>(sample);detail.put("overlap",s.overlap());detail.put("playerBox",s.playerBox());detail.put("targetBox",s.targetBox());samples.set(samples.size()-1,Map.copyOf(detail));}
        if(pending){
            if(s.tick()>lastAttack && (s.health()<pendingHealth || s.cooldown()<.99))pending=false;
            else if(s.tick()-lastAttack>20){fail(c,"TACTICAL_ATTACK_ACK_TIMEOUT");return;}
        }
        if(firstAck){if(pending)return;if(!trial().ranged())walk(c);enter(c,Phase.OBSERVE);}
        if(!trial().ranged()){
            walk(c);if(s.pz()>=8.5 && exitTick<0)exitTick=s.tick();
            if(exitTick>=0 || s.tick()-firstTick>=100){finish(c,s);return;}
        }else{
            if(s.health()<=0 || s.tick()-firstTick>=160){finish(c,s);return;}
            if(trial().attack() && !pending && s.axe() && s.cooldown()>=.99 && distance(s)<=(trial().chase()?3.25:5))send(c,s,false);
        }
    }
    public static float attackYaw(double px,double pz,double tx,double tz,String tactic){
        float radial=(float)(Math.toDegrees(Math.atan2(tz-pz,tx-px))-90);
        return switch(tactic){case "PULL"->radial+180;case "SIDE_POS_X"->radial-90;case "SIDE_NEG_X"->radial+90;default->radial;};
    }
    private void sprint(CreativeTestContext c,boolean active){c.client.player.setSprinting(active);c.client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(c.client.player,active?ClientCommandC2SPacket.Mode.START_SPRINTING:ClientCommandC2SPacket.Mode.STOP_SPRINTING));}
    private void look(CreativeTestContext c,float yaw){c.client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(yaw,0,c.client.player.isOnGround()));}
    private void send(CreativeTestContext c,Snapshot s,boolean first){
        Entity e=c.client.world.getEntityById(s.id());if(e==null){fail(c,"TACTICAL_CLIENT_TARGET_MISSING");return;}
        boolean chain=trial().tactic().equals("PULL_CHAIN") || trial().tactic().equals("PULL_CONTACT") || impulseControl();
        boolean repeatPull=!first && chain && s.health()+.2>9 && Math.hypot(s.tx()-s.px(),s.tz()-s.pz())>.8;
        String tactic=first && (chain || trial().tactic().equals("PULL")) || repeatPull?"PULL":first?trial().tactic():"OUTWARD";
        boolean sprintHit=first || repeatPull;float yaw=attackYaw(s.px(),s.pz(),s.tx(),s.tz(),tactic);
        c.client.player.yaw=yaw;c.client.player.pitch=0;look(c,yaw);sprint(c,sprintHit);
        c.client.interactionManager.attackEntity(c.client.player,e);c.client.player.swingHand(Hand.MAIN_HAND);
        sprint(c,false);look(c,0);c.client.player.yaw=0;
        Map<String,Object> row=Map.of("case",index+1,"tick",s.tick(),"first",first,"tactic",tactic,"yaw",yaw,"sprintFlag",sprintHit,"distance",distance(s),"healthBefore",s.health(),"cooldown",s.cooldown());
        if(owned!=null && owned.servo!=null)owned.servo.attackSent(s.tick());
        if(owned!=null && owned.jumper!=null && tactic.equals("PULL") && s.health()>9){
            owned.jumper.attackSent(s.tick(),c.client.player.age,s.health());
            c.event("contact_jump_request",Map.of("case",index+1,"tick",s.tick(),"mode",trial().jumpMode().name(),"playerPos",List.of(s.px(),s.py(),s.pz()),"targetPos",List.of(s.tx(),s.ty(),s.tz())));
        }
        attacks.add(row);c.event("tactical_attack_sent",row);lastAttack=s.tick();pendingHealth=s.health();pending=true;
    }
    private void setup(CreativeTestContext c,boolean resetRetries){
        restore(c);stopProbe();if(resetRetries)recoveries=0;if(target!=null)c.command("/kill "+target);
        c.command("/kill @e[type=minecraft:arrow]");
        target=UUID.randomUUID();previousExposure=null;overlapObservedTicks=0;overlapSamples=0;overlapObservedHealthLoss=0;arrowShots=List.of();approachTick=-1;chasing=haveTravelSample=false;measuredTravel=0;chaseUpdates=0;reply=latest=null;querying=false;pending=confirmed=false;firstTick=lastTick=lastAttack=exitTick=-1;previousHealth=1000;damage=0;minimum=1000;samples.clear();attacks.clear();probe=List.of();
        observation=CriticalCombatScenario.beginObservation(c.client.player.getUuid(),List.of(target),rows->probe=rows);
        if(overlapExperiment)arrowProbe=new RangedArrowProbe(c.client.player.getUuid(),target);
        if(projectileExperiment){int caseNumber=index+1;clientArrowClock=new ClientArrowTimingProbe(c.client.world,target,data->{Map<String,Object> row=new LinkedHashMap<>(data);row.put("case",caseNumber);c.event("client_arrow_timing",row);});}
        c.command("/fill -12 4 -12 12 9 12 minecraft:air");
        if(!trial().ranged()){c.command("/fill -2 4 -2 -2 6 10 minecraft:stone");c.command("/fill 3 4 -2 3 6 10 minecraft:stone");}
        if(trial().layout().equals("wall"))c.command("/fill 3 4 -2 3 6 10 minecraft:stone");
        c.client.player.setVelocity(0,0,0);IntegratedServer server=c.client.getServer();UUID expected=target,player=c.client.player.getUuid();
        server.execute(()->{if(closed || !expected.equals(target))return;ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);if(p!=null)p.setVelocity(0,0,0);});
        sprint(c,false);c.command("/tp @s 0.5 4.0 0.5 0 0");c.command("/clear @s");c.command("/effect give @s minecraft:instant_health 1 10 true");
        c.command("/replaceitem entity @s hotbar.1 minecraft:iron_axe");
        c.command("/summon minecraft:"+trial().group()+" "+spawnX()+" 4.0 "+spawnZ()+" {UUID:"+PacketCombatScenario.uuidNbt(target)+",NoAI:1b,Silent:1b,PersistenceRequired:1b,IsBaby:0b}");
        c.command("/attribute "+target+" minecraft:generic.knockback_resistance base set 0");
        c.command("/data merge entity "+target+" {Health:20f,CanPickUpLoot:0b,ArmorItems:[{},{},{},{}],HandItems:"+(trial().ranged()?"[{id:\"minecraft:bow\",Count:1b},{}]":"[{},{}]")+"}");
        c.event("tactical_case_setup",Map.of("layout",trial().layout(),"spawn",List.of(spawnX(),4.0,spawnZ()),"case",index+1,"group",trial().group(),"tactic",trial().tactic(),"repeat",trial().repeat(),"attack",trial().attack(),"chase",trial().chase()));enter(c,Phase.TERRAIN);
    }
    private void release(CreativeTestContext c){
        IntegratedServer server=c.client.getServer();UUID expected=target,player=c.client.player.getUuid();server.execute(()->{
            if(closed || !expected.equals(target))return;Entity e=server.getWorld(World.OVERWORLD).getEntity(expected);ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);
            if(!(e instanceof MobEntity m) || p==null){error="release target/player missing";return;}m.setAiDisabled(false);m.setTarget(p);
        });
    }
    private void query(CreativeTestContext c){
        querying=true;IntegratedServer server=c.client.getServer();UUID expected=target,player=c.client.player.getUuid();Snapshot prior=latest;boolean preparing=phase==Phase.SETUP;
        server.execute(()->{try{
            if(closed || !expected.equals(target))return;ServerWorld w=server.getWorld(World.OVERWORLD);ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);Entity e=w.getEntity(expected);
            if(p==null)throw new IllegalStateException("player missing");
            Snapshot value;
            if(e instanceof MobEntity m)value=new Snapshot(w.getTime(),e.getEntityId(),p.getX(),p.getY(),p.getZ(),p.getHealth(),p.isOnGround(),p.getAttackCooldownProgress(.5f),p.getMainHandStack().getItem()==Items.IRON_AXE,
                    e.getX(),e.getY(),e.getZ(),m.getHealth(),m.isAiDisabled(),m.getTarget()==p,m.getMainHandStack().getItem()==Items.BOW,p.getBoundingBox().intersects(m.getBoundingBox()),RangedArrowProbe.box(p.getBoundingBox()),RangedArrowProbe.box(m.getBoundingBox()));
            else if(prior!=null && prior.health()<=0)value=new Snapshot(w.getTime(),prior.id(),p.getX(),p.getY(),p.getZ(),p.getHealth(),p.isOnGround(),p.getAttackCooldownProgress(.5f),p.getMainHandStack().getItem()==Items.IRON_AXE,
                    prior.tx(),prior.ty(),prior.tz(),0,false,false,prior.bow(),false,RangedArrowProbe.box(p.getBoundingBox()),prior.targetBox());
            else {if(preparing){querying=false;return;}throw new IllegalStateException("target disappeared before observed death");}
            if(!closed && expected.equals(target))reply=value;
        }catch(Exception ex){if(!closed && expected.equals(target))error=ex.toString();}});
    }
    private Snapshot take(){Snapshot s=reply;reply=null;querying=false;latest=s;
        if(phase==Phase.APPROACH || phase==Phase.ACK || phase==Phase.OBSERVE){
            if(overlapExperiment && (previousExposure==null || s.tick()>previousExposure.tick())){
                if(s.overlap())overlapSamples++;
                if(previousExposure!=null){
                    if(previousExposure.overlap() && s.overlap())overlapObservedTicks+=s.tick()-previousExposure.tick();
                    double lost=Math.max(0,previousExposure.playerHealth()-s.playerHealth());
                    if(lost>0){if(previousExposure.overlap() && s.overlap())overlapObservedHealthLoss+=lost;
                        exposureContext.event("ranged_damage_state",Map.of("case",index+1,"tick",s.tick(),"damage",lost,"overlapBefore",previousExposure.overlap(),"overlapNow",s.overlap(),"playerBox",s.playerBox(),"targetBox",s.targetBox()));
                    }
                    if(previousExposure.overlap()!=s.overlap())exposureContext.event("ranged_overlap_state",Map.of("case",index+1,"tick",s.tick(),"overlap",s.overlap(),"playerBox",s.playerBox(),"targetBox",s.targetBox()));
                }
                previousExposure=s;
            }
            if(haveTravelSample)measuredTravel+=Math.hypot(s.px()-travelX,s.pz()-travelZ);
            travelX=s.px();travelZ=s.pz();haveTravelSample=true;
        }
        return s;
    }
    private void finish(CreativeTestContext c,Snapshot s){
        Map<String,Object> nativeTiming=clientArrowClock==null?Map.of():clientArrowClock.summary();
        Map<String,Object> evasionSummary=owned!=null && owned.evasion!=null?owned.evasion.summary():Map.of();
        List<Map<String,Object>> jumpInputs=owned==null?List.of():List.copyOf(owned.jumpInputs);
        Map<String,Object> jumpSummary=owned!=null && owned.jumper!=null?owned.jumper.summary():Map.of();
        Map<String,Object> servoSummary=owned!=null && owned.servo!=null?owned.servo.summary():Map.of();
        stopProbe();restore(c);
        Map<String,Object> head=probe.stream().filter(e->e.get("signal").equals("ATTACK_HEAD")).findFirst().orElse(Map.of());
        Map<String,Object> returned=probe.stream().filter(e->e.get("signal").equals("ATTACK_RETURN")).findFirst().orElse(Map.of());
        double firstDamage=head.isEmpty() || returned.isEmpty()?0:((Number)head.get("targetHealth")).doubleValue()-((Number)returned.get("targetHealth")).doubleValue();
        double yawError=0;
        if(!head.isEmpty() && !attacks.isEmpty()){
            yawError=(((Number)head.get("yaw")).doubleValue()-((Number)attacks.get(0).get("yaw")).doubleValue())%360;
            if(yawError>180)yawError-=360;if(yawError<-180)yawError+=360;yawError=Math.abs(yawError);
        }
        boolean firstCondition=!trial().attack() || !head.isEmpty() && head.get("sprinting").equals(true) && yawError<1 && ((Number)head.get("cooldown")).doubleValue()>=.99;
        Map<String,Object> row=new LinkedHashMap<>();row.put("firstAttackConditionMatched",firstCondition);row.put("firstYawErrorDegrees",yawError);row.put("firstHitDamage",firstDamage);row.put("case",index+1);row.put("group",trial().group());row.put("tactic",trial().tactic());row.put("repeat",trial().repeat());row.put("layout",trial().layout());row.put("jumpMode",trial().jumpMode().name());row.put("jump",jumpSummary);row.put("jumpInputs",jumpInputs);row.put("evasion",evasionSummary);row.put("nativeArrowTiming",nativeTiming);row.put("attackEnabled",trial().attack());
        if(overlapExperiment){
            long overlapShots=arrowShots.stream().filter(a->a.get("overlap").equals(true)).count();
            row.put("arrowShots",arrowShots);row.put("arrowSpawnCount",arrowShots.size());row.put("arrowSpawnsWhileOverlap",overlapShots);
            row.put("overlapSamples",overlapSamples);row.put("overlapObservedTicks",overlapObservedTicks);row.put("healthLossWithBothSamplesOverlapping",overlapObservedHealthLoss);
            row.put("overlapExposureObserved",overlapSamples>0 && overlapShots>0);row.put("contactPursuit",trial().contact());row.put("contactController",controller());row.put("servo",servoSummary);
        }
        row.put("movementPolicy",trial().chase()?"chase":"stationary");row.put("chaseUpdates",chaseUpdates);row.put("measuredTravel",measuredTravel);
        row.put("approachTicks",approachTick<0?-1:firstTick-approachTick);row.put("totalCombatTicks",approachTick<0?-1:s.tick()-approachTick);
        row.put("confirmedTargeting",confirmed);row.put("damage",damage);row.put("killed",s.health()<=0);row.put("escaped",exitTick>=0);row.put("escapeTicks",exitTick<0?-1:exitTick-firstTick);row.put("observedTicks",s.tick()-firstTick);
        row.put("blockedSamples",samples.stream().filter(sample->sample.get("laneBlocked").equals(true)).count());
        row.put("remainingHealth",s.health());row.put("finalDistance",distance(s));row.put("minimumDistance",minimum);row.put("packets",attacks.size());row.put("exitProgressZ",s.pz()-.5);
        row.put("attacks",List.copyOf(attacks));row.put("samples",List.copyOf(samples));row.put("probeEvents",probe);results.add(row);c.event("tactical_case_result",row);
        index++;if(index<trials.size()){setup(c,true);return;}phase=Phase.FINISHED;c.command("/kill "+target);c.command("/kill @e[type=minecraft:arrow]");c.command("/gamemode creative");
        boolean observed=results.stream().allMatch(r->((List<Map<String,Object>>)r.get("probeEvents")).stream().filter(p->p.get("signal").equals("ATTACK_HEAD")).count()==((Number)r.get("packets")).longValue()
                && ((List<Map<String,Object>>)r.get("probeEvents")).stream().filter(p->p.get("signal").equals("ATTACK_RETURN")).count()==((Number)r.get("packets")).longValue());
        boolean pursuit=results.stream().allMatch(r->r.get("confirmedTargeting").equals(true));
        boolean active=results.stream().filter(r->r.get("attackEnabled").equals(true)).allMatch(r->((Number)r.get("packets")).intValue()>0 && ((Number)r.get("firstHitDamage")).doubleValue()>0 && r.get("firstAttackConditionMatched").equals(true));
        boolean blanks=results.stream().filter(r->r.get("attackEnabled").equals(false)).allMatch(r->((Number)r.get("packets")).intValue()==0 && (overlapExperiment && r.get("contactPursuit").equals(true) || ((Number)r.get("damage")).doubleValue()>0));
        if(!rangedExperiment)for(String group:List.of("zombie","skeleton"))for(String tactic:group.equals("zombie")?List.of("OUTWARD","SIDE_POS_X","SIDE_NEG_X"):List.of("OUTWARD","PULL")){
            List<Map<String,Object>> selected=results.stream().filter(r->r.get("group").equals(group) && r.get("tactic").equals(tactic) && r.get("attackEnabled").equals(true)).toList();
            c.event("tactical_summary",Map.of("group",group,"tactic",tactic,"runs",selected.size(),"damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),
                    "escapes",selected.stream().filter(r->r.get("escaped").equals(true)).count(),"kills",selected.stream().filter(r->r.get("killed").equals(true)).count(),
                    "meanObservedTicks",selected.stream().mapToDouble(r->((Number)r.get("observedTicks")).doubleValue()).average().orElse(0)));
        }
        if(rangedExperiment && !overlapExperiment)for(String policy:List.of("stationary","chase"))for(String tactic:List.of("OUTWARD","PULL")){
            List<Map<String,Object>> selected=results.stream().filter(r->r.get("movementPolicy").equals(policy) && r.get("tactic").equals(tactic) && r.get("attackEnabled").equals(true)).toList();
            c.event("ranged_pursuit_summary",Map.of("policy",policy,"tactic",tactic,"runs",selected.size(),
                    "damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),"kills",selected.stream().filter(r->r.get("killed").equals(true)).count(),
                    "meanApproachTicks",selected.stream().mapToDouble(r->((Number)r.get("approachTicks")).doubleValue()).average().orElse(0),
                    "meanTotalTicks",selected.stream().mapToDouble(r->((Number)r.get("totalCombatTicks")).doubleValue()).average().orElse(0),
                    "meanObservedTicks",selected.stream().mapToDouble(r->((Number)r.get("observedTicks")).doubleValue()).average().orElse(0)));
        }
        boolean arrowObserver=!overlapExperiment || results.stream().filter(r->r.get("attackEnabled").equals(false) && !r.get("contactPursuit").equals(true)).anyMatch(r->((Number)r.get("arrowSpawnCount")).intValue()>0);
        if(overlapExperiment && !trackingExperiment)for(String tactic:List.of("PULL","PULL_CHAIN","PULL_CONTACT")){
            List<Map<String,Object>> selected=results.stream().filter(r->r.get("tactic").equals(tactic) && r.get("attackEnabled").equals(true)).toList();
            c.event("ranged_contact_summary",Map.of("tactic",tactic,"runs",selected.size(),"damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),
                    "kills",selected.stream().filter(r->r.get("killed").equals(true)).count(),"meanTotalTicks",selected.stream().mapToDouble(r->((Number)r.get("totalCombatTicks")).doubleValue()).average().orElse(0),
                    "overlapExposureRuns",selected.stream().filter(r->r.get("overlapExposureObserved").equals(true)).count()));
        }
        if(trackingExperiment)for(boolean attack:List.of(true,false))for(String controller:impulseExperiment?List.of("servo","impulse"):List.of("legacy","servo")){
            List<Map<String,Object>> selected=results.stream().filter(r->r.get("contactController").equals(controller) && r.get("attackEnabled").equals(attack)).toList();
            c.event("contact_tracking_summary",Map.of("controller",controller,"attacksEnabled",attack,"runs",selected.size(),
                    "damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),"kills",selected.stream().filter(r->r.get("killed").equals(true)).count(),
                    "meanTotalTicks",selected.stream().mapToDouble(r->((Number)r.get("totalCombatTicks")).doubleValue()).average().orElse(0),
                    "overlapObservedTicks",selected.stream().mapToLong(r->((Number)r.get("overlapObservedTicks")).longValue()).sum(),
                    "arrowsDuringOverlap",selected.stream().mapToLong(r->((Number)r.get("arrowSpawnsWhileOverlap")).longValue()).sum()));
        }
        if(robustnessExperiment && !jumpExperiment)for(String layout:List.of("near","far","diagonal","wall"))for(String controller:List.of("servo","impulse")){
            List<Map<String,Object>> selected=results.stream().filter(r->layout.equals(r.get("layout")) && controller.equals(r.get("contactController"))).toList();
            c.event("contact_robustness_summary",Map.of("layout",layout,"controller",controller,"runs",selected.size(),
                    "kills",selected.stream().filter(r->r.get("killed").equals(true)).count(),
                    "damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),
                    "meanTotalTicks",selected.stream().mapToDouble(r->((Number)r.get("totalCombatTicks")).doubleValue()).average().orElse(0),
                    "arrowsDuringOverlap",selected.stream().mapToLong(r->((Number)r.get("arrowSpawnsWhileOverlap")).longValue()).sum()));
        }
        if(jumpExperiment && !projectileExperiment)for(String layout:List.of("near","far","diagonal","wall"))for(ContactJumpFollow.Mode mode:ContactJumpFollow.Mode.values()){
            List<Map<String,Object>> selected=results.stream().filter(r->layout.equals(r.get("layout")) && mode.name().equals(r.get("jumpMode"))).toList();
            c.event("contact_jump_summary",Map.of("layout",layout,"mode",mode.name(),"runs",selected.size(),
                    "kills",selected.stream().filter(r->r.get("killed").equals(true)).count(),
                    "damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),
                    "meanTotalTicks",selected.stream().mapToDouble(r->((Number)r.get("totalCombatTicks")).doubleValue()).average().orElse(0),
                    "spawnInsidePlayerBox",selected.stream().mapToLong(r->((List<Map<String,Object>>)r.get("arrowShots")).stream().filter(a->Boolean.TRUE.equals(a.get("spawnInsidePlayerBox"))).count()).sum()));
        }
        if(projectileExperiment){
            boolean timingHook=results.stream().anyMatch(r->((Number)((Map<?,?>)r.get("nativeArrowTiming")).get("tickPairs")).longValue()>0);
            if(!timingHook){c.complete(TestBatch.Outcome.ERROR,"CLIENT_ARROW_TIMING_HOOK_NOT_OBSERVED",Map.of("cases",results.size()));return;}
            boolean exercised=results.stream().filter(r->"evasion".equals(r.get("contactController"))).anyMatch(r->((Number)((Map<?,?>)r.get("evasion")).get("plans")).intValue()>0);
            if(!exercised){c.complete(TestBatch.Outcome.ERROR,"NO_LIVE_ARROW_PREDICTION_EXERCISED",Map.of("cases",results.size()));return;}
            for(String layout:List.of("near","far","diagonal","wall"))for(String control:List.of("impulse","evasion")){
                List<Map<String,Object>> selected=results.stream().filter(r->layout.equals(r.get("layout")) && control.equals(r.get("contactController"))).toList();
                c.event("projectile_evasion_summary",Map.of("layout",layout,"controller",control,"runs",selected.size(),"damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),"kills",selected.stream().filter(r->r.get("killed").equals(true)).count(),"meanTotalTicks",selected.stream().mapToDouble(r->((Number)r.get("totalCombatTicks")).doubleValue()).average().orElse(0)));
            }
        }
        c.complete(observed&&pursuit&&active&&blanks&&arrowObserver?TestBatch.Outcome.PASS:TestBatch.Outcome.ERROR,observed&&pursuit&&active&&blanks&&arrowObserver?"TACTICAL_MEASUREMENT_COMPLETE":"TACTICAL_CONTROLS_FAILED",Map.of("cases",results.size(),"observer",observed,"targeting",pursuit,"activeAttacks",active,"blankControls",blanks,"arrowObserver",arrowObserver));
    }
    private void stopProbe(){if(clientArrowClock!=null){clientArrowClock.close();clientArrowClock=null;}if(arrowProbe!=null){arrowShots=arrowProbe.snapshot();arrowProbe.close();arrowProbe=null;}if(observation!=null)try{observation.close();}catch(Exception ex){error=ex.toString();}finally{observation=null;}}
    private void restore(CreativeTestContext c){if(owned!=null){owned.escape=false;owned.tick(false);if(c.client.player!=null && c.client.player.input==owned)c.client.player.input=saved;c.client.options.autoJump=autoJump;}owned=null;saved=null;}
    @Override public void close(CreativeTestContext c){closed=true;stopProbe();restore(c);if(c.client.player!=null && c.client.getNetworkHandler()!=null)sprint(c,false);}
}
