package com.fredoseep.motion;

import java.util.Set;

/** Keep the original dry policy; fusion may additionally simulate vanilla climbing plants. */
public final class MotionEnvironmentPolicy {
    private static final Set<String> VINES=Set.of("minecraft:vine","minecraft:weeping_vines","minecraft:weeping_vines_plant",
            "minecraft:twisting_vines","minecraft:twisting_vines_plant");
    private MotionEnvironmentPolicy(){}
    public static boolean vine(String id){return VINES.contains(id);}
    public static boolean reject(String id,boolean fluid,boolean allowVines){
        boolean special=id.contains("portal") || id.contains("piston") || id.contains("slime") || id.contains("honey")
                || id.contains("soul_sand") || id.contains("soul_soil") || id.contains("cobweb") || id.contains("ladder")
                || id.contains("vine") || id.contains("cactus") || id.contains("fire") || id.contains("magma")
                || id.contains("berry") || id.contains("pressure_plate") || id.contains("tripwire")
                || id.contains("farmland") || id.endsWith("_bed");
        return fluid || (special && !(allowVines && vine(id)));
    }
}
