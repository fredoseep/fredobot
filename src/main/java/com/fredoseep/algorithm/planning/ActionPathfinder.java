package com.fredoseep.algorithm.planning;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.block.*;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Immutable captured terrain + branch-local world edits. Worker search never accesses a live World/Player. */
public final class ActionPathfinder {
    private ActionPathfinder() {}
    public static final class Action {
        public final String kind;
        public final SimplePathfinder.MovementState movement;
        public final List<BlockPos> breaks;
        public final List<Item> tools;
        public final Item material;
        public boolean isShoreExit() { return "shore exit".equals(kind); }
        public boolean isSurfaceRise() { return "surface rise".equals(kind); }
        public boolean isSmoothWalk() { return "smooth walk".equals(kind); }
        public boolean isSmoothDive() { return "smooth dive".equals(kind); }
        Action(String kind, SimplePathfinder.MovementState movement, List<BlockPos> breaks, List<Item> tools, Item material) {
            this.kind = kind; this.movement = movement;
            this.breaks = List.copyOf(breaks); this.tools = List.copyOf(tools); this.material = material;
        }
    }
    public static final class State {
        public final BlockPos pos;
        // -1 = removed; >=0 = material index placed at that location. Canonical map, not action history.
        public final Map<BlockPos, Integer> edits;
        public final List<Integer> materials, durability;
        public State(BlockPos pos, Map<BlockPos, Integer> edits, List<Integer> materials, List<Integer> durability) {
            this.pos = pos.toImmutable(); this.edits = Map.copyOf(edits);
            this.materials = List.copyOf(materials); this.durability = List.copyOf(durability);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof State)) return false;
            State s = (State)o;
            return pos.equals(s.pos) && edits.equals(s.edits) && materials.equals(s.materials) && durability.equals(s.durability);
        }
        @Override public int hashCode() { return Objects.hash(pos, edits, materials, durability); }
    }
    private static final class Tool {
        final ItemStack stack;
        Tool(ItemStack stack) { this.stack = stack.copy(); }
    }
    private static final class Material {
        final Item item; final boolean gravity; final double price;
        final Cell cell;
        Material(Item item, boolean gravity, double price, Cell cell) {
            this.item = item; this.gravity = gravity; this.price = price; this.cell = cell;
        }
    }
    private static final class Cell {
        final boolean clear, support, water, lava, replaceable, gravity;
        final int[] dryTicks, wetTicks, airDryTicks, airWetTicks;
        final boolean wearsTool;
        List<net.minecraft.util.math.Box> doorPanels=null;
        String label="air/unknown";
        BlockPos example;
        Runnable prepareMining; // Runs only on the planner worker, before graph expansion.
        Cell(boolean clear, boolean support, boolean water, boolean lava, boolean replaceable, boolean gravity,
             int[] dryTicks, int[] wetTicks, int[] airDryTicks, int[] airWetTicks, boolean wearsTool) {
            this.clear = clear; this.support = support; this.water = water; this.lava = lava;
            this.replaceable = replaceable; this.gravity = gravity;
            this.dryTicks = dryTicks; this.wetTicks = wetTicks;
            this.airDryTicks = airDryTicks; this.airWetTicks = airWetTicks; this.wearsTool = wearsTool;
        }
    }
    private static final Cell AIR = new Cell(true, false, false, false, true, false, new int[0], new int[0], new int[0], new int[0], false);
    private static final Cell UNKNOWN = new Cell(false, false, false, true, false, false, new int[0], new int[0], new int[0], new int[0], false);
    public static final class Snapshot {
        final Map<BlockPos, Cell> cells;
        final List<Material> materials;
        final List<Tool> tools;
        final Set<BlockPos> blacklist;
        public final State start;
        public final BlockPos goal;
        final PlannerSettings settings;
        final boolean startGroundContact;
        volatile boolean costsPrepared;
        PlannerDiagnostics diagnostics;
        List<Object> buildingMaterialCandidates=List.of();
        Snapshot(Map<BlockPos, Cell> cells, List<Material> materials, List<Tool> tools, Set<BlockPos> blacklist,
                 State start, BlockPos goal, PlannerSettings settings) {
            this(cells,materials,tools,blacklist,start,goal,settings,false);
        }
        Snapshot(Map<BlockPos, Cell> cells, List<Material> materials, List<Tool> tools, Set<BlockPos> blacklist,
                 State start, BlockPos goal, PlannerSettings settings,boolean startGroundContact) {
            // Capture transfers exclusive ownership; avoid an O(volume) copy on the render thread.
            this.cells = Collections.unmodifiableMap(cells);
            this.materials = List.copyOf(materials); this.tools = List.copyOf(tools);
            this.blacklist = Set.copyOf(blacklist); this.start = start; this.goal = goal.toImmutable(); this.settings = settings;
            this.startGroundContact=startGroundContact;
        }
        Cell cell(State s, BlockPos p) {
            Integer edit = s.edits.get(p);
            return edit == null ? cells.getOrDefault(p, UNKNOWN) : edit == -1 ? AIR : materials.get(edit).cell;
        }
        boolean body(State s, BlockPos p) { return cell(s,p).clear && cell(s,p.up()).clear; }
        boolean grounded(State s) {
            // Live onGround can be true while the centre block is air: the bounding box
            // still overlaps the pit rim, or the player stands on a chest's partial top.
            // This contact applies ONLY to the exact initial state, never to future air nodes.
            return cell(s,s.pos.down()).support || (startGroundContact && s.equals(start));
        }
    }

    /** Client-thread capture, deliberately sliced into small frame budgets. */
    public static final class CaptureSession {
        final World world;
        final BlockPos start, goal;
        final Set<BlockPos> blacklist;
        final PlannerSettings settings;
        final List<Tool> tools = new ArrayList<>();
        final List<Integer> durability = new ArrayList<>(), counts = new ArrayList<>();
        final List<Material> materials = new ArrayList<>();
        final List<Object> buildingMaterialCandidates = new ArrayList<>();
        final Map<BlockState,Cell> prototypes = new HashMap<>();
        final Map<BlockPos,Cell> cells = new HashMap<>();
        final float effect;
        final boolean aqua;
        final boolean startGroundContact;
        final int minX,maxX,minY,maxY,minZ,maxZ;
        int x,y,z;
        long captured;
        public final long total;
        boolean finished;
        PlannerDiagnostics diagnostics;
        public void diagnostics(PlannerDiagnostics diagnostics){this.diagnostics=diagnostics;
            if(diagnostics!=null)diagnostics.describeStates(ActionPathfinder::diagnosticValue);}
        CaptureSession(World world,PlayerEntity player,BlockPos start,BlockPos goal,Set<BlockPos> blacklist,PlannerSettings settings) {
            this.world=world; this.start=start.toImmutable(); this.goal=goal.toImmutable();
            this.startGroundContact=player.isOnGround();
            this.blacklist=Set.copyOf(blacklist == null ? Set.of() : blacklist); this.settings=settings.copy();
            int m=this.settings.margin;
            minX=Math.min(start.getX(),goal.getX())-m; maxX=Math.max(start.getX(),goal.getX())+m;
            minZ=Math.min(start.getZ(),goal.getZ())-m; maxZ=Math.max(start.getZ(),goal.getZ())+m;
            minY=Math.max(0,Math.min(start.getY(),goal.getY())-m); maxY=Math.min(255,Math.max(start.getY(),goal.getY())+m);
            if (start.getY()<0 || start.getY()>254 || goal.getY()<0 || goal.getY()>254)
                throw new IllegalArgumentException("Goal/start outside supported 1.16 build height");
            x=minX; y=minY; z=minZ;
            total=(long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);
            boolean emptyAdded=false;
            for(ItemStack stack:player.inventory.main) {
                if(stack.getItem()==Items.GOLDEN_PICKAXE) continue;
                if(stack.isEmpty()) { if(emptyAdded) continue; emptyAdded=true; }
                tools.add(new Tool(stack)); durability.add(stack.isDamageable()?stack.getMaxDamage()-stack.getDamage():Integer.MAX_VALUE);
            }
            float multiplier=1; int haste=-1;
            if(player.hasStatusEffect(StatusEffects.HASTE)) haste=player.getStatusEffect(StatusEffects.HASTE).getAmplifier();
            if(player.hasStatusEffect(StatusEffects.CONDUIT_POWER)) haste=Math.max(haste,player.getStatusEffect(StatusEffects.CONDUIT_POWER).getAmplifier());
            if(haste>=0) multiplier*=1+.2f*(haste+1);
            if(player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
                int level=player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier();
                multiplier*=level==0?.3f:level==1?.09f:level==2?.0027f:.00081f;
            }
            effect=multiplier; aqua=EnchantmentHelper.hasAquaAffinity(player);
            for(InventoryHelper.PlaceableBlock pb:InventoryHelper.PlaceableBlock.values()) {
                int count=0;
                for(ItemStack stack:player.inventory.main) if(stack.getItem()==pb.getItem()) count+=stack.getCount();
                if(count==0) continue;
                int price=pb.getCost();
                Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("item",pb.getItem().toString());
                candidate.put("inventory_count",count);candidate.put("unit_price",price);
                buildingMaterialCandidates.add(candidate);
                if(price==Integer.MAX_VALUE){candidate.put("decision","EXCLUDED_BY_PRICE_POLICY");continue;}
                Cell c=captureCell(world,start,((BlockItem)pb.getItem()).getBlock().getDefaultState(),tools,effect,aqua);
                if(!c.support){candidate.put("decision","NOT_SAFE_FULL_SUPPORT");continue;}
                candidate.put("decision","ACCEPTED");
                materials.add(new Material(pb.getItem(),pb.isGravity(),price*this.settings.materialPriceTicks,c)); counts.add(count);
            }
        }
        /** Returns true once all blocks were captured; no worker is allowed to call this. */
        public boolean step(BooleanSupplier cancelled) {
            if(finished) return true;
            long deadline=System.nanoTime()+settings.captureBudgetMillis*1_000_000L;
            do {
                if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                if(y==minY && !world.getChunkManager().isChunkLoaded(x>>4,z>>4))
                    throw new IllegalStateException("Terrain unloaded during capture: chunk="+(x>>4)+","+(z>>4));
                BlockPos p=new BlockPos(x,y,z);
                if(diagnostics!=null)diagnostics.operation("capture.read block","client",p);
                BlockState b=world.getBlockState(p);
                Cell c=prototypes.get(b);
                if(c==null) {
                    if(diagnostics!=null)diagnostics.operation("capture.block metadata","client",p);
                    c=captureCell(world,p,b,tools,effect,aqua); prototypes.put(b,c);
                }
                cells.put(p,c); captured++;
                if(++y>maxY) { y=minY; if(++z>maxZ) { z=minZ; x++; } }
                if(x>maxX) { finished=true; return true; }
            } while(System.nanoTime()<deadline);
            return false;
        }
        public long captured() { return captured; }
        public Snapshot finish() {
            if(!finished) throw new IllegalStateException("Capture is incomplete");
            Snapshot snapshot=new Snapshot(cells,materials,tools,blacklist,new State(start,Map.of(),counts,durability),goal,settings,startGroundContact);
            snapshot.buildingMaterialCandidates=List.copyOf(buildingMaterialCandidates);return snapshot;
        }
    }
    public static CaptureSession beginCapture(World world,PlayerEntity player,BlockPos start,BlockPos goal,
                                             Set<BlockPos> blacklist,PlannerSettings settings) {
        return new CaptureSession(world,player,start,goal,blacklist,settings);
    }
    /** Worker-only preprocessing. A cancelled partial pass may be retried safely. */
    public static void prepareMiningCosts(Snapshot snapshot,BooleanSupplier cancelled) {
        if(snapshot.costsPrepared) return;
        synchronized(snapshot) {
            if(snapshot.costsPrepared) return;
            Set<Cell> prepared=Collections.newSetFromMap(new IdentityHashMap<>());
            long scanned=0;
            for(Cell cell:snapshot.cells.values()) {
                if(snapshot.diagnostics!=null && (++scanned & 1023)==0)
                    snapshot.diagnostics.operation("costs.scan","",Map.of("scanned_cells",scanned,"total_cells",snapshot.cells.size()));
                if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                if(prepared.add(cell) && cell.prepareMining!=null) {
                    if(snapshot.diagnostics!=null)snapshot.diagnostics.operation("costs.compute",cell.label,cellDiagnostic(cell));
                    cell.prepareMining.run(); cell.prepareMining=null;
                }
            }
            for(Material material:snapshot.materials) if(prepared.add(material.cell) && material.cell.prepareMining!=null) {
                if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                if(snapshot.diagnostics!=null)snapshot.diagnostics.operation("costs.material",material.cell.label,cellDiagnostic(material.cell));
                material.cell.prepareMining.run(); material.cell.prepareMining=null;
            }
            snapshot.costsPrepared=true;
        }
    }
    private static Cell captureCell(World world, BlockPos p, BlockState b, List<Tool> tools, float effect, boolean aqua) {
        if (b.isAir()) return AIR;
        boolean water = b.getFluidState().isIn(FluidTags.WATER);
        boolean lava = b.getFluidState().isIn(FluidTags.LAVA);
        Block block = b.getBlock();
        boolean hazard = lava || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.SWEET_BERRY_BUSH;
        var shape=b.getCollisionShape(world,p);
        List<net.minecraft.util.math.Box> doorPanels=block instanceof DoorBlock?shape.getBoundingBoxes():null;
        boolean clear = !hazard && (shape.isEmpty() || (doorPanels!=null
                && DoorClearance.clear(doorPanels,.5,0,.5,.5,0,.5)));
        boolean support = !hazard && Block.isShapeFullCube(b.getCollisionShape(world,p));
        float hardness = b.getHardness(world,p);
        int[] dry = new int[tools.size()], wet = new int[tools.size()], airDry = new int[tools.size()], airWet = new int[tools.size()];
        Cell cell=new Cell(clear,support,water,lava,b.getMaterial().isReplaceable(),block instanceof FallingBlock,
                dry,wet,airDry,airWet,hardness>0);
        cell.doorPanels=doorPanels==null?null:List.copyOf(doorPanels);
        cell.label=b.toString();cell.example=p.toImmutable();
        // Only immutable block/tool metadata is captured here. No World/Player reference enters the worker.
        cell.prepareMining=() -> {
            for(int i=0;i<tools.size();i++) {
                ItemStack stack=tools.get(i).stack;
                float speed=stack.getMiningSpeedMultiplier(b);
                int efficiency=EnchantmentHelper.getLevel(Enchantments.EFFICIENCY,stack);
                if(speed>1 && efficiency>0 && !stack.isEmpty()) speed+=efficiency*efficiency+1;
                speed*=effect;
                boolean harvest=!b.isToolRequired() || stack.isEffectiveOn(b);
                boolean impossible=hazard || !b.getFluidState().isEmpty();
                dry[i]=impossible?Integer.MAX_VALUE:MiningTime.ticks(hardness,speed,harvest);
                float wetSpeed=aqua?speed:speed/5;
                wet[i]=impossible?Integer.MAX_VALUE:MiningTime.ticks(hardness,wetSpeed,harvest);
                airDry[i]=impossible?Integer.MAX_VALUE:MiningTime.ticks(hardness,speed/5,harvest);
                airWet[i]=impossible?Integer.MAX_VALUE:MiningTime.ticks(hardness,wetSpeed/5,harvest);
            }
        };
        return cell;
    }
    public static SearchEngine.Result<State, Action> search(Snapshot snapshot, BooleanSupplier cancelled) {
        prepareMiningCosts(snapshot,cancelled);
        return search(snapshot,cancelled,null);
    }
    public static SearchEngine.Result<State,Action> search(Snapshot snapshot,BooleanSupplier cancelled,SearchEngine.Observer<State> observer) {
        return search(snapshot,cancelled,observer,null);
    }
    public static SearchEngine.Result<State,Action> search(Snapshot snapshot,BooleanSupplier cancelled,SearchEngine.Observer<State> observer,
                                                          java.util.function.Consumer<String> stage) {
        prepareMiningCosts(snapshot,cancelled);
        PlannerSettings settings=snapshot.settings;
        int stateLimit=effectiveStateLimit(snapshot);
        GroundActions ground=new GroundActions(snapshot);WaterActions water=new WaterActions(snapshot);
        java.util.function.ToDoubleFunction<State> h=s -> settings.dijkstra?0:estimateMinimumTicks(s.pos,snapshot.goal,settings);
        java.util.function.ToDoubleFunction<State> progress=s -> distance(s.pos,snapshot.goal);
        // First find a FULL route in unchanged terrain. Its cost is a safe upper bound,
        // not a reason to discard edit states or execute a geometrically close prefix.
        SearchEngine<State,Action> baseline=new SearchEngine<>();
        baseline.register(s -> unchanged(ground.expand(s),snapshot.start));
        baseline.register(s -> unchanged(water.expand(s),snapshot.start));
        searchPass(snapshot,"BASELINE",null);
        if(stage!=null)stage.accept("SEARCH_BASELINE");
        var plain=baseline.search(snapshot.start,s -> s.pos.equals(snapshot.goal),h,progress,settings.maxExpanded,cancelled,observer,
                snapshot.diagnostics==null?null:new DiagnosticProfiler(snapshot,"BASELINE"),
                new SearchEngine.Policy<>(1,settings.optimizationMillis,stateLimit,null));
        searchPass(snapshot,"BASELINE_DONE",plain);
        if(plain.status==SearchEngine.Status.CANCELLED || cancelled.getAsBoolean())return plain;
        SearchEngine<State,Action> engine=new SearchEngine<>();engine.register(ground);engine.register(water);
        searchPass(snapshot,"OPTIMIZING",plain.status==SearchEngine.Status.COMPLETE?plain:null);
        if(stage!=null)stage.accept("SEARCH_OPTIMIZING");
        var result=engine.search(snapshot.start,s -> s.pos.equals(snapshot.goal),h,progress,settings.maxExpanded,cancelled,observer,
                snapshot.diagnostics==null?null:new DiagnosticProfiler(snapshot,"OPTIMIZING"),
                new SearchEngine.Policy<>(settings.dijkstra?1:settings.searchWeight,settings.optimizationMillis,stateLimit,
                        plain.status==SearchEngine.Status.COMPLETE?plain:null));
        searchPass(snapshot,"RESULT",result);
        return result;
    }
    private static Iterable<SearchEngine.Edge<State,Action>> allowed(Snapshot w,State from,Iterable<SearchEngine.Edge<State,Action>> edges) {
        List<SearchEngine.Edge<State,Action>> result=new ArrayList<>();
        for(var edge:edges) {
            BlockPos p=edge.next.pos;
            if(p.getY()<w.settings.minimumRouteY || (!w.settings.allowWorldEdits && !edge.next.edits.equals(w.start.edits)))continue;
            int span=Math.max(Math.abs(p.getX()-from.pos.getX()),Math.max(Math.abs(p.getY()-from.pos.getY()),Math.abs(p.getZ()-from.pos.getZ())));
            boolean crossesDoor=false;
            for(int i=1;i<span;i++) {
                BlockPos middle=new BlockPos(from.pos.getX()+(p.getX()-from.pos.getX())*i/span,
                        from.pos.getY()+(p.getY()-from.pos.getY())*i/span,from.pos.getZ()+(p.getZ()-from.pos.getZ())*i/span);
                if(w.cell(edge.next,middle).doorPanels!=null || w.cell(edge.next,middle.up()).doorPanels!=null){crossesDoor=true;break;}
            }
            if(crossesDoor)continue; // Do not jump/fall through a door without a level entry sweep.
            Cell old=w.cell(from,from.pos),next=w.cell(edge.next,p);
            if(old.doorPanels!=null || next.doorPanels!=null) {
                if(from.pos.getY()!=p.getY())continue; // Enter/exit the breathing pocket from its sides.
                List<net.minecraft.util.math.Box> panels=new ArrayList<>();
                for(BlockPos base:List.of(from.pos,p))for(int y=0;y<=1;y++) {
                    BlockPos q=base.up(y);Cell c=w.cell(edge.next,q);
                    if(c.doorPanels!=null)for(var b:c.doorPanels)panels.add(b.offset(q.getX(),q.getY(),q.getZ()));
                }
                if(!DoorClearance.clear(panels,from.pos.getX()+.5,from.pos.getY(),from.pos.getZ()+.5,p.getX()+.5,p.getY(),p.getZ()+.5))continue;
            }
            result.add(edge);
        }
        return result;
    }
    private static List<SearchEngine.Edge<State,Action>> unchanged(Iterable<SearchEngine.Edge<State,Action>> edges,State start) {
        List<SearchEngine.Edge<State,Action>> result=new ArrayList<>();
        for(var edge:edges)if(edge.next.edits.equals(start.edits))result.add(edge);
        return result;
    }
    public static int effectiveStateLimit(Snapshot snapshot) {
        BlockPos a=snapshot.start.pos,b=snapshot.goal;
        return snapshot.settings.effectiveStateLimit((double)b.getX()-a.getX(),(double)b.getY()-a.getY(),(double)b.getZ()-a.getZ());
    }
    private static void searchPass(Snapshot snapshot,String pass,SearchEngine.Result<State,Action> result) {
        if(snapshot.diagnostics==null)return;
        Map<String,Object> data=new LinkedHashMap<>();data.put("pass",pass);
        data.put("effective_max_known",effectiveStateLimit(snapshot));
        data.put("optimal_scope",pass.startsWith("BASELINE")?"unchanged_terrain":"full_action_graph");
        if(result!=null){data.put("status",result.status.name());data.put("ticks",result.ticks);data.put("penalty",result.penalty);
            data.put("expanded",result.expanded);data.put("steps",result.steps.size());
            data.put("optimal",!pass.equals("OPTIMIZING") && result.optimal);
            data.put("termination",pass.equals("OPTIMIZING")?"BASELINE_INCUMBENT":result.termination);}
        snapshot.diagnostics.event("search_pass",data);
    }
    public static void diagnose(Snapshot snapshot,PlannerDiagnostics diagnostics) {
        snapshot.diagnostics=diagnostics;
        if(diagnostics==null)return;
        diagnostics.describeStates(ActionPathfinder::diagnosticValue);
        Map<String,Object> info=new LinkedHashMap<>();info.put("start",coordinates(snapshot.start.pos));info.put("goal",coordinates(snapshot.goal));
        info.put("distance_blocks",distance(snapshot.start.pos,snapshot.goal));info.put("captured_cells",snapshot.cells.size());
        info.put("tools",toolDiagnostic(snapshot));info.put("material_counts",snapshot.start.materials);info.put("tool_durability",snapshot.start.durability);
        info.put("settings",settingsDiagnostic(snapshot.settings));
        info.put("building_material_candidates",snapshot.buildingMaterialCandidates);
        info.put("start_ground_contact",snapshot.startGroundContact);info.put("start_body_clear",snapshot.body(snapshot.start,snapshot.start.pos));
        info.put("start_foot",cellDiagnostic(snapshot.cell(snapshot.start,snapshot.start.pos)));
        info.put("start_head",cellDiagnostic(snapshot.cell(snapshot.start,snapshot.start.pos.up())));
        info.put("start_support",cellDiagnostic(snapshot.cell(snapshot.start,snapshot.start.pos.down())));
        info.put("goal_in_snapshot",snapshot.cells.containsKey(snapshot.goal));info.put("goal_blacklisted",snapshot.blacklist.contains(snapshot.goal));
        info.put("goal_foot",cellDiagnostic(snapshot.cell(snapshot.start,snapshot.goal)));
        info.put("goal_head",cellDiagnostic(snapshot.cell(snapshot.start,snapshot.goal.up())));
        info.put("goal_support",cellDiagnostic(snapshot.cell(snapshot.start,snapshot.goal.down())));
        diagnostics.event("snapshot",info);
    }
    private static Object diagnosticValue(Object value){return value instanceof State s?stateDiagnostic(s):value instanceof BlockPos p?coordinates(p):value;}
    private static List<Integer> coordinates(BlockPos pos){return List.of(pos.getX(),pos.getY(),pos.getZ());}
    private static Map<String,Object> stateDiagnostic(State s) {
        Map<String,Object> state=new LinkedHashMap<>();state.put("pos",coordinates(s.pos));state.put("edits_count",s.edits.size());
        state.put("materials",s.materials);state.put("durability",s.durability);
        List<Object> edits=new ArrayList<>();for(var e:s.edits.entrySet()){if(edits.size()==8)break;edits.add(List.of(coordinates(e.getKey()),e.getValue()));}
        state.put("edits_sample",edits);return state;
    }
    private static Map<String,Object> cellDiagnostic(Cell c) {
        Map<String,Object> cell=new LinkedHashMap<>();cell.put("label",c.label);cell.put("example",c.example==null?null:coordinates(c.example));
        cell.put("clear",c.clear);cell.put("support",c.support);cell.put("water",c.water);cell.put("lava",c.lava);
        cell.put("door",c.doorPanels!=null);cell.put("replaceable",c.replaceable);cell.put("gravity",c.gravity);cell.put("wears_tool",c.wearsTool);return cell;
    }
    private static Map<String,Object> settingsDiagnostic(PlannerSettings s) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("margin",s.margin);m.put("max_expanded",s.maxExpanded);m.put("max_edits",s.maxEdits);
        m.put("dynamic_state_budget",s.dynamicStateBudget);m.put("state_budget_reference_distance",s.stateBudgetReferenceDistance);
        m.put("state_budget_max_known",s.stateBudgetMaxKnown);
        m.put("optimization_millis",s.optimizationMillis);m.put("optimization_max_known",s.optimizationMaxKnown);m.put("search_weight",s.searchWeight);
        m.put("keep_successful_diagnostics",s.keepSuccessfulDiagnostics);m.put("diagnostic_error_retention",50);
        m.put("minimum_route_y",s.minimumRouteY);m.put("allow_world_edits",s.allowWorldEdits);
        m.put("stuck_window_ticks",s.stuckWindowTicks);m.put("stuck_min_movement",s.stuckMinMovement);
        m.put("stuck_min_vertical_movement",s.stuckMinVerticalMovement);
        m.put("smooth_paths",s.smoothPaths);m.put("max_smooth_distance",s.maxSmoothDistance);
        m.put("walk_ticks",s.walkTicks);m.put("swim_ticks",s.swimTicks);m.put("jump_ticks",s.jumpTicks);m.put("bridge_ticks",s.bridgeTicks);
        m.put("pillar_ticks",s.pillarTicks);m.put("aim_ticks",s.aimTicks);m.put("break_delay_ticks",s.breakDelayTicks);
        m.put("material_price_ticks",s.materialPriceTicks);m.put("dijkstra",s.dijkstra);m.put("prefer_vertical_surfacing",s.preferVerticalSurfacing);return m;
    }
    private static List<Object> toolDiagnostic(Snapshot snapshot) {
        List<Object> tools=new ArrayList<>();for(Tool t:snapshot.tools)tools.add(Map.of("item",t.stack.getItem().toString(),"damage",t.stack.getDamage(),
                "max_damage",t.stack.getMaxDamage(),"damageable",t.stack.isDamageable(),"empty",t.stack.isEmpty(),
                "wear_per_mine",t.stack.getItem() instanceof SwordItem?2:1,"efficiency",EnchantmentHelper.getLevel(Enchantments.EFFICIENCY,t.stack)));return tools;
    }
    /** Dumps the exact captured planning terrain and cost palette; uses no live World/Player. */
    public static void exportDiagnosticTerrain(Snapshot snapshot,BooleanSupplier cancelled) throws Exception {
        PlannerDiagnostics diagnostics=snapshot.diagnostics;if(diagnostics==null)return;
        diagnostics.exportTerrain(writer -> {
            Map<Cell,Integer> ids=new IdentityHashMap<>();List<Cell> palette=new ArrayList<>();
            for(Cell cell:snapshot.cells.values()) {if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                if(!ids.containsKey(cell)){ids.put(cell,palette.size());palette.add(cell);}}
            for(Material m:snapshot.materials)if(!ids.containsKey(m.cell)){ids.put(m.cell,palette.size());palette.add(m.cell);}
            List<Object> types=new ArrayList<>();for(Cell cell:palette){Map<String,Object> data=cellDiagnostic(cell);
                data.put("dry_ticks",cell.dryTicks);data.put("wet_ticks",cell.wetTicks);data.put("air_dry_ticks",cell.airDryTicks);data.put("air_wet_ticks",cell.airWetTicks);types.add(data);}
            List<Object> materials=new ArrayList<>();for(Material m:snapshot.materials)materials.add(Map.of("item",m.item.toString(),"gravity",m.gravity,"price_ticks",m.price,"cell_type",ids.get(m.cell)));
            List<Object> blacklist=new ArrayList<>();for(BlockPos p:snapshot.blacklist)blacklist.add(coordinates(p));
            Map<String,Object> header=new LinkedHashMap<>();header.put("schema",1);header.put("model","fredobot-action-v4");header.put("start",coordinates(snapshot.start.pos));
            header.put("goal",coordinates(snapshot.goal));header.put("settings",settingsDiagnostic(snapshot.settings));header.put("tools",toolDiagnostic(snapshot));
            header.put("start_ground_contact",snapshot.startGroundContact);
            header.put("material_types",materials);header.put("material_counts",snapshot.start.materials);header.put("durability",snapshot.start.durability);header.put("blacklist",blacklist);
            header.put("cell_palette",types);header.put("cell_count",snapshot.cells.size());
            String json=PlannerDiagnostics.json(header);writer.write(json.substring(0,json.length()-1));writer.write(",\"cells_xyzt\":[");
            boolean first=true;long count=0;
            for(var e:snapshot.cells.entrySet()) {
                if((++count & 1023)==0){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                    diagnostics.operation("export terrain","",Map.of("written_cells",count,"total_cells",snapshot.cells.size()));}
                if(!first)writer.write(',');first=false;BlockPos p=e.getKey();writer.write(PlannerDiagnostics.json(List.of(p.getX(),p.getY(),p.getZ(),ids.get(e.getValue()))));
            }
            writer.write("]}");
        });
    }
    private static final class DiagnosticProfiler implements SearchEngine.Profiler<State,Action> {
        final Snapshot snapshot;final PlannerDiagnostics diagnostics;
        final String pass;
        final Map<BlockPos,Integer> positionVariants=new HashMap<>();
        final Map<String,long[]> providerTimes=new LinkedHashMap<>();
        final Map<String,Long> acceptedActions=new LinkedHashMap<>();
        final long[] acceptedEdits=new long[65],expandedEdits=new long[65];
        boolean positionTrackingCapped;int maxVariants;BlockPos hottest;
        double bestGoal=Double.POSITIVE_INFINITY,bestComplete=Double.POSITIVE_INFINITY;long goalCandidates;int loggedGoals;
        DiagnosticProfiler(Snapshot snapshot,String pass){this.snapshot=snapshot;diagnostics=snapshot.diagnostics;this.pass=pass;}
        public void incumbent(SearchEngine.Result<State,Action> route) {
            bestComplete=route.ticks+route.penalty;
            diagnostics.event("complete_route",Map.of("pass",pass,"ticks",route.ticks,"penalty",route.penalty,"steps",route.steps.size()));
        }
        public void operation(String name,String provider,State state){diagnostics.operation(name,provider,state);}
        public void expanded(State state){expandedEdits[Math.min(64,state.edits.size())]++;}
        public void accepted(State state,Action action,boolean newState,double score,double heuristic) {
            if(state.pos.equals(snapshot.goal)){goalCandidates++;if(score<bestGoal){bestGoal=score;if(loggedGoals++<16)
                diagnostics.event("goal_candidate",Map.of("score",score,"heuristic",heuristic,"state",stateDiagnostic(state)));}}
            if(!newState)return;
            acceptedEdits[Math.min(64,state.edits.size())]++;
            if(action!=null)acceptedActions.merge(action.kind,1L,Long::sum);
            Integer count=positionVariants.get(state.pos);
            if(count!=null || positionVariants.size()<65536){int n=count==null?1:count+1;positionVariants.put(state.pos,n);
                if(n>maxVariants){maxVariants=n;hottest=state.pos;}}
            else positionTrackingCapped=true;
        }
        public void providerTime(String provider,long nanoseconds,int edges){long[] m=providerTimes.computeIfAbsent(provider,k -> new long[4]);m[0]++;m[1]+=nanoseconds;m[2]=Math.max(m[2],nanoseconds);m[3]+=edges;}
        private Map<String,Object> report(SearchEngine.Metrics metrics) {
            Map<String,Object> data=new LinkedHashMap<>(metrics.asMap());data.put("pass",pass);data.put("best_complete_route_score",bestComplete);
            data.put("unique_positions_tracked",positionVariants.size());data.put("position_tracking_capped",positionTrackingCapped);
            data.put("max_states_at_one_position",maxVariants);data.put("hottest_position",hottest==null?null:coordinates(hottest));data.put("best_goal_score_seen",bestGoal);data.put("goal_candidates",goalCandidates);
            data.put("accepted_edits_histogram",acceptedEdits.clone());data.put("expanded_edits_histogram",expandedEdits.clone());data.put("accepted_actions",new LinkedHashMap<>(acceptedActions));
            Map<String,Object> providers=new LinkedHashMap<>();for(var e:providerTimes.entrySet()){long[] n=e.getValue();providers.put(e.getKey(),Map.of("calls",n[0],"total_ms",n[1]/1e6,"max_ms",n[2]/1e6,"edges",n[3]));}
            data.put("providers",providers);return data;
        }
        public void progress(SearchEngine.Progress<State> progress){
            Map<String,Object> data=report(progress.metrics);data.put("current",stateDiagnostic(progress.current));data.put("current_g",progress.score);data.put("current_h",progress.heuristic);
            diagnostics.progress(progress.expanded,data);
        }
        public void finished(SearchEngine.Status status,SearchEngine.Metrics metrics){Map<String,Object> data=report(metrics);data.put("status",status.name());diagnostics.progress(metrics.expanded,data);diagnostics.event("search_summary",data);}
    }
    /** Consistent lower bound for the built-in actions. Combine horizontal/vertical with max, not sum:
     * a diagonal shore rise or falling step can achieve both during one action. */
    public static double estimateMinimumTicks(BlockPos a,BlockPos b,PlannerSettings settings) {
        double horizontalRate=Math.min(5,Math.min(settings.walkTicks,Math.min(settings.swimTicks,
                Math.min(settings.jumpTicks/2,settings.bridgeTicks))));
        double verticalRate=Math.min(2,Math.min(settings.swimTicks,Math.min(settings.jumpTicks,settings.pillarTicks)));
        double horizontal=(Math.abs(a.getX()-b.getX())+Math.abs(a.getZ()-b.getZ()))*horizontalRate;
        double vertical=Math.abs(a.getY()-b.getY())*verticalRate;
        return Math.max(horizontal,vertical);
    }
    private static double distance(BlockPos a, BlockPos b) {
        double x = a.getX()-b.getX(), y = a.getY()-b.getY(), z = a.getZ()-b.getZ();
        return Math.sqrt(x*x+y*y+z*z); // at least one tick per block in all built-in actions
    }
    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final class Builder {
        final Snapshot w; final State from;
        final Map<BlockPos,Integer> edits; final List<Integer> counts, durability;
        final List<BlockPos> breaks = new ArrayList<>(); final List<Item> tools = new ArrayList<>();
        double ticks, penalty; Item material;
        Builder(Snapshot w, State from) {
            this.w=w; this.from=from; edits=new HashMap<>(from.edits);
            counts=new ArrayList<>(from.materials); durability=new ArrayList<>(from.durability);
        }
        State at(BlockPos p) { return new State(p,edits,counts,durability); }
        boolean clear(BlockPos p) {
            State s = at(from.pos); Cell c = w.cell(s,p);
            if (c.clear) return true;
            if (c.doorPanels!=null || !w.settings.allowWorldEdits || w.blacklist.contains(p) || c.lava || c.dryTicks.length == 0) return false;
            // Falling sand/gravel or fluid entering the hole need a dynamic simulation, not a fake air edit.
            for (int[] d : DIRS) if (w.cell(s,p.add(d[0],0,d[1])).lava) return false;
            Cell above = w.cell(s,p.up());
            if (above.gravity || above.lava) return false;
            boolean wet = w.cell(s,from.pos.up()).water;
            int best = -1, bestTicks = Integer.MAX_VALUE;
            for (int i=0;i<w.tools.size();i++) {
                ItemStack stack = w.tools.get(i).stack;
                int wear = c.wearsTool && stack.isDamageable() ? stack.getItem() instanceof SwordItem ? 2 : 1 : 0;
                if (durability.get(i) <= wear && wear > 0) continue; // retain tool instead of assuming broken tool survives
                int n = (w.grounded(from) ? wet ? c.wetTicks : c.dryTicks : wet ? c.airWetTicks : c.airDryTicks)[i];
                if (n < bestTicks) { bestTicks=n; best=i; }
            }
            if (best < 0 || bestTicks == Integer.MAX_VALUE) return false;
            ItemStack tool = w.tools.get(best).stack;
            if (c.wearsTool && tool.isDamageable()) durability.set(best,durability.get(best)-(tool.getItem() instanceof SwordItem ? 2 : 1));
            ticks += bestTicks + w.settings.aimTicks + w.settings.breakDelayTicks;
            edits.put(p.toImmutable(),-1); breaks.add(p.toImmutable()); tools.add(tool.getItem());
            return edits.size() <= w.settings.maxEdits;
        }
        boolean body(BlockPos p) { return clear(p.up()) && clear(p); } // head first
        boolean place(BlockPos p,int index,boolean gravityAllowed) {
            State s=at(from.pos); Material m=w.materials.get(index);
            if (counts.get(index)<=0 || w.blacklist.contains(p) || !w.cell(s,p).replaceable || w.cell(s,p).water || w.cell(s,p).lava) return false;
            if (m.gravity && (!gravityAllowed || !w.cell(s,p.down()).support)) return false;
            boolean attached = w.cell(s,p.down()).support || w.cell(s,p.up()).support;
            for (int[] d:DIRS) attached |= w.cell(s,p.add(d[0],0,d[1])).support;
            if (!attached) return false;
            edits.put(p.toImmutable(),index); counts.set(index,counts.get(index)-1);
            material=m.item; penalty+=m.price;
            return edits.size()<=w.settings.maxEdits;
        }
        SearchEngine.Edge<State,Action> edge(BlockPos p,String name,SimplePathfinder.MovementState movement,double movementTicks) {
            return new SearchEngine.Edge<>(at(p),new Action(name,movement,breaks,tools,material),ticks+movementTicks,penalty);
        }
    }
    public static final class GroundActions implements SearchEngine.ActionProvider<State,Action> {
        final Snapshot w;
        public GroundActions(Snapshot w) { this.w=w; }
        public Iterable<SearchEngine.Edge<State,Action>> expand(State s) {
            List<SearchEngine.Edge<State,Action>> out=new ArrayList<>();
            if (w.cell(s,s.pos).water) return allowed(w,s,out);
            if (!w.grounded(s)) {
                fall(s,s.pos,out); return allowed(w,s,out);
            }
            for (int[] d:DIRS) {
                BlockPos p=s.pos.add(d[0],0,d[1]);
                Builder b=new Builder(w,s);
                if (b.body(p)) {
                    State next=b.at(p);
                    if (w.cell(next,p.down()).support || w.cell(next,p).water) {
                        SimplePathfinder.MovementState mode = w.cell(next,p).water ? waterMode(w,next,p) : SimplePathfinder.MovementState.WALKING;
                        out.add(b.edge(p,"walk/mine",mode,w.settings.walkTicks));
                    } else if (b.breaks.isEmpty()) fall(s,p,out);
                }
                // Jump up with explicit head clearance, also allowing overhead mining.
                if (w.cell(s,p).support) {
                    Builder up=new Builder(w,s);
                    if (up.clear(s.pos.up(2)) && up.body(p.up()))
                        out.add(up.edge(p.up(),"step up",SimplePathfinder.MovementState.JUMPING_UP,w.settings.jumpTicks));
                }
                // Dig a descending stair; clearance is part of the edge rather than a guessed mining state.
                Builder down=new Builder(w,s);
                if (w.cell(s,p.down(2)).support && down.clear(p.up()) && down.body(p.down()))
                    out.add(down.edge(p.down(),"descending stair",SimplePathfinder.MovementState.FALLING,w.settings.walkTicks+4));
                // Bridge choices remain separate branches; do not greedily consume materials needed later.
                for (int i=0;i<w.materials.size();i++) {
                    Builder bridge=new Builder(w,s);
                    if (bridge.body(p) && !w.cell(bridge.at(p),p.down()).support && bridge.place(p.down(),i,false))
                        out.add(bridge.edge(p,"bridge",SimplePathfinder.MovementState.BUILDING_BRIDGE,w.settings.bridgeTicks));
                }
                // Conservative two-block gap jump. Longer sprint trajectories need velocity-aware simulation.
                BlockPos landing=p.add(d[0],0,d[1]);
                if (w.body(s,p) && !w.cell(s,p.down()).support && w.body(s,landing) && w.cell(s,landing.down()).support
                        && w.cell(s,s.pos.up(2)).clear && w.cell(s,p.up(2)).clear && w.cell(s,landing.up(2)).clear) {
                    Builder jump=new Builder(w,s);
                    out.add(jump.edge(landing,"gap jump",SimplePathfinder.MovementState.JUMPING_AIR,w.settings.jumpTicks));
                }
            }
            // Pillar and down-mining explicitly alter support for later expansions.
            for (int i=0;i<w.materials.size();i++) {
                Builder pillar=new Builder(w,s);
                if (pillar.clear(s.pos.up(2)) && pillar.place(s.pos,i,true))
                    out.add(pillar.edge(s.pos.up(),"pillar",SimplePathfinder.MovementState.BUILDING_PILLAR,w.settings.pillarTicks));
            }
            Builder dig=new Builder(w,s);
            if (w.cell(s,s.pos.down(2)).support && dig.clear(s.pos.down()))
                out.add(dig.edge(s.pos.down(),"dig down",SimplePathfinder.MovementState.FALLING,5));
            return allowed(w,s,out);
        }
        void fall(State s,BlockPos column,List<SearchEngine.Edge<State,Action>> out) {
            for(int drop=1;drop<=32;drop++) {
                BlockPos p=column.down(drop);
                if (!w.body(s,p)) break;
                if (w.cell(s,p).water) {
                    out.add(new Builder(w,s).edge(p,"fall into water",waterMode(w,s,p),Math.max(5,drop*2))); break;
                }
                if(w.cell(s,p.down()).support) {
                    if(drop<=3) out.add(new Builder(w,s).edge(p,"safe fall",SimplePathfinder.MovementState.FALLING,Math.max(5,drop*2)));
                    break;
                }
            }
        }
    }
    public static final class WaterActions implements SearchEngine.ActionProvider<State,Action> {
        final Snapshot w;
        public WaterActions(Snapshot w) { this.w=w; }
        public Iterable<SearchEngine.Edge<State,Action>> expand(State s) {
            List<SearchEngine.Edge<State,Action>> out=new ArrayList<>();
            if(!w.cell(s,s.pos).water) return allowed(w,s,out);
            if(w.settings.preferVerticalSurfacing && s.pos.getY()<w.goal.getY() && w.cell(s,s.pos.up()).water
                    && !w.cell(s,w.goal.up()).water) {
                // A surfacing mission prefers the current water column when its whole
                // body corridor is clear. Never infer air beyond the captured snapshot.
                for(int rise=1;rise<=255-s.pos.getY();rise++) {
                    BlockPos p=s.pos.up(rise);
                    if(!w.body(s,p) || !w.cell(s,p).water)break;
                    if(!w.cell(s,p.up()).water) {
                        if(p.getY()<=w.goal.getY()+1)
                            return allowed(w,s,List.of(new Builder(w,s).edge(p,"surface rise",SimplePathfinder.MovementState.SWIMMING,rise*w.settings.swimTicks)));
                        break;
                    }
                }
            }
            // A one-block bank is support, not an obstacle at the player's intended destination.
            // Enumerate a direct, non-destructive diagonal rise with feet above that support block.
            for(int[] d:DIRS) {
                BlockPos bank=s.pos.add(d[0],0,d[1]);
                BlockPos landing=bank.up();
                if(w.cell(s,bank).support && !w.cell(s,landing).water && w.body(s,landing)
                        && w.cell(s,s.pos.up(2)).clear) {
                    out.add(new Builder(w,s).edge(landing,"shore exit",SimplePathfinder.MovementState.JUMPING_UP,w.settings.jumpTicks));
                }
            }
            int[][] dirs={{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
            for(int[] d:dirs) {
                BlockPos p=s.pos.add(d[0],d[1],d[2]); Builder b=new Builder(w,s);
                if(!b.body(p)) continue;
                State next=b.at(p);
                if(w.cell(next,p).water || w.cell(next,p.down()).support)
                    out.add(b.edge(p,"swim/mine",w.cell(next,p).water ? waterMode(w,next,p) : SimplePathfinder.MovementState.WALKING,w.settings.swimTicks));
            }
            return allowed(w,s,out);
        }
    }
    private static SimplePathfinder.MovementState waterMode(Snapshot w,State s,BlockPos p) {
        return w.cell(s,p.up()).water ? SimplePathfinder.MovementState.DIVING : SimplePathfinder.MovementState.SWIMMING;
    }
    /** Merge effect-free walking and at most five deep-water actions. World edits remain barriers. */
    public static SearchEngine.Result<State,Action> smooth(Snapshot snapshot,SearchEngine.Result<State,Action> plan,BooleanSupplier cancelled) {
        if(!snapshot.settings.smoothPaths || plan.status!=SearchEngine.Status.COMPLETE)return plan;
        List<SearchEngine.Edge<State,Action>> steps=new ArrayList<>();State from=snapshot.start;boolean changed=false;
        for(int i=0;i<plan.steps.size();) {
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            int best=i;
            if(plan.steps.get(i).action.movement==SimplePathfinder.MovementState.DIVING) {
                for(int j=i;j<Math.min(i+5,plan.steps.size());j++) {
                    var edge=plan.steps.get(j);Action a=edge.action;
                    if(a.movement!=SimplePathfinder.MovementState.DIVING || !a.breaks.isEmpty() || a.material!=null
                            || edge.penalty!=0 || (!a.kind.equals("swim/mine") && !a.isSmoothDive())
                            || !edge.next.edits.equals(from.edits) || !edge.next.materials.equals(from.materials)
                            || !edge.next.durability.equals(from.durability))break;
                    State view=from;BlockPos end=edge.next.pos;
                    if(DiveClearance.clear(from.pos.getX()+.5,from.pos.getY(),from.pos.getZ()+.5,
                            end.getX()+.5,end.getY(),end.getZ()+.5,
                            p -> {Cell c=snapshot.cell(view,p);return c.clear && c.water && !c.lava && c.doorPanels==null;},cancelled))best=j;
                }
                var end=plan.steps.get(best);
                if(best>i) {
                    double length=distance(from.pos,end.next.pos);
                    steps.add(new SearchEngine.Edge<>(end.next,new Action("smooth dive",SimplePathfinder.MovementState.DIVING,
                            List.of(),List.of(),null),Math.max(1,length*snapshot.settings.swimTicks),0));changed=true;
                } else steps.add(end);
                from=end.next;i=best+1;
                continue;
            }
            for(int j=i;j<plan.steps.size();j++) {
                var edge=plan.steps.get(j);Action a=edge.action;
                if(a.movement!=SimplePathfinder.MovementState.WALKING || !a.breaks.isEmpty() || a.material!=null || edge.penalty!=0
                        || (!a.kind.equals("walk/mine") && !a.isSmoothWalk()) || edge.next.pos.getY()!=from.pos.getY()
                        || !edge.next.edits.equals(from.edits) || !edge.next.materials.equals(from.materials)
                        || !edge.next.durability.equals(from.durability))break;
                double length=Math.hypot(edge.next.pos.getX()-from.pos.getX(),edge.next.pos.getZ()-from.pos.getZ());
                if(length>snapshot.settings.maxSmoothDistance)break;
                State view=from;
                boolean safe=WalkClearance.clear(from.pos.getX()+.5,from.pos.getZ()+.5,edge.next.pos.getX()+.5,edge.next.pos.getZ()+.5,from.pos.getY(),
                        p -> {Cell c=snapshot.cell(view,p);return c.clear && c.doorPanels==null && !c.water && !c.lava;},
                        p -> snapshot.cell(view,p).support,cancelled);
                if(safe)best=j;
            }
            var end=plan.steps.get(best);
            if(best>i) {
                double length=Math.hypot(end.next.pos.getX()-from.pos.getX(),end.next.pos.getZ()-from.pos.getZ());
                steps.add(new SearchEngine.Edge<>(end.next,new Action("smooth walk",SimplePathfinder.MovementState.WALKING,List.of(),List.of(),null),
                        Math.max(1,length*snapshot.settings.walkTicks),0));changed=true;
            } else steps.add(end);
            from=end.next;i=best+1;
            if(snapshot.diagnostics!=null)snapshot.diagnostics.operation("smooth path","worker",Map.of("processed_edges",i,"total_edges",plan.steps.size()));
        }
        double ticks=0,penalty=0;for(var edge:steps){ticks+=edge.ticks;penalty+=edge.penalty;}
        if(snapshot.diagnostics!=null)snapshot.diagnostics.event("smoothing",Map.of("before",plan.steps.size(),"after",steps.size(),"ticks_before",plan.ticks,"ticks_after",ticks));
        return changed?new SearchEngine.Result<>(SearchEngine.Status.COMPLETE,steps,ticks,penalty,plan.expanded,false,"SMOOTHED_ROUTE"):plan;
    }
    /** Prerequisites have been preserved before converting the final execution route. */
    public static List<SimplePathfinder.Node> toNodes(Snapshot snapshot,SearchEngine.Result<State,Action> result) {
        List<SimplePathfinder.Node> path=new ArrayList<>();
        SimplePathfinder.Node parent=new SimplePathfinder.Node(snapshot.start.pos,null,null,0,0,SimplePathfinder.MovementState.WALKING,0);
        path.add(parent); double cost=0; int placed=0;
        for(SearchEngine.Edge<State,Action> edge:result.steps) {
            cost+=edge.ticks+edge.penalty; if(edge.action.material!=null) placed++;
            SimplePathfinder.Node node=new SimplePathfinder.Node(edge.next.pos,null,parent,cost,0,edge.action.movement,placed);
            node.plannedAction=edge.action; node.actionTicks=edge.ticks; node.materialPenalty=edge.penalty;
            path.add(node); parent=node;
        }
        return path;
    }
}
