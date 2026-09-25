package dev.seedy.mixin;

import dev.seedy.SeedyClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ConnectionMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void seedyLogin(ClientboundLoginPacket packet, CallbackInfo callback) { SeedyClient.beginSession(packet.commonPlayerSpawnInfo().seed(), true); }
    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void seedyRespawn(ClientboundRespawnPacket packet, CallbackInfo callback) { SeedyClient.beginSession(packet.commonPlayerSpawnInfo().seed(), false); }
}
