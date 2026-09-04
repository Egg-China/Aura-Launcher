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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

/// Installs one Mixin-like incapable transformer before Aura and eagerly loads the shared target.
@NotNullByDefault
public final class PatchMixinFixtureAgent {
    /// Internal name of the launcher-owned target transformed before Aura starts.
    private static final String TARGET_INTERNAL_NAME =
            "org/jackhuang/hmcl/patchfixture/PatchAgentLoadedTarget";

    /// Internal name of the target whose live method identity intentionally differs from its resource.
    private static final String MALFORMED_TARGET_INTERNAL_NAME =
            "org/jackhuang/hmcl/patchfixture/PatchAgentMalformedTarget";

    /// Number of real retransformation callbacks observed for the malformed target.
    private static final AtomicInteger MALFORMED_RETRANSFORMATIONS = new AtomicInteger();

    /// Prevents instantiation.
    private PatchMixinFixtureAgent() {
    }

    /// Installs the incapable transformer and loads its target before the Aura Patch agent is installed.
    ///
    /// @param arguments unused agent arguments, or `null`
    /// @param instrumentation active JVM instrumentation handle
    /// @throws ClassNotFoundException if the isolated target class cannot be loaded
    public static void premain(
            @Nullable String arguments,
            Instrumentation instrumentation
    ) throws ClassNotFoundException {
        instrumentation.addTransformer(new MarkerTransformer(), false);
        instrumentation.addTransformer(new RetransformationObserver(), true);
        Class.forName(
                TARGET_INTERNAL_NAME.replace('/', '.'),
                true,
                ClassLoader.getSystemClassLoader()
        );
        Class.forName(
                MALFORMED_TARGET_INTERNAL_NAME.replace('/', '.'),
                true,
                ClassLoader.getSystemClassLoader()
        );
    }

    /// Returns the number of real retransformation callbacks observed for the malformed target.
    ///
    /// @return malformed-target retransformation count
    static int malformedRetransformations() {
        return MALFORMED_RETRANSFORMATIONS.get();
    }

    /// Rewrites only the target marker while remaining ineligible for later retransformation callbacks.
    @NotNullByDefault
    private static final class MarkerTransformer implements ClassFileTransformer {
        /// Rewrites the exact marker method during its first definition.
        ///
        /// @param module defining module, or `null`
        /// @param loader defining loader, or `null`
        /// @param className internal class name, or `null`
        /// @param classBeingRedefined class being redefined, or `null`
        /// @param protectionDomain definition protection domain, or `null`
        /// @param classFileBuffer original class bytes
        /// @return rewritten target bytes, or `null` for unrelated definitions
        @Override
        public byte @Nullable @Unmodifiable [] transform(
                @Nullable Module module,
                @Nullable ClassLoader loader,
                @Nullable String className,
                @Nullable Class<?> classBeingRedefined,
                @Nullable ProtectionDomain protectionDomain,
                byte[] classFileBuffer
        ) {
            if ((!TARGET_INTERNAL_NAME.equals(className)
                    && !MALFORMED_TARGET_INTERNAL_NAME.equals(className))
                    || classBeingRedefined != null) {
                return null;
            }
            ClassReader reader = new ClassReader(classFileBuffer);
            ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new MarkerClassVisitor(writer, className), 0);
            return writer.toByteArray();
        }
    }

    /// Counts retransformation attempts without changing their input bytes.
    @NotNullByDefault
    private static final class RetransformationObserver implements ClassFileTransformer {
        /// Counts exact malformed-target retransformation callbacks.
        ///
        /// @param module defining module, or `null`
        /// @param loader defining loader, or `null`
        /// @param className internal class name, or `null`
        /// @param classBeingRedefined class being redefined, or `null`
        /// @param protectionDomain definition protection domain, or `null`
        /// @param classFileBuffer current class bytes
        /// @return always `null` to preserve current bytes
        @Override
        public byte @Nullable @Unmodifiable [] transform(
                @Nullable Module module,
                @Nullable ClassLoader loader,
                @Nullable String className,
                @Nullable Class<?> classBeingRedefined,
                @Nullable ProtectionDomain protectionDomain,
                byte[] classFileBuffer
        ) {
            if (MALFORMED_TARGET_INTERNAL_NAME.equals(className) && classBeingRedefined != null) {
                MALFORMED_RETRANSFORMATIONS.incrementAndGet();
            }
            return null;
        }
    }

    /// Replaces the marker constant in one exact fixture method.
    @NotNullByDefault
    private static final class MarkerClassVisitor extends ClassVisitor {
        /// Internal name of the class currently being rewritten.
        private final String className;

        /// Creates one marker visitor.
        ///
        /// @param delegate rewritten class sink
        /// @param className internal name of the rewritten class
        private MarkerClassVisitor(ClassVisitor delegate, String className) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
        }

        /// Wraps only the exact marker method.
        ///
        /// @param access JVM access flags
        /// @param name method name
        /// @param descriptor JVM descriptor
        /// @param signature generic signature, or `null`
        /// @param exceptions declared exceptions, or `null`
        /// @return original or marker-rewriting visitor
        @Override
        public @Nullable MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                @Nullable String signature,
                String @Nullable [] exceptions
        ) {
            String emittedName = MALFORMED_TARGET_INTERNAL_NAME.equals(className)
                    && "echo".equals(name)
                    && "(Ljava/lang/String;)Ljava/lang/String;".equals(descriptor)
                    ? "mixinEcho"
                    : name;
            @Nullable MethodVisitor delegate = super.visitMethod(
                    access,
                    emittedName,
                    descriptor,
                    signature,
                    exceptions
            );
            if (delegate == null
                    || !TARGET_INTERNAL_NAME.equals(className)
                    || !"mixinMarker".equals(name)
                    || !"()Ljava/lang/String;".equals(descriptor)) {
                return delegate;
            }
            return new MarkerMethodVisitor(delegate);
        }
    }

    /// Rewrites one exact constant without changing method structure.
    @NotNullByDefault
    private static final class MarkerMethodVisitor extends MethodVisitor {
        /// Creates one constant-rewriting visitor.
        ///
        /// @param delegate original method visitor
        private MarkerMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        /// Replaces the original marker constant and preserves every other instruction.
        ///
        /// @param value loaded constant
        @Override
        public void visitLdcInsn(Object value) {
            super.visitLdcInsn("original-marker".equals(value) ? "mixin-marker" : value);
        }
    }
}
