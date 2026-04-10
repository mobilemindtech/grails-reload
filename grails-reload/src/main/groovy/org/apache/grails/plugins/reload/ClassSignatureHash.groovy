package org.apache.grails.plugins.reload

import org.objectweb.asm.*

class ClassSignatureHash {

    static String getFingerprint(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes)
        StringBuilder sb = new StringBuilder()

        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                // Foca na hierarquia e nome
                sb.append(name).append(superName).append(access)
            }

            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                // Foca na assinatura dos métodos, ignorando o corpo (code)
                // Se o corpo mudar, o HotswapAgent já detecta.
                // Para evitar falsos positivos de "redeploy", olhamos a estrutura.
                sb.append(name).append(descriptor).append(access)
                return null
            }

            @Override
            FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                sb.append(name).append(descriptor).append(access)
                return null
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)

        sb.toString()
    }
}