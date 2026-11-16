package com.musicbus.fabric.mixin.intermediary;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(
        targets = "net.minecraft.class_4237",
        priority = 1500,
        remap = false
)
public abstract class IntermediarySoundBufferLibraryMixin {
}