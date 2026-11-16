package com.musicbus.core;

import com.musicbus.mappings.McClass;
import com.musicbus.mappings.McFeature;
import com.musicbus.mappings.McField;
import com.musicbus.mappings.McMappings;
import com.musicbus.mappings.McMethod;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class MusicBusTransformer
        implements ClassFileTransformer {

    private static final String MANAGER =
            "com/musicbus/core/MusicBusManager";

    private static final String CHANNEL_TRACKER =
            "com/musicbus/audio/VanillaChannelTracker";

    private static final String OPTIONS_SCREEN_HOOK =
            "com/musicbus/core/OptionsScreenHook";

    private static final MusicBusTransformer INSTANCE =
            new MusicBusTransformer();

    public static boolean isTargetClass(String internalClassName) {
        if (internalClassName == null) {
            return false;
        }
        if (!McMappings.tryInitialize(
                Thread.currentThread().getContextClassLoader()
        )) {
            return false;
        }
        McClass target = McMappings.findClassByInternalName(internalClassName);
        return target == McClass.SOUND_OPTIONS_SCREEN
                || target == McClass.SOUND_ENGINE
                || target == McClass.ABSTRACT_SOUND_INSTANCE
                || target == McClass.SOUND_BUFFER_LIBRARY
                || target == McClass.AUDIO_CHANNEL;
    }

    public static byte[] transformClassBytes(
            String internalClassName,
            byte[] classfileBuffer
    ) {
        if (internalClassName == null || classfileBuffer == null) {
            return null;
        }
        if (!McMappings.tryInitialize(
                Thread.currentThread().getContextClassLoader()
        )) {
            return null;
        }
        return INSTANCE.transformInternal(
                internalClassName,
                classfileBuffer
        );
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        if (loader != null && shouldAttemptInit(className)) {
            McMappings.tryInitialize(loader);
        }
        return transformClassBytes(className, classfileBuffer);
    }

    private static boolean shouldAttemptInit(String className) {
        return !className.startsWith("java/")
                && !className.startsWith("javax/")
                && !className.startsWith("sun/")
                && !className.startsWith("jdk/")
                && !className.startsWith("com/musicbus/")
                && !className.startsWith("org/objectweb/asm/");
    }

    private byte[] transformInternal(
            String className,
            byte[] classfileBuffer
    ) {
        McClass target = McMappings.findClassByInternalName(className);
        if (target == null) {
            return null;
        }

        if (target == McClass.SOUND_OPTIONS_SCREEN) {
            return transformSoundOptions(classfileBuffer);
        }

        if (target == McClass.SOUND_ENGINE) {
            return transformSoundEngine(classfileBuffer);
        }

        if (target == McClass.ABSTRACT_SOUND_INSTANCE) {
            return transformAbstractSoundInstance(classfileBuffer);
        }

        if (target == McClass.SOUND_BUFFER_LIBRARY) {
            return transformSoundBufferLibrary(classfileBuffer);
        }

        if (target == McClass.AUDIO_CHANNEL) {
            return transformChannel(classfileBuffer);
        }

        return null;
    }

    /**
     * Injects the MusicBus toggle into SoundOptionsScreen.
     */
    private byte[] transformSoundOptions(byte[] originalBytes) {

        if (!McMappings.hasFeature(McFeature.HAS_OPTION_INSTANCE)
                || !McMappings.hasMethod(McMethod.SOUND_OPTIONS_SCREEN_POPULATE)) {
            return null;
        }

        ClassReader reader = new ClassReader(originalBytes);

        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS
        );

        final boolean[] found = {false};

        ClassVisitor visitor = new ClassVisitor(
                Opcodes.ASM9,
                writer
        ) {

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {

                MethodVisitor mv = super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                );

                if (!McMappings.matchesMethod(
                        McMethod.SOUND_OPTIONS_SCREEN_POPULATE,
                        name,
                        descriptor
                )) {
                    return mv;
                }

                found[0] = true;

                System.out.println(
                        "[MusicBus] Found SoundOptionsScreen." + name + descriptor
                );

                return new MethodVisitor(Opcodes.ASM9, mv) {

                    @Override
                    public void visitInsn(int opcode) {

                        if (opcode == Opcodes.RETURN) {

                            super.visitVarInsn(
                                    Opcodes.ALOAD,
                                    0
                            );

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    OPTIONS_SCREEN_HOOK,
                                    "onSoundOptionsScreen",
                                    "(Ljava/lang/Object;)V",
                                    false
                            );
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        };

        reader.accept(visitor, 0);

        if (!found[0]) {
            System.err.println(
                    "[MusicBus] SoundOptionsScreen populate method not found!"
            );
            return null;
        }

        System.out.println(
                "[MusicBus] SoundOptionsScreen transformed."
        );

        return writer.toByteArray();
    }

    /**
     * Intercepts SoundEngine.play(SoundInstance).
     *
     * Only SoundSource.MUSIC is affected.
     * Every other category follows vanilla logic.
     */
    private byte[] transformSoundEngine(byte[] originalBytes) {

        final String soundInstanceInternal =
                McMappings.internalName(McClass.SOUND_INSTANCE);
        final String soundSourceInternal =
                McMappings.internalName(McClass.SOUND_CATEGORY);
        final String getCategoryName =
                McMappings.methodName(McMethod.SOUND_INSTANCE_GET_CATEGORY);
        final String getCategoryDesc =
                McMappings.methodDescriptor(McMethod.SOUND_INSTANCE_GET_CATEGORY);
        final String musicFieldName =
                McMappings.fieldName(McField.SOUND_CATEGORY_MUSIC);
        final String musicFieldDesc =
                McMappings.fieldDescriptor(McField.SOUND_CATEGORY_MUSIC);

        final boolean playReturnsResult =
                McMappings.hasFeature(McFeature.PLAY_RETURNS_RESULT);
        final String playResultInternal = playReturnsResult
                ? McMappings.internalName(McClass.PLAY_RESULT)
                : null;
        final String playResultStartedName = playReturnsResult
                ? McMappings.fieldName(McField.PLAY_RESULT_STARTED)
                : null;
        final String playResultStartedDesc = playReturnsResult
                ? McMappings.fieldDescriptor(McField.PLAY_RESULT_STARTED)
                : null;

        final boolean pauseAllExcept =
                McMappings.hasFeature(McFeature.SOUND_ENGINE_PAUSE_ALL_EXCEPT);

        if (soundInstanceInternal == null
                || soundSourceInternal == null
                || getCategoryName == null
                || getCategoryDesc == null
                || musicFieldName == null
                || musicFieldDesc == null
                || (playReturnsResult && (playResultInternal == null
                        || playResultStartedName == null
                        || playResultStartedDesc == null))) {
            System.err.println(
                    "[MusicBus] SoundEngine mappings incomplete!"
            );
            return null;
        }

        ClassReader reader = new ClassReader(originalBytes);

        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS
        );

        final boolean[] found = new boolean[8];

        ClassVisitor visitor = new ClassVisitor(
                Opcodes.ASM9,
                writer
        ) {

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {

                MethodVisitor mv = super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                );

                // ------------------------------------------
                // 1. SoundEngine.play(SoundInstance)
                // ------------------------------------------

                if (McMappings.matchesMethod(
                        McMethod.SOUND_ENGINE_PLAY,
                        name,
                        descriptor
                )) {

                    found[0] = true;

                    System.out.println(
                            "[MusicBus] Hooked SoundEngine.play()."
                    );

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {

                            super.visitCode();

                            Label vanilla = new Label();

                            // instance.getSource()
                            super.visitVarInsn(Opcodes.ALOAD, 1);

                            super.visitMethodInsn(
                                    Opcodes.INVOKEINTERFACE,
                                    soundInstanceInternal,
                                    getCategoryName,
                                    getCategoryDesc,
                                    true
                            );

                            // Compare with SoundSource.MUSIC.
                            super.visitFieldInsn(
                                    Opcodes.GETSTATIC,
                                    soundSourceInternal,
                                    musicFieldName,
                                    musicFieldDesc
                            );

                            super.visitJumpInsn(
                                    Opcodes.IF_ACMPNE,
                                    vanilla
                            );

                            // MusicBusManager.interceptMusicPlay(instance)
                            super.visitVarInsn(Opcodes.ALOAD, 1);

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "interceptMusicPlay",
                                    "(Ljava/lang/Object;)Z",
                                    false
                            );

                            super.visitJumpInsn(
                                    Opcodes.IFEQ,
                                    vanilla
                            );

                            if (playReturnsResult) {
                                // Return STARTED instead of NOT_STARTED.
                                super.visitFieldInsn(
                                        Opcodes.GETSTATIC,
                                        playResultInternal,
                                        playResultStartedName,
                                        playResultStartedDesc
                                );
                                super.visitInsn(Opcodes.ARETURN);
                            } else {
                                super.visitInsn(Opcodes.RETURN);
                            }

                            super.visitLabel(vanilla);

                            super.visitFrame(
                                    Opcodes.F_SAME,
                                    0,
                                    null,
                                    0,
                                    null
                            );
                        }
                    };
                }

                // ------------------------------------------
                // 2. SoundEngine.isActive(SoundInstance)
                // ------------------------------------------

                if (McMappings.matchesMethod(
                        McMethod.SOUND_ENGINE_IS_ACTIVE,
                        name,
                        descriptor
                )) {

                    found[1] = true;

                    System.out.println(
                            "[MusicBus] Hooked SoundEngine.isActive()."
                    );

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {

                            super.visitCode();

                            Label vanilla = new Label();

                            super.visitVarInsn(
                                    Opcodes.ALOAD,
                                    1
                            );

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "isBusActive",
                                    "(Ljava/lang/Object;)Z",
                                    false
                            );

                            super.visitJumpInsn(
                                    Opcodes.IFEQ,
                                    vanilla
                            );

                            // Bused audio player reports active playback.
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitInsn(Opcodes.IRETURN);

                            super.visitLabel(vanilla);

                            super.visitFrame(
                                    Opcodes.F_SAME,
                                    0,
                                    null,
                                    0,
                                    null
                            );
                        }
                    };
                }

                // ------------------------------------------
                // 3. SoundEngine.stop(SoundInstance)
                // ------------------------------------------

                if (McMappings.matchesMethod(
                        McMethod.SOUND_ENGINE_STOP_INSTANCE,
                        name,
                        descriptor
                )) {

                    found[2] = true;

                    System.out.println(
                            "[MusicBus] Hooked SoundEngine.stop()."
                    );

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {

                            super.visitCode();

                            Label vanilla = new Label();

                            // Check category first.
                            super.visitVarInsn(
                                    Opcodes.ALOAD,
                                    1
                            );

                            super.visitMethodInsn(
                                    Opcodes.INVOKEINTERFACE,
                                    soundInstanceInternal,
                                    getCategoryName,
                                    getCategoryDesc,
                                    true
                            );

                            super.visitFieldInsn(
                                    Opcodes.GETSTATIC,
                                    soundSourceInternal,
                                    musicFieldName,
                                    musicFieldDesc
                            );

                            super.visitJumpInsn(
                                    Opcodes.IF_ACMPNE,
                                    vanilla
                            );

                            // Ask MusicBusManager to intercept stop.
                            super.visitVarInsn(
                                    Opcodes.ALOAD,
                                    1
                            );

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "interceptMusicStop",
                                    "(Ljava/lang/Object;)Z",
                                    false
                            );

                            super.visitJumpInsn(
                                    Opcodes.IFEQ,
                                    vanilla
                            );

                            // Stop was handled by MusicBus.
                            super.visitInsn(Opcodes.RETURN);

                            super.visitLabel(vanilla);

                            super.visitFrame(
                                    Opcodes.F_SAME,
                                    0,
                                    null,
                                    0,
                                    null
                            );
                        }
                    };
                }

                // ------------------------------------------
                // 4. SoundEngine.refreshCategoryVolume / updateCategoryVolume
                // ------------------------------------------

                if (McMappings.matchesMethod(
                        McMethod.SOUND_ENGINE_UPDATE_CATEGORY_VOLUME,
                        name,
                        descriptor
                )) {

                    found[3] = true;

                    System.out.println(
                            "[MusicBus] Hooked SoundEngine category volume refresh ("
                                    + name + descriptor + ")."
                    );

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {

                            super.visitCode();

                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "onCategoryVolumeRefreshed",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false
                            );
                        }
                    };
                }

                // ------------------------------------------
                // 5. SoundEngine.pauseAllExcept(SoundSource...) / pause()
                // ------------------------------------------

                if (McMappings.matchesMethod(
                        McMethod.SOUND_ENGINE_PAUSE,
                        name,
                        descriptor
                )) {

                    found[4] = true;

                    System.out.println(
                            "[MusicBus] Hooked SoundEngine." + name + descriptor
                    );

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {

                            super.visitCode();

                            if (pauseAllExcept) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                            } else {
                                super.visitInsn(Opcodes.ACONST_NULL);
                            }

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "onPauseAllExcept",
                                    "([Ljava/lang/Object;)V",
                                    false
                            );
                        }
                    };
                }

                // ------------------------------------------
                // 6. SoundEngine.resume()
                // ------------------------------------------

                if (McMappings.matchesMethod(
                        McMethod.SOUND_ENGINE_RESUME,
                        name,
                        descriptor
                )) {

                    found[5] = true;

                    System.out.println(
                            "[MusicBus] Hooked SoundEngine.resume()."
                    );

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {

                            super.visitCode();

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "onSoundEngineResume",
                                    "()V",
                                    false
                            );
                        }
                    };
                }

                // ------------------------------------------
                // 7. SoundEngine.stop(Identifier, SoundSource)
                // ------------------------------------------

                if (McMappings.matchesMethod(
                        McMethod.SOUND_ENGINE_STOP_BY_ID,
                        name,
                        descriptor
                )) {

                    found[6] = true;

                    System.out.println(
                            "[MusicBus] Hooked SoundEngine.stop(Id, Source)."
                    );

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {

                            super.visitCode();

                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.ALOAD, 2);

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "onStopByIdAndSource",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false
                            );
                        }
                    };
                }

                // ------------------------------------------
                // 8. SoundEngine.stopAll()
                // ------------------------------------------

                if (McMappings.matchesMethod(
                        McMethod.SOUND_ENGINE_STOP_ALL,
                        name,
                        descriptor
                )) {

                    found[7] = true;

                    System.out.println(
                            "[MusicBus] Hooked SoundEngine.stopAll()."
                    );

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {

                            super.visitCode();

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "onStopAll",
                                    "()V",
                                    false
                            );
                        }
                    };
                }

                return mv;
            }
        };

        reader.accept(visitor, 0);

        if (!found[0] || !found[1] || !found[2] || !found[3]
                || !found[4] || !found[5] || !found[6] || !found[7]) {

            System.err.println(
                    "[MusicBus] SoundEngine transformation incomplete!"
            );
            System.err.println("[MusicBus] play found: " + found[0]);
            System.err.println("[MusicBus] isActive found: " + found[1]);
            System.err.println("[MusicBus] stop found: " + found[2]);
            System.err.println("[MusicBus] updateCategoryVolume found: " + found[3]);
            System.err.println("[MusicBus] pause found: " + found[4]);
            System.err.println("[MusicBus] resume found: " + found[5]);
            System.err.println("[MusicBus] stop(Id, Source) found: " + found[6]);
            System.err.println("[MusicBus] stopAll found: " + found[7]);

            // Do not install a partially functioning interceptor.
            return null;
        }

        System.out.println(
                "[MusicBus] SoundEngine fully transformed."
        );

        return writer.toByteArray();
    }

    /**
     * Prevents AbstractSoundInstance.resolve() from replacing
     * an already locked Sound for an active music session.
     */
    private byte[] transformAbstractSoundInstance(byte[] originalBytes) {

        ClassReader reader = new ClassReader(originalBytes);

        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS
        );

        final boolean[] found = {false};

        ClassVisitor visitor = new ClassVisitor(
                Opcodes.ASM9,
                writer
        ) {

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {

                MethodVisitor mv = super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                );

                if (!McMappings.matchesMethod(
                        McMethod.SOUND_INSTANCE_RESOLVE,
                        name,
                        descriptor
                )) {
                    return mv;
                }

                found[0] = true;

                System.out.println(
                        "[MusicBus] Hooked AbstractSoundInstance."
                                + name + descriptor
                );

                return new MethodVisitor(Opcodes.ASM9, mv) {

                    @Override
                    public void visitInsn(int opcode) {

                        if (opcode == Opcodes.ARETURN) {

                            super.visitVarInsn(
                                    Opcodes.ALOAD,
                                    0
                            );

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "onSoundResolved",
                                    "(Ljava/lang/Object;)V",
                                    false
                            );
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        };

        reader.accept(visitor, 0);

        if (!found[0]) {
            System.err.println(
                    "[MusicBus] AbstractSoundInstance.resolve() not found!"
            );
            return null;
        }

        System.out.println(
                "[MusicBus] AbstractSoundInstance transformed."
        );

        return writer.toByteArray();
    }

    /**
     * Wraps CompletableFuture<AudioStream> returned by
     * SoundBufferLibrary.getStream() when resuming vanilla playback
     * from a non-zero position.
     */
    private byte[] transformSoundBufferLibrary(byte[] originalBytes) {

        ClassReader reader = new ClassReader(originalBytes);

        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS
        );

        final boolean[] found = {false};

        ClassVisitor visitor = new ClassVisitor(
                Opcodes.ASM9,
                writer
        ) {

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {

                MethodVisitor mv = super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                );

                if (!McMappings.matchesMethod(
                        McMethod.SOUND_BUFFER_LIBRARY_GET_STREAM,
                        name,
                        descriptor
                )) {
                    return mv;
                }

                found[0] = true;

                System.out.println(
                        "[MusicBus] Hooked SoundBufferLibrary."
                                + name + descriptor
                );

                return new MethodVisitor(Opcodes.ASM9, mv) {

                    @Override
                    public void visitInsn(int opcode) {

                        if (opcode == Opcodes.ARETURN) {

                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MANAGER,
                                    "wrapAudioStreamFuture",
                                    "(Ljava/util/concurrent/CompletableFuture;)"
                                    + "Ljava/util/concurrent/CompletableFuture;",
                                    false
                            );
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        };

        reader.accept(visitor, 0);

        if (!found[0]) {
            System.err.println(
                    "[MusicBus] SoundBufferLibrary.getStream() not found!"
            );
            return null;
        }

        System.out.println(
                "[MusicBus] SoundBufferLibrary transformed."
        );

        return writer.toByteArray();
    }

    /**
     * Instruments com.mojang.blaze3d.audio.Channel to track exact
     * queued/unqueued OpenAL buffer durations and stop/destroy transitions.
     */
    private byte[] transformChannel(byte[] originalBytes) {

        String[] lambdas = detectChannelLambdas(originalBytes);
        final String queueBuffersLambda = lambdas[0];
        final String attachStaticLambda = lambdas[1];

        ClassReader reader = new ClassReader(originalBytes);

        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS
        );

        final boolean[] found = new boolean[6];

        ClassVisitor visitor = new ClassVisitor(
                Opcodes.ASM9,
                writer
        ) {

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {

                MethodVisitor mv = super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                );

                if (queueBuffersLambda != null
                        && queueBuffersLambda.equals(name)
                        && "(I)V".equals(descriptor)) {

                    found[0] = true;

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ILOAD, 1);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    CHANNEL_TRACKER,
                                    "onBufferQueued",
                                    "(Ljava/lang/Object;I)V",
                                    false
                            );
                        }
                    };
                }

                if (attachStaticLambda != null
                        && attachStaticLambda.equals(name)
                        && "(I)V".equals(descriptor)) {

                    found[1] = true;

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ILOAD, 1);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    CHANNEL_TRACKER,
                                    "onStaticBufferAttached",
                                    "(Ljava/lang/Object;I)V",
                                    false
                            );
                        }
                    };
                }

                if (McMappings.matchesMethod(
                        McMethod.AUDIO_CHANNEL_REMOVE_PROCESSED_BUFFERS,
                        name,
                        descriptor
                )) {

                    found[2] = true;

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                super.visitInsn(Opcodes.DUP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        CHANNEL_TRACKER,
                                        "onBuffersUnqueued",
                                        "(Ljava/lang/Object;I)V",
                                        false
                                );
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }

                if (McMappings.matchesMethod(
                        McMethod.AUDIO_CHANNEL_PLAY,
                        name,
                        descriptor
                )) {

                    found[3] = true;

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    CHANNEL_TRACKER,
                                    "onChannelPlay",
                                    "(Ljava/lang/Object;)V",
                                    false
                            );
                        }
                    };
                }

                if (McMappings.matchesMethod(
                        McMethod.AUDIO_CHANNEL_STOP,
                        name,
                        descriptor
                )) {

                    found[4] = true;

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    CHANNEL_TRACKER,
                                    "onChannelStopping",
                                    "(Ljava/lang/Object;)V",
                                    false
                            );
                        }
                    };
                }

                if (McMappings.matchesMethod(
                        McMethod.AUDIO_CHANNEL_DESTROY,
                        name,
                        descriptor
                )) {

                    found[5] = true;

                    return new MethodVisitor(Opcodes.ASM9, mv) {

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    CHANNEL_TRACKER,
                                    "onChannelDestroying",
                                    "(Ljava/lang/Object;)V",
                                    false
                            );
                        }
                    };
                }

                return mv;
            }
        };

        reader.accept(visitor, 0);

        for (int i = 0; i < found.length; i++) {
            if (!found[i]) {
                System.err.println(
                        "[MusicBus] Channel transformation incomplete! Missing hook index: " + i
                );
                return null;
            }
        }

        System.out.println(
                "[MusicBus] Channel transformed."
        );

        return writer.toByteArray();
    }

    private static String[] detectChannelLambdas(byte[] originalBytes) {
        final String[] result = new String[2];
        ClassReader reader = new ClassReader(originalBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    final String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if ((access & Opcodes.ACC_SYNTHETIC) == 0
                        || (access & Opcodes.ACC_STATIC) != 0
                        || !"(I)V".equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String insnName,
                            String insnDescriptor,
                            boolean isInterface
                    ) {
                        if ("org/lwjgl/openal/AL10".equals(owner)) {
                            if ("alSourceQueueBuffers".equals(insnName)) {
                                result[0] = name;
                            } else if ("alSourcei".equals(insnName)) {
                                result[1] = name;
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }
}