package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;

/** Relative portal work volume only; fluid-angle and bucket actions are intentionally independent. */
public final class PortalClearanceGeometry {
    private PortalClearanceGeometry(){}
    public static List<BlockPos> cells(BlockPos magma,BlockPos aligned,Direction towardAligned){
        List<BlockPos> cells=new ArrayList<>();
        for(BlockPos base:List.of(magma,aligned,magma.offset(towardAligned.getOpposite()),aligned.offset(towardAligned))){
            cells.add(base.up());cells.add(base.up(2));
        }
        cells.add(magma.down(2));cells.add(aligned.down(2));
        return List.copyOf(cells);
    }
}
