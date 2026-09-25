package com.idm.test.connector.soap.client;

import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.connector.soap.generated.resource.IRemoteResource;
import com.idm.test.connector.soap.generated.resource.NrfServiceException_Exception;
import com.idm.test.connector.soap.generated.resource.ObjectFactory;
import com.idm.test.connector.soap.generated.resource.RequestResourceGrantRequest;
import com.idm.test.connector.soap.generated.resource.RequestResourceGrantResponse;
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeRequest;
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeResponse;
import com.idm.test.connector.soap.generated.resource.ResourceService;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.ws.BindingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * NetIQ Identity Applications resource service connector.
 * Uses JAX-WS generated stubs from idmprov-resource.wsdl.
 * <p>
 * Grants and revokes resources on User objects. The verbs match the Identity
 * Applications API rather than the role service's assign/remove, because that
 * is the vocabulary consultants use for resources.
 * <p>
 * Only created when idm.test.soap.resource-url is configured, so tests run
 * without resource service infrastructure.
 */
@Component
@ConditionalOnProperty(prefix = "idm.test.soap", name = "resource-url")
public class ResourceServiceConnector {

    private static final Logger log = LoggerFactory.getLogger(ResourceServiceConnector.class);

    private final IRemoteResource resourcePort;
    private final ObjectFactory objectFactory;

    public ResourceServiceConnector(SoapProperties soapProperties) {
        this.objectFactory = new ObjectFactory();

        ResourceService service = new ResourceService();
        this.resourcePort = service.getIRemoteResourcePort();

        BindingProvider bindingProvider = (BindingProvider) resourcePort;
        bindingProvider.getRequestContext().put(
                BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
                soapProperties.getResourceUrl());
        SoapCredentials.apply(bindingProvider, soapProperties);

        log.info("ResourceServiceConnector initialized with endpoint: {}",
                soapProperties.getResourceUrl());
    }

    /**
     * Grants a resource to a user.
     *
     * @param userDn     the user's DN
     * @param resourceDn the resource's DN
     * @param reason     reason for the request, or {@code null}
     * @return the grant response, or a failure result; never throws
     */
    public OperationResult<RequestResourceGrantResponse> grantResource(
            String userDn, String resourceDn, String reason) {

        log.debug("Granting resource {} to user {}", resourceDn, userDn);

        try {
            RequestResourceGrantRequest request = new RequestResourceGrantRequest();
            request.setUserTarget(
                    objectFactory.createRequestResourceGrantRequestUserTarget(userDn));
            request.setResourceTarget(
                    objectFactory.createRequestResourceGrantRequestResourceTarget(resourceDn));
            if (reason != null) {
                request.setReasonForRequest(
                        objectFactory.createRequestResourceGrantRequestReasonForRequest(reason));
            }

            RequestResourceGrantResponse response = resourcePort.requestResourceGrant(request);

            log.debug("Resource grant accepted: {}", valueOf(response.getResult()));

            return OperationResult.<RequestResourceGrantResponse>builder()
                    .success(true)
                    .data(response)
                    .message("Resource granted: " + valueOf(response.getResult()))
                    .build();

        } catch (NrfServiceException_Exception e) {
            log.error("Resource grant rejected by the service: {}", e.getFaultInfo().getReason());
            return OperationResult.<RequestResourceGrantResponse>builder()
                    .success(false)
                    .message("Resource grant failed: " + e.getFaultInfo().getReason())
                    .errorType(ErrorType.SERVER_ERROR)
                    .throwable(e)
                    .build();

        } catch (Exception e) {
            log.error("Resource grant call failed: {}", e.getMessage());
            return OperationResult.<RequestResourceGrantResponse>builder()
                    .success(false)
                    .message("Resource grant failed: " + e.getMessage())
                    .errorType(SoapErrorTypes.classify(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Revokes a resource from a user.
     *
     * @param userDn     the user's DN
     * @param resourceDn the resource's DN
     * @param reason     reason for the request, or {@code null}
     * @return the revoke response, or a failure result; never throws
     */
    public OperationResult<RequestResourceRevokeResponse> revokeResource(
            String userDn, String resourceDn, String reason) {

        log.debug("Revoking resource {} from user {}", resourceDn, userDn);

        try {
            RequestResourceRevokeRequest request = new RequestResourceRevokeRequest();
            request.setUserTarget(
                    objectFactory.createRequestResourceRevokeRequestUserTarget(userDn));
            request.setResourceTarget(
                    objectFactory.createRequestResourceRevokeRequestResourceTarget(resourceDn));
            if (reason != null) {
                request.setReasonForRequest(
                        objectFactory.createRequestResourceRevokeRequestReasonForRequest(reason));
            }

            RequestResourceRevokeResponse response = resourcePort.requestResourceRevoke(request);

            log.debug("Resource revoke accepted: {}", valueOf(response.getResult()));

            return OperationResult.<RequestResourceRevokeResponse>builder()
                    .success(true)
                    .data(response)
                    .message("Resource revoked: " + valueOf(response.getResult()))
                    .build();

        } catch (NrfServiceException_Exception e) {
            log.error("Resource revoke rejected by the service: {}", e.getFaultInfo().getReason());
            return OperationResult.<RequestResourceRevokeResponse>builder()
                    .success(false)
                    .message("Resource revoke failed: " + e.getFaultInfo().getReason())
                    .errorType(ErrorType.SERVER_ERROR)
                    .throwable(e)
                    .build();

        } catch (Exception e) {
            log.error("Resource revoke call failed: {}", e.getMessage());
            return OperationResult.<RequestResourceRevokeResponse>builder()
                    .success(false)
                    .message("Resource revoke failed: " + e.getMessage())
                    .errorType(SoapErrorTypes.classify(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Unwraps a JAXBElement that the generated model may leave null.
     */
    private static String valueOf(JAXBElement<String> element) {
        return element == null ? null : element.getValue();
    }
}
