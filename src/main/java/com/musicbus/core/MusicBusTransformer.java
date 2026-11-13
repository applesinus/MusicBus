package com.musicbus.core;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class MusicBusTransformer
        implements ClassFileTransformer {

    private static final String TARGET =
            "net/minecraft/client/gui/screens/options/SoundOptionsScreen";

    private static final String HELPER =
            "com.musicbus.core.MusicBus";

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {
        if (!TARGET.equals(className)) {
            return null;
        }

        MusicBusLog.log("Target class found.");
        MusicBusLog.log("Target ClassLoader: " + loader);

        try {
            // Проверяем, видит ли загрузчик Minecraft наш агент.
            Class.forName(HELPER, false, loader);

            MusicBusLog.log("MusicBus helper is visible.");

            ClassReader reader = new ClassReader(classfileBuffer);

            ClassWriter writer = new ClassWriter(
                    reader,
                    ClassWriter.COMPUTE_MAXS
            );

            boolean[] methodFound = {false};
            int[] injections = {0};

            ClassVisitor visitor = new ClassVisitor(
                    Opcodes.ASM9, writer
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
                            access, name, descriptor,
                            signature, exceptions
                    );

                    if (!name.equals("addOptions")
                            || !descriptor.equals("()V")) {
                        return mv;
                    }

                    methodFound[0] = true;
                    MusicBusLog.log("Found addOptions()V.");

                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(
                                        Opcodes.ALOAD, 0
                                );

                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        "com/musicbus/core/MusicBus",
                                        "onSoundOptionsScreen",
                                        "(Ljava/lang/Object;)V",
                                        false
                                );

                                injections[0]++;
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }
            };

            reader.accept(visitor, 0);

            if (!methodFound[0]) {
                MusicBusLog.log("ERROR: addOptions()V not found.");
                return null;
            }

            if (injections[0] == 0) {
                MusicBusLog.log("ERROR: No injection points found.");
                return null;
            }

            byte[] result = writer.toByteArray();

            MusicBusLog.log(
                    "Transformation successful. Injection points: "
                            + injections[0]
            );

            return result;

        } catch (Throwable error) {
            MusicBusLog.error("Transformation failed.", error);
            return null;
        }
    }
}