# Per-user TLS trust for `idmtest`

## Intent

People connecting `idmtest` to internal LDAPS or HTTPS services should be able to trust their lab's certificates once, without setting `JAVA_TOOL_OPTIONS` for every `idmtest ui` or `idmtest run`. Trust belongs to the user on the machine, outside workspaces and outside the replaceable application ZIP.

## Scope

- Add `idmtest trust import <certificate-file>` for X.509 certificates in PEM or DER form. A file containing a PEM chain imports each certificate. Document that importing the issuing CA is preferred to importing a server certificate.
- Keep one combined PKCS12 truststore at `<user.home>/.idmtest/truststore.p12`. On the first import, copy trusted certificates from the Java runtime currently running `idmtest` (`java.home/lib/security/cacerts`), then add the supplied certificate(s). Subsequent imports preserve existing entries and add only new fingerprints. The CLI prints each imported certificate's subject, SHA-256 fingerprint, and whether it was already present.
- Add `idmtest trust refresh` to rebuild the combined store from the current Java runtime's `cacerts` while preserving certificates imported by `idmtest`. This is an explicit action after changing the bundled runtime or `IDM_TEST_JAVA_HOME`; an application update does not silently replace the user's trusted roots.
- At startup, before either Spring context or a connection is created, `IdmTest` selects this store if it exists. When no store exists, the JVM keeps its normal default trust. An explicit `javax.net.ssl.trustStore` JVM property takes precedence. The same behavior applies to the ZIP launcher and plain JAR.
- If the per-user store exists but is unreadable or invalid, commands that may connect (`run`, `ui`, and connection checking) fail with an actionable error rather than silently reverting to default trust. Import and refresh also stop without changing the store, with recovery instructions; they must never rebuild over a store that might contain earlier imports. Help and version remain usable.
- Update CLI help, bundled README, runner and workbench guides, and TLS reference documentation. Include an example import and explain the refresh step and explicit JVM override.

## Boundaries

- CLI import only; no workbench certificate controls or workspace-specific store in this version.
- No changes to hostname verification, client certificate authentication, or connector TLS implementations.
- A truststore password protects file integrity, not certificate secrecy. Use one application-defined password internally so ordinary users never need to configure it; do not print it. Restrict the per-user store's filesystem permissions where the platform supports it.
- Import and refresh must write atomically and leave an existing usable store intact if parsing or writing fails. A missing or malformed input certificate fails before changing trust.

## Approaches considered

1. **Chosen: combined per-user store.** Copy the current runtime's public roots into `~/.idmtest/truststore.p12` and add user certificates. It uses Java's standard truststore selection and works across the existing LDAP, HTTP, and SOAP connectors. A refresh is needed to pick up changed public roots.
2. **Separate user certificates merged at startup.** This would track runtime changes automatically, but adds startup work and state management to every command. Defer unless manual refresh proves problematic.
3. **Modify the bundled runtime's `cacerts`.** Dropped because replacing the ZIP could discard imported certificates and installations may be read-only.

## Implementation and verification notes

- Extend `CommandLine` and `IdmTest` with a `trust` command; keep truststore loading in Java rather than the POSIX and Windows launchers so plain-JAR use behaves the same.
- Use Java `KeyStore` and `CertificateFactory`, so the bundled `jlink` image does not need a `keytool` binary.
- Mark imported aliases distinctly from copied runtime aliases so refresh can preserve only user imports. Deduplicate by SHA-256 fingerprint, regardless of alias.
- Test first import, repeated import, malformed input, refresh after a changed base store, precedence of an explicit JVM property, startup failure for a corrupt store, and the no-store default. Exercise both `ui` and `run` startup paths as appropriate. Run the module's tests and the repository verification gate.
