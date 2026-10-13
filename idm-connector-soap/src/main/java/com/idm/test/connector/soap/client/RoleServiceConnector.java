package com.idm.test.connector.soap.client;

import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.connector.soap.generated.role.*;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import jakarta.xml.ws.BindingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * NetIQ Identity Manager Role Service SOAP connector.
 * Uses JAX-WS generated stubs from idmprov-role.wsdl.
 *
 * Features:
 * - Assign roles to users via requestRolesAssignment operation
 * - Remove roles from users via removeRoles operation
 * - Get assigned roles for users
 * - Error handling with OperationResult pattern
 * - Type-safe WSDL-generated classes
 * <p>
 * This connector is only created if idm.test.soap.url is configured,
 * allowing tests to run without SOAP infrastructure.
 */
@Component
@ConditionalOnProperty(prefix = "idm.test.soap", name = "url")
public class RoleServiceConnector {

    private static final Logger log = LoggerFactory.getLogger(RoleServiceConnector.class);

    private final IRemoteRole roleServicePort;
    private final String serviceUrl;
    private final ObjectFactory objectFactory;
    private final SoapCapture capture;

    public RoleServiceConnector(SoapProperties soapProperties) {
        this.serviceUrl = soapProperties.getUrl();
        this.objectFactory = new ObjectFactory();

        // Create JAX-WS service and port
        RoleService service = new RoleService();
        this.roleServicePort = service.getIRemoteRolePort();

        // Configure endpoint URL
        BindingProvider bindingProvider = (BindingProvider) roleServicePort;
        bindingProvider.getRequestContext().put(
            BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
            serviceUrl
        );
        SoapCredentials.apply(bindingProvider, soapProperties);
        SoapTimeouts.apply(bindingProvider, soapProperties);
        this.capture = SoapCapture.installOn(bindingProvider);

        log.info("RoleServiceConnector initialized with endpoint: {}", serviceUrl);
    }

