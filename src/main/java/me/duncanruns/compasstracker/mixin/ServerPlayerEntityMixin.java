package me.duncanruns.compasstracker.mixin;

import me.duncanruns.compasstracker.CompassTracker;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "onDeath", at = @At("RETURN"))
    private void relayDeath(DamageSource damageSource, CallbackInfo ci) {
        CompassTracker.onDeath((ServerPlayerEntity) (Object) this);
    }
}
