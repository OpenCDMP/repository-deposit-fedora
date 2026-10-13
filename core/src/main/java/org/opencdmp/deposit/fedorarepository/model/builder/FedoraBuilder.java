package org.opencdmp.deposit.fedorarepository.model.builder;

import gr.cite.tools.logging.LoggerService;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.DC;
import org.opencdmp.commonmodels.enums.PlanAccessType;
import org.opencdmp.commonmodels.models.PlanUserModel;
import org.opencdmp.commonmodels.models.description.*;
import org.opencdmp.commonmodels.models.planblueprintdescriptiontemplatefields.fielddata.RadioBoxDataModel;
import org.opencdmp.commonmodels.models.planblueprintdescriptiontemplatefields.fielddata.SelectDataModel;
import org.opencdmp.commonmodels.models.plan.PlanModel;
import org.opencdmp.commonmodels.models.plandescriptionfields.FieldModel;
import org.opencdmp.commonmodels.models.plandescriptionfields.PropertyDefinitionFieldSetItemModel;
import org.opencdmp.commonmodels.models.plandescriptionfields.PropertyDefinitionFieldSetModel;
import org.opencdmp.commonmodels.models.reference.ReferenceModel;
import org.opencdmp.deposit.fedorarepository.configuration.semantics.SemanticsProperties;
import org.opencdmp.deposit.fedorarepository.service.fedora.FedoraDepositServiceImpl;
import org.opencdmp.deposit.fedorarepository.service.fedora.FedoraServiceProperties;
import org.opencdmp.depositbase.repository.DepositPluginSettings;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FedoraBuilder {
    private static final LoggerService logger = new LoggerService(LoggerFactory.getLogger(FedoraDepositServiceImpl.class));

    private final FedoraServiceProperties fedoraServiceProperties;
    private final SemanticsProperties semanticsProperties;

    private static final String SEMANTIC_CREATOR = "fedora.creator";
    private static final String SEMANTIC_SUBJECT = "fedora.subject";
    private static final String SEMANTIC_DESCRIPTION = "fedora.description";
    private static final String SEMANTIC_PUBLISHER = "fedora.publisher";
    private static final String SEMANTIC_CONTRIBUTOR = "fedora.contributor";
    private static final String SEMANTIC_TYPE = "fedora.type";
    private static final String SEMANTIC_FORMAT = "fedora.format";
    private static final String SEMANTIC_IDENTIFIER = "fedora.identifier";
    private static final String SEMANTIC_SOURCE = "fedora.source";
    private static final String SEMANTIC_LANGUAGE = "fedora.language";
    private static final String SEMANTIC_RELATION = "fedora.relation";
    private static final String SEMANTIC_COVERAGE = "fedora.coverage";
    private static final String SEMANTIC_RIGHTS = "fedora.rights";

    public static final String SETTING_PUBLISHER = "publisher";
    public static final String SETTING_RIGHTS = "rights";

    @Autowired
    public FedoraBuilder(FedoraServiceProperties fedoraServiceProperties, SemanticsProperties semanticsProperties){
        this.fedoraServiceProperties = fedoraServiceProperties;
        this.semanticsProperties = semanticsProperties;
    }


    public Model build(PlanModel planModel){

        if (planModel == null) return null;
        Model model = ModelFactory.createDefaultModel();
        Resource resource = model.createResource("");

        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        resource.addProperty(DC.date, df.format(new Date()));
        resource.addProperty(DC.title, planModel.getLabel());
        
        DepositPluginSettings settings = DepositPluginSettings.of(planModel, this.fedoraServiceProperties.getDepositConfiguration());
        if(planModel.getAccessType().equals(PlanAccessType.Public) && settings.linkPublicPlan()) resource.addProperty(DC.identifier, fedoraServiceProperties.getDomain() + "explore-plans/overview/public/" + planModel.getId().toString());
        this.buildOwners(planModel, resource, settings);
        this.applySemantics(planModel, resource);
        this.applyDefault(resource, DC.publisher, settings.text(SETTING_PUBLISHER));
        this.applyDefault(resource, DC.rights, settings.text(SETTING_RIGHTS));
        return model;
    }

    private void applyDefault(Resource resource, Property property, String value) {
        if (value != null && !resource.hasProperty(property)) resource.addProperty(property, value);
    }

    // there was code smell coupling of serialization in builder before...

    public String serialize(Model model) {
        if (model == null) return null;
        StringWriter writer = new StringWriter();
        model.write(writer, "TURTLE");
        return writer.toString();
    }

    public void buildOwners(PlanModel plan, Resource resource, DepositPluginSettings settings){
        for (PlanUserModel planUser: settings.authors(plan.getUsers())) {
           resource.addProperty(DC.creator, planUser.getUser().getName());
        }
    }


    private List<org.opencdmp.commonmodels.models.planblueprintdescriptiontemplatefields.FieldModel> findSchematicValues(String relatedId, List<org.opencdmp.commonmodels.models.planblueprintdescriptiontemplatefields.FieldModel> fields){
        return fields.stream().filter(x-> x.getSemantics() != null && x.getSemantics().contains(relatedId)).toList();
    }


    private Set<String> extractSchematicValues(List<org.opencdmp.commonmodels.models.planblueprintdescriptiontemplatefields.FieldModel> fields, Map<String, PropertyDefinitionFieldSetModel> fieldSets) {
        Set<String> values = new HashSet<>();
        for (org.opencdmp.commonmodels.models.planblueprintdescriptiontemplatefields.FieldModel field : fields) {
            if (field.getData() == null) continue;
            List<FieldModel> valueFields = this.findValueFieldsByIds(field.getId(), fieldSets);
            for (FieldModel valueField : valueFields) {
                switch (field.getData().getFieldType()) {
                    case FREE_TEXT, TEXT_AREA, RICH_TEXT_AREA -> {
                        if (valueField.getTextValue() != null && !valueField.getTextValue().isBlank()) values.add(valueField.getTextValue());
                    }
                    case NUMBER -> {
                        if (valueField.getNumberValue() != null) values.add(String.valueOf(valueField.getNumberValue()));
                    }
                    case BOOLEAN_DECISION, CHECK_BOX -> {
                        if (valueField.getBooleanValue() != null) values.add(valueField.getBooleanValue().toString());
                    }
                    case DATE_PICKER -> {
                        if (valueField.getDateValue() != null) values.add(DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(valueField.getDateValue()));
                    }
                    case DATASET_IDENTIFIER, VALIDATION -> {
                        if (valueField.getExternalIdentifier() != null && valueField.getExternalIdentifier().getIdentifier() != null && !valueField.getExternalIdentifier().getIdentifier().isBlank()) {
                            values.add(valueField.getExternalIdentifier().getIdentifier());
                        }
                    }
                    case TAGS -> {
                        if (valueField.getTextListValue() != null && !valueField.getTextListValue().isEmpty()) {
                            values.addAll(valueField.getTextListValue());
                        }
                    }
                    case SELECT -> {
                        if (valueField.getTextListValue() != null && !valueField.getTextListValue().isEmpty()) {
                            SelectDataModel selectDataModel = (SelectDataModel)field.getData();
                            if (selectDataModel != null && selectDataModel.getOptions() != null && !selectDataModel.getOptions().isEmpty()){
                                for (SelectDataModel.OptionModel option : selectDataModel.getOptions()){
                                    if (valueField.getTextListValue().contains(option.getValue()) || valueField.getTextListValue().contains(option.getLabel())) values.add(option.getLabel());
                                }
                            }
                        }
                    }
                    case RADIO_BOX -> {
                        if (valueField.getTextListValue() != null && !valueField.getTextListValue().isEmpty()) {
                            RadioBoxDataModel radioBoxModel = (RadioBoxDataModel)field.getData();
                            if (radioBoxModel != null && radioBoxModel.getOptions() != null && !radioBoxModel.getOptions().isEmpty()){
                                for (RadioBoxDataModel.RadioBoxOptionModel option : radioBoxModel.getOptions()){
                                    if (valueField.getTextListValue().contains(option.getValue()) || valueField.getTextListValue().contains(option.getLabel())) values.add(option.getLabel());
                                }
                            }
                        }
                    }
                    case REFERENCE_TYPES -> {
                        if (valueField.getReferences() != null && !valueField.getReferences().isEmpty()) {
                            for (ReferenceModel referenceModel : valueField.getReferences()) {
                                if (referenceModel == null
                                        || referenceModel.getType() == null || referenceModel.getType().getCode() == null || referenceModel.getType().getCode().isBlank()
                                        || referenceModel.getDefinition() == null || referenceModel.getDefinition().getFields() == null || referenceModel.getDefinition().getFields().isEmpty()) continue;
                                if (referenceModel.getReference() != null && !referenceModel.getReference().isBlank()) {
                                    values.add(referenceModel.getReference());
                                }
                            }
                        }
                    }
                }
            }
        }
        return values;
    }


    private List<FieldModel> findValueFieldsByIds(String fieldId, Map<String, PropertyDefinitionFieldSetModel> fieldSets){
        List<FieldModel> models = new ArrayList<>();
        if (fieldSets == null || fieldSets.isEmpty()) return models;
        for (PropertyDefinitionFieldSetModel propertyDefinitionFieldSetModel : fieldSets.values()){
            if (propertyDefinitionFieldSetModel == null ||propertyDefinitionFieldSetModel.getItems() == null || propertyDefinitionFieldSetModel.getItems().isEmpty()) continue;
            for (PropertyDefinitionFieldSetItemModel propertyDefinitionFieldSetItemModel : propertyDefinitionFieldSetModel.getItems()){
                if (propertyDefinitionFieldSetItemModel == null ||propertyDefinitionFieldSetItemModel.getFields() == null || propertyDefinitionFieldSetItemModel.getFields().isEmpty()) continue;
                for (Map.Entry<String, FieldModel> entry : propertyDefinitionFieldSetItemModel.getFields().entrySet()){
                    if (entry == null || entry.getValue() == null) continue;
                    if (entry.getKey().equalsIgnoreCase(fieldId)) models.add(entry.getValue());
                }
            }
        }
        return models;
    }



    public void applySemantics(PlanModel planModel, Resource resource) {

        List<SemanticsProperties.PathName> acceptedSemantics = this.semanticsProperties.getAvailable() != null ? this.semanticsProperties.getAvailable() : List.of();

        Map<String, Set<String>> pathToValuesMap = new HashMap<>();

        if (planModel.getDescriptions() != null) {
            for (DescriptionModel descriptionModel : planModel.getDescriptions()) {
                for (SemanticsProperties.PathName relatedName : acceptedSemantics) {
                    List<org.opencdmp.commonmodels.models.planblueprintdescriptiontemplatefields.FieldModel> fieldsWithSemantics = this.findSchematicValues(relatedName.getName(), descriptionModel.getDescriptionTemplate().getDefinition().getAllField());
                    Set<String> values = extractSchematicValues(fieldsWithSemantics, descriptionModel.getProperties().getFieldSets());
                    if (!values.isEmpty()) pathToValuesMap.computeIfAbsent(relatedName.getName(), k -> new HashSet<>()).addAll(values);
                }
            }
        }

        for (SemanticsProperties.PathName relatedName : acceptedSemantics) {
            List<org.opencdmp.commonmodels.models.planblueprintdescriptiontemplatefields.FieldModel> fieldsWithSemantics = this.findSchematicValues(relatedName.getName(), planModel.getPlanBlueprint().getDefinition().getAllField());
            Set<String> values = extractSchematicValues(fieldsWithSemantics, planModel.getProperties().getFieldSets());
            if (!values.isEmpty()) pathToValuesMap.computeIfAbsent(relatedName.getName(), k -> new HashSet<>()).addAll(values);
        }

        for (Map.Entry<String, Set<String>> entry : pathToValuesMap.entrySet()) {
                String name = entry.getKey();
                Set<String> values = entry.getValue();
                for (String value : values) {
                    if(name.equals(SEMANTIC_CREATOR)) resource.addProperty(DC.creator,value);
                    if(name.equals(SEMANTIC_SUBJECT)) resource.addProperty(DC.subject,value);
                    if(name.equals(SEMANTIC_DESCRIPTION)) resource.addProperty(DC.description,value);
                    if(name.equals(SEMANTIC_PUBLISHER)) resource.addProperty(DC.publisher,value);
                    if(name.equals(SEMANTIC_CONTRIBUTOR)) resource.addProperty(DC.contributor,value);
                    if(name.equals(SEMANTIC_TYPE)) resource.addProperty(DC.type,value);
                    if(name.equals(SEMANTIC_FORMAT)) resource.addProperty(DC.format,value);
                    if(name.equals(SEMANTIC_IDENTIFIER)) resource.addProperty(DC.identifier,value);
                    if(name.equals(SEMANTIC_SOURCE)) resource.addProperty(DC.source,value);
                    if(name.equals(SEMANTIC_LANGUAGE)) resource.addProperty(DC.language,value);
                    if(name.equals(SEMANTIC_RELATION)) resource.addProperty(DC.relation,value);
                    if(name.equals(SEMANTIC_COVERAGE)) resource.addProperty(DC.coverage,value);
                    if(name.equals(SEMANTIC_RIGHTS)) resource.addProperty(DC.rights,value);
                }
        }

    }




}



