package com.idm.test.engine.steps;

import com.idm.test.connector.rest.config.RestProperties;

import java.util.TreeSet;

/**
 * Resolves a {@code rest.*} step's {@code target} to a base URL, distinguishing two different
 * misconfigurations that need two different messages: no target by that name at all, versus a
 * target that exists but was never given a {@code base-url}.
 * <p>
 * Shared deliberately, the same way {@link SoapDns} and {@link SoapValues} are shared between
 * the two role/resource steps: {@code rest.request} and {@code rest.verify} both answer "what
 * URL does this target mean?", and two copies of that answer drifted apart during final review
 * — {@code rest.request} conflated the two cases into one self-contradictory message
 * ("names unknown target 'idm'. Configured targets: [idm]"), while {@code rest.verify} did not
 * check at all and built the URL {@code "null/users/1"}, retrying that nonsense as a transport
 * error for the whole {@code wait} timeout.
 */
final class RestTargets {

    private RestTargets() {
    }

    /**
     * @param baseUrl the target's base URL, or null when resolution failed
     * @param error   a message naming the step, the target, and what was wrong, or null on
     *                success
     */
    record Resolution(String baseUrl, String error) {

        static Resolution ok(String baseUrl) {
            return new Resolution(baseUrl, null);
        }

        static Resolution failed(String error) {
            return new Resolution(null, error);
        }

        boolean isFailure() {
            return error != null;
        }
    }

    /**
     * @param stepName   the calling step's {@link com.idm.test.engine.spi.StepType#name()}, so
     *                   the failure message names which step hit the problem
     * @param target     the {@code target} the scenario named
     * @param properties the configured REST targets
     * @return the target's base URL, or a {@link Resolution} carrying a message that says
     * whether the target is unknown or merely unconfigured
     */
    static Resolution resolveBaseUrl(String stepName, String target, RestProperties properties) {
        RestProperties.RestApiConfig config = properties.getRest().get(target);
        if (config == null) {
            return Resolution.failed(stepName + " names unknown target '" + target
                    + "'. Configured targets: " + new TreeSet<>(properties.getRest().keySet()));
        }

        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return Resolution.failed(stepName + " target '" + target
                    + "' is configured but has no base-url");
        }

        return Resolution.ok(baseUrl);
    }
}
