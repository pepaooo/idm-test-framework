package com.idm.test.engine.result;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a connection URL safe to show in a report: nothing of a credential may remain, and losing
 * some host detail is acceptable. The rules, applied in order:
 * <ul>
 *   <li>Oracle {@code jdbc:oracle:<driver>:user/password@host}: everything up to the last {@code @}
 *       is dropped, leaving {@code jdbc:oracle:<driver>:@host}.</li>
 *   <li>After {@code //}, if an {@code @} appears before the first {@code ?} or {@code #}, everything
 *       from {@code //} up to and including the last such {@code @} is dropped, so raw {@code @} or
 *       {@code /} in a password, and multi-host lists, cannot leak (the first host may be lost).</li>
 *   <li>The value of a parameter whose name contains password, passwd, pwd, pass, secret, token, key,
 *       credential, sig or auth (any case) is replaced by {@code ***}. {@code (} also starts a
 *       parameter and {@code )} ends a value (MySQL address form); a {@code {braced}} value is
 *       masked whole.</li>
 * </ul>
 * The report is shared more widely than the configuration it came from.
 */
public final class ReportUrls {

    private static final Pattern ORACLE_DRIVER = Pattern.compile("(?i)jdbc:oracle:[a-z0-9]+:");
    private static final Pattern CREDENTIAL_PARAMETER = Pattern.compile(
            "(?i)([?&;(][^=&;?#()]*(?:password|passwd|pwd|pass|secret|token|key|credential|sig|auth)"
                    + "[^=&;?#()]*=)(\\{[^}]*\\}|[^&;#)]*)");

    private ReportUrls() {
    }

    /** @return {@code url} without credentials, or null when {@code url} is null */
    public static String sanitise(String url) {
        if (url == null) {
            return null;
        }
        String text = removeOracleCredentials(url);
        text = removeUserInfo(text);
        return CREDENTIAL_PARAMETER.matcher(text).replaceAll("$1***");
    }

    private static String removeOracleCredentials(String text) {
        Matcher driver = ORACLE_DRIVER.matcher(text);
        if (!driver.lookingAt()) {
            return text;
        }
        int restStart = driver.end();
        if (restStart < text.length() && text.charAt(restStart) == '@') {
            return text;
        }
        int at = text.lastIndexOf('@');
        if (at < restStart) {
            return text;
        }
        return text.substring(0, restStart) + text.substring(at);
    }

    private static String removeUserInfo(String text) {
        int slashes = text.indexOf("//");
        if (slashes < 0) {
            return text;
        }
        int start = slashes + 2;
        int end = text.length();
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        int at = text.lastIndexOf('@', end - 1);
        if (at < start) {
            return text;
        }
        return text.substring(0, start) + text.substring(at + 1);
    }
}
