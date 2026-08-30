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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/// Enforces the launcher ownership boundary and resolves exact transformable Patch methods from bytecode.
@NotNullByDefault
public final class PluginPatchTargetPolicy {
    /// Largest launcher class resource accepted for safe Patch inspection.
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

    /// Required safe target namespace.
    private static final String LAUNCHER_PREFIX = "org.jackhuang.hmcl.";

    /// Complete protected Plugin System namespace.
    private static final String PROTECTED_PLUGIN_PREFIX = "org.jackhuang.hmcl.plugin.";

    /// Exact launcher application class loader.
    private final ClassLoader launcherClassLoader;

    /// Normalized launcher code-source location.
    private final URI launcherCodeSource;

    /// Supplies an immutable snapshot of currently loaded classes.
    private final Supplier<@Unmodifiable List<Class<?>>> loadedClassesSupplier;

    /// Creates a policy anchored to one exact launcher class loader and code source.
    ///
    /// @param launcherAnchor class proving the active launcher's loader and code source
    /// @param loadedClassesSupplier current loaded-class snapshot supplier
    PluginPatchTargetPolicy(
            Class<?> launcherAnchor,
            Supplier<@Unmodifiable List<Class<?>>> loadedClassesSupplier
    ) {
        Class<?> anchor = Objects.requireNonNull(launcherAnchor, "launcherAnchor");
        this.launcherClassLoader = Objects.requireNonNull(anchor.getClassLoader(), "launcher class loader");
        this.launcherCodeSource = requireCodeSource(anchor);
        this.loadedClassesSupplier = Objects.requireNonNull(loadedClassesSupplier, "loadedClassesSupplier");
    }

    /// Returns the exact launcher class loader used for descriptor type identity.
    ///
    /// @return launcher class loader
    ClassLoader launcherClassLoader() {
        return launcherClassLoader;
    }

    /// Resolves one declaration only when its class, loader, code source, resource, and method body are safe.
    ///
    /// @param method unresolved Patch method identity
    /// @return exact resolved target
    /// @throws PluginPatchFailure if the target falls outside policy or has no transformable matching method
    public PluginPatchTarget resolve(PluginPatchMethod method) throws PluginPatchFailure {
        PluginPatchMethod requested = Objects.requireNonNull(method, "method");
        requireAllowedName(requested.target());
        @Nullable Class<?> loadedClass = findLoadedClass(requested.target());
        String resourceName = requested.target().replace('.', '/') + ".class";
        URL resource = launcherClassLoader.getResource(resourceName);
        if (resource == null || !isOwnedResource(resource, resourceName)) {
            throw failure(PluginPatchFailure.Category.DENIED_TARGET,
                    "Patch target has no launcher-owned class resource: " + requested.target());
        }
        byte[] classBytes = readClassBytes(resource, requested.target());
        return resolveMethod(requested, classBytes, loadedClass);
    }

    /// Rejects protected, foreign, and generated binary names before any resource access.
    ///
    /// @param target binary target name
    /// @throws PluginPatchFailure if the name is outside the safe surface
    private static void requireAllowedName(String target) throws PluginPatchFailure {
        if (!target.startsWith(LAUNCHER_PREFIX)
                || target.startsWith(PROTECTED_PLUGIN_PREFIX)
                || target.contains("$$")
                || target.contains("/0x")) {
            throw failure(PluginPatchFailure.Category.DENIED_TARGET,
                    "Patch target is outside the safe launcher namespace: " + target);
        }
    }

    /// Finds the exact loaded launcher class while rejecting same-name foreign definitions.
    ///
    /// @param binaryName requested binary name
    /// @return exact launcher class, or `null` when not loaded
    /// @throws PluginPatchFailure if only a foreign or wrong-code-source definition is loaded
    private @Nullable Class<?> findLoadedClass(String binaryName) throws PluginPatchFailure {
        @Nullable Class<?> launcherClass = null;
        boolean foreignDefinition = false;
        @Unmodifiable List<Class<?>> loadedClasses = List.copyOf(Objects.requireNonNull(
                loadedClassesSupplier.get(), "loadedClassesSupplier result"));
        for (Class<?> candidate : loadedClasses) {
            if (!binaryName.equals(candidate.getName())) {
                continue;
            }
            if (candidate.getClassLoader() != launcherClassLoader) {
                foreignDefinition = true;
                continue;
            }
            if (!launcherCodeSource.equals(codeSource(candidate))) {
                throw failure(PluginPatchFailure.Category.DENIED_TARGET,
                        "Patch target uses a foreign code source: " + binaryName);
            }
            if (launcherClass != null && launcherClass != candidate) {
                throw failure(PluginPatchFailure.Category.DENIED_TARGET,
                        "Patch target has multiple launcher definitions: " + binaryName);
            }
            launcherClass = candidate;
        }
        if (launcherClass == null && foreignDefinition) {
            throw failure(PluginPatchFailure.Category.DENIED_TARGET,
                    "Patch target uses a foreign class loader: " + binaryName);
        }
        return launcherClass;
    }

