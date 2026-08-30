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

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginPatchResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ASM advice shape, verifier correctness, and dispatcher-only callback references.
@NotNullByDefault
public final class PluginPatchTransformerTest {
    /// Isolated-loader anchor binary name.
    private static final String ANCHOR_NAME = "org.jackhuang.hmcl.patchfixture.PatchTransformerAnchor";

    /// Isolated-loader target binary name.
    private static final String TARGET_NAME = "org.jackhuang.hmcl.patchfixture.PatchTransformerFixture";

    /// Complete exact-artifact digest used by test registrations.
    private static final String ARTIFACT_SHA256 = "1".repeat(64);

    /// Callback workers retained for cleanup after every test.
    private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(8);

    /// Registrations retained for idempotent dispatcher cleanup.
    private final List<PluginPatchRegistration> registrations = new ArrayList<>();

    /// Closes dispatcher registrations and terminates callback workers.
    @AfterEach
    public void tearDown() throws InterruptedException {
        registrations.forEach(PluginPatchRegistration::close);
        callbackExecutor.shutdownNow();
        assertTrue(callbackExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /// Executes transformed static, instance, primitive, reference, array, void, replacement, and throw paths.
    ///
    /// @param temporaryDirectory isolated class-path root
    /// @throws Exception if fixture generation, transformation, or invocation fails
    @Test
    public void transformEveryJvmValueCategory(@TempDir Path temporaryDirectory) throws Exception {
        FixtureEnvironment environment = environment(temporaryDirectory);
        PluginPatchEngine engine = environment.engine();
        AtomicInteger voidAfterCalls = new AtomicInteger();
        AtomicInteger exceptionAfterCalls = new AtomicInteger();

        register(engine, "wide", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("long", "double", "int"), "dev.example.wide",
                invocation -> PluginPatchResult.unchanged());
        register(engine, "allArguments", PluginPatchDeclaration.PatchType.BEFORE,
                List.of(
                        "boolean", "byte", "char", "short", "int", "long", "float", "double",
                        "java.lang.String", "java.lang.String[]"
                ), "dev.example.arguments", invocation -> PluginPatchResult.arguments(List.of(
                        true, (byte) 2, 'z', (short) 3, 4, 5L, 6.0f, 7.0d,
                        "changed", new String[]{"x", "y"}
                )));
        registerAfterReturn(engine, "booleanValue", "dev.example.boolean", true);
        registerAfterReturn(engine, "byteValue", "dev.example.byte", (byte) 8);
        registerAfterReturn(engine, "charValue", "dev.example.char", 'z');
        registerAfterReturn(engine, "shortValue", "dev.example.short", (short) 9);
        registerAfterReturn(engine, "intValue", "dev.example.int", 10);
        registerAfterReturn(engine, "longValue", "dev.example.long", 11L);
        registerAfterReturn(engine, "floatValue", "dev.example.float", 12.5f);
        registerAfterReturn(engine, "doubleValue", "dev.example.double", 13.5d);
        register(engine, "arrayLength", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String[][]"), "dev.example.array",
                invocation -> PluginPatchResult.arguments(List.of((Object) new String[][]{
                        {"one"}, {"two"}, {"three"}
                })));
        register(engine, "appendOriginal", PluginPatchDeclaration.PatchType.AFTER,
                List.of("java.lang.StringBuilder"), "dev.example.void", invocation -> {
                    voidAfterCalls.incrementAndGet();
                    return PluginPatchResult.unchanged();
                });
        register(engine, "replaceMe", PluginPatchDeclaration.PatchType.REPLACE,
                List.of(), "dev.example.replace",
                invocation -> PluginPatchResult.returnValue("replaced"));
        register(engine, "replaceMe", PluginPatchDeclaration.PatchType.AFTER,
                List.of(), "dev.example.replaceafter",
                invocation -> PluginPatchResult.returnValue(invocation.result() + "-after"));
        register(engine, "throwsOriginal", PluginPatchDeclaration.PatchType.AFTER,
                List.of(), "dev.example.exception", invocation -> {
                    exceptionAfterCalls.incrementAndGet();
                    return PluginPatchResult.returnValue("wrong");
                });

        byte @Unmodifiable [] transformed = environment.transformer().transform(
                environment.anchor().getModule(),
                environment.loader(),
                TARGET_NAME.replace('.', '/'),
                null,
                environment.anchor().getProtectionDomain(),
                environment.originalTargetBytes()
        );

        assertNotNull(transformed);
        verifyClass(transformed);
        assertNoCallbackImplementationReferences(transformed);
        Class<?> target = environment.loader().defineTransformed(TARGET_NAME, transformed);
        Object fixture = target.getConstructor().newInstance();

        assertEquals(17L, invoke(target, null, "wide", new Class<?>[]{long.class, double.class, int.class},
                5L, 3.0d, 9));
        assertEquals("changed", invoke(target, fixture, "allArguments", new Class<?>[]{
                boolean.class, byte.class, char.class, short.class, int.class,
                long.class, float.class, double.class, String.class, String[].class
        }, false, (byte) 0, 'a', (short) 0, 0, 0L, 0.0f, 0.0d, "original", new String[0]));
        assertEquals(true, invokeNoArguments(target, fixture, "booleanValue"));
        assertEquals((byte) 8, invokeNoArguments(target, fixture, "byteValue"));
        assertEquals('z', invokeNoArguments(target, fixture, "charValue"));
        assertEquals((short) 9, invokeNoArguments(target, fixture, "shortValue"));
        assertEquals(10, invokeNoArguments(target, fixture, "intValue"));
        assertEquals(11L, invokeNoArguments(target, fixture, "longValue"));
        assertEquals(12.5f, invokeNoArguments(target, fixture, "floatValue"));
        assertEquals(13.5d, invokeNoArguments(target, fixture, "doubleValue"));
        assertEquals(3, invoke(target, fixture, "arrayLength", new Class<?>[]{String[][].class},
                (Object) new String[][]{{"original"}}));
        StringBuilder sink = new StringBuilder();
        assertNull(invoke(target, fixture, "appendOriginal", new Class<?>[]{StringBuilder.class}, sink));
        assertEquals("original", sink.toString());
        assertEquals(1, voidAfterCalls.get());
        assertEquals("replaced-after", invokeNoArguments(target, fixture, "replaceMe"));

        IllegalStateException failure = (IllegalStateException) target.getField("FAILURE").get(null);
        InvocationTargetException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                InvocationTargetException.class,
                () -> invokeNoArguments(target, fixture, "throwsOriginal")
        );
        assertSame(failure, thrown.getCause());
        assertEquals(0, exceptionAfterCalls.get());
    }

    /// Leaves unrelated classes unchanged even when another target has active plans.
    ///
    /// @param temporaryDirectory isolated class-path root
    /// @throws Exception if fixture preparation or registration fails
    @Test
    public void ignoreClassWithoutActiveMethodPlan(@TempDir Path temporaryDirectory) throws Exception {
        FixtureEnvironment environment = environment(temporaryDirectory);
        register(environment.engine(), "wide", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("long", "double", "int"), "dev.example.wide",
                invocation -> PluginPatchResult.unchanged());

        byte @Nullable [] result = environment.transformer().transform(
                environment.anchor().getModule(),
                environment.loader(),
                ANCHOR_NAME.replace('.', '/'),
                null,
                environment.anchor().getProtectionDomain(),
                classBytes(ANCHOR_NAME)
        );

        assertNull(result);
    }

    /// Refuses an active target presented by any class loader other than the exact launcher loader.
    ///
    /// @param temporaryDirectory isolated class-path root
    /// @throws Exception if fixture preparation or registration fails
    @Test
    public void ignoreActiveTargetFromForeignClassLoader(@TempDir Path temporaryDirectory) throws Exception {
        FixtureEnvironment environment = environment(temporaryDirectory);
        register(environment.engine(), "wide", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("long", "double", "int"), "dev.example.wide",
                invocation -> PluginPatchResult.unchanged());

        byte @Nullable [] result = environment.transformer().transform(
                environment.anchor().getModule(),
                getClass().getClassLoader(),
                TARGET_NAME.replace('.', '/'),
                null,
                environment.anchor().getProtectionDomain(),
                environment.originalTargetBytes()
        );

        assertNull(result);
    }

    /// Refuses an active target whose definition does not carry the launcher's exact code source.
    ///
    /// @param temporaryDirectory isolated class-path root
    /// @throws Exception if fixture preparation or registration fails
    @Test
    public void ignoreActiveTargetFromForeignCodeSource(@TempDir Path temporaryDirectory) throws Exception {
        FixtureEnvironment environment = environment(temporaryDirectory);
        register(environment.engine(), "wide", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("long", "double", "int"), "dev.example.wide",
                invocation -> PluginPatchResult.unchanged());
        ProtectionDomain foreignDomain = new ProtectionDomain(null, null);

        byte @Nullable [] result = environment.transformer().transform(
                environment.anchor().getModule(),
                environment.loader(),
                TARGET_NAME.replace('.', '/'),
                null,
                foreignDomain,
                environment.originalTargetBytes()
        );

        assertNull(result);
    }

    /// Creates an isolated launcher loader, target policy, engine, and absent transformer under test.
    ///
    /// @param temporaryDirectory isolated class-path root
    /// @return complete fixture environment
    /// @throws Exception if class resources cannot be prepared
    private FixtureEnvironment environment(Path temporaryDirectory) throws Exception {
        writeClass(temporaryDirectory, ANCHOR_NAME, classBytes(ANCHOR_NAME));
        byte @Unmodifiable [] targetBytes = classBytes(TARGET_NAME);
        writeClass(temporaryDirectory, TARGET_NAME, targetBytes);
        FixtureClassLoader loader = new FixtureClassLoader(
                new URL[]{temporaryDirectory.toUri().toURL()},
                getClass().getClassLoader()
        );
        Class<?> anchor = loader.loadClass(ANCHOR_NAME);
        PluginPatchTargetPolicy policy = new PluginPatchTargetPolicy(anchor, () -> List.of(anchor));
        PluginPatchEngine engine = new PluginPatchEngine(
                policy,
                callbackExecutor,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5)
        );
        return new FixtureEnvironment(
                loader,
                anchor,
                engine,
                new PluginPatchTransformer(engine, loader),
                targetBytes
        );
    }

