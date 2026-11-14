package com.musicbus.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class MusicBusTransformer
        implements ClassFileTransformer {

    private static final String SOUND_OPTIONS_SCREEN =
            "net/minecraft/client/gui/screens/options/SoundOptionsScreen";

    private static final String SOUND_ENGINE =
            "net/minecraft/client/sounds/SoundEngine";

    private static final String SOUND_INSTANCE =
            "net/minecraft/client/resources/sounds/SoundInstance";

    private static final String ABSTRACT_SOUND_INSTANCE =
            "net/minecraft/client/resources/sounds/AbstractSoundInstance";

    private static final String SOUND_BUFFER_LIBRARY =
            "net/minecraft/client/sounds/SoundBufferLibrary";

    private static final String CHANNEL =
            "com/mojang/blaze3d/audio/Channel";

    private static final String SOUND_SOURCE =
            "net/minecraft/sounds/SoundSource";

    private static final String PLAY_RESULT =
            "net/minecraft/client/sounds/SoundEngine$PlayResult";

    private static final String MANAGER =
            "com/musicbus/core/MusicBusManager";

    private static final String CHANNEL_TRACKER =
            "com/musicbus/audio/VanillaChannelTracker";

    private static final String PLAY_DESCRIPTOR =
            "(Lnet/minecraft/client/resources/sounds/SoundInstance;)"
            + "Lnet/minecraft/client/sounds/SoundEngine$PlayResult;";

    private static final String IS_ACTIVE_DESCRIPTOR =
            "(Lnet/minecraft/client/resources/sounds/SoundInstance;)Z";

    private static final String STOP_DESCRIPTOR =
            "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V";

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {

        if (className == null) {
            return null;
        }

        if (SOUND_OPTIONS_SCREEN.equals(className)) {
            return transformSoundOptions(classfileBuffer);
        }

        if (SOUND_ENGINE.equals(className)) {
            return transformSoundEngine(classfileBuffer);
        }

        if (ABSTRACT_SOUND_INSTANCE.equals(className)) {
            return transformAbstractSoundInstance(classfileBuffer);
        }

        if (SOUND_BUFFER_LIBRARY.equals(className)) {
            return transformSoundBufferLibrary(classfileBuffer);
        }

        if (CHANNEL.equals(className)) {
            return transformChannel(classfileBuffer);
        }

        return null;
    }

    /**
     * Injects the MusicBus toggle into SoundOptionsScreen.
     */
    private byte[] transformSoundOptions(byte[] originalBytes) {

        ClassReader reader = new ClassReader(originalBytes);

        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS
        );

        boolean[] found = {false};

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

                if (!name.equals("addOptions")
                        || !descriptor.equals("()V")) {
                    return mv;
                }

                found[0] = true;

                System.out.println(
                        "[MusicBus] Found SoundOptionsScreen.addOptions()"
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
                                    "com/musicbus/core/MusicBus",
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
                    "[MusicBus] addOptions() not found!"
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

        ClassReader reader = new ClassReader(originalBytes);

        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS
        );

        boolean[] found = new boolean[3];

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

                if (name.equals("play")
                        && descriptor.equals(PLAY_DESCRIPTOR)) {

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
                                    SOUND_INSTANCE,
                                    "getSource",
                                    "()Lnet/minecraft/sounds/SoundSource;",
                                    true
                            );

                            // Compare with SoundSource.MUSIC.
                            super.visitFieldInsn(
                                    Opcodes.GETSTATIC,
                                    SOUND_SOURCE,
                                    "MUSIC",
                                    "Lnet/minecraft/sounds/SoundSource;"
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

                            // Return STARTED instead of NOT_STARTED.
                            super.visitFieldInsn(
                                    Opcodes.GETSTATIC,
                                    PLAY_RESULT,
                                    "STARTED",
                                    "Lnet/minecraft/client/sounds/SoundEngine$PlayResult;"
                            );

                            super.visitInsn(Opcodes.ARETURN);

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

                if (name.equals("isActive")
                        && descriptor.equals(IS_ACTIVE_DESCRIPTOR)) {

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
                                    "isMockActive",
                                    "(Ljava/lang/Object;)Z",
                                    false
                            );

                            super.visitJumpInsn(
                                    Opcodes.IFEQ,
                                    vanilla
                            );

                            // Mock audio player reports active playback.
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

                if (name.equals("stop")
                        && descriptor.equals(STOP_DESCRIPTOR)) {

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
                                    SOUND_INSTANCE,
                                    "getSource",
                                    "()Lnet/minecraft/sounds/SoundSource;",
                                    true
                            );

                            super.visitFieldInsn(
                                    Opcodes.GETSTATIC,
                                    SOUND_SOURCE,
                                    "MUSIC",
                                    "Lnet/minecraft/sounds/SoundSource;"
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

                return mv;
            }
        };

        reader.accept(visitor, 0);

        if (!found[0] || !found[1] || !found[2]) {

            System.err.println(
                    "[MusicBus] SoundEngine transformation incomplete!"
            );

            System.err.println(
                    "[MusicBus] play found: " + found[0]
            );

            System.err.println(
                    "[MusicBus] isActive found: " + found[1]
            );

            System.err.println(
                    "[MusicBus] stop found: " + found[2]
            );

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

        boolean[] found = {false};

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

                if (!name.equals("resolve")
                        || !descriptor.equals(
                                "(Lnet/minecraft/client/sounds/SoundManager;)"
                                + "Lnet/minecraft/client/sounds/WeighedSoundEvents;"
                        )) {
                    return mv;
                }

                found[0] = true;

                System.out.println(
                        "[MusicBus] Hooked AbstractSoundInstance.resolve()."
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

        boolean[] found = {false};

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

                if (!name.equals("getStream")
                        || !descriptor.equals(
                                "(Lnet/minecraft/resources/Identifier;Z)"
                                + "Ljava/util/concurrent/CompletableFuture;"
                        )) {
                    return mv;
                }

                found[0] = true;

                System.out.println(
                        "[MusicBus] Hooked SoundBufferLibrary.getStream()."
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

        ClassReader reader = new ClassReader(originalBytes);

        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS
        );

        boolean[] found = new boolean[6];

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

                if (name.equals("lambda$pumpBuffers$0")
                        && descriptor.equals("(I)V")) {

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

                if (name.equals("lambda$attachStaticBuffer$0")
                        && descriptor.equals("(I)V")) {

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

                if (name.equals("removeProcessedBuffers")
                        && descriptor.equals("()I")) {

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

                if (name.equals("play")
                        && descriptor.equals("()V")) {

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

                if (name.equals("stop")
                        && descriptor.equals("()V")) {

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

                if (name.equals("destroy")
                        && descriptor.equals("()V")) {

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

        for (boolean ok : found) {
            if (!ok) {
                System.err.println(
                        "[MusicBus] Channel transformation incomplete!"
                );
                return null;
            }
        }

        System.out.println(
                "[MusicBus] Channel transformed."
        );

        return writer.toByteArray();
    }
}
