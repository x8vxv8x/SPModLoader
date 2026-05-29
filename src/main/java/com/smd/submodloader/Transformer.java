package com.smd.submodloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class Transformer implements ClassFileTransformer {

    private static final String HELPER = "com/smd/submodloader/Helper";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null
                || !"net.minecraftforge.fml.relauncher.libraries.LibraryManager"
                .equals(className.replace('/', '.'))) {
            return null;
        }

        System.out.println("[SubModLoader] Patching LibraryManager");
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new LibraryManagerPatcher(cw), 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[SubModLoader] LibraryManager transform failed:");
            t.printStackTrace(System.err);
            return null;
        }
    }

    private static class LibraryManagerPatcher extends ClassVisitor {
        LibraryManagerPatcher(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (isLegacyCandidateGatherer(name, desc)) {
                return new LegacyModDirInjector(mv, access, name, desc);
            }
            return mv;
        }

        private static boolean isLegacyCandidateGatherer(String name, String desc) {
            return ("gatherLegacyCanidates".equals(name) || "gatherLegacyCandidates".equals(name))
                    && "(Ljava/io/File;)Ljava/util/List;".equals(desc);
        }
    }

    private static class LegacyModDirInjector extends MethodVisitor {
        private boolean buildingStringArray;
        private int stringArrayStores;
        private boolean expandNextStringArrayStore;

        LegacyModDirInjector(MethodVisitor mv, int access, String name, String desc) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            super.visitTypeInsn(opcode, type);
            if (opcode == Opcodes.ANEWARRAY && "java/lang/String".equals(type)) {
                buildingStringArray = true;
                stringArrayStores = 0;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            super.visitInsn(opcode);
            if (buildingStringArray && opcode == Opcodes.AASTORE) {
                stringArrayStores++;
            }
            if (buildingStringArray && stringArrayStores == 2) {
                expandNextStringArrayStore = true;
                buildingStringArray = false;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            super.visitVarInsn(opcode, varIndex);
            if (opcode == Opcodes.ASTORE && expandNextStringArrayStore) {
                System.out.println("[SubModLoader] Injected legacy mod directory expansion.");
                mv.visitVarInsn(Opcodes.ALOAD, varIndex);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "expandLegacyModDirs",
                        "([Ljava/lang/String;)[Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                expandNextStringArrayStore = false;
            }
        }
    }
}
