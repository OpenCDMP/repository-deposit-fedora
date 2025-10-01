package org.opencdmp.deposit.fedorarepository.service.fedora;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({FedoraServiceProperties.class})
public class FedoraServiceConfiguration {
}
