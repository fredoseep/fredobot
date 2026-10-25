package com.fredoseep.testing.combat;

import com.fredoseep.testing.CreativeTestContext;
import com.fredoseep.testing.CreativeWorldScenario;
import com.fredoseep.testing.TestBatch;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/** Multi-target experiment, not the production defense module. Server code only sets up
 * fixtures and returns immutable observations. All attacks use the ordinary client API. */
public final class ThreatDefenseScenario implements CreativeWorldScenario {
    private boolean criticalExperiment;
    private boolean combinedExperiment;
    private boolean continuationExperiment;
    private boolean validatedExperiment;
    private long loggedPlanSource=-1000;
    private int loggedPlanSegment=-1;
    private Map<String,Object> jumpAssessment=Map.of();
    private long lastJumpTick=-1;
    private double lastJumpMinimum=-1;
    private UUID finishWaitTarget;
    private final PursuitCriticalInput criticalInput=new PursuitCriticalInput();
    private AutoCloseable observation;
    private List<Map<String,Object>> criticalEvents=List.of();
    private final boolean comparison;
    private final boolean movementExperiment;
    private final boolean terrainExperiment;
    private final boolean escapeExperiment;
    private final CombatSidestepExperiment sidestep=new CombatSidestepExperiment();
    public ThreatDefenseScenario(){this(false);}
    private ThreatDefenseScenario(boolean comparison){this(comparison,false);}
    private ThreatDefenseScenario(boolean comparison,boolean movementExperiment){this(comparison,movementExperiment,false);}
    private ThreatDefenseScenario(boolean comparison,boolean movementExperiment,boolean terrainExperiment){this(comparison,movementExperiment,terrainExperiment,false);}
    private ThreatDefenseScenario(boolean comparison,boolean movementExperiment,boolean terrainExperiment,boolean escapeExperiment){this.comparison=comparison;this.movementExperiment=movementExperiment;this.terrainExperiment=terrainExperiment;this.escapeExperiment=escapeExperiment;
        this.trials=escapeExperiment?escapeTrials():terrainExperiment?terrainTrials():movementExperiment?movementTrials():comparison?comparisonTrials():trials();}
    public static ThreatDefenseScenario predictiveComparison(){return new ThreatDefenseScenario(true);}
    public static ThreatDefenseScenario sidestepComparison(){return new ThreatDefenseScenario(true,true);}
    public static ThreatDefenseScenario terrainComparison(){return new ThreatDefenseScenario(true,true,true);}
    public static ThreatDefenseScenario escapeComparison(){return new ThreatDefenseScenario(true,true,true,true);}
    public static ThreatDefenseScenario validatedContinuationComparison(){
        ThreatDefenseScenario s=continuationComparison();s.validatedExperiment=true;
        s.trials=continuationTrials().stream().map(t->new Trial(t.name().replace("_committed_","_validated_"),t.spawns(),t.emergency(),t.attack())).toList();return s;
    }
    public static ThreatDefenseScenario continuationComparison(){
        ThreatDefenseScenario s=new ThreatDefenseScenario(true,true,true,true);
        s.criticalExperiment=s.combinedExperiment=s.continuationExperiment=true;s.trials=continuationTrials();return s;
    }
    public static List<Trial> continuationTrials(){
        List<Trial> out=new ArrayList<>();List<Spawn> spawns=List.of(new Spawn(0,7),new Spawn(6,3),new Spawn(3,6));
        for(int repeat=1;repeat<=3;repeat++)for(boolean commit:repeat%2==1?new boolean[]{false,true}:new boolean[]{true,false})
            out.add(new Trial("corner_predictive_sidestep_critical_"+(commit?"committed":"rolling")+"_r"+repeat,spawns,true,true));
        out.add(new Trial("front",List.of(new Spawn(0,7)),false,true));
        out.add(new Trial("no_attack_control",List.of(new Spawn(0,7)),false,false));return List.copyOf(out);
    }
    private boolean continuationCase(){return continuationExperiment && (trials.get(index).name().contains("_committed_") || trials.get(index).name().contains("_validated_"));}
    public static ThreatDefenseScenario movingCriticalComparison(){
        ThreatDefenseScenario s=new ThreatDefenseScenario(true,true,true,true);
        s.criticalExperiment=true;s.combinedExperiment=true;s.trials=movingCriticalTrials();return s;
    }
    public static List<Trial> movingCriticalTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=2;repeat++)for(boolean jumping:repeat==1?new boolean[]{false,true}:new boolean[]{true,false})
            for(String group:List.of("single","two_sides","three_sides","corner")){
                List<Spawn> spawns=group.equals("single")?List.of(new Spawn(0,7)):
                        group.equals("two_sides")?List.of(new Spawn(0,7),new Spawn(0,-7)):
                        group.equals("corner")?List.of(new Spawn(0,7),new Spawn(6,3),new Spawn(3,6)):
                                List.of(new Spawn(0,7),new Spawn(-6,-3.5),new Spawn(6,-3.5));
                out.add(new Trial(group+"_predictive_sidestep_"+(jumping?"critical":"ground")+"_r"+repeat,spawns,true,true));
            }
        out.add(new Trial("front",List.of(new Spawn(0,7)),false,true));
        out.add(new Trial("no_attack_control",List.of(new Spawn(0,7)),false,false));return List.copyOf(out);
    }
    public static ThreatDefenseScenario criticalComparison(){
        ThreatDefenseScenario s=new ThreatDefenseScenario(true);s.criticalExperiment=true;s.trials=criticalTrials();return s;
    }
    public static List<Trial> criticalTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=2;repeat++)for(boolean jumping:repeat==1?new boolean[]{false,true}:new boolean[]{true,false})
            for(int count=1;count<=3;count++){
                List<Spawn> spawns=count==1?List.of(new Spawn(0,7)):count==2?List.of(new Spawn(0,7),new Spawn(0,-7)):
                        List.of(new Spawn(0,7),new Spawn(-6,-3.5),new Spawn(6,-3.5));
                out.add(new Trial(count+"_predictive_"+(jumping?"critical":"ground")+"_r"+repeat,spawns,true,true));
            }
        out.add(new Trial("front",List.of(new Spawn(0,7)),false,true));
        out.add(new Trial("no_attack_control",List.of(new Spawn(0,7)),false,false));return List.copyOf(out);
    }
    private boolean criticalCase(){return criticalExperiment && trials.get(index).name().contains("_critical_");}
    private void stopObservation(){
        if(observation!=null)try{observation.close();}catch(Exception ex){error=ex.toString();}finally{observation=null;}
    }
    public static List<Trial> escapeTrials(){
        List<Trial> out=new ArrayList<>();List<Spawn> spawns=List.of(new Spawn(0,7),new Spawn(6,3),new Spawn(3,6));
        for(int repeat=1;repeat<=3;repeat++)for(boolean escape:repeat%2==1?new boolean[]{false,true}:new boolean[]{true,false})
            out.add(new Trial("corner_predictive_sidestep_"+(escape?"escape":"legacy")+"_r"+repeat,spawns,true,true));
        out.add(new Trial("front",List.of(new Spawn(0,7)),false,true));
        out.add(new Trial("no_attack_control",List.of(new Spawn(0,7)),false,false));return List.copyOf(out);
    }
    private boolean escapeCase(){return combinedExperiment || escapeExperiment && trials.get(index).name().contains("_escape_");}
    public static List<Trial> terrainTrials(){
        List<Trial> out=new ArrayList<>();
        for(int repeat=1;repeat<=2;repeat++)for(boolean moving:repeat==1?new boolean[]{false,true}:new boolean[]{true,false})
            for(String layout:List.of("corner","corridor","step")){
                List<Spawn> spawns=layout.equals("corner")?List.of(new Spawn(0,7),new Spawn(6,3),new Spawn(3,6)):
                        layout.equals("corridor")?List.of(new Spawn(0,7),new Spawn(0,-7)):
                                List.of(new Spawn(0,7),new Spawn(-6,-3.5),new Spawn(6,-3.5));
                out.add(new Trial(layout+"_predictive_"+(moving?"sidestep":"stationary")+"_r"+repeat,spawns,true,true));
            }
        out.add(new Trial("front",List.of(new Spawn(0,7)),false,true));
        out.add(new Trial("no_attack_control",List.of(new Spawn(0,7)),false,false));return List.copyOf(out);
    }
    private CombatTerrainLayout.Kind terrain(){return terrainExperiment?CombatTerrainLayout.forName(trials.get(index).name()):CombatTerrainLayout.Kind.FLAT;}
    private String teleportStart(){return "/tp @s 0.5 "+CombatTerrainLayout.playerY(terrain())+" 0.5 0 0";}
    private boolean terrainReady(MinecraftClient c){
        if(!chunks(c))return false;
        for(int x=-12;x<=12;x++)for(int y=4;y<=6;y++)for(int z=-12;z<=12;z++)
            if(c.world.getBlockState(new BlockPos(x,y,z)).getBlock()!=(CombatTerrainLayout.stoneAt(terrain(),x,y,z)?Blocks.STONE:Blocks.AIR))return false;
        return true;
    }
    public static List<Trial> movementTrials(){
        List<Trial> out=new ArrayList<>();
        List<Spawn> pair=List.of(new Spawn(0,7),new Spawn(0,-7));
        List<Spawn> triple=List.of(new Spawn(0,7),new Spawn(-6,-3.5),new Spawn(6,-3.5));
        for(int repeat=1;repeat<=2;repeat++)for(boolean moving:repeat==1?new boolean[]{false,true}:new boolean[]{true,false}) {
            out.add(new Trial("two_sides_predictive_"+(moving?"sidestep":"stationary")+"_r"+repeat,pair,true,true));
            out.add(new Trial("three_sides_predictive_"+(moving?"sidestep":"stationary")+"_r"+repeat,triple,true,true));
        }
        out.add(new Trial("front",List.of(new Spawn(0,7)),false,true));
        out.add(new Trial("no_attack_control",List.of(new Spawn(0,7)),false,false));return List.copyOf(out);
    }
    private boolean movingCase(){return movementExperiment && trials.get(index).name().contains("_sidestep_");}
    public static List<Trial> comparisonTrials(){
        List<Trial> out=new ArrayList<>();
        List<Spawn> pair=List.of(new Spawn(0,7),new Spawn(0,-7));
        List<Spawn> triple=List.of(new Spawn(0,7),new Spawn(-6,-3.5),new Spawn(6,-3.5));
        for(int repeat=1;repeat<=2;repeat++) {
            // Reverse order on repeat 2 to reduce a fixed order bias; AI itself remains stochastic.
            for(boolean predictive:repeat==1?new boolean[]{false,true}:new boolean[]{true,false}) {
                out.add(new Trial("two_sides_"+(predictive?"predictive":"legacy")+"_r"+repeat,pair,true,true));
                out.add(new Trial("three_sides_"+(predictive?"predictive":"legacy")+"_r"+repeat,triple,true,true));
            }
        }
        out.add(new Trial("front",List.of(new Spawn(0,7)),false,true));
        out.add(new Trial("no_attack_control",List.of(new Spawn(0,7)),false,false));
        return List.copyOf(out);
    }
    private boolean predictive(){return comparison && trials.get(index).name().contains("_predictive_");}
    public record Spawn(double dx,double dz) {}
    public record Trial(String name,List<Spawn> spawns,boolean emergency,boolean attack) {}
    private enum Phase { ARRIVE, FLOOR, ARENA, TERRAIN, SETUP, PREPARE, FIGHT, FINISHED }
    private record Target(UUID uuid,int id,double x,double y,double z,float health,
                          double vx,double vy,double vz,boolean targetsPlayer,boolean noAI) {}
    private record Snapshot(long tick,double px,double py,double pz,float health,float cooldown,
                            boolean axe,boolean ground,float fall,List<Target> targets) {}
    public static List<Trial> trials() {
        List<Spawn> pair=List.of(new Spawn(0,7),new Spawn(0,-7));
        return List.of(new Trial("front",List.of(new Spawn(0,10)),false,true),
                new Trial("behind",List.of(new Spawn(0,-10)),false,true),
                new Trial("right",List.of(new Spawn(-10,0)),false,true),
                new Trial("left",List.of(new Spawn(10,0)),false,true),
                new Trial("close_front",List.of(new Spawn(0,2.25)),false,true),
                new Trial("two_sides_charged",pair,false,true),
                new Trial("two_sides_emergency",pair,true,true),
                new Trial("three_sides_emergency",List.of(new Spawn(0,7),new Spawn(-6,-3.5),new Spawn(6,-3.5)),true,true),
                new Trial("no_attack_control",List.of(new Spawn(0,7)),false,false));
    }
    private static final double ATTACK_RADIUS=5, EMERGENCY_RADIUS=2.5;
    private static final int EMERGENCY_GAP=10, WINDOW=300;
    private List<Trial> trials;
    private final Map<UUID,Long> damageTicks=new HashMap<>();
    private Snapshot motionPrevious;
    private final List<Map<String,Object>> samples=new ArrayList<>(), attacks=new ArrayList<>(),results=new ArrayList<>();
    private volatile List<UUID> ids=List.of();
    private volatile Snapshot reply;
    private volatile String error;
    private volatile boolean querying,closed;
    private Phase phase;
    private int index,enteredAge,waitAge;
    private int fixtureRecoveries;
    private long lastTick=-1,firstFightTick=-1,lastAttackTick=-1;
    private boolean confirmed,pending;
    private UUID pendingTarget,lastTarget;
    private float pendingHealth;
    private double previousHealth=1000,damage,nearest=Double.POSITIVE_INFINITY;
    private int targetSwitches;
    private Snapshot latest;

    @Override public String name(){return validatedExperiment?"validated_defense":continuationExperiment?"continuation_defense":combinedExperiment?"moving_critical":criticalExperiment?"critical_pursuit":escapeExperiment?"escape_defense":terrainExperiment?"terrain_defense":movementExperiment?"sidestep_defense":comparison?"predictive_defense":"threat_defense";}
    @Override public void start(CreativeTestContext c) {
        if(c.client.getServer()==null){fail(c,"SINGLEPLAYER_REQUIRED");return;}
        c.event("threat_defense_plan",Map.of("cases",trials.size(),"attackRadius",ATTACK_RADIUS,
                "emergencyRadius",EMERGENCY_RADIUS,"emergencyMinGapTicks",EMERGENCY_GAP,
                "windowServerTicks",WINDOW,"health","player 1000, adult unarmored zombies 20; natural regeneration disabled",
                "policy","charged attack when cooldown >= .99; emergency groups may strike early within 2.5 blocks after 10 ticks",
                "selection","shortest estimated time to 1.5-block separation; normal attacks prioritize threats closing toward player",
                "coordinates",movementExperiment?"player starts facing +Z; walking allowed only in sidestep groups":"player starts facing +Z; behind=-Z, right=-X, left=+X; no chase or evasive movement"));
        if(comparison && !combinedExperiment)c.event("predictive_comparison_plan",Map.of("repeats",escapeExperiment?3:2,"pairedGroups",escapeExperiment?List.of("corner"):terrainExperiment?List.of("corner","corridor","step"):List.of("two_sides","three_sides"),
                "prediction","relative displacement plus radial velocity; contact distance 2; charge estimate 22.23 ticks; latency margin 3",
                "execution","conservative empirical damage estimate plus .2 health margin; finish only if other threats have >15 estimated ticks",
                "note",movementExperiment?"same predictive attack policy; stationary vs short walking escapes; zombie AI is approximate":"heuristic estimates, not exact simulation; no chase or movement; old groups retain fixed 2.5-block emergency rule"));
        if(movementExperiment && !combinedExperiment)c.event("sidestep_plan",Map.of("playerSimulation","FusionWorld + GroundAgent vanilla detached player",
                "enemySimulation","approximate flat-ground pursuit and decaying knockback, not vanilla zombie AI",
                "horizonTicks",8,"directions",8,"radiusFromStart",8,"speed","walk; no jump/sprint; rolling replan; attack yaw independent of world movement",
                "comparison","predictive stationary vs same attack policy plus sidestep; two repeats, no production bot integration"));
        if(terrainExperiment && !combinedExperiment)c.event("terrain_defense_plan",Map.of("layouts",List.of("L-shaped corner, 3 zombies","3-block-wide corridor, 2 zombies","one-block-high 3x3 platform, 3 zombies"),
                "verification","wait for exact client terrain blocks before fixture validation and release",
                "note","step fixture starts player at Y=5; walking down allowed, no jumping up; enemy predictor still ignores terrain; packet attacks can pass through walls"));
        if(escapeExperiment && !combinedExperiment)c.event("escape_comparison_plan",Map.of("pairs",3,"layout","CORNER",
                "old","8 tick straight-line candidates; activates within 4.5 blocks",
                "new","up to 24 ticks in three 8-tick segments; beam width 6; activates within 7 blocks when >=2 directions blocked at radius 2",
                "limits","40ms soft budget per search; replan every 6 observed server ticks; actual completed horizon logged; movement TTL unchanged"));
        if(criticalExperiment && !combinedExperiment)c.event("critical_pursuit_plan",Map.of("cases",trials.size(),"repeats",2,
                "groups","1/2/3 actively pursuing zombies; same predictive attack policy, ground vs optional jump",
                "jump","one ordinary input pulse; estimate first falling window at 8 ticks; no horizontal movement",
                "safety","only jump with estimated contact >12 ticks; rising wait interrupted if contact <=5 ticks; normal attack fallback",
                "measurement","actual server attack entry and crit callback; no injected velocity, damage or fall distance during combat"));
        if(criticalExperiment)c.event("normal_finish_plan",Map.of("enabled",true,
                "rule","do not initiate a jump for selected target killable by full normal attack; wait for full charge when safe",
                "damageModel","existing conservative iron-axe / unarmored-zombie empirical estimate plus .2 health margin",
                "emergency","original attack policy may strike early when contact is imminent; high-health targets may require additional crits"));
        if(combinedExperiment)c.event("moving_critical_plan",Map.of("cases",trials.size(),"repeats",2,
                "groups","single/two_sides/three_sides/corner; identical 24-tick escape planner in both arms",
                "jumpGate","18-tick detached GroundAgent simulation; selected movement direction; landing required; minimum predicted separation 2.8",
                "input","one shared world-direction Input; jump is a single pulse; no combat position/velocity injection",
                "limits","enemy AI remains approximate; movement is replanned during flight; no guarantee against actual attacks"));
        if(continuationExperiment)c.event("continuation_defense_plan",Map.of("pairs",3,"layout","CORNER",
                "samePolicy","24 tick escape search; optional crit plus full normal finish",
                "rolling","replan every 6 observed server ticks, always execute first segment",
                "committed",validatedExperiment?"up to 16 ticks, validate next 6 scheduled ticks at every observation from actual player and current enemy states":"execute up to 16 ticks of the 24 tick plan, including its second 8-tick segment",
                "interrupt","position deviation >.85 blocks or enemy within 1.8 blocks; partial plans expire at actual horizon"));
        if(validatedExperiment)c.event("validated_defense_plan",Map.of("horizonTicks",6,"minimumPredictedSeparation",2.4,
                "comparison","rolling 6-tick replan vs validated continuation; both retain crit and normal finisher",
                "fallback","failed validation immediately requests a new ordinary escape search; emergency attacks unchanged",
                "limits","latest observations, approximate enemy chase; no future attack/knockback assumed in validation"));
        c.command("/tp @s 0.5 4 0.5 0 0");enter(c,Phase.ARRIVE);
    }
    private void enter(CreativeTestContext c,Phase p){phase=p;enteredAge=c.client.player.age;c.phase((validatedExperiment?"VALIDATED ":continuationExperiment?"CONTINUATION ":combinedExperiment?"MOVING CRIT ":criticalExperiment?"CRIT PURSUIT ":comparison?"PREDICTIVE ":"THREAT DEFENSE ")+(index+1)+"/"+trials.size()+" "+p);}
    private boolean chunks(MinecraftClient c){
        for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++)if(c.world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)==null)return false;
        return true;
    }
    @Override public void onChat(CreativeTestContext c,Text text){c.event("threat_command_feedback",Map.of("message",text.getString()));}
    @Override public void tick(CreativeTestContext c) {
        if(c.done() || phase==Phase.FINISHED)return;
        if(error!=null){c.complete(TestBatch.Outcome.ERROR,"THREAT_OBSERVATION_ERROR",Map.of("error",error,"case",index+1));return;}
        if(c.phaseMillis()>30000){fail(c,"THREAT_PHASE_TIMEOUT");return;}
        if(c.client.player.age-waitAge>=40){waitAge=c.client.player.age;c.event("threat_wait",Map.of(
                "case",index+1,"phase",phase.name(),"attacks",attacks.size(),"damage",damage,"confirmed",confirmed));}
        switch(phase) {
            case ARRIVE:
                if(!chunks(c.client) || Math.hypot(c.client.player.getX()-.5,c.client.player.getZ()-.5)>.1)return;
                c.command("/fill -12 3 -12 12 3 12 minecraft:stone");
                c.command("/fill -12 4 -12 12 9 12 minecraft:air");
                c.command("/time set midnight");c.command("/gamerule doDaylightCycle false");
                c.command("/gamerule doMobSpawning false");c.command("/gamerule naturalRegeneration false");
                c.command("/difficulty normal");c.command("/effect clear @s");
                c.command("/effect give @s minecraft:saturation 100000 0 true");
                c.command("/attribute @s minecraft:generic.max_health base set 1000");enter(c,Phase.FLOOR);return;
            case FLOOR:
                if(!chunks(c.client))return;
                for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)if(c.client.world.getBlockState(new BlockPos(x,3,z)).getBlock()!=Blocks.STONE)return;
                c.command("/gamemode survival");enter(c,Phase.ARENA);return;
            case ARENA:
                if(c.client.player.abilities.creativeMode || c.client.player.age-enteredAge<20)return;
                setup(c);return;
            case TERRAIN:
                if(!terrainReady(c.client))return;
                c.event("terrain_ready",Map.of("case",index+1,"layout",terrain().name(),"startY",CombatTerrainLayout.playerY(terrain())));
                enter(c,Phase.SETUP);return;
            case SETUP:
                if(c.client.player.age-enteredAge<15 || c.client.player.inventory.getStack(1).getItem()!=Items.IRON_AXE)return;
                if(!querying)query(c);if(reply==null)return;
                Snapshot ready=take();
                if(ready.targets().stream().anyMatch(t->c.client.world.getEntityById(t.id())==null))return;
                if(ready.health()<999 || Math.abs(ready.py()-CombatTerrainLayout.playerY(terrain()))>.05 || ready.axe()
                        || Math.hypot(ready.px()-.5,ready.pz()-.5)>.05){
                    c.event("threat_fixture_not_ready",Map.of("case",index+1,"recovery",fixtureRecoveries,
                            "serverPos",List.of(ready.px(),ready.py(),ready.pz()),"serverHealth",ready.health(),"serverHoldingAxe",ready.axe(),
                            "clientPos",List.of(c.client.player.getX(),c.client.player.getY(),c.client.player.getZ()),
                            "clientVelocity",List.of(c.client.player.getVelocity().x,c.client.player.getVelocity().y,c.client.player.getVelocity().z)));
                    if(fixtureRecoveries++>=3){fail(c,"THREAT_FIXTURE_INVALID");return;}
                    resetFixtureMotion(c);c.command(teleportStart());
                    c.command("/effect give @s minecraft:instant_health 1 10 true");
                    c.client.player.inventory.selectedSlot=0;c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(0));
                    enteredAge=c.client.player.age;return;
                }
                for(int i=0;i<ready.targets().size();i++) {
                    Target t=ready.targets().get(i);Spawn s=trials.get(index).spawns().get(i);
                    if(!t.noAI() || Math.abs(t.health()-20)>.05 || Math.abs(t.x()-(.5+s.dx()))>.05
                            || Math.abs(t.z()-(.5+s.dz()))>.05 || Math.abs(t.y()-4)>.05){fail(c,"THREAT_TARGET_FIXTURE_INVALID");return;}
                }
                c.client.player.inventory.selectedSlot=1;c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(1));
                enter(c,Phase.PREPARE);return;
            case PREPARE:
                if(c.client.player.age-enteredAge<35)return;
                release(c);enter(c,Phase.FIGHT);return;
            case FIGHT:
                fight(c);return;
            default:return;
        }
    }
    private void setup(CreativeTestContext c) {
        stopObservation();criticalInput.reset(c.client);criticalEvents=List.of();
        sidestep.reset(c.client);sidestep.setJumpExperiment(combinedExperiment);jumpAssessment=Map.of();lastJumpTick=-1;lastJumpMinimum=-1;finishWaitTarget=null;loggedPlanSource=-1000;loggedPlanSegment=-1;
        for(UUID id:ids)c.command("/kill "+id);
        if(terrainExperiment)for(String command:CombatTerrainLayout.commands(terrain()))c.command(command);
        List<UUID> next=new ArrayList<>();for(Spawn ignored:trials.get(index).spawns())next.add(UUID.randomUUID());ids=List.copyOf(next);
        if(criticalExperiment)observation=CriticalCombatScenario.beginObservation(c.client.player.getUuid(),ids,rows->criticalEvents=rows);
        reply=null;latest=null;querying=false;pending=confirmed=false;fixtureRecoveries=0;lastTick=firstFightTick=lastAttackTick=-1;
        motionPrevious=null;damageTicks.clear();
        pendingTarget=lastTarget=null;targetSwitches=0;previousHealth=1000;damage=0;nearest=Double.POSITIVE_INFINITY;samples.clear();attacks.clear();
        resetFixtureMotion(c);
        c.command(teleportStart());c.command("/clear @s");
        c.command("/effect give @s minecraft:instant_health 1 10 true");
        c.command("/replaceitem entity @s hotbar.0 minecraft:stick");c.command("/replaceitem entity @s hotbar.1 minecraft:iron_axe");
        c.client.player.inventory.selectedSlot=0;c.client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(0));
        c.client.player.yaw=0;c.client.player.pitch=0;
        c.client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(0,0,c.client.player.isOnGround()));
        for(int i=0;i<ids.size();i++){
            Spawn s=trials.get(index).spawns().get(i);UUID id=ids.get(i);
            c.command("/summon minecraft:zombie "+(.5+s.dx())+" 4 "+(.5+s.dz())+" {UUID:"+PacketCombatScenario.uuidNbt(id)
                    +",NoAI:1b,Silent:1b,PersistenceRequired:1b,IsBaby:0b}");
            c.command("/data merge entity "+id+" {Health:20f,CanPickUpLoot:0b,HandItems:[{},{}],ArmorItems:[{},{},{},{}]}");
        }
        c.event("threat_case_setup",Map.of("case",index+1,"name",trials.get(index).name(),"emergency",trials.get(index).emergency(),
                "targets",ids.stream().map(UUID::toString).toList()));enter(c,terrainExperiment?Phase.TERRAIN:Phase.SETUP);
    }
    /** Only between experiments. Teleporting alone can retain the preceding hit's velocity. */
    private void resetFixtureMotion(CreativeTestContext c){
        c.client.player.setVelocity(0,0,0);
        IntegratedServer server=c.client.getServer();List<UUID> expected=ids;UUID playerId=c.client.player.getUuid();
        server.execute(()->{
            if(closed || ids!=expected)return;
            ServerPlayerEntity p=server.getPlayerManager().getPlayer(playerId);
            if(p!=null)p.setVelocity(0,0,0);
        });
        c.event("threat_fixture_motion_reset",Map.of("case",index+1,"scope","between trials only; combat physics unchanged"));
    }
    private void release(CreativeTestContext c){
        IntegratedServer server=c.client.getServer();List<UUID> expected=ids;UUID playerId=c.client.player.getUuid();
        server.execute(()->{
            if(closed || ids!=expected)return;
            ServerPlayerEntity p=server.getPlayerManager().getPlayer(playerId);
            for(UUID id:expected){Entity e=server.getWorld(World.OVERWORLD).getEntity(id);
                if(!(e instanceof MobEntity m) || p==null){error="release fixture missing";return;}
                m.setAiDisabled(false);m.setTarget(p);
            }
        });
    }
    private void query(CreativeTestContext c){
        querying=true;IntegratedServer server=c.client.getServer();List<UUID> expected=ids;UUID playerId=c.client.player.getUuid();
        Snapshot prior=latest;boolean inFight=phase==Phase.FIGHT;
        server.execute(()->{
            try {
                if(closed || ids!=expected)return;
                ServerWorld world=server.getWorld(World.OVERWORLD);ServerPlayerEntity p=server.getPlayerManager().getPlayer(playerId);
                if(p==null)throw new IllegalStateException("player missing");
                List<Target> out=new ArrayList<>();
                for(UUID id:expected){Entity e=world.getEntity(id);
                    if(!(e instanceof MobEntity m)){
                        Target dead=prior==null?null:prior.targets().stream().filter(t->t.uuid().equals(id) && t.health()<=0).findFirst().orElse(null);
                        if(dead!=null){out.add(dead);continue;}
                        if(inFight)throw new IllegalStateException("target disappeared without observed death: "+id);
                        querying=false;return;
                    }
                    out.add(new Target(id,e.getEntityId(),e.getX(),e.getY(),e.getZ(),m.getHealth(),
                            e.getVelocity().x,e.getVelocity().y,e.getVelocity().z,m.getTarget()==p,m.isAiDisabled()));
                }
                Snapshot o=new Snapshot(world.getTime(),p.getX(),p.getY(),p.getZ(),p.getHealth(),p.getAttackCooldownProgress(.5F),
                        p.getMainHandStack().getItem()==Items.IRON_AXE,p.isOnGround(),p.fallDistance,List.copyOf(out));
                if(!closed && ids==expected)reply=o;
            }catch(Exception ex){if(!closed && ids==expected)error=ex.toString();}
        });
    }
    private Snapshot take(){Snapshot o=reply;reply=null;querying=false;latest=o;return o;}
    private static double distance(Snapshot s,Target t){return Math.sqrt(sq(t.x()-s.px())+sq(t.y()-s.py())+sq(t.z()-s.pz()));}
    private static double sq(double d){return d*d;}
    private static double closingSpeed(Snapshot s,Target t){
        double dx=t.x()-s.px(),dz=t.z()-s.pz(),d=Math.hypot(dx,dz);
        return d<.001?0:-(dx*t.vx()+dz*t.vz())/d;
    }
    private static double urgency(Snapshot s,Target t){return Math.max(0,distance(s,t)-1.5)/Math.max(.08,closingSpeed(s,t));}
    private List<PredictiveThreatPolicy.Threat> observedThreats(Snapshot s,List<Target> alive,Snapshot previous){
            List<PredictiveThreatPolicy.Threat> threats=new ArrayList<>();
            for(Target t:alive) {
                double speed=closingSpeed(s,t);
                if(previous!=null && s.tick()>previous.tick()) {
                    Target old=previous.targets().stream().filter(o->o.uuid().equals(t.uuid())).findFirst().orElse(null);
                    if(old!=null) {
                        double observed=(Math.hypot(old.x()-previous.px(),old.z()-previous.pz())-Math.hypot(t.x()-s.px(),t.z()-s.pz()))/(s.tick()-previous.tick());
                        speed=Math.max(speed,observed);
                    }
                }
                threats.add(new PredictiveThreatPolicy.Threat(t.uuid(),distance(s,t),speed,t.health(),
                        s.tick()-damageTicks.getOrDefault(t.uuid(),s.tick()-1000)));
            }
        return threats;
    }
    private void fight(CreativeTestContext c){
        if(!querying)query(c);if(reply==null)return;Snapshot s=take();if(s.tick()<=lastTick)return;lastTick=s.tick();
        Snapshot previous=motionPrevious;motionPrevious=s;
        if(previous!=null)for(Target t:s.targets()) {
            Target old=previous.targets().stream().filter(o->o.uuid().equals(t.uuid())).findFirst().orElse(null);
            if(old!=null && t.health()<old.health())damageTicks.put(t.uuid(),s.tick());
        }
        List<Target> alive=s.targets().stream().filter(t->t.health()>0).toList();
        if(!confirmed){if(alive.size()!=ids.size() || alive.stream().anyMatch(t->t.noAI() || !t.targetsPlayer()))return;
            confirmed=true;firstFightTick=s.tick();c.event("threat_targets_confirmed",Map.of("case",index+1,"count",alive.size(),"serverTick",s.tick()));}
        if(alive.stream().anyMatch(t->t.noAI() || !t.targetsPlayer())){fail(c,"THREAT_TARGET_LOST");return;}
        if(movementExperiment){
            Map<String,Object> movement=sidestep.update(c.client,s.tick(),s.px(),s.pz(),s.targets().stream()
                    .map(t->new CombatSidestepExperiment.Enemy(t.uuid(),t.x(),t.z(),t.vx(),t.vz(),t.health())).toList(),movingCase(),escapeCase(),continuationCase(),validatedExperiment && continuationCase());
            if(continuationExperiment){
                Map<?,?> search=(Map<?,?>)movement.get("escapeSearch"),continuation=(Map<?,?>)movement.get("continuation");
                long source=((Number)search.get("planSourceTick")).longValue();int segment=((Number)continuation.get("segment")).intValue();
                if(source!=loggedPlanSource || segment!=loggedPlanSegment){
                    c.event("movement_continuation",Map.of("case",index+1,"tick",s.tick(),"planSourceTick",source,
                            "direction",movement.get("worldDirection"),"search",search,"continuation",continuation,"validation",movement.get("continuationValidation")));
                    loggedPlanSource=source;loggedPlanSegment=segment;
                }
            }
            if(s.tick()-firstFightTick<4 || (s.tick()-firstFightTick)%20<2)c.event("sidestep_plan_update",movement);
        }
        double hitDamage=Math.max(0,previousHealth-s.health());
        if(combinedExperiment && hitDamage>0)c.event("moving_critical_damage",Map.of("case",index+1,"tick",s.tick(),"damage",hitDamage,
                "sinceJumpTicks",lastJumpTick<0?-1:s.tick()-lastJumpTick,"lastJumpPredictedMinimum",lastJumpMinimum,
                "pos",List.of(s.px(),s.py(),s.pz()),"ground",s.ground(),"fallDistance",s.fall()));
        damage+=hitDamage;previousHealth=s.health();
        List<Map<String,Object>> targetRows=new ArrayList<>();
        for(Target t:s.targets()){
            if(t.health()>0)nearest=Math.min(nearest,distance(s,t));
            targetRows.add(Map.of("uuid",t.uuid().toString(),"pos",List.of(t.x(),t.y(),t.z()),"health",t.health(),
                    "velocity",List.of(t.vx(),t.vy(),t.vz()),"distance",distance(s,t),"targetsPlayer",t.targetsPlayer()));
        }
        samples.add(Map.of("tick",s.tick(),"playerPos",List.of(s.px(),s.py(),s.pz()),"playerHealth",s.health(),"cooldown",s.cooldown(),"targets",targetRows));
        if(alive.isEmpty() || s.tick()-firstFightTick>=WINDOW){finishCase(c,s,alive.size());return;}
        if(!s.axe()){fail(c,"THREAT_AXE_NOT_EQUIPPED");return;}
        if(pending){Target t=s.targets().stream().filter(x->x.uuid().equals(pendingTarget)).findFirst().orElseThrow();
            if(s.tick()>lastAttackTick && (t.health()<pendingHealth || s.cooldown()<.99))pending=false;
            else if(s.tick()-lastAttackTick>20){fail(c,"THREAT_ATTACK_ACK_TIMEOUT");return;}
        }
        if(pending || !trials.get(index).attack())return;
        List<Target> inRange=alive.stream().filter(t->distance(s,t)<=ATTACK_RADIUS).toList();if(inRange.isEmpty())return;
        double contact=alive.stream().mapToDouble(t->Math.max(0,Math.hypot(t.x()-s.px(),t.z()-s.pz())-2)/Math.max(.115,closingSpeed(s,t))).min().orElse(1000);
        double nearestHorizontal=alive.stream().mapToDouble(t->Math.hypot(t.x()-s.px(),t.z()-s.pz())).min().orElse(1000);
        if(criticalCase()){
            List<PredictiveThreatPolicy.Threat> projectedThreats=observedThreats(s,alive,previous);
            PredictiveThreatPolicy.Decision projected=PredictiveThreatPolicy.choose(projectedThreats,1,1000);
            Target jumpTarget=projected==null?null:alive.stream().filter(t->t.uuid().equals(projected.id())).findFirst().orElse(null);
            boolean usefulJump=jumpTarget!=null && !PredictiveThreatPolicy.canFinishNormally(jumpTarget.health());
            String action;
            if(combinedExperiment){
                // Only evaluate a full jump when the cheap timing/range gate permits one.
                boolean eligible=usefulJump && criticalInput.wouldJump(s.tick(),s.ground(),s.fall(),s.cooldown(),nearestHorizontal,contact);
                jumpAssessment=eligible?sidestep.assessJump(c.client,s.targets().stream()
                        .map(t->new CombatSidestepExperiment.Enemy(t.uuid(),t.x(),t.z(),t.vx(),t.vz(),t.health())).toList()):Map.of();
                if(eligible)c.event("moving_critical_gate",Map.of("case",index+1,"tick",s.tick(),"assessment",jumpAssessment));
                action=criticalInput.updateWithPulse(s.tick(),s.ground(),s.fall(),s.cooldown(),nearestHorizontal,contact,
                        usefulJump && jumpAssessment.getOrDefault("safe",false).equals(true),()->sidestep.requestJump(c.client));
            }else action=criticalInput.update(c.client,s.tick(),s.ground(),s.fall(),s.cooldown(),nearestHorizontal,contact,usefulJump);
            if(action.equals("JUMP")){
                lastJumpTick=s.tick();lastJumpMinimum=((Number)jumpAssessment.getOrDefault("minimumPredictedSeparation",-1)).doubleValue();
            }
            if(action.equals("JUMP"))c.event("critical_pursuit_jump",Map.of("case",index+1,"tick",s.tick(),"nearest",nearestHorizontal,"contactEstimate",contact,"cooldown",s.cooldown(),"target",jumpTarget.uuid().toString(),"targetHealth",jumpTarget.health()));
            if(action.equals("WAIT_FALL" ) || action.equals("JUMP"))return;
        }
        boolean ready=s.cooldown()>=.99;
        PredictiveThreatPolicy.Decision decision=null;
        List<Target> candidates;
        if(predictive()) {
            List<PredictiveThreatPolicy.Threat> threats=observedThreats(s,alive,previous);
            decision=PredictiveThreatPolicy.choose(threats,s.cooldown(),lastAttackTick<0?1000:s.tick()-lastAttackTick);
            if(decision==null)return;
            UUID selected=decision.id();candidates=inRange.stream().filter(t->t.uuid().equals(selected)).toList();
        }
        else if(ready){List<Target> closing=inRange.stream().filter(t->closingSpeed(s,t)>=-.005).toList();candidates=closing.isEmpty()?inRange:closing;}
        else if(trials.get(index).emergency() && lastAttackTick>=0 && s.tick()-lastAttackTick>=EMERGENCY_GAP)
            candidates=inRange.stream().filter(t->distance(s,t)<=EMERGENCY_RADIUS && closingSpeed(s,t)>=-.005).toList();
        else return;
        Target chosen=candidates.stream().min(Comparator.comparingDouble((Target t)->urgency(s,t)).thenComparingDouble(t->distance(s,t))).orElse(null);
        if(chosen==null)return;
        if(criticalCase() && PredictiveThreatPolicy.waitForNormalFinish(chosen.health(),s.cooldown(),contact)){
            if(!chosen.uuid().equals(finishWaitTarget))c.event("normal_finish_wait",Map.of("case",index+1,"target",chosen.uuid().toString(),
                    "health",chosen.health(),"cooldown",s.cooldown(),"fullNormalDamageEstimate",PredictiveThreatPolicy.damageEstimate(1),"contactEstimate",contact));
            finishWaitTarget=chosen.uuid();return;
        }
        finishWaitTarget=null;
        Entity clientTarget=c.client.world.getEntityById(chosen.id());
        if(clientTarget==null){fail(c,"THREAT_CLIENT_TARGET_MISSING");return;}
        float yaw=(float)(Math.toDegrees(Math.atan2(chosen.z()-s.pz(),chosen.x()-s.px()))-90);
        c.client.player.yaw=yaw;c.client.player.pitch=0;c.client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(yaw,0,c.client.player.isOnGround()));
        c.client.interactionManager.attackEntity(c.client.player,clientTarget);c.client.player.swingHand(Hand.MAIN_HAND);
        if(lastTarget!=null && !lastTarget.equals(chosen.uuid()))targetSwitches++;
        Map<String,Object> row=new LinkedHashMap<>();row.put("case",index+1);row.put("number",attacks.size()+1);row.put("target",chosen.uuid().toString());
        row.put("tick",s.tick());row.put("gap",lastAttackTick<0?-1:s.tick()-lastAttackTick);row.put("distance",distance(s,chosen));
        row.put("cooldown",s.cooldown());row.put("emergency",!ready);row.put("urgencyEstimate",urgency(s,chosen));row.put("healthBefore",chosen.health());
        row.put("playerHealth",s.health());row.put("alive",alive.size());
        if(criticalExperiment){row.put("fallDistance",s.fall());row.put("onGround",s.ground());}
        if(decision!=null){row.put("decisionReason",decision.reason());row.put("predictedContactTicks",decision.contactTicks());
            row.put("remainingCooldownEstimate",decision.remainingCooldown());row.put("damageEstimate",decision.damageEstimate());
            c.event("predictive_decision",Map.copyOf(row));}
        attacks.add(row);c.event("threat_attack_sent",row);
        lastAttackTick=s.tick();lastTarget=pendingTarget=chosen.uuid();pendingHealth=chosen.health();pending=true;
        if(movementExperiment)sidestep.markAttack(chosen.uuid(),s.tick());
    }
    private void finishCase(CreativeTestContext c,Snapshot s,int remaining){
        if(criticalExperiment){stopObservation();criticalInput.stop(c.client);}
        Map<String,Object> row=new LinkedHashMap<>();row.put("case",index+1);row.put("name",trials.get(index).name());
        row.put("emergencyEnabled",trials.get(index).emergency());row.put("spawned",ids.size());row.put("remaining",remaining);
        row.put("policy",criticalExperiment?(criticalCase()?"predictive_critical":"predictive_ground"):escapeExperiment?(movingCase()?(escapeCase()?"predictive_escape":"predictive_sidestep"):"predictive_stationary"):movementExperiment?(movingCase()?"predictive_sidestep":"predictive_stationary"):predictive()?"predictive":"legacy");
        row.put("terrain",terrain().name());
        if(continuationExperiment)row.put("movementPolicy",continuationCase()?(validatedExperiment?"validated":"committed"):"rolling");
        if(movementExperiment){row.put("movement",sidestep.summary());sidestep.stop(c.client);}
        row.put("killed",ids.size()-remaining);row.put("allKilled",remaining==0);row.put("observedServerTicks",s.tick()-firstFightTick);
        row.put("damage",damage);row.put("noDamage",damage==0);row.put("nearestDistance",nearest);row.put("packets",attacks.size());
        row.put("emergencyPackets",attacks.stream().filter(a->a.get("emergency").equals(true)).count());row.put("targetSwitches",targetSwitches);
        if(criticalExperiment){
            row.put("probeEvents",criticalEvents);
            row.put("attackHeadCount",criticalEvents.stream().filter(e->e.get("signal").equals("ATTACK_HEAD")).count());
            row.put("critSignalCount",criticalEvents.stream().filter(e->e.get("signal").equals("CRIT_PARTICLES")).count());
            row.put("jumpAttempts",criticalInput.attempts());
            row.put("firstAttackToEndTicks",attacks.isEmpty()?-1:s.tick()-((Number)attacks.get(0).get("tick")).longValue());
        }
        row.put("attacks",List.copyOf(attacks));row.put("samples",List.copyOf(samples));results.add(row);c.event("threat_case_result",row);
        index++;if(index<trials.size()){setup(c);return;}
        phase=Phase.FINISHED;for(UUID id:ids)c.command("/kill "+id);c.command("/gamemode creative");
        boolean control=results.stream().anyMatch(r->r.get("name").equals("no_attack_control") && ((Number)r.get("packets")).intValue()==0 && ((Number)r.get("damage")).doubleValue()>0);
        boolean positive=results.stream().anyMatch(r->r.get("name").equals("front") && r.get("allKilled").equals(true));
        if(criticalExperiment && !combinedExperiment)for(int group=1;group<=3;group++)for(String policy:List.of("predictive_ground","predictive_critical")){
            String prefix=group+"_";List<Map<String,Object>> selected=results.stream().filter(r->r.get("name").toString().startsWith(prefix) && r.get("policy").equals(policy)).toList();
            c.event("critical_pursuit_summary",Map.of("group",group,"policy",policy,"runs",selected.size(),
                    "damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),
                    "meanTicks",selected.stream().mapToDouble(r->((Number)r.get("observedServerTicks")).doubleValue()).average().orElse(0),
                    "kills",selected.stream().filter(r->r.get("allKilled").equals(true)).count(),
                    "attacks",selected.stream().mapToLong(r->((Number)r.get("packets")).longValue()).sum(),
                    "crits",selected.stream().mapToLong(r->((Number)r.get("critSignalCount")).longValue()).sum()));
        }
        if(combinedExperiment && !continuationExperiment)for(String group:List.of("single","two_sides","three_sides","corner"))for(String policy:List.of("predictive_ground","predictive_critical")){
            List<Map<String,Object>> selected=results.stream().filter(r->r.get("name").toString().startsWith(group+"_") && r.get("policy").equals(policy)).toList();
            c.event("moving_critical_summary",Map.of("group",group,"policy",policy,"runs",selected.size(),
                    "damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),
                    "meanTicks",selected.stream().mapToDouble(r->((Number)r.get("observedServerTicks")).doubleValue()).average().orElse(0),
                    "kills",selected.stream().filter(r->r.get("allKilled").equals(true)).count(),
                    "attacks",selected.stream().mapToLong(r->((Number)r.get("packets")).longValue()).sum(),
                    "crits",selected.stream().mapToLong(r->((Number)r.get("critSignalCount")).longValue()).sum()));
        }
        if(continuationExperiment)for(String policy:List.of("rolling",validatedExperiment?"validated":"committed")){
            List<Map<String,Object>> selected=results.stream().filter(r->r.get("name").toString().startsWith("corner_") && r.get("movementPolicy").equals(policy)).toList();
            c.event("continuation_defense_summary",Map.of("policy",policy,"runs",selected.size(),
                    "damage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),
                    "meanTicks",selected.stream().mapToDouble(r->((Number)r.get("observedServerTicks")).doubleValue()).average().orElse(0),
                    "kills",selected.stream().filter(r->r.get("allKilled").equals(true)).count(),
                    "attacks",selected.stream().mapToLong(r->((Number)r.get("packets")).longValue()).sum()));
        }
        if(comparison && !criticalExperiment)for(String group:escapeExperiment?List.of("corner"):terrainExperiment?List.of("corner","corridor","step"):List.of("two_sides","three_sides"))for(String policy:escapeExperiment?List.of("predictive_sidestep","predictive_escape"):movementExperiment?List.of("predictive_stationary","predictive_sidestep"):List.of("legacy","predictive")) {
            List<Map<String,Object>> selected=results.stream().filter(r->r.get("name").toString().startsWith(group+"_") && r.get("policy").equals(policy)).toList();
            c.event("predictive_comparison_summary",Map.of("group",group,"policy",policy,"runs",selected.size(),
                    "totalDamage",selected.stream().mapToDouble(r->((Number)r.get("damage")).doubleValue()).sum(),
                    "noDamageKills",selected.stream().filter(r->r.get("allKilled").equals(true) && r.get("noDamage").equals(true)).count(),
                    "allKilledRuns",selected.stream().filter(r->r.get("allKilled").equals(true)).count()));
        }
        c.event("threat_summary",Map.of("attackCases",trials.size()-1,"allKilledNoDamage",results.stream().filter(r->!r.get("name").equals("no_attack_control") && r.get("allKilled").equals(true) && r.get("noDamage").equals(true)).count(),
                "note","PASS validates controls; evaluate single-direction, close and multiple-target results separately"));
        boolean observer=!criticalExperiment || results.stream().allMatch(r->((Number)r.get("attackHeadCount")).intValue()==((Number)r.get("packets")).intValue());
        boolean critControl=!criticalExperiment || results.stream().anyMatch(r->r.get("policy").equals("predictive_critical") && ((Number)r.get("critSignalCount")).intValue()>0);
        c.complete(control&&positive&&observer&&critControl?TestBatch.Outcome.PASS:TestBatch.Outcome.ERROR,control&&positive&&observer&&critControl?"THREAT_MEASUREMENT_COMPLETE":"THREAT_CONTROLS_FAILED",
                Map.of("cases",results.size(),"positiveControl",positive,"negativeControl",control,"attackObserver",observer,"critObserved",critControl));
    }
    private void fail(CreativeTestContext c,String reason){c.complete(TestBatch.Outcome.ERROR,reason,Map.of("phase",phase==null?"START":phase.name(),"case",index+1,"completed",results.size()));}
    @Override public void close(CreativeTestContext c){closed=true;stopObservation();criticalInput.stop(c.client);sidestep.stop(c.client);}
}