    /// Returns whether a class resource belongs to the exact launcher directory or JAR.
    ///
    /// @param resource class resource URL
    /// @param resourceName expected class resource path
    /// @return whether the resource is launcher-owned
    private boolean isOwnedResource(URL resource, String resourceName) {
        try {
            if ("jar".equals(resource.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) resource.openConnection();
                return launcherCodeSource.equals(connection.getJarFileURL().toURI().normalize())
                        && resourceName.equals(connection.getEntryName());
            }
            return "file".equals(resource.getProtocol())
                    && launcherCodeSource.resolve(resourceName).normalize().equals(resource.toURI().normalize());
        } catch (IOException | URISyntaxException | ClassCastException exception) {
            return false;
        }
    }

    /// Reads one bounded class resource without retaining a mutable external buffer.
    ///
    /// @param resource launcher-owned class resource
    /// @param binaryName target binary name used in diagnostics
    /// @return exact class bytes
    /// @throws PluginPatchFailure if the resource cannot be read within the class-size bound
    private static byte[] readClassBytes(URL resource, String binaryName) throws PluginPatchFailure {
        try (InputStream input = resource.openStream()) {
            byte[] bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
            if (bytes.length > MAX_CLASS_BYTES) {
                throw failure(PluginPatchFailure.Category.DENIED_TARGET,
                        "Patch target class resource is oversized: " + binaryName);
            }
            return bytes;
        } catch (IOException exception) {
            throw new PluginPatchFailure(
                    PluginPatchFailure.Category.DENIED_TARGET,
                    "Patch target class resource cannot be read: " + binaryName,
                    exception
            );
        }
    }

    /// Resolves one exact method from validated class bytes and rejects methods without bodies.
    ///
    /// @param requested unresolved requested method
    /// @param classBytes validated class bytes
    /// @param loadedClass exact loaded class, or `null`
    /// @return resolved target
    /// @throws PluginPatchFailure if class bytes or the method are missing, ambiguous, or unsupported
    private static PluginPatchTarget resolveMethod(
            PluginPatchMethod requested,
            byte[] classBytes,
            @Nullable Class<?> loadedClass
    ) throws PluginPatchFailure {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        try {
            new ClassReader(classBytes).accept(
                    classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException exception) {
            throw new PluginPatchFailure(
                    PluginPatchFailure.Category.DENIED_TARGET,
                    "Patch target has an invalid class resource: " + requested.target(),
                    exception
            );
        }
        if (!requested.target().replace('.', '/').equals(classNode.name)) {
            throw failure(PluginPatchFailure.Category.DENIED_TARGET,
                    "Patch target resource declares another class: " + requested.target());
        }
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode candidate : classNode.methods) {
            if (requested.matches(candidate.name, candidate.desc)) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            throw failure(PluginPatchFailure.Category.MISSING_METHOD,
                    "Patch target method is missing: " + requested.target() + "." + requested.name());
        }
        if (matches.size() != 1) {
            throw failure(PluginPatchFailure.Category.UNSUPPORTED_METHOD,
                    "Patch target method identity is ambiguous: " + requested.target() + "." + requested.name());
        }
        MethodNode selected = matches.get(0);
        if ((selected.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw failure(PluginPatchFailure.Category.UNSUPPORTED_METHOD,
                    "Patch target method has no transformable body: "
                            + requested.target() + "." + requested.name());
        }
        return new PluginPatchTarget(
                requested.withDescriptor(selected.desc), selected.access, classBytes, loadedClass);
    }

    /// Returns the normalized non-null code source of one class.
    ///
    /// @param type class whose code source is required
    /// @return normalized code-source URI
    private static URI requireCodeSource(Class<?> type) {
        @Nullable URI value = codeSource(type);
        if (value == null) {
            throw new IllegalArgumentException("Launcher anchor has no code source: " + type.getName());
        }
        return value;
    }

    /// Returns one class code source without initializing any additional class.
    ///
    /// @param type inspected class
    /// @return normalized code-source URI, or `null`
    private static @Nullable URI codeSource(Class<?> type) {
        @Nullable CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        try {
            return source.getLocation().toURI().normalize();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    /// Creates one stable failure without callback or class-resource contents.
    ///
    /// @param category stable category
    /// @param message redacted diagnostic
    /// @return failure
    private static PluginPatchFailure failure(PluginPatchFailure.Category category, String message) {
        return new PluginPatchFailure(category, message);
    }
}