    /// Registers one callback against the isolated target class.
    ///
    /// @param engine engine under test
    /// @param method method name
    /// @param type callback position
    /// @param parameters ordered Java parameter names
    /// @param pluginId exact artifact plugin ID
    /// @param callback callback behavior
    /// @throws PluginPatchFailure if registration fails
    private void register(
            PluginPatchEngine engine,
            String method,
            PluginPatchDeclaration.PatchType type,
            List<String> parameters,
            String pluginId,
            PluginPatchCallback callback
    ) throws PluginPatchFailure {
        PluginPatchRegistration registration = engine.register(
                new PluginArtifactIdentity(pluginId, "1.0.0", ARTIFACT_SHA256),
                Set.of(),
                new PluginPatchDeclaration(TARGET_NAME, method, type, parameters),
                callback
        );
        registrations.add(registration);
    }

    /// Registers one no-argument `after` callback that replaces a primitive result.
    ///
    /// @param engine engine under test
    /// @param method method name
    /// @param pluginId exact artifact plugin ID
    /// @param value replacement boxed primitive
    /// @throws PluginPatchFailure if registration fails
    private void registerAfterReturn(
            PluginPatchEngine engine,
            String method,
            String pluginId,
            Object value
    ) throws PluginPatchFailure {
        register(
                engine,
                method,
                PluginPatchDeclaration.PatchType.AFTER,
                List.of(),
                pluginId,
                invocation -> PluginPatchResult.returnValue(value)
        );
    }

