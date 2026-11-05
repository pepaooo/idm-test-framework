package com.idm.test.connector.soap.client;

import com.idm.test.connector.soap.generated.resource.ResourceService;
import com.idm.test.connector.soap.generated.role.RoleService;
import jakarta.xml.ws.WebServiceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated services must load their WSDL from the classpath, never from the build
 * machine's source tree: a ZIP built on CI otherwise looks for the runner's checkout path
 * (for example {@code D:\a\idm-test-framework\...}) and the run cannot start.
 */
@DisplayName("Generated SOAP services load their WSDL from the classpath")
class GeneratedServiceWsdlLocationTest {

    @Test
    @DisplayName("Role service WSDL resolves from the classpath, not a build-time file path")
    void roleServiceWsdlComesFromClasspath() {
        assertThat(RoleService.class.getAnnotation(WebServiceClient.class).wsdlLocation())
                .doesNotStartWith("file:");
        assertThat(new RoleService().getWSDLDocumentLocation())
                .isEqualTo(RoleService.class.getResource("/idmprov-role.wsdl"));
    }

    @Test
    @DisplayName("Resource service WSDL resolves from the classpath, not a build-time file path")
    void resourceServiceWsdlComesFromClasspath() {
        assertThat(ResourceService.class.getAnnotation(WebServiceClient.class).wsdlLocation())
                .doesNotStartWith("file:");
        assertThat(new ResourceService().getWSDLDocumentLocation())
                .isEqualTo(ResourceService.class.getResource("/idmprov-resource.wsdl"));
    }
}