    /**
     * Assigns roles to a user using the NetIQ requestRolesAssignment operation.
     *
     * @param userDn the user's DN
     * @param roleDns array of role DNs to assign
     * @param reason reason for the request (optional)
     * @return operation result containing assignment response
     */
    public OperationResult<RequestRolesAssignmentResponse> assignRoles(
            String userDn,
            String[] roleDns,
            String reason) {
        capture.clear();

        log.debug("Assigning {} role(s) to user: {}", roleDns.length, userDn);

        try {
            // Build the inner RoleAssignmentRequest
            RoleAssignmentRequest assignmentRequest = new RoleAssignmentRequest();
            assignmentRequest.setIdentity(userDn);

            // Set action type to GRANT
            assignmentRequest.setActionType(RoleAssignmentActionType.GRANT);

            // Set assignment type to USER_TO_ROLE
            assignmentRequest.setAssignmentType(RoleAssignmentType.USER_TO_ROLE);

            // Convert String[] to DNStringArray
            DNStringArray roleArray = new DNStringArray();
            for (String roleDn : roleDns) {
                DNString dnString = new DNString();
                dnString.setDn(roleDn);
                roleArray.getDnstring().add(dnString);
            }
            assignmentRequest.setRoles(roleArray);

            if (reason != null) {
                assignmentRequest.setReason(reason);
            }

            // Wrap in outer request with JAXBElement
            RequestRolesAssignmentRequest outerRequest = new RequestRolesAssignmentRequest();
            outerRequest.setAssignRequest(objectFactory.createRequestRolesAssignmentRequestAssignRequest(assignmentRequest));

            long startTime = System.currentTimeMillis();

            // Call SOAP service
            RequestRolesAssignmentResponse response = roleServicePort.requestRolesAssignment(outerRequest);

            long duration = System.currentTimeMillis() - startTime;

            log.debug("Role assignment completed in {}ms: {}", duration, response.getResult());

            return OperationResult.<RequestRolesAssignmentResponse>builder()
                    .success(true)
                    .data(response)
                    .message("Roles assigned successfully: " + response.getResult())
                    .build();

        } catch (NrfServiceException_Exception e) {
            log.error("NRF Service exception during role assignment: {}", e.getFaultInfo().getReason());
            return OperationResult.<RequestRolesAssignmentResponse>builder()
                    .success(false)
                    .message(SoapProblem.assignRoles(e).english())
                    .errorType(ErrorType.SERVER_ERROR)
                    .throwable(e)
                    .build();

        } catch (Exception e) {
            log.error("Role assignment failed for user '{}': {}", userDn, e.getMessage(), e);
            return OperationResult.<RequestRolesAssignmentResponse>builder()
                    .success(false)
                    .message(SoapProblem.assignRoles(e).english())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Convenience method to assign a single role to a user.
     *
     * @param userDn the user's DN
     * @param roleDn the role DN to assign
     * @return operation result containing assignment response
     */
    public OperationResult<RequestRolesAssignmentResponse> assignRole(String userDn, String roleDn) {
        return assignRoles(userDn, new String[]{roleDn}, null);
    }

    /**
     * Removes roles from a user using the NetIQ removeRoles operation.
     * Note: The removeRoles operation simply removes role DNs without specifying the user.
     * The DNs provided should be role assignment DNs (not role definition DNs).
     *
     * @param roleDns array of role assignment DNs to remove
     * @return operation result containing removal response
     */
    public OperationResult<RemoveRolesResponse> removeRoles(String[] roleDns) {
        capture.clear();

        log.debug("Removing {} role assignment(s)", roleDns.length);

        try {
            // Convert String[] to DNStringArray
            DNStringArray roleArray = new DNStringArray();
            for (String roleDn : roleDns) {
                DNString dnString = new DNString();
                dnString.setDn(roleDn);
                roleArray.getDnstring().add(dnString);
            }

            // Create request and wrap roleDns in JAXBElement
            RemoveRolesRequest request = new RemoveRolesRequest();
            request.setRoleDns(objectFactory.createRemoveRolesRequestRoleDns(roleArray));

            long startTime = System.currentTimeMillis();

            // Call SOAP service
            RemoveRolesResponse response = roleServicePort.removeRoles(request);

            long duration = System.currentTimeMillis() - startTime;

            log.debug("Role removal completed in {}ms: {}", duration, response.getResult());

            return OperationResult.<RemoveRolesResponse>builder()
                    .success(true)
                    .data(response)
                    .message("Roles removed successfully: " + response.getResult())
                    .build();

        } catch (NrfServiceException_Exception e) {
            log.error("NRF Service exception during role removal: {}", e.getFaultInfo().getReason());
            return OperationResult.<RemoveRolesResponse>builder()
                    .success(false)
                    .message(SoapProblem.removeRoles(e).english())
                    .errorType(ErrorType.SERVER_ERROR)
                    .throwable(e)
                    .build();

        } catch (Exception e) {
            log.error("Role removal failed: {}", e.getMessage(), e);
            return OperationResult.<RemoveRolesResponse>builder()
                    .success(false)
                    .message(SoapProblem.removeRoles(e).english())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Convenience method to remove a single role assignment.
     *
     * @param roleAssignmentDn the role assignment DN to remove
     * @return operation result containing removal response
     */
    public OperationResult<RemoveRolesResponse> removeRole(String roleAssignmentDn) {
        return removeRoles(new String[]{roleAssignmentDn});
    }

    /**
     * Gets user information including assigned roles using the NetIQ getUser operation.
     *
     * @param userDn the user's DN
     * @return operation result containing user information with associated roles
     */
    public OperationResult<GetUserResponse> getAssignedRoles(String userDn) {
        capture.clear();
        log.debug("Getting assigned roles for user: {}", userDn);

        try {
            GetUserRequest request = new GetUserRequest();
            request.setUserDN(objectFactory.createGetUserRequestUserDN(userDn));

            GetUserResponse response = roleServicePort.getUser(request);

            int count = 0;
            if (response.getResult() != null &&
                response.getResult().getValue() != null &&
                response.getResult().getValue().getAssociatedRoles() != null) {
                count = response.getResult().getValue().getAssociatedRoles().getDnstring().size();
            }

            log.debug("Retrieved {} associated roles for user: {}", count, userDn);

            return OperationResult.<GetUserResponse>builder()
                    .success(true)
                    .data(response)
                    .message("Successfully retrieved user with " + count + " associated roles")
                    .build();

        } catch (NrfServiceException_Exception e) {
            log.error("NRF Service exception getting user: {}", e.getFaultInfo().getReason());
            return OperationResult.<GetUserResponse>builder()
                    .success(false)
                    .message("Role service error: " + e.getFaultInfo().getReason())
                    .errorType(ErrorType.SERVER_ERROR)
                    .throwable(e)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get user '{}': {}", userDn, e.getMessage(), e);
            return OperationResult.<GetUserResponse>builder()
                    .success(false)
                    .message("Failed to get user information: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Determines ErrorType based on exception.
     *
     * @param e the exception
     * @return the error type
     */
    private ErrorType determineErrorType(Exception e) {
        return SoapErrorTypes.classify(e);
    }

    /**
     * The envelopes, endpoint and status of this thread's last call, for a step's evidence; empty
     * before any call, or when the last call failed before anything was sent.
     */
    public Optional<SoapExchange> lastExchange() {
        return capture.last();
    }
}
