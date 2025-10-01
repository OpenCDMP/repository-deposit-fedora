package org.opencdmp.deposit.fedorarepository.configuration;

import org.opencdmp.deposit.fedorarepository.configuration.semantics.SemanticsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SemanticsProperties.class})
public class GenericConfiguration {
}
