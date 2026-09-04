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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Rewrites launcher-owned methods to call the stable Aura Patch dispatcher.
///
/// The transformer consumes only immutable engine plans and JVM-provided pre-Patch bytes. It never embeds plugin
/// callback classes, values, or class loaders in transformed bytecode.
@NotNullByDefault
public final class PluginPatchTransformer implements ClassFileTransformer {
    /// ASM type of the stable dispatcher.
    private static final Type DISPATCHER_TYPE = Type.getType(PluginPatchDispatcher.class);

    /// ASM type of one immutable dispatch frame.
    private static final Type FRAME_TYPE = Type.getType(PluginPatchDispatchFrame.class);

    /// ASM type of `java.lang.Object`.
    private static final Type OBJECT_TYPE = Type.getType(Object.class);

    /// Stable dispatcher entry method.
    private static final Method ENTER_METHOD = new Method(
            "enter",
            "(JLjava/lang/Object;[Ljava/lang/Object;)Lorg/jackhuang/hmcl/plugin/patch/PluginPatchDispatchFrame;"
    );

    /// Stable dispatcher normal-return method.
    private static final Method FINISH_METHOD = new Method(
            "finish",
            "(Lorg/jackhuang/hmcl/plugin/patch/PluginPatchDispatchFrame;Ljava/lang/Object;)Ljava/lang/Object;"
    );

    /// Dispatch-frame argument accessor.
    private static final Method ARGUMENTS_METHOD = new Method("arguments", "()[Ljava/lang/Object;");

    /// Dispatch-frame replacement decision accessor.
    private static final Method SHOULD_RETURN_METHOD = new Method("shouldReturn", "()Z");

    /// Dispatch-frame replacement value accessor.
    private static final Method RETURN_VALUE_METHOD = new Method("returnValue", "()Ljava/lang/Object;");

    /// Immutable Patch engine supplying current exact-method plans.
    private final PluginPatchEngine engine;

    /// Exact launcher class loader accepted for definitions and hierarchy resolution.
    private final ClassLoader launcherClassLoader;

    /// Correlates retransformation callbacks with failures otherwise suppressed by the JVM.
    private final PluginPatchRetransformationMonitor retransformationMonitor;

    /// Creates one transformer for an engine and exact launcher loader.
    ///
    /// @param engine immutable-plan owner
    /// @param launcherClassLoader exact launcher class loader
    PluginPatchTransformer(PluginPatchEngine engine, ClassLoader launcherClassLoader) {
        this(engine, launcherClassLoader, new PluginPatchRetransformationMonitor());
    }

    /// Creates one transformer with an explicit retransformation outcome monitor.
    ///
    /// @param engine immutable-plan owner
    /// @param launcherClassLoader exact launcher class loader
    /// @param retransformationMonitor retransformation outcome monitor shared with Instrumentation
    PluginPatchTransformer(
            PluginPatchEngine engine,
            ClassLoader launcherClassLoader,
            PluginPatchRetransformationMonitor retransformationMonitor
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.launcherClassLoader = Objects.requireNonNull(launcherClassLoader, "launcherClassLoader");
        this.retransformationMonitor = Objects.requireNonNull(
                retransformationMonitor,
                "retransformationMonitor"
        );
    }

    /// Applies dispatcher advice to every currently planned method in one exact launcher definition.
    ///
    /// @param module defining module, or `null`
    /// @param loader defining loader, or `null`
    /// @param className internal class name, or `null`
    /// @param classBeingRedefined exact retransformed class, or `null` for first definition
    /// @param protectionDomain definition protection domain, or `null`
    /// @param classFileBuffer JVM-provided pre-Patch class bytes
    /// @return rewritten class bytes, or `null` when this definition has no active plan
    /// @throws IllegalClassFormatException if active target bytes cannot be safely rewritten
    @Override
    public byte @Nullable [] transform(
            @Nullable Module module,
            @Nullable ClassLoader loader,
            @Nullable String className,
            @Nullable Class<?> classBeingRedefined,
            @Nullable ProtectionDomain protectionDomain,
            byte[] classFileBuffer
    ) throws IllegalClassFormatException {
        if (className == null
                || loader != launcherClassLoader
                || !engine.ownsDefinition(loader, protectionDomain)) {
            return null;
        }
        String binaryName = className.replace('/', '.');
        if (classBeingRedefined != null
                && (!binaryName.equals(classBeingRedefined.getName())
                || classBeingRedefined.getClassLoader() != launcherClassLoader
                || !engine.ownsDefinition(
                        classBeingRedefined.getClassLoader(),
                        classBeingRedefined.getProtectionDomain()))) {
            return null;
        }

        try {
            @Unmodifiable Map<String, Long> methodIds = methodIds(binaryName);
            if (methodIds.isEmpty()) {
                retransformationMonitor.record(classBeingRedefined, null);
                return null;
            }
            ClassReader reader = new ClassReader(classFileBuffer);
            if (!className.equals(reader.getClassName())) {
                throw new IllegalArgumentException("Class bytes declare another name");
            }
            SafeClassWriter writer = new SafeClassWriter(
                    reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                    launcherClassLoader
            );
            PatchClassVisitor visitor = new PatchClassVisitor(writer, methodIds);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            if (!visitor.transformedEveryMethod()) {
                throw new IllegalArgumentException("Current class bytes omit an active Patch method");
            }
            byte[] transformed = writer.toByteArray();
            retransformationMonitor.record(classBeingRedefined, null);
            return transformed;
        } catch (RuntimeException | LinkageError exception) {
            retransformationMonitor.record(classBeingRedefined, exception);
            IllegalClassFormatException failure = new IllegalClassFormatException(
                    "Unable to transform launcher Patch target " + binaryName + ": " + exception.getMessage()
            );
            failure.initCause(exception);
            throw failure;
        }
    }

