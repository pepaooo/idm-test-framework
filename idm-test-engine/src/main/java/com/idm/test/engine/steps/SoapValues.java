package com.idm.test.engine.steps;

import jakarta.xml.bind.JAXBElement;

/**
 * Unwraps the {@code JAXBElement<String>} result the resource operations return.
 * <p>
 * The element and its value may each be null; an absent identifier becomes an empty string
 * rather than null, because a null captured value fails a scenario that requested one — and
 * "the operation succeeded but returned no identifier" is not a failure. One implementation so
 * {@code resources.grant} and {@code resources.revoke} cannot disagree about what "no
 * identifier" looks like, the same reasoning that put DN unwrapping in {@link SoapDns} rather
 * than in one of the two role steps.
 */
final class SoapValues {

    private SoapValues() {
    }

    static String of(JAXBElement<String> result) {
        if (result == null || result.getValue() == null) {
            return "";
        }
        return result.getValue();
    }
}
