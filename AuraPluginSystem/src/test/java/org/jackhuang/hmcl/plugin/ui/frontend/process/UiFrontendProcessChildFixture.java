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
package org.jackhuang.hmcl.plugin.ui.frontend.process;

import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/// Runs a real token-free UI child protocol conversation for process-session integration tests.
@NotNullByDefault
public final class UiFrontendProcessChildFixture {
    /// Prevents construction of this process entry point.
    private UiFrontendProcessChildFixture() {
    }

    /// Completes the handshake, invokes one launcher command, echoes one navigation, and shuts down cleanly.
    ///
    /// @param arguments unused process arguments
    /// @throws Exception if the launcher violates the fixture protocol
    public static void main(String[] arguments) throws Exception {
        requireProcessBoundary(arguments);
        UiFrontendMessage.Request hello = request(read(), 1L, "ui.hello");
        requireHello(hello.params());
        UiFrontendWireCodec.write(System.out, new UiFrontendMessage.Result(hello.requestId(), hello.params()));
        System.out.flush();

        UiFrontendMessage.Request snapshot = request(read(), 3L, "ui.snapshot.replace");
        if (!BridgeValue.string("redacted").equals(snapshot.params())) {
            throw new IOException("Launcher sent the wrong redacted snapshot fixture");
        }
        UiFrontendWireCodec.write(System.out,
                new UiFrontendMessage.Result(snapshot.requestId(), BridgeValue.nullValue()));
        System.out.flush();

        UiFrontendWireCodec.write(System.out,
                new UiFrontendMessage.Request(2L, "ui.ready", BridgeValue.nullValue()));
        System.out.flush();
        result(read(), 2L);

        UiFrontendWireCodec.write(System.out,
                new UiFrontendMessage.Request(4L, "core.snapshot.get", BridgeValue.nullValue()));
        System.out.flush();
        boolean callbackReceived = false;
        boolean navigationReceived = false;
        while (!callbackReceived || !navigationReceived) {
            UiFrontendMessage message = read();
            if (message instanceof UiFrontendMessage.Result callback && callback.requestId() == 4L) {
                if (!BridgeValue.string("launcher-state").equals(callback.value())) {
                    throw new IOException("Launcher command returned the wrong fixture value");
                }
                callbackReceived = true;
            } else if (message instanceof UiFrontendMessage.Request navigate
                    && navigate.requestId() == 5L && "ui.navigate".equals(navigate.method())) {
                UiFrontendWireCodec.write(System.out,
                        new UiFrontendMessage.Result(navigate.requestId(), navigate.params()));
                System.out.flush();
                navigationReceived = true;
            } else {
                throw new IOException("Launcher sent an unexpected concurrent message");
            }
        }

        UiFrontendMessage.Request shutdown = request(read(), 7L, "ui.shutdown");
        UiFrontendWireCodec.write(System.out,
                new UiFrontendMessage.Result(shutdown.requestId(), BridgeValue.nullValue()));
        System.out.flush();
    }

    /// Reads one launcher-originated frame from stdin.
    ///
    /// @return complete message
    /// @throws IOException if stdin ends or contains invalid wire data
    private static UiFrontendMessage read() throws IOException {
        UiFrontendMessage message = UiFrontendWireCodec.read(
                System.in, UiFrontendWireCodec.InboundEndpoint.FRONTEND);
        if (message == null) {
            throw new IOException("Launcher stdin ended unexpectedly");
        }
        return message;
    }

    /// Requires one request with the expected identifier and method.
    ///
    /// @param message candidate message
    /// @param requestId expected identifier
    /// @param method expected method
    /// @return validated request
    /// @throws IOException if the message differs
    private static UiFrontendMessage.Request request(
            UiFrontendMessage message, long requestId, String method) throws IOException {
        if (message instanceof UiFrontendMessage.Request request
                && request.requestId() == requestId && method.equals(request.method())) {
            return request;
        }
        throw new IOException("Launcher sent an unexpected request");
    }

    /// Requires one successful reply with the expected identifier.
    ///
    /// @param message candidate message
    /// @param requestId expected identifier
    /// @return validated result
    /// @throws IOException if the message differs
    private static UiFrontendMessage.Result result(UiFrontendMessage message, long requestId) throws IOException {
        if (message instanceof UiFrontendMessage.Result result && result.requestId() == requestId) {
            return result;
        }
        throw new IOException("Launcher sent an unexpected result");
    }

    /// Requires the exact ordered protocol and ABI hello fields independently from production construction.
    ///
    /// @param value candidate hello parameters
    /// @throws IOException if fields, order, types, or values differ
    private static void requireHello(BridgeValue value) throws IOException {
        if (!(value instanceof BridgeValue.MapValue map) || map.values().size() != 2) {
            throw new IOException("Launcher hello had the wrong field count");
        }
        Iterator<Map.Entry<String, BridgeValue>> entries = map.values().entrySet().iterator();
        Map.Entry<String, BridgeValue> protocol = entries.next();
        Map.Entry<String, BridgeValue> abi = entries.next();
        if (!"protocol".equals(protocol.getKey())
                || !(protocol.getValue() instanceof BridgeValue.StringValue text)
                || !"aura.ui.v1".equals(text.value())
                || !"abi".equals(abi.getKey())
                || !(abi.getValue() instanceof BridgeValue.IntegerValue integer)
                || integer.value() != 1L) {
            throw new IOException("Launcher hello fields were invalid");
        }
    }

    /// Requires the injected real process to receive the original exact argv and only filtered environment values.
    ///
    /// @param arguments original frontend command represented as fixture arguments
    /// @throws IOException if argv or environment filtering differs
    private static void requireProcessBoundary(String[] arguments) throws IOException {
        if (arguments.length != 2 || arguments[0].isBlank() || !"--stdio".equals(arguments[1])) {
            throw new IOException("Fixture received invalid frontend argv");
        }
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        requireEnvironment(windows ? "Path" : "PATH", "fixture-path", windows);
        requireEnvironment(windows ? "ComSpec" : "COMSPEC", "fixture-comspec", windows);
        requireEnvironment(windows ? "windir" : "WINDIR", "fixture-windir", windows);
        requireEnvironment("TEMP", "fixture-temp", windows);
        if (findEnvironment("PASSWORD", windows) != null
                || findEnvironment("AURA_SENTINEL_TOKEN", windows) != null) {
            throw new IOException("Fixture received a forbidden environment key");
        }
    }

    /// Requires one harmless environment fixture entry without printing inherited values.
    ///
    /// @param name expected key
    /// @param value expected harmless value
    /// @param windows whether names use Windows case semantics
    /// @throws IOException if the entry is absent or changed
    private static void requireEnvironment(String name, String value, boolean windows) throws IOException {
        String actual = findEnvironment(name, windows);
        if (!value.equals(actual)) {
            throw new IOException("Fixture did not receive an expected allowed environment key");
        }
    }

    /// Finds one environment value under platform-correct key semantics.
    ///
    /// @param expected expected key
    /// @param windows whether names use Windows case semantics
    /// @return value or `null` when absent
    private static @Nullable String findEnvironment(String expected, boolean windows) {
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            boolean matches = windows
                    ? entry.getKey().equalsIgnoreCase(expected)
                    : entry.getKey().equals(expected);
            if (matches) {
                return entry.getValue();
            }
        }
        return null;
    }
}
