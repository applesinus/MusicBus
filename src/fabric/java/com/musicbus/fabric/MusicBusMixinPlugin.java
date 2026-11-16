package com.musicbus.fabric;

import com.musicbus.core.MusicBusTransformer;
import com.musicbus.mappings.McFeature;
import com.musicbus.mappings.McMappings;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class MusicBusMixinPlugin implements IMixinConfigPlugin {

    private boolean mappingsInitialized;

    @Override
    public void onLoad(String mixinPackage) {
        ensureMappingsInitialized();
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
        if (Boolean.getBoolean("musicbus.agent.active")) {
            return false;
        }
        try {
            if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public void acceptTargets(
            Set<String> myTargets,
            Set<String> otherTargets
    ) {
    }

    @Override
    public List<String> getMixins() {
        if (Boolean.getBoolean("musicbus.agent.active")) {
            return Collections.emptyList();
        }
        try {
            if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
                return Collections.emptyList();
            }
        } catch (Throwable ignored) {
        }

        if (!ensureMappingsInitialized()) {
            System.err.println(
                    "[MusicBus] WARNING: Could not initialize McMappings in MusicBusMixinPlugin; "
                            + "falling back to unobfuscated Mojang mixin targets."
            );
            return Arrays.asList(
                    "AbstractSoundInstanceMixin",
                    "ChannelMixin",
                    "SoundBufferLibraryMixin",
                    "SoundEngineMixin",
                    "SoundOptionsScreenMixin"
            );
        }

        List<String> mixins = new ArrayList<String>(5);
        McMappings.Namespace ns = McMappings.getNamespace();
        boolean hasOptionInstance = McMappings.hasFeature(McFeature.HAS_OPTION_INSTANCE);

        if (ns == McMappings.Namespace.INTERMEDIARY) {
            mixins.add("intermediary.IntermediaryAbstractSoundInstanceMixin");
            mixins.add("intermediary.IntermediaryChannelMixin");
            mixins.add("intermediary.IntermediarySoundBufferLibraryMixin");
            mixins.add("intermediary.IntermediarySoundEngineMixin");
            if (hasOptionInstance) {
                mixins.add("intermediary.IntermediarySoundOptionsScreenMixin");
            }
        } else {
            mixins.add("AbstractSoundInstanceMixin");
            mixins.add("ChannelMixin");
            mixins.add("SoundBufferLibraryMixin");
            mixins.add("SoundEngineMixin");
            if (hasOptionInstance) {
                if (McMappings.hasFeature(McFeature.SOUND_OPTIONS_IN_OPTIONS_PKG)) {
                    mixins.add("SoundOptionsScreenMixin");
                } else {
                    mixins.add("LegacySoundOptionsScreenMixin");
                }
            }
        }

        return mixins;
    }

    private boolean ensureMappingsInitialized() {
        if (mappingsInitialized) {
            return true;
        }
        ClassLoader loader = MusicBusMixinPlugin.class.getClassLoader();
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        if (McMappings.tryInitialize(loader)) {
            mappingsInitialized = true;
            return true;
        }
        try {
            FabricLoader.getInstance().getModContainer("minecraft").ifPresent(mod -> {
                String ver = mod.getMetadata().getVersion().getFriendlyString();
                if (ver != null && !ver.isEmpty()
                        && System.getProperty("musicbus.mc.version") == null) {
                    System.setProperty("musicbus.mc.version", ver);
                }
            });
        } catch (Throwable ignored) {
        }
        if (McMappings.tryInitialize(loader)) {
            mappingsInitialized = true;
            return true;
        }
        return false;
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