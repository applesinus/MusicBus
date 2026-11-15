package com.musicbus.fabric;

import com.musicbus.core.MusicBusTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class MusicBusMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(
            String targetClassName,
            String mixinClassName
    ) {
        return !Boolean.getBoolean("musicbus.agent.active");
    }

    @Override
    public void acceptTargets(
            Set<String> myTargets,
            Set<String> otherTargets
    ) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo
    ) {
    }

    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo
    ) {
        if (Boolean.getBoolean("musicbus.agent.active")) {
            return;
        }

        String internalName = targetClassName.replace('.', '/');
        if (!MusicBusTransformer.isTargetClass(internalName)) {
            return;
        }

        ClassWriter writer = new ClassWriter(0);
        targetClass.accept(writer);

        byte[] transformed = MusicBusTransformer.transformClassBytes(
                internalName,
                writer.toByteArray()
        );

        if (transformed == null) {
            throw new IllegalStateException(
                    "[MusicBus] Failed to transform target class via Fabric Mixin: "
                            + targetClassName
            );
        }

        ClassNode updated = new ClassNode();
        new ClassReader(transformed).accept(updated, 0);

        targetClass.methods.clear();
        targetClass.methods.addAll(updated.methods);
    }
}