    /// Builds exact name-and-descriptor keys for one target class from the current immutable plan snapshot.
    ///
    /// @param binaryName target binary class name
    /// @return immutable method IDs keyed by exact method identity
    private @Unmodifiable Map<String, Long> methodIds(String binaryName) {
        Map<String, Long> matches = new HashMap<>();
        engine.snapshotMethods().forEach((methodId, method) -> {
            if (binaryName.equals(method.target())) {
                @Nullable Long previous = matches.put(methodKey(method.name(), method.descriptor()), methodId);
                if (previous != null && previous.longValue() != methodId.longValue()) {
                    throw new IllegalStateException("Duplicate Patch method plan: "
                            + binaryName + "." + method.name() + method.descriptor());
                }
            }
        });
        return Map.copyOf(matches);
    }

    /// Creates an unambiguous bytecode method key.
    ///
    /// @param name method name
    /// @param descriptor complete JVM descriptor
    /// @return exact map key
    private static String methodKey(String name, String descriptor) {
        return name + '\u0000' + descriptor;
    }

    /// Visits one target class and wraps only exact active methods.
    @NotNullByDefault
    private static final class PatchClassVisitor extends ClassVisitor {
        /// Immutable exact active method IDs.
        private final @Unmodifiable Map<String, Long> methodIds;

        /// Exact method keys transformed from the current bytecode.
        private final Set<String> transformedMethods = new HashSet<>();

        /// Creates one class visitor.
        ///
        /// @param delegate class writer
        /// @param methodIds immutable active method IDs
        private PatchClassVisitor(ClassVisitor delegate, Map<String, Long> methodIds) {
            super(Opcodes.ASM9, delegate);
            this.methodIds = Map.copyOf(methodIds);
        }

        /// Wraps one exact active method with Patch advice.
        ///
        /// @param access JVM access flags
        /// @param name method name
        /// @param descriptor complete JVM descriptor
        /// @param signature generic signature, or `null`
        /// @param exceptions declared exception names, or `null`
        /// @return original or Patch-aware method visitor
        @Override
        public @Nullable MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                @Nullable String signature,
                String @Nullable [] exceptions
        ) {
            @Nullable MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            String key = methodKey(name, descriptor);
            @Nullable Long methodId = methodIds.get(key);
            if (methodId == null || delegate == null) {
                return delegate;
            }
            transformedMethods.add(key);
            return new PatchMethodVisitor(delegate, access, name, descriptor, methodId);
        }

