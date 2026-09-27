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
}
