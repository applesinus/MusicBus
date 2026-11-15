package com.musicbus.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(
        targets = "net.minecraft.client.sounds.SoundEngine",
        priority = 1500,
        remap = false
)
public abstract class SoundEngineMixin {
}