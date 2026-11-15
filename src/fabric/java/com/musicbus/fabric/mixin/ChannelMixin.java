package com.musicbus.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(
        targets = "com.mojang.blaze3d.audio.Channel",
        priority = 1500,
        remap = false
)
public abstract class ChannelMixin {
}