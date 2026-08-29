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
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Owns generation-safe opaque JVM references and scopes every lookup to the authority's plugin owner.
///
/// @param <T> opaque authority type whose owner is resolved by the injected verifier
@NotNullByDefault
public final class BridgeHandleRegistry<T> {
    /// Release action used for references that need no explicit cleanup.
    private static final Runnable NO_RELEASE = () -> {
    };

    /// Derives an already authenticated plugin owner from an opaque authority.
    private final OwnerVerifier<T> ownerVerifier;

    /// Live entries keyed only by registry-local numeric slot.
    private final Map<Long, Entry> entries = new HashMap<>();

    /// Live foreign ownership reference counts keyed by registry-local numeric slot.
    private final Map<Long, Integer> referenceCounts = new HashMap<>();

    /// Current generation for every allocated or reusable numeric slot.
    private final Map<Long, Long> generations = new HashMap<>();

    /// Revoked numeric slots eligible for generation-safe reuse.
    private final ArrayDeque<Long> reusableIds = new ArrayDeque<>();

    /// Next never-before-used positive numeric slot.
    private long nextId = 1L;

    /// Creates an empty registry backed by an authority-to-owner verifier.
    ///
    /// Task 7 supplies the capability-token authority through this narrow boundary.
    ///
    /// @param ownerVerifier verifier that rejects invalid authority and returns its canonical plugin owner
    public BridgeHandleRegistry(OwnerVerifier<T> ownerVerifier) {
        this.ownerVerifier = Objects.requireNonNull(ownerVerifier, "ownerVerifier");
    }

    /// Registers one opaque JVM reference without an explicit release action.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @param type language-neutral type descriptor
    /// @param reference referenced JVM object
    /// @return opaque owner-free handle
    public BridgeHandle register(String ownerPluginId, String type, Object reference) {
        return register(ownerPluginId, type, reference, NO_RELEASE);
    }

    /// Registers one opaque JVM reference and its idempotent release action.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @param type language-neutral type descriptor
    /// @param reference referenced JVM object
    /// @param release action invoked once after the handle is invalidated
    /// @return opaque owner-free handle
    public synchronized BridgeHandle register(
            String ownerPluginId,
            String type,
            Object reference,
            Runnable release
    ) {
        requireOwnerId(ownerPluginId);
        String validatedType = BridgeHandle.requireValidType(type);
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(release, "release");

        long id = allocateId();
        long generation = generations.computeIfAbsent(id, ignored -> 1L);
        BridgeHandle handle = new BridgeHandle(id, generation, validatedType);
        entries.put(id, new Entry(ownerPluginId, handle, reference, release));
        referenceCounts.put(id, 1);
        return handle;
    }

    /// Retains one exact live handle after owner and generation verification.
    ///
    /// @param authority opaque authority verified by the owner resolver
    /// @param objectId registry-local numeric slot
    /// @param generation exact live slot generation
    /// @throws BridgeError when owner, generation, or reference-count bounds fail
    public synchronized void retain(T authority, long objectId, long generation) throws BridgeError {
        Entry entry = requireOwnedEntry(authority, objectId, generation);
        int references = referenceCounts.getOrDefault(entry.handle().id(), 0);
        if (references <= 0 || references == Integer.MAX_VALUE) {
            throw BridgeError.of(BridgeError.Category.UNAVAILABLE);
        }
        referenceCounts.put(entry.handle().id(), references + 1);
    }

    /// Releases one exact live handle and cleans up its JVM reference after the final owner reference drops.
    ///
    /// @param authority opaque authority verified by the owner resolver
    /// @param objectId registry-local numeric slot
    /// @param generation exact live slot generation
    /// @throws BridgeError when owner, generation, or cleanup validation fails
    public void release(T authority, long objectId, long generation) throws BridgeError {
        Entry released;
        synchronized (this) {
            Entry entry = requireOwnedEntry(authority, objectId, generation);
            int references = referenceCounts.getOrDefault(entry.handle().id(), 0);
            if (references <= 0) {
                throw BridgeError.of(BridgeError.Category.STALE_HANDLE);
            }
            if (references > 1) {
                referenceCounts.put(entry.handle().id(), references - 1);
                return;
            }
            invalidate(entry);
            entries.remove(entry.handle().id());
            referenceCounts.remove(entry.handle().id());
            if (generations.get(entry.handle().id()) > 0L) {
                reusableIds.addLast(entry.handle().id());
            }
            released = entry;
        }
        try {
            released.release().run();
        } catch (RuntimeException | Error exception) {
            throw BridgeError.of(BridgeError.Category.INTERNAL);
        }
    }

    /// Resolves one opaque handle after authority owner, generation, and type validation.
    ///
    /// @param authority opaque authority verified by the injected owner resolver
    /// @param handle presented handle
    /// @param expectedType operation-required type descriptor
    /// @return referenced JVM object
    /// @throws BridgeError when authority, generation, owner, or type validation fails
    public Object resolve(T authority, BridgeHandle handle, String expectedType) throws BridgeError {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(expectedType, "expectedType");
        synchronized (this) {
            Entry entry = entries.get(handle.id());
            if (entry == null || entry.handle().generation() != handle.generation()) {
                throw BridgeError.of(BridgeError.Category.STALE_HANDLE);
            }
            String authorityOwner = requireVerifiedOwner(authority, entry.ownerPluginId());
            if (!entry.ownerPluginId().equals(authorityOwner)) {
                throw BridgeError.of(BridgeError.Category.PERMISSION_DENIED);
            }
            if (!entry.handle().type().equals(handle.type()) || !entry.handle().type().equals(expectedType)) {
                throw BridgeError.of(BridgeError.Category.TYPE_MISMATCH);
            }
            return entry.reference();
        }
    }

    /// Reports whether one exact slot generation remains live without resolving its JVM reference.
    ///
    /// @param handle opaque handle
    /// @return whether the exact generation is live
    public synchronized boolean isLive(BridgeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        Entry entry = entries.get(handle.id());
        return entry != null && entry.handle().equals(handle);
    }

    /// Counts live references owned by one plugin for lifecycle diagnostics.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @return live reference count
    public synchronized int liveCount(String ownerPluginId) {
        requireOwnerId(ownerPluginId);
        return (int) entries.values().stream()
                .filter(entry -> entry.ownerPluginId().equals(ownerPluginId))
                .count();
    }

    /// Invalidates every owner handle before releasing any corresponding JVM reference.
    ///
    /// All release actions are attempted even if one fails. Any cleanup failure is reported only as a redacted
    /// internal Bridge error after the remaining references have been released.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @throws BridgeError after complete cleanup when one or more release actions fail
    public void revokeOwner(String ownerPluginId) throws BridgeError {
        requireOwnerId(ownerPluginId);
        List<Entry> revoked;
        synchronized (this) {
            revoked = entries.values().stream()
                    .filter(entry -> entry.ownerPluginId().equals(ownerPluginId))
                    .toList();

            // Advance every generation first so release callbacks can never observe a partially live owner.
            for (Entry entry : revoked) {
                invalidate(entry);
            }
            for (Entry entry : revoked) {
                entries.remove(entry.handle().id());
                referenceCounts.remove(entry.handle().id());
                if (generations.get(entry.handle().id()) > 0L) {
                    reusableIds.addLast(entry.handle().id());
                }
            }
        }

        boolean releaseFailed = false;
        for (Entry entry : revoked) {
            try {
                entry.release().run();
            } catch (RuntimeException | Error exception) {
                releaseFailed = true;
            }
        }
        if (releaseFailed) {
            throw BridgeError.of(BridgeError.Category.INTERNAL);
        }
    }

    /// Allocates one new or generation-advanced numeric slot.
    ///
    /// @return positive numeric slot
    private long allocateId() {
        if (!reusableIds.isEmpty()) {
            return reusableIds.removeFirst();
        }
        if (nextId <= 0L) {
            throw BridgeError.of(BridgeError.Category.UNAVAILABLE);
        }
        return nextId++;
    }

    /// Advances one handle generation before its entry is removed.
    ///
    /// A slot whose generation is exhausted is marked negative and never reused.
    ///
    /// @param entry live owner entry
    private void invalidate(Entry entry) {
        long generation = entry.handle().generation();
        generations.put(entry.handle().id(), generation == Long.MAX_VALUE ? -1L : generation + 1L);
    }

    /// Resolves one live entry after exact generation and authenticated owner validation.
    ///
    /// @param authority opaque authority
    /// @param objectId registry-local numeric slot
    /// @param generation exact live generation
    /// @return verified live entry
    /// @throws BridgeError if the slot is stale or owned by another plugin
    private Entry requireOwnedEntry(T authority, long objectId, long generation) throws BridgeError {
        Entry entry = entries.get(objectId);
        if (entry == null || entry.handle().generation() != generation) {
            throw BridgeError.of(BridgeError.Category.STALE_HANDLE);
        }
        String authorityOwner = requireVerifiedOwner(authority, entry.ownerPluginId());
        if (!entry.ownerPluginId().equals(authorityOwner)) {
            throw BridgeError.of(BridgeError.Category.PERMISSION_DENIED);
        }
        return entry;
    }

    /// Verifies the opaque authority and normalizes verifier failures to the permission-denied category.
    ///
    /// @param authority opaque authority
    /// @return canonical authority owner
    private String requireVerifiedOwner(T authority, String expectedOwnerPluginId) {
        try {
            String owner = ownerVerifier.requireOwner(
                    Objects.requireNonNull(authority, "authority"),
                    expectedOwnerPluginId
            );
            requireOwnerId(owner);
            return owner;
        } catch (BridgeError error) {
            throw error;
        } catch (RuntimeException | Error exception) {
            throw BridgeError.of(BridgeError.Category.PERMISSION_DENIED);
        }
    }

    /// Validates one canonical executable plugin ID.
    ///
    /// @param ownerPluginId candidate owner ID
    private static void requireOwnerId(String ownerPluginId) {
        if (!PluginManifest.isCanonicalExecutableId(ownerPluginId)) {
            throw new IllegalArgumentException("Bridge handle owner must be a canonical plugin ID");
        }
    }

    /// Resolves an opaque authority to its authenticated plugin owner.
    ///
    /// Task 7 implements this interface with the plugin capability authority rather than trusting Host input.
    ///
    /// @param <T> opaque authority type
    @FunctionalInterface
    @NotNullByDefault
    public interface OwnerVerifier<T> {
        /// Verifies one authority and returns its canonical plugin owner.
        ///
        /// @param authority opaque authority
        /// @return canonical authenticated owner plugin ID
        String requireOwner(T authority, String expectedOwnerPluginId);
    }

    /// Stores one JVM reference and its owner privately behind an owner-free handle.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @param handle exact live handle generation
    /// @param reference referenced JVM object
    /// @param release post-invalidation cleanup action
    @NotNullByDefault
    private record Entry(String ownerPluginId, BridgeHandle handle, Object reference, Runnable release) {
    }
}
