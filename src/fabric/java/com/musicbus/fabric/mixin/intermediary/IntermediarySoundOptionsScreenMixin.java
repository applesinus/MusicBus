package com.musicbus.fabric.mixin.intermediary;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(
        targets = "net.minecraft.class_443",
        priority = 1500,
        remap = false
)
public abstract class IntermediarySoundOptionsScreenMixin {
}