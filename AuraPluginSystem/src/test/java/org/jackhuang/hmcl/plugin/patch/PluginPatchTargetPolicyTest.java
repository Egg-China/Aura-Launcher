/*
 * Copyright 2026 Aura Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jackhuang.hmcl.plugin.patch;

import org.jackhuang.hmcl.patchfixture.PatchTargetFixture;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launcher ownership and exact transformable-method resolution before Patch registration.
@NotNullByDefault
public final class PluginPatchTargetPolicyTest {
    /// Temporary roots used to give generated classes distinct real code sources.
    @TempDir
    private Path temporaryDirectory;

    /// Resolves one exact loaded overload and retains its original launcher bytes.
    @Test
    public void resolveExactLoadedLauncherMethod() throws Exception {
        PluginPatchTargetPolicy policy = policy(List.of(PatchTargetFixture.class));

        PluginPatchTarget target = policy.resolve(method(
                PatchTargetFixture.class.getName(), "join", "java.lang.String", "int"));

        assertEquals("(Ljava/lang/String;I)Ljava/lang/String;", target.method().descriptor());
        assertEquals(PatchTargetFixture.class, target.loadedClass());
        assertTrue(target.classBytes().length > 0);
    }

    /// Resolves a launcher resource without loading the target class as a side effect.
    @Test
    public void resolveUnloadedLauncherMethodFromOwnedResource() throws Exception {
        PluginPatchTargetPolicy policy = policy(List.of());

        PluginPatchTarget target = policy.resolve(method(
                PatchTargetFixture.class.getName(), "arrayLength", "java.lang.String[][]"));

        assertEquals("([[Ljava/lang/String;)I", target.method().descriptor());
        assertNull(target.loadedClass());
    }

    /// Rejects the entire protected Plugin System namespace before bytecode lookup.
    @Test
    public void rejectProtectedPluginNamespace() {
        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () -> policy(List.of()).resolve(method(
                "org.jackhuang.hmcl.plugin.PluginManager", "enablePlugin", "java.lang.String")));

        assertEquals(PluginPatchFailure.Category.DENIED_TARGET, failure.category());
    }

    /// Rejects JDK targets even when their class and method exist.
    @Test
    public void rejectBootstrapNamespace() {
        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () -> policy(List.of(String.class)).resolve(
                method("java.lang.String", "length")));

        assertEquals(PluginPatchFailure.Category.DENIED_TARGET, failure.category());
    }

    /// Rejects generated launcher class names that are outside the stable Patch surface.
    @Test
    public void rejectGeneratedClassName() {
        Class<?> generated = PatchTargetFixture.Generated$$Target.class;
        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () -> policy(List.of(generated)).resolve(
                method(generated.getName(), "value")));

        assertEquals(PluginPatchFailure.Category.DENIED_TARGET, failure.category());
    }

    /// Rejects a matching binary name defined by a foreign class loader.
    @Test
    public void rejectForeignClassLoader() throws Exception {
        String binaryName = "org.jackhuang.hmcl.foreign.LoaderTarget";
        byte[] bytes = concreteClass(binaryName, "value", "()Ljava/lang/String;");
        Class<?> foreign = new ByteArrayClassLoader().define(binaryName, bytes);
        PluginPatchTargetPolicy policy = policy(List.of(foreign));

        PluginPatchFailure failure = assertThrows(
                PluginPatchFailure.class, () -> policy.resolve(method(binaryName, "value")));

        assertEquals(PluginPatchFailure.Category.DENIED_TARGET, failure.category());
    }

    /// Rejects a target from another code source even when one loader defines both classes.
    @Test
    public void rejectForeignCodeSource() throws Exception {
        String anchorName = "org.jackhuang.hmcl.generated.PolicyAnchor";
        String targetName = "org.jackhuang.hmcl.generated.ForeignSourceTarget";
        Path anchorRoot = temporaryDirectory.resolve("anchor");
        Path targetRoot = temporaryDirectory.resolve("target");
        writeClass(anchorRoot, anchorName, concreteClass(anchorName, "anchor", "()V"));
        writeClass(targetRoot, targetName, concreteClass(targetName, "value", "()Ljava/lang/String;"));

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{anchorRoot.toUri().toURL(), targetRoot.toUri().toURL()}, null)) {
            Class<?> anchor = loader.loadClass(anchorName);
            Class<?> target = loader.loadClass(targetName);
            PluginPatchTargetPolicy policy = new PluginPatchTargetPolicy(anchor, () -> List.of(target));

            PluginPatchFailure failure = assertThrows(
                    PluginPatchFailure.class, () -> policy.resolve(method(targetName, "value")));
            assertEquals(PluginPatchFailure.Category.DENIED_TARGET, failure.category());
        }
    }

    /// Rejects abstract and native methods because neither has transformable bytecode.
    @Test
    public void rejectMethodsWithoutBodies() {
        PluginPatchTargetPolicy policy = policy(List.of(
                PatchTargetFixture.class, PatchTargetFixture.AbstractTarget.class));

        PluginPatchFailure abstractFailure = assertThrows(PluginPatchFailure.class, () -> policy.resolve(method(
                PatchTargetFixture.AbstractTarget.class.getName(), "abstractValue")));
        PluginPatchFailure nativeFailure = assertThrows(PluginPatchFailure.class, () -> policy.resolve(method(
                PatchTargetFixture.class.getName(), "nativeValue")));

        assertEquals(PluginPatchFailure.Category.UNSUPPORTED_METHOD, abstractFailure.category());
        assertEquals(PluginPatchFailure.Category.UNSUPPORTED_METHOD, nativeFailure.category());
    }

    /// Reports a missing overload instead of selecting another method with the same name.
    @Test
    public void rejectMissingOverload() {
        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () -> policy(
                List.of(PatchTargetFixture.class)).resolve(method(
                PatchTargetFixture.class.getName(), "join", "long", "java.lang.String")));

        assertEquals(PluginPatchFailure.Category.MISSING_METHOD, failure.category());
    }

    /// Keeps a uniquely parameterized compiler bridge addressable by its legal Java method name.
    @Test
    public void resolveUniqueBridgeMethod() throws Exception {
        PluginPatchTarget target = policy(List.of(PatchTargetFixture.class)).resolve(method(
                PatchTargetFixture.class.getName(), "accept", "java.lang.Object"));

        assertEquals("(Ljava/lang/Object;)V", target.method().descriptor());
        assertTrue((target.access() & Opcodes.ACC_BRIDGE) != 0);
        assertFalse((target.access() & Opcodes.ACC_ABSTRACT) != 0);
    }

    /// Creates a policy whose launcher identity is the test fixture's real loader and code source.
    ///
    /// @param loadedClasses classes visible as already loaded
    /// @return target policy
    private static PluginPatchTargetPolicy policy(List<Class<?>> loadedClasses) {
        return new PluginPatchTargetPolicy(PatchTargetFixture.class, () -> List.copyOf(loadedClasses));
    }

    /// Creates one unresolved method identity for a `before` declaration.
    ///
    /// @param target binary target class name
    /// @param name method name
    /// @param parameters ordered Java parameter names
    /// @return unresolved method identity
    private static PluginPatchMethod method(String target, String name, String... parameters) {
        return PluginPatchMethod.from(new PluginPatchDeclaration(
                target, name, PluginPatchDeclaration.PatchType.BEFORE, List.of(parameters)));
    }

    /// Writes one generated class under a URLClassLoader root.
    ///
    /// @param root class-path root
    /// @param binaryName generated binary name
    /// @param bytes class bytes
    /// @throws IOException if the class cannot be written
    private static void writeClass(Path root, String binaryName, byte[] bytes) throws IOException {
        Path classFile = root.resolve(binaryName.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
    }

    /// Generates one concrete public class with a single public method.
    ///
    /// @param binaryName generated binary name
    /// @param methodName generated method name
    /// @param descriptor generated method descriptor
    /// @return valid class bytes
    private static byte[] concreteClass(String binaryName, String methodName, String descriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                binaryName.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        method.visitCode();
        org.objectweb.asm.Type returnType = org.objectweb.asm.Type.getReturnType(descriptor);
        switch (returnType.getSort()) {
            case org.objectweb.asm.Type.VOID -> method.visitInsn(Opcodes.RETURN);
            case org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY -> {
                method.visitInsn(Opcodes.ACONST_NULL);
                method.visitInsn(Opcodes.ARETURN);
            }
            default -> {
                method.visitInsn(Opcodes.ICONST_0);
                method.visitInsn(Opcodes.IRETURN);
            }
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /// Defines arbitrary bytes without delegating application classes to the launcher loader.
    @NotNullByDefault
    private static final class ByteArrayClassLoader extends ClassLoader {
        /// Creates a loader whose only parent visibility is the bootstrap loader.
        private ByteArrayClassLoader() {
            super(null);
        }

        /// Defines one class from exact test bytes.
        ///
        /// @param binaryName class binary name
        /// @param bytes class bytes
        /// @return defined class
        private Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
