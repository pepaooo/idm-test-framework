# Import a server certificate from the CLI

## Intent

Users commonly know an internal HTTPS or LDAPS server's hostname and port but do not have its CA file. Give them one CLI command to retrieve the server's certificate, see what they are about to trust, and add it to the existing per-user `idmtest` truststore after explicit confirmation.

## Approaches considered

1. **Chosen: interactive `idmtest trust import-server <host:port>`.** Connect using direct TLS, show the presented leaf certificate and its SHA-256 fingerprint, then ask before adding it to `~/.idmtest/truststore.p12`. This is convenient with only a hostname and port. It is trust on first use: the command cannot prove the endpoint's identity from those inputs alone.
2. **Inspect/save, then `trust import <file>`.** Separates retrieval and trust but requires more commands and still cannot authenticate the retrieved certificate without an independent fingerprint or CA.
3. **Import the server-sent chain.** May survive leaf renewal when the server sends an intermediate CA, but grants broader trust, and servers need not send their root CA. Do not use this as the default.

## Behavior

- Accept a DNS hostname and port, including bracketed IPv6 literals; reject a missing/invalid port and ambiguous unbracketed IPv6. The command supports direct TLS endpoints such as HTTPS on 443 and LDAPS on 636. STARTTLS and HTTP proxy negotiation are outside this version.
- Open a bounded TLS connection using the hostname for SNI where applicable. Capture the certificate chain presented during the handshake without requiring that it already be trusted. Send no HTTP, LDAP, or other application data. Close the connection after capture.
- Select the leaf certificate only. Display the endpoint, subject, issuer, validity dates, DNS/IP subject alternative names, and SHA-256 fingerprint. State that the certificate has **not** been authenticated by a trusted CA and advise comparing the fingerprint with a trusted source when possible.
- Check that the leaf is currently valid and covers the requested hostname or IP address. If either check fails, show a specific error and do not offer to import it; importing it would not make an ordinary `idmtest` TLS connection pass those checks.
- Prompt `Trust this certificate on this machine? [y/N]`. Only an explicit `y` or `yes` imports. `n`, Enter, EOF, or noninteractive input leaves the store unchanged and returns a clear outcome. No `--yes` bypass in this version.
- Reuse `UserTrustStore`'s import logic so the public roots, fingerprint deduplication, atomic write, and corrupt-store protections remain the same. A successful import prints the existing truststore location. Connection, handshake, parsing, and store errors return exit code 2 with an actionable message.
- The store is additive, like `trust import <file>`: importing a renewed leaf does not remove an older imported certificate. Say this in the command output and guide. Removing stale certificates is a separate future operation.

## Boundaries and risks

- Do not disable normal TLS verification for `idmtest run`, `idmtest ui`, or the connection tester. The temporary certificate-capture connection belongs only to this command.
- Confirmation alone does not prevent a first-connection interception. The user accepted this lab-oriented first-use flow; the command must never claim that the certificate is verified.
- Keep existing file-based `trust import` and `trust refresh` behavior and JVM truststore override precedence.
- Update English and Spanish CLI text, `--help`, bundled README, runner guide, and TLS reference with an example using `host:port`. Prefer a verified CA file and `trust import <file>` when one becomes available.

## Verification

- CLI parsing: hostname, port, bracketed IPv6, malformed inputs, and no `--yes`.
- Local TLS server tests: capture a self-signed leaf, show fingerprint and details, import only after `yes`, leave the store unchanged on `no`/EOF, reject hostname mismatch and expired certificates, and stop on connection failure.
- Confirm that ordinary connections still reject an untrusted certificate before import and use normal TLS verification afterward. Run the runner tests, full repository verification, and packaged launcher smoke test.
