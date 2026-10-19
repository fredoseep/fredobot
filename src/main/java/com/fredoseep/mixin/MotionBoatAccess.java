package com.fredoseep.mixin;

import net.minecraft.entity.vehicle.BoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BoatEntity.class)
public interface MotionBoatAccess {
    @Accessor("velocityDecay") float fredobot$getvelocityDecay();
    @Accessor("velocityDecay") void fredobot$setvelocityDecay(float value);
    @Accessor("yawVelocity") float fredobot$getyawVelocity();
    @Accessor("yawVelocity") void fredobot$setyawVelocity(float value);
    @Accessor("waterLevel") double fredobot$getwaterLevel();
    @Accessor("waterLevel") void fredobot$setwaterLevel(double value);
    @Accessor("field_7714") float fredobot$getfield_7714();
    @Accessor("field_7714") void fredobot$setfield_7714(float value);
    @Accessor("location") BoatEntity.Location fredobot$getlocation();
    @Accessor("location") void fredobot$setlocation(BoatEntity.Location value);
    @Accessor("lastLocation") BoatEntity.Location fredobot$getlastLocation();
    @Accessor("lastLocation") void fredobot$setlastLocation(BoatEntity.Location value);
    @Accessor("fallVelocity") double fredobot$getfallVelocity();
    @Accessor("fallVelocity") void fredobot$setfallVelocity(double value);
    @Accessor("ticksUnderwater") float fredobot$getticksUnderwater();
    @Accessor("ticksUnderwater") void fredobot$setticksUnderwater(float value);
    @Invoker("checkLocation") BoatEntity.Location fredobot$checkLocation();
    @Invoker("updateVelocity") void fredobot$updateVelocity();
    @Invoker("updatePaddles") void fredobot$updatePaddles();
}
