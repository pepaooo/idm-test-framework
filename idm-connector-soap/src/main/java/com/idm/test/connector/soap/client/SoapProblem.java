package com.idm.test.connector.soap.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Why a role or resource call failed, as a kind plus the service's or the transport's own text,
 * so a caller can word it in any language. {@link #english()} is the one English wording; the
 * failure messages of {@link RoleServiceConnector} and {@link ResourceServiceConnector} are built
 * on it, and the factories rebuild the problem from a failed result's throwable.
 */
public record SoapProblem(Kind kind, List<Object> values) {

    /** Every failure the role and resource operations the steps call can report. */
    public enum Kind {
        ROLE_FAULT, ROLE_ASSIGN_FAILED, ROLE_REMOVE_FAILED, RESOURCE_GRANT_FAILED, RESOURCE_REVOKE_FAILED
    }

    public SoapProblem {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static SoapProblem of(Kind kind, Object... values) {
        List<Object> list = new ArrayList<>();
        Collections.addAll(list, values);
        return new SoapProblem(kind, list);
    }

    /** Why {@code assignRoles} failed with {@code failure}. */
    public static SoapProblem assignRoles(Throwable failure) {
        return failure instanceof com.idm.test.connector.soap.generated.role.NrfServiceException_Exception fault
                ? of(Kind.ROLE_FAULT, fault.getFaultInfo().getReason())
                : of(Kind.ROLE_ASSIGN_FAILED, failure.getMessage());
    }

    /** Why {@code removeRoles} failed with {@code failure}. */
    public static SoapProblem removeRoles(Throwable failure) {
        return failure instanceof com.idm.test.connector.soap.generated.role.NrfServiceException_Exception fault
                ? of(Kind.ROLE_FAULT, fault.getFaultInfo().getReason())
                : of(Kind.ROLE_REMOVE_FAILED, failure.getMessage());
    }

    /** Why {@code grantResource} failed with {@code failure}. */
    public static SoapProblem grantResource(Throwable failure) {
        return of(Kind.RESOURCE_GRANT_FAILED, resourceText(failure));
    }

    /** Why {@code revokeResource} failed with {@code failure}. */
    public static SoapProblem revokeResource(Throwable failure) {
        return of(Kind.RESOURCE_REVOKE_FAILED, resourceText(failure));
    }

    private static String resourceText(Throwable failure) {
        return failure instanceof com.idm.test.connector.soap.generated.resource.NrfServiceException_Exception fault
                ? fault.getFaultInfo().getReason()
                : failure.getMessage();
    }

    /** The English wording, character for character what the connectors have always reported. */
    public String english() {
        String text = String.valueOf(values.get(0));
        return switch (kind) {
            case ROLE_FAULT -> "Role service error: " + text;
            case ROLE_ASSIGN_FAILED -> "Role assignment failed: " + text;
            case ROLE_REMOVE_FAILED -> "Role removal failed: " + text;
            case RESOURCE_GRANT_FAILED -> "Resource grant failed: " + text;
            case RESOURCE_REVOKE_FAILED -> "Resource revoke failed: " + text;
        };
    }
}
