# Aura Launcher for HarmonyOS PC

This directory contains the experimental ARM64 Stage application and private-HNP source layout for
Aura Launcher on HarmonyOS PC. It targets `2in1` devices and HarmonyOS SDK `6.0.1(21)`.

The package does not include Java. Install `BiShengJDK17-OH` separately so its public HNP exposes a
Java 17 or later executable to the application. The private `aura_launcher` HNP contains only the
fixed launcher entrypoint, the exact `Aura-Launcher-27.1-next.jar`, and required license notices.

This target is experimental and has not been tested on a physical HarmonyOS PC. A Linux ARM64
kernel does not establish that JavaFX, Minecraft, or external Runtime Hosts work correctly. Do not
publish an HNP or HAP until the device acceptance gates in the packaging design have passed.

Generated HNP/HAP files, JDK archives, SDK files, signing profiles, certificates, and private keys
must stay outside Git. The application wrapper and packaging sources remain under the launcher's
GPL-3.0 license boundary.
