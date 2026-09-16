package com.idm.test.engine.steps;

import com.idm.test.connector.soap.generated.role.DNString;
import com.idm.test.connector.soap.generated.role.DNStringArray;

import jakarta.xml.bind.JAXBElement;
import java.util.List;

/**
 * Unwraps the {@code JAXBElement<DNStringArray>} both role operations return.
 * <p>
 * Three levels of optionality — the element, its value, and the list — each of which JAXB may
 * leave null. One implementation so {@code roles.grant} and {@code roles.revoke} cannot
 * disagree about what "no DNs" looks like.
 */
final class SoapDns {

    private SoapDns() {
    }

    /**
     * @return every DN in the result, or an empty list when the service returned none
     */
    static List<String> of(JAXBElement<DNStringArray> result) {
        if (result == null || result.getValue() == null
                || result.getValue().getDnstring() == null) {
            return List.of();
        }
        return result.getValue().getDnstring().stream()
                .map(DNString::getDn)
                .filter(dn -> dn != null)
                .toList();
    }
}
