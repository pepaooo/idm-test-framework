package com.idm.test.engine.result;

import java.util.regex.Pattern;

/**
 * Makes a connection URL safe to show in a report: no user name or password before the host,
 * and no value for a parameter whose name looks like a credential. The report is shared more
 * widely than the configuration it came from.
 */
public final class ReportUrls {

    private static final Pattern USER_INFO = Pattern.compile("(//)[^/?#@]*@");
    private static final Pattern ORACLE_THIN_CREDENTIALS = Pattern.compile("(:thin:)[^@/]*/[^@]*@");
    private static final Pattern CREDENTIAL_PARAMETER = Pattern.compile(
            "(?i)([?&;][^=&;?#]*(?:password|passwd|secret|token|key|credential)[^=&;?#]*=)[^&;#]*");

    private ReportUrls() {
    }

    /** @return {@code url} without credentials, or null when {@code url} is null */
    public static String sanitise(String url) {
        if (url == null) {
            return null;
        }
        String text = USER_INFO.matcher(url).replaceFirst("$1");
        text = ORACLE_THIN_CREDENTIALS.matcher(text).replaceFirst("$1@");
        return CREDENTIAL_PARAMETER.matcher(text).replaceAll("$1***");
    }
}
