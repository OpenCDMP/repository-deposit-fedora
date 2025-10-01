package org.opencdmp.deposit.fedorarepository.configuration.semantics;

import org.json.Property;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.apache.jena.vocabulary.DC;
import java.util.List;

@ConfigurationProperties(prefix = "semantics")
public class SemanticsProperties {

   private  List<PathName> available;

    public List<PathName> getAvailable() {
        return available;
    }

    public void setAvailable(List<PathName> available) {
        this.available = available;
    }

    public static class PathName {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

    }
}
