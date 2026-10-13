package org.opencdmp.deposit.fedorarepository.configuration.semantics;

import org.opencdmp.commonmodels.enums.PluginEntityType;
import org.opencdmp.commonmodels.enums.SemanticKeyLevel;
import org.opencdmp.commonmodels.enums.SemanticValueFormat;
import org.opencdmp.commonmodels.models.semantics.SemanticKeyUsage;
import org.opencdmp.commonmodels.models.semantics.SemanticVocabulary;
import org.opencdmp.commonmodels.models.semantics.SemanticsManifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The semantics manifest the Fedora deposit publishes with its configuration: one usage per key in
 * {@code semantics.available}, each written as Dublin Core triples on the deposited resource.
 * <p>
 * Every value is "can use": the deposit always writes the title and date, plus the creators and, for a public plan,
 * its public page as identifier when the organisation's settings keep them, so nothing a template or blueprint leaves
 * out breaks it. Keys are read from
 * blueprint and template inputs alike, every answer becomes its own triple, and a reference input gives its
 * reference's code.
 */
public final class FedoraSemanticsManifest {

    public static final String PREFIX = "fedora";

    private record Known(String label, String element, String purpose) {
    }

    private static final Map<String, Known> KNOWN = Map.ofEntries(
            Map.entry("fedora.creator", new Known("Creator", "dc:creator", "Added to the plan members written as creators, as the organisation's \"Authors from plan members\" setting chooses (every member by default).")),
            Map.entry("fedora.subject", new Known("Subject", "dc:subject", "Written as a subject of the deposit.")),
            Map.entry("fedora.description", new Known("Description", "dc:description", "Written as a description of the deposit.")),
            Map.entry("fedora.publisher", new Known("Publisher", "dc:publisher", "Written as the publisher of the deposit.")),
            Map.entry("fedora.contributor", new Known("Contributor", "dc:contributor", "Written as a contributor to the deposit.")),
            Map.entry("fedora.type", new Known("Type", "dc:type", "Written as the type of the deposit.")),
            Map.entry("fedora.format", new Known("Format", "dc:format", "Written as the format of the deposit.")),
            Map.entry("fedora.identifier", new Known("Identifier", "dc:identifier", "Added to the identifiers; a public plan also gets its public page as one, unless the organisation turns off \"Link the public plan page\".")),
            Map.entry("fedora.source", new Known("Source", "dc:source", "Written as a source of the deposit.")),
            Map.entry("fedora.language", new Known("Language", "dc:language", "Written as the language of the deposit, as given; it is not checked against a code list.")),
            Map.entry("fedora.relation", new Known("Relation", "dc:relation", "Written as a related resource.")),
            Map.entry("fedora.coverage", new Known("Coverage", "dc:coverage", "Written as the coverage of the deposit.")),
            Map.entry("fedora.rights", new Known("Rights", "dc:rights", "Written as the rights statement of the deposit."))
    );

    private FedoraSemanticsManifest() {
    }

    public static SemanticsManifest build(SemanticsProperties properties) {
        List<SemanticKeyUsage> usages = new ArrayList<>();
        List<SemanticsProperties.PathName> available = properties == null || properties.getAvailable() == null ? List.of() : properties.getAvailable();
        for (String key : available.stream().map(SemanticsProperties.PathName::getName).filter(Objects::nonNull).distinct().toList()) {
            Known known = KNOWN.get(key);
            String name = key.substring(key.lastIndexOf('.') + 1);
            SemanticKeyUsage usage = new SemanticKeyUsage(
                    List.of(key),
                    false,
                    SemanticKeyLevel.CanUse,
                    List.of(PluginEntityType.Plan, PluginEntityType.Description),
                    known != null ? known.label() : name,
                    known != null ? known.purpose() : "Written as a Dublin Core triple on the deposit.",
                    known != null ? known.element() : "dc:" + name);
            usage.setFormat(SemanticValueFormat.Text);
            usage.setMultiple(true);
            usages.add(usage);
        }
        return new SemanticsManifest(List.of(new SemanticVocabulary(PREFIX, "Fedora (Dublin Core)", false)), usages);
    }
}
