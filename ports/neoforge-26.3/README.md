# NeoForge 26.3 port (experimental)

Standalone development target for Minecraft 26.3, NeoForge 26.3.0.95 and Java 25.
Run `gradlew.bat build` from this directory with a Java 25 JDK. Outputs go to
`build/libs/`. This target does not alter the existing root or Fabric builds.

Unchanged code is drawn from the committed 26.3 implementation in `multiversion/`.
Only loader-specific source replacements are kept here. Assets and translations
are shared with the repository. Keep the multi-version tree in its committed
26.3 state when building this target.

The port replaces Fabric startup, network registration, lifecycle callbacks,
key bindings, model wrapping, HUD layers, configuration paths and settings-menu
integration with native NeoForge APIs. The original 26.3 mixins are retained and
access wideners are translated to access transformers. The development brewing
assertion checks the synchronized bottle cache used by the renderer.

Use `gradlew.bat runShowcase -Pfrom=b -Ponly=true` for a brewing walkthrough.
See `VERIFICATION.md` for the tested configurations and remaining limitations.
Code and third-party assets retain the repository's LICENSE and CREDITS.md.
Prepared with Codex assistance; this is an unofficial port, not a release.