    /// Invokes one public fixture method and preserves reflective target failures for assertions.
    ///
    /// @param target target class
    /// @param receiver instance receiver, or `null` for a static method
    /// @param name method name
    /// @param parameterTypes exact parameter classes
    /// @param arguments invocation arguments
    /// @return nullable reflective result
    /// @throws ReflectiveOperationException if lookup or invocation fails
    private static @Nullable Object invoke(
            Class<?> target,
            @Nullable Object receiver,
            String name,
            Class<?>[] parameterTypes,
            @Nullable Object... arguments
    ) throws ReflectiveOperationException {
        Method method = target.getMethod(name, parameterTypes);
        return method.invoke(receiver, arguments);
    }

    /// Invokes one no-argument public fixture method.
    ///
    /// @param target target class
    /// @param receiver instance receiver
    /// @param name method name
    /// @return nullable reflective result
    /// @throws ReflectiveOperationException if lookup or invocation fails
    private static @Nullable Object invokeNoArguments(
            Class<?> target,
            Object receiver,
            String name
    ) throws ReflectiveOperationException {
        return invoke(target, receiver, name, new Class<?>[0]);
    }

    /// Runs ASM's structural and data-flow verifier against transformed bytes.
    ///
    /// @param bytes transformed class bytes
    private static void verifyClass(byte[] bytes) {
        StringWriter diagnostics = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(diagnostics));
        assertEquals("", diagnostics.toString());
    }

    /// Confirms transformed bytecode references only launcher dispatch types, never callback implementations.
    ///
    /// @param bytes transformed class bytes
    private static void assertNoCallbackImplementationReferences(byte[] bytes) {
        Set<String> owners = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            /// Collects type references from one method body.
            ///
            /// @param access method access
            /// @param name method name
            /// @param descriptor method descriptor
            /// @param signature generic signature, or `null`
            /// @param exceptions internal exception names, or `null`
            /// @return collecting visitor
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    @Nullable String signature,
                    String @Nullable [] exceptions
            ) {
                return new MethodVisitor(Opcodes.ASM9) {
                    /// Collects one type instruction owner.
                    ///
                    /// @param opcode instruction opcode
                    /// @param type internal type name
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        owners.add(type);
                    }

                    /// Collects one field owner.
                    ///
                    /// @param opcode instruction opcode
                    /// @param owner field owner
                    /// @param fieldName field name
                    /// @param fieldDescriptor field descriptor
                    @Override
                    public void visitFieldInsn(
                            int opcode,
                            String owner,
                            String fieldName,
                            String fieldDescriptor
                    ) {
                        owners.add(owner);
                    }

                    /// Collects one method owner.
                    ///
                    /// @param opcode instruction opcode
                    /// @param owner method owner
                    /// @param methodName method name
                    /// @param methodDescriptor method descriptor
                    /// @param isInterface whether owner is an interface
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String methodName,
                            String methodDescriptor,
                            boolean isInterface
                    ) {
                        owners.add(owner);
                    }

                    /// Collects type references nested in constants.
                    ///
                    /// @param value constant value
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type type) {
                            owners.add(type.getInternalName());
                        } else if (value instanceof Handle handle) {
                            owners.add(handle.getOwner());
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertTrue(owners.contains("org/jackhuang/hmcl/plugin/patch/PluginPatchDispatcher"));
        assertTrue(owners.contains("org/jackhuang/hmcl/plugin/patch/PluginPatchDispatchFrame"));
        assertFalse(owners.stream().anyMatch(owner -> owner.contains("PluginPatchTransformerTest")));
        assertFalse(owners.stream().anyMatch(owner -> owner.startsWith("dev/example/")));
    }

    /// Reads one compiled test class without initializing it.
    ///
    /// @param binaryName class binary name
    /// @return complete class bytes
    /// @throws IOException if the resource is missing or unreadable
    private static byte @Unmodifiable [] classBytes(String binaryName) throws IOException {
        String resourceName = binaryName.replace('.', '/') + ".class";
        try (InputStream input = PluginPatchTransformerTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing fixture class resource: " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    /// Writes one compiled class under an isolated URL class-path root.
    ///
    /// @param root isolated class-path root
    /// @param binaryName class binary name
    /// @param bytes complete class bytes
    /// @throws IOException if the class cannot be written
    private static void writeClass(Path root, String binaryName, byte[] bytes) throws IOException {
        Path target = root.resolve(binaryName.replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    /// Complete isolated transformer test environment.
    ///
    /// @param loader exact launcher-like class loader
    /// @param anchor loaded code-source anchor
    /// @param engine Patch engine
    /// @param transformer transformer under test
    /// @param originalTargetBytes original target bytes
    @NotNullByDefault
    private record FixtureEnvironment(
            FixtureClassLoader loader,
            Class<?> anchor,
            PluginPatchEngine engine,
            PluginPatchTransformer transformer,
            byte @Unmodifiable [] originalTargetBytes
    ) {
        /// Copies mutable byte input at construction.
        private FixtureEnvironment {
            originalTargetBytes = originalTargetBytes.clone();
        }

        /// Returns defensive target bytes.
        ///
        /// @return original target bytes
        @Override
        public byte @Unmodifiable [] originalTargetBytes() {
            return originalTargetBytes.clone();
        }
    }

    /// Loads only the two fixture classes child-first and defines transformed target bytes exactly once.
    @NotNullByDefault
    private static final class FixtureClassLoader extends URLClassLoader {
        /// Creates one isolated fixture loader.
        ///
        /// @param urls isolated class-path roots
        /// @param parent host test loader
        private FixtureClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        /// Loads fixture classes from the isolated root before consulting the host test loader.
        ///
        /// @param name binary class name
        /// @param resolve whether to resolve the class
        /// @return loaded class
        /// @throws ClassNotFoundException if no authorized source defines it
        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(ANCHOR_NAME) && !name.equals(TARGET_NAME)) {
                return super.loadClass(name, resolve);
            }
            @Nullable Class<?> loaded = findLoadedClass(name);
            Class<?> result = loaded == null ? findClass(name) : loaded;
            if (resolve) {
                resolveClass(result);
            }
            return result;
        }

        /// Resolves fixture class resources from the same child-first code source as their definitions.
        ///
        /// @param name resource path
        /// @return matching resource URL, or `null`
        @Override
        public @Nullable URL getResource(String name) {
            if (name.equals(ANCHOR_NAME.replace('.', '/') + ".class")
                    || name.equals(TARGET_NAME.replace('.', '/') + ".class")) {
                @Nullable URL resource = findResource(name);
                if (resource != null) {
                    return resource;
                }
            }
            return super.getResource(name);
        }

        /// Defines transformed bytes under the exact target binary name.
        ///
        /// @param binaryName target binary name
        /// @param bytes transformed bytes
        /// @return defined target class
        private synchronized Class<?> defineTransformed(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
