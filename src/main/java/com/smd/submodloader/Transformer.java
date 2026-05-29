package com.smd.submodloader;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * ASM 字节码注入，只拦截 2 个类：
 * <ol>
 *   <li>ActualClassLoader.&lt;init&gt; — 构造函数中 super() 后注入 URL</li>
 *   <li>CoreModManager.discoverCoreMods — getCandidates() 返回后追加候选 jar</li>
 * </ol>
 */
public class Transformer implements ClassFileTransformer {

    private static final String HELPER = "com/smd/submodloader/Helper";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null) return null;

        return switch (className) {
            case "top.outlands.foundation.boot.ActualClassLoader" -> {
                System.out.println("[SubModLoader] >> ActualClassLoader");
                yield patch(classfileBuffer, ActualClassLoaderPatcher::new);
            }
            case "net.minecraftforge.fml.relauncher.CoreModManager" -> {
                System.out.println("[SubModLoader] >> CoreModManager");
                yield patch(classfileBuffer, CoreModManagerPatcher::new);
            }
            default -> null;
        };
    }

    private static byte[] patch(byte[] bytes, java.util.function.Function<ClassVisitor, ClassVisitor> factory) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cr.accept(factory.apply(cw), 0);
        return cw.toByteArray();
    }

    // ─────────────────────────────────────────────
    //  注入点 1：ActualClassLoader.<init>
    //  在 super(sources, loader) 返回后立刻注入 Helper.injectURLs(this)
    //  使用 invokespecial 调用 URLClassLoader.addURL()，绕过 override
    //  避免访问尚未初始化的 this.sources 字段
    // ─────────────────────────────────────────────

    private static class ActualClassLoaderPatcher extends ClassVisitor {
        ActualClassLoaderPatcher(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if ("<init>".equals(name) && "([Ljava/net/URL;Ljava/lang/ClassLoader;)V".equals(desc)) {
                return new ConstructorInjector(mv, access, name, desc);
            }
            return mv;
        }
    }

    private static class ConstructorInjector extends AdviceAdapter {
        ConstructorInjector(MethodVisitor mv, int access, String name, String desc) {
            super(Opcodes.ASM9, mv, access, name, desc);
        }

        @Override
        protected void onMethodExit(int opcode) {
            // 注入：Helper.injectURLs(this)
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "injectURLs",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    // ─────────────────────────────────────────────
    //  注入点 2：CoreModManager.discoverCoreMods
    //  在 LibraryManager.getCandidates() 返回后追加额外 jar 到候选列表
    //
    //  原始字节码（大致）：
    //    INVOKESTATIC LibraryManager.getCandidates -> List<File>
    //    ASTORE n
    //  注入后：
    //    INVOKESTATIC LibraryManager.getCandidates -> List<File>
    //    DUP                                     ← 复制返回的 List
    //    GETSTATIC CoreModManager.mcDir : File   ← 加载游戏目录
    //    INVOKESTATIC Helper.addCandidates(List, File)
    //    ASTORE n                                ← 原始逻辑继续
    // ─────────────────────────────────────────────

    private static class CoreModManagerPatcher extends ClassVisitor {
        CoreModManagerPatcher(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            // 目标：discoverCoreMods(File, LaunchClassLoader)
            if ("discoverCoreMods".equals(name)
                    && "(Ljava/io/File;Lnet/minecraft/launchwrapper/LaunchClassLoader;)V".equals(desc)) {
                return new DiscoverCoreModsInjector(mv);
            }
            return mv;
        }
    }

    private static class DiscoverCoreModsInjector extends MethodVisitor {
        DiscoverCoreModsInjector(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            // 先写入原始指令
            super.visitMethodInsn(opcode, owner, name, desc, itf);

            // 拦截 LibraryManager.getCandidates() 的返回值
            if (opcode == Opcodes.INVOKESTATIC
                    && "net/minecraftforge/fml/relauncher/libraries/LibraryManager".equals(owner)
                    && "getCandidates".equals(name)
                    && "(Ljava/io/File;)Ljava/util/List;".equals(desc)) {

                // 栈顶：List<File>（getCandidates 的返回值）
                mv.visitInsn(Opcodes.DUP);
                // 栈顶：List, List

                mv.visitFieldInsn(Opcodes.GETSTATIC,
                        "net/minecraftforge/fml/relauncher/CoreModManager",
                        "mcDir",
                        "Ljava/io/File;");
                // 栈顶：List, List, File

                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "addCandidates",
                        "(Ljava/util/List;Ljava/io/File;)V", false);
                // 栈顶：List（原始返回值，留给后续 ASTORE）
            }
        }
    }
}
