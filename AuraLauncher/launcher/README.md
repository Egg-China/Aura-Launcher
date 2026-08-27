# Aura Launcher Windows Launcher Header

`HMCLauncher.exe` is the retained upstream Windows executable header prepended to Aura Launcher's
distributable JAR.

It is derived from `org.glavo.hmcl:HMCLauncher:3.7.0.1`, whose corresponding source is available at
<https://github.com/Glavo/HMCLauncher>. HMCLauncher and this modified binary are distributed under GPLv3 with the
additional terms stated by that project.

The Aura binary changes only Windows resources:

- The retained application icon comes from `AuraLauncher/src/main/resources/assets/img/icon@8x.png`.
- The product name and description identify `Aura Launcher`.
- The resource version is `3.7.0.3` to distinguish it from both the unmodified upstream binary and
  the earlier HMCL CE resource build.
- The original copyright notice is retained, with the downstream resource modifications credited
  separately.

Artifact hashes:

- Original `HMCLauncher.exe`: `07476d13694bdba5ffcbb4786acf213c26eec650b78e710e0cd95c23d0e4147e`
- Aura `HMCLauncher.exe`: `e7586abbe1a194ced0b96de1093430c694f1d582b512bf477599f84fae52f258`

The icon was converted with `png2icons 2.0.1` using its Windows-executable ICO profile, then the resources were
updated with `rcedit 2.0.0`. `AURA_LAUNCHER_EXE` can override this bundled header for local builds.
