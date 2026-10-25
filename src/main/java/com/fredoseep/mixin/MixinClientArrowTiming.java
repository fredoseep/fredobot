package com.fredoseep.mixin;

import com.fredoseep.testing.combat.ClientArrowTimingProbe;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** UUID/world-scoped read-only observation; does not cancel or modify native methods. */
@Mixin(PersistentProjectileEntity.class)
public abstract class MixinClientArrowTiming {
    @Inject(method="tick",at=@At("HEAD"))
    private void fredobot$arrowTickHead(CallbackInfo ci){PersistentProjectileEntity a=(PersistentProjectileEntity)(Object)this;ClientArrowTimingProbe.beforeTick(a.world,a);}
    @Inject(method="tick",at=@At("RETURN"))
    private void fredobot$arrowTickTail(CallbackInfo ci){PersistentProjectileEntity a=(PersistentProjectileEntity)(Object)this;ClientArrowTimingProbe.afterTick(a.world,a);}
    @Inject(method="updateTrackedPositionAndAngles",at=@At("HEAD"),require=0)
    private void fredobot$arrowPositionHead(double x,double y,double z,float yaw,float pitch,int steps,boolean interpolate,CallbackInfo ci){fredobot$recordPosition(true,x,y,z,yaw,pitch,steps,interpolate);}
    @Inject(method="updateTrackedPositionAndAngles",at=@At("RETURN"),require=0)
    private void fredobot$arrowPositionTail(double x,double y,double z,float yaw,float pitch,int steps,boolean interpolate,CallbackInfo ci){fredobot$recordPosition(false,x,y,z,yaw,pitch,steps,interpolate);}
    @Inject(method="setVelocityClient",at=@At("HEAD"),require=0)
    private void fredobot$arrowVelocityHead(double x,double y,double z,CallbackInfo ci){fredobot$recordVelocity(true,x,y,z);}
    @Inject(method="setVelocityClient",at=@At("RETURN"),require=0)
    private void fredobot$arrowVelocityTail(double x,double y,double z,CallbackInfo ci){fredobot$recordVelocity(false,x,y,z);}
    private void fredobot$recordPosition(boolean before,double x,double y,double z,float yaw,float pitch,int steps,boolean interpolate){PersistentProjectileEntity a=(PersistentProjectileEntity)(Object)this;ClientArrowTimingProbe.network(a.world,a,"POSITION",before,List.of(x,y,z,yaw,pitch,steps,interpolate));}
    private void fredobot$recordVelocity(boolean before,double x,double y,double z){PersistentProjectileEntity a=(PersistentProjectileEntity)(Object)this;ClientArrowTimingProbe.network(a.world,a,"VELOCITY",before,List.of(x,y,z));}
}
