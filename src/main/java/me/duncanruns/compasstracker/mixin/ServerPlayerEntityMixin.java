package me.duncanruns.compasstracker.mixin;

import me.duncanruns.compasstracker.CompassTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "die", at = @At("RETURN"))
    private void relayDeath(DamageSource source, CallbackInfo ci) {
        CompassTracker.onDeath((ServerPlayer) (Object) this);
    }
}
