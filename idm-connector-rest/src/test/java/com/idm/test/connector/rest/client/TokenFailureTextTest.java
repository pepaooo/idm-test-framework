package com.idm.test.connector.rest.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenFailureText")
class TokenFailureTextTest {

    @Test
    @DisplayName("A null text becomes empty")
    void testNull() {
        assertThat(TokenFailureText.safe(null, "s3cret")).isEmpty();
    }

    @Test
    @DisplayName("Every occurrence of the client secret is masked, even inside a longer word")
    void testSecretMasked() {
        assertThat(TokenFailureText.safe("got s3cret and again s3cret; xs3cretx", "s3cret"))
                .isEqualTo("got **** and again ****; x****x");
    }

    @Test
    @DisplayName("A secret containing regex characters is replaced literally")
    void testSecretLiteral() {
        assertThat(TokenFailureText.safe("secret is a.b*c", "a.b*c")).isEqualTo("secret is ****");
        assertThat(TokenFailureText.safe("secret is axbbc", "a.b*c")).isEqualTo("secret is axbbc");
    }

    @Test
    @DisplayName("A null or empty secret leaves the text alone")
    void testNoSecret() {
        assertThat(TokenFailureText.safe("plain text", null)).isEqualTo("plain text");
        assertThat(TokenFailureText.safe("plain text", "")).isEqualTo("plain text");
    }

    @Test
    @DisplayName("Credentials in a URL are masked; a URL without them is unchanged")
    void testUserInfoMasked() {
        assertThat(TokenFailureText.safe("POST \"https://user:pa55@host.example/x\" failed", null))
                .isEqualTo("POST \"https://****@host.example/x\" failed");
        assertThat(TokenFailureText.safe("FTP://svc@host/x", null)).isEqualTo("FTP://****@host/x");
        assertThat(TokenFailureText.safe("https://host.example/x?a=b", null)).isEqualTo("https://host.example/x?a=b");
    }

    @Test
    @DisplayName("An email-like value with no scheme is unchanged")
    void testEmailUnchanged() {
        assertThat(TokenFailureText.safe("contact a@b.c", null)).isEqualTo("contact a@b.c");
    }

    @Test
    @DisplayName("Whitespace runs, newlines included, collapse to one space")
    void testWhitespace() {
        assertThat(TokenFailureText.safe("  <html>\n\t  <body>  hi </body>\r\n</html>  ", null))
                .isEqualTo("<html> <body> hi </body> </html>");
    }

    @Test
    @DisplayName("Exactly 300 characters are kept; 301 are cut to 300 plus an ellipsis")
    void testLength() {
        String exact = "a".repeat(TokenFailureText.MAX_LENGTH);
        assertThat(TokenFailureText.safe(exact, null)).isEqualTo(exact);
        assertThat(TokenFailureText.safe(exact + "b", null)).isEqualTo(exact + "…");
    }

    @Test
    @DisplayName("The secret is masked before truncation, so a cut can never expose part of it")
    void testMaskBeforeCut() {
        String text = "a".repeat(295) + "s3cret-value-long";
        assertThat(TokenFailureText.safe(text, "s3cret-value-long"))
                .isEqualTo("a".repeat(295) + "****")
                .doesNotContain("s3cret");
    }

    @Test
    @DisplayName("A URL-encoded occurrence of the secret is masked too")
    void testUrlEncodedSecretMasked() {
        String result = TokenFailureText.safe(
                "client_secret=Az%7Esecret%2Bval%2Fue%3D&x=1", "Az~secret+val/ue=");

        assertThat(result).doesNotContain("Az%7E").contains("client_secret=****");
    }

    @Test
    @DisplayName("An echoed Basic or Bearer Authorization value is masked")
    void testAuthorizationValueMasked() {
        assertThat(TokenFailureText.safe("auth Basic c3ZjLWNsaWVudDpBeiU3RXNlY3JldA== ok", null))
                .isEqualTo("auth Basic **** ok");
        assertThat(TokenFailureText.safe("got Bearer eyJhbGciOi.abc-def_ghi", null))
                .isEqualTo("got Bearer ****");
        assertThat(TokenFailureText.safe("short Basic abc value", null))
                .isEqualTo("short Basic abc value");
    }

    @Test
    @DisplayName("A raw @ inside userinfo is masked whole, not just up to the first @")
    void testRawAtInUserInfoMasked() {
        assertThat(TokenFailureText.safe("https://svc@corp.com:hunter2@idp/token", null))
                .isEqualTo("https://****@idp/token");
    }

    @Test
    @DisplayName("A secret that is a substring of a scheme still lets the userinfo rule mask")
    void testSchemeSubstringSecret() {
        String result = TokenFailureText.safe("https://user:pw@host/x", "s");

        assertThat(result).doesNotContain("pw@").doesNotContain("user:");
    }
}