        /// Returns whether every active method was present and transformed exactly once.
        ///
        /// @return whether the bytecode matches the immutable plan snapshot
        private boolean transformedEveryMethod() {
            return transformedMethods.size() == methodIds.size();
        }
    }

    /// Injects boxed dispatcher entry and normal-return calls around one exact method body.
    @NotNullByDefault
    private static final class PatchMethodVisitor extends AdviceAdapter {
        /// Stable dispatcher identity for this exact method.
        private final long methodId;

        /// Immutable ordered JVM argument types.
        private final Type @Unmodifiable [] argumentTypes;

        /// JVM method return type.
        private final Type returnType;

        /// Local slot holding the immutable dispatch frame after method entry.
        private int frameLocal = -1;

        /// Reusable local slot holding a non-void normal result.
        private int resultLocal = -1;

        /// Creates one method advice adapter.
        ///
        /// @param delegate original method visitor
        /// @param access JVM access flags
        /// @param name method name
        /// @param descriptor complete JVM descriptor
        /// @param methodId stable dispatcher identity
        private PatchMethodVisitor(
                MethodVisitor delegate,
                int access,
                String name,
                String descriptor,
                long methodId
        ) {
            super(Opcodes.ASM9, delegate, access, name, descriptor);
            this.methodId = methodId;
            argumentTypes = Type.getArgumentTypes(descriptor);
            returnType = Type.getReturnType(descriptor);
        }

        /// Dispatches entry callbacks, writes validated arguments back to locals, and handles replacement return.
        @Override
        protected void onMethodEnter() {
            push(methodId);
            if ((methodAccess & Opcodes.ACC_STATIC) == 0) {
                loadThis();
            } else {
                visitInsn(Opcodes.ACONST_NULL);
            }
            loadArgArray();
            invokeStatic(DISPATCHER_TYPE, ENTER_METHOD);
            frameLocal = newLocal(FRAME_TYPE);
            storeLocal(frameLocal);

            int argumentsLocal = newLocal(Type.getType(Object[].class));
            loadLocal(frameLocal);
            invokeVirtual(FRAME_TYPE, ARGUMENTS_METHOD);
            storeLocal(argumentsLocal);
            for (int index = 0; index < argumentTypes.length; index++) {
                loadLocal(argumentsLocal);
                push(index);
                arrayLoad(OBJECT_TYPE);
                unbox(argumentTypes[index]);
                storeArg(index);
            }

            loadLocal(frameLocal);
            invokeVirtual(FRAME_TYPE, SHOULD_RETURN_METHOD);
            Label runOriginal = newLabel();
            ifZCmp(EQ, runOriginal);
            loadLocal(frameLocal);
            loadLocal(frameLocal);
            invokeVirtual(FRAME_TYPE, RETURN_VALUE_METHOD);
            invokeStatic(DISPATCHER_TYPE, FINISH_METHOD);
            if (Type.VOID_TYPE.equals(returnType)) {
                pop();
            } else {
                unbox(returnType);
            }
            returnValue();
            mark(runOriginal);
        }

        /// Routes every normal return through `after` callbacks while preserving original exception flow.
        ///
        /// @param opcode original exit opcode
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == Opcodes.ATHROW) {
                return;
            }
            if (frameLocal < 0) {
                throw new IllegalStateException("Patch frame local was not initialized");
            }
            if (Type.VOID_TYPE.equals(returnType)) {
                loadLocal(frameLocal);
                visitInsn(Opcodes.ACONST_NULL);
                invokeStatic(DISPATCHER_TYPE, FINISH_METHOD);
                pop();
                return;
            }
            if (resultLocal < 0) {
                resultLocal = newLocal(returnType);
            }
            storeLocal(resultLocal);
            loadLocal(frameLocal);
            loadLocal(resultLocal);
            box(returnType);
            invokeStatic(DISPATCHER_TYPE, FINISH_METHOD);
            unbox(returnType);
        }
    }

    /// Computes frames using only bootstrap classes or classes defined by the exact launcher loader.
    @NotNullByDefault
    private static final class SafeClassWriter extends ClassWriter {
        /// Exact launcher loader used without initialization for hierarchy resolution.
        private final ClassLoader launcherClassLoader;

        /// Creates one frame-computing writer.
        ///
        /// @param reader current JVM-provided pre-Patch bytes
        /// @param flags ASM computation flags
        /// @param launcherClassLoader exact launcher loader
        private SafeClassWriter(ClassReader reader, int flags, ClassLoader launcherClassLoader) {
            super(reader, flags);
            this.launcherClassLoader = Objects.requireNonNull(launcherClassLoader, "launcherClassLoader");
        }

        /// Resolves a common superclass without consulting plugin artifact class loaders.
        ///
        /// @param leftInternalName first internal type name
        /// @param rightInternalName second internal type name
        /// @return common internal superclass name
        @Override
        protected String getCommonSuperClass(String leftInternalName, String rightInternalName) {
            try {
                Class<?> left = loadHierarchyType(leftInternalName);
                Class<?> right = loadHierarchyType(rightInternalName);
                if (left.isAssignableFrom(right)) {
                    return leftInternalName;
                }
                if (right.isAssignableFrom(left)) {
                    return rightInternalName;
                }
                if (left.isInterface() || right.isInterface()) {
                    return "java/lang/Object";
                }
                @Nullable Class<?> common = left;
                do {
                    common = common.getSuperclass();
                } while (common != null && !common.isAssignableFrom(right));
                return common == null ? "java/lang/Object" : Type.getInternalName(common);
            } catch (ClassNotFoundException | LinkageError | SecurityException exception) {
                throw new TypeNotPresentException(leftInternalName + " or " + rightInternalName, exception);
            }
        }

        /// Loads one hierarchy type without initialization and rejects foreign defining loaders.
        ///
        /// @param internalName JVM internal name or array descriptor
        /// @return resolved hierarchy class
        /// @throws ClassNotFoundException if the type is absent or uses a foreign loader
        private Class<?> loadHierarchyType(String internalName) throws ClassNotFoundException {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false, launcherClassLoader);
            @Nullable ClassLoader definingLoader = type.getClassLoader();
            if (definingLoader != null && definingLoader != launcherClassLoader) {
                throw new ClassNotFoundException("Hierarchy type uses a foreign class loader: " + internalName);
            }
            return type;
        }
    }
}
