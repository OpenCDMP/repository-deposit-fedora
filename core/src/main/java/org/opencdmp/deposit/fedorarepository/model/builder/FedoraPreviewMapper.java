package org.opencdmp.deposit.fedorarepository.model.builder;


import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.DC_11;
import org.jspecify.annotations.Nullable;
import org.opencdmp.commonmodels.enums.PreviewFieldSource;
import org.opencdmp.commonmodels.enums.PreviewFieldType;
import org.opencdmp.commonmodels.models.deposit.*;
import org.opencdmp.depositbase.repository.DepositPluginSettings;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FedoraPreviewMapper {

    public static final String GROUP_FILES_CODE = "files";
    public static final String GROUP_BASIC_INFORMATION = "basic-information";
    public static final String FIELD_FILES = "files";
    public static final List<String> propertyNames = List.of("title","creator","subject",
            "description","publisher","contributor","date",
            "type","format","identifier","source",
            "language","relation","coverage","rights");
    private static final Set<String> LOCKABLE_FIELDS = Set.of(FedoraBuilder.SETTING_PUBLISHER, FedoraBuilder.SETTING_RIGHTS);


    public DepositPreviewModel toPreview(Model dataset , List<String> uploadFileNames, DepositPluginSettings settings){
        DepositPreviewModel model = new DepositPreviewModel();
        model.setGroups(new ArrayList<>());
        if (uploadFileNames != null && !uploadFileNames.isEmpty()) model.getGroups().add(this.buildFilesGroup(uploadFileNames));
        Resource resource = dataset.getResource("");
        if (!settings.isHidden(GROUP_BASIC_INFORMATION)) {
            PreviewFieldGroup group = buildBasicInformationGroup(resource);
            for (PreviewField field : group.getFields()) {
                if (this.isLocked(field.getCode(), settings)) field.setSource(PreviewFieldSource.System);
            }
            model.getGroups().add(group);
        }
        return model;
    }

    private boolean isLocked(String fieldCode, DepositPluginSettings settings) {
        return fieldCode != null && LOCKABLE_FIELDS.contains(fieldCode) && settings.isLocked(fieldCode);
    }

    private @Nullable Property grabPropertyFromD11(String itemName)
    {
        for (Field field : DC_11.class.getDeclaredFields()) {
            if (field.getType() == Property.class &&
                    Modifier.isStatic(field.getModifiers()) && field.getName().equals(itemName)) {
                try {
                    return (Property) field.get(null);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private PreviewFieldGroup buildBasicInformationGroup(Resource resource) {
        PreviewFieldGroup group = this.buildGroup(GROUP_BASIC_INFORMATION, "Basic information");
        List<PreviewField> fields = group.getFields();
        for(String item: propertyNames)
        {
            Property currentProperty = grabPropertyFromD11(item);
            List<RDFNode> objects = resource.listProperties(currentProperty).mapWith(Statement::getObject).toList();
            RDFNode resolvedObject = objects.isEmpty() ? null : objects.get(0);
            String label = getLabel(item);
            // a property with several values is shown as tags, so an unedited field still sends all of them back
            PreviewFieldType previewFieldType = objects.size() > 1 ? PreviewFieldType.Tags : getPreviewFieldType(item);
            PreviewFieldValue fieldValue = objects.size() > 1
                    ? textListValue(item, objects.stream().map(x -> x.isLiteral() ? x.asLiteral().getString() : x.toString()).toList())
                    : resolvedObject != null ? resolveValue(resolvedObject, item, previewFieldType) : null;
            PreviewField field = this.buildField(item,label, previewFieldType ,PreviewFieldSource.Mapped,false,()-> fieldValue);
            field = customizeField(field,item);
            fields.add(field);
        }
        return group;
    }

    private PreviewField customizeField(PreviewField field, String item) {
        return switch (item) {
            case "title" -> {
                field.setRequired(true);
                yield field;
            }
            default -> field;
        };
    }


    private PreviewFieldValue resolveValue(RDFNode node,String code, PreviewFieldType previewFieldType) {
        if (node == null) return null;
        String stringValue = node.isLiteral() ? node.asLiteral().getString() : node.toString();
        return switch (previewFieldType) {
            case Text -> textValue(code, stringValue);
            case Date -> dateValue(code, stringValue);
            case TextArea -> textValue(code, stringValue);
            case Number -> numberValue(code, Double.valueOf(stringValue));
            case ReadOnly -> textValue(code, stringValue);
            case Boolean -> booleanValue(code, Boolean.valueOf(stringValue));
            default -> {
                PreviewFieldValue value = new PreviewFieldValue();
                value.setCode(code);
                yield value;
            }
        };
    }

    private PreviewFieldType getPreviewFieldType(String propertyName) {
        return switch (propertyName) {
            case "title" -> PreviewFieldType.Text;
            case "description" -> PreviewFieldType.TextArea;
            case "tags" -> PreviewFieldType.Tags;
            case "date" -> PreviewFieldType.Date;
            case "type", "format" -> PreviewFieldType.Text;
            case "identifier", "source" -> PreviewFieldType.Text;
            default -> PreviewFieldType.Text;
        };
    }

    private String getLabel(String propertyName) {
        return switch (propertyName) {
            case "title" -> "Title";
            case "creator" -> "Creator";
            case "subject" -> "Subject";
            case "description" -> "Description";
            case "publisher" -> "Publisher";
            case "contributor" -> "Contributor";
            case "date" -> "Date";
            case "type" -> "Type";
            case "format" -> "Format";
            case "identifier" -> "Identifier";
            case "source" -> "Source";
            case "language" -> "Language";
            case "relation" -> "Relation";
            case "coverage" -> "Coverage";
            case "rights" -> "Rights";
            default -> propertyName;
        };
    }

    private PreviewFieldGroup buildFilesGroup(List<String> uploadFileNames) {
        PreviewFieldGroup group = this.buildGroup(GROUP_FILES_CODE, "Files");
        group.getFields().add(this.buildField(FIELD_FILES, "Files to be uploaded", PreviewFieldType.ReadOnly, PreviewFieldSource.System, false,
                () -> this.textListValue(FIELD_FILES, uploadFileNames)));
        return group;
    }


    public void applyOverrides(Model model, List<PreviewFieldValue> editedFields, List<PreviewGroupValuesModel> editedGroups, DepositPluginSettings settings) {
        if (model == null) return;
        if (editedFields != null) {
            for (PreviewFieldValue previewFieldValue : editedFields) {
                if (previewFieldValue == null || previewFieldValue.getCode() == null || this.isLocked(previewFieldValue.getCode(), settings)) continue;
                this.applyFieldOverride(model.getResource(""), previewFieldValue);
            }
        }
        if (editedGroups != null) {
            for (PreviewGroupValuesModel group : editedGroups) {
                if (group == null || group.getGroupCode() == null) continue;
                String groupCode = group.getGroupCode();
                switch (groupCode) { // if we wanted to add another group we would probably add a new resource
                    case GROUP_BASIC_INFORMATION -> {
                        for (PreviewGroupItemModel item : group.getItems()) {
                            List<PreviewFieldValue> real = item.getValues();
                            real.stream().filter(x -> !this.isLocked(x.getCode(), settings)).forEach(x -> this.applyFieldOverride(model.getResource(""),x));
                        }
                    }
                }
            }
        }
    }


   private void applyFieldOverride(Resource resource, PreviewFieldValue previewFieldValue) {
       Property currentProperty = grabPropertyFromD11(previewFieldValue.getCode());
       Statement resourceProperty = resource.getProperty(currentProperty);
       RDFNode resolvedObject = resourceProperty != null ? resourceProperty.getObject() : null; // essentially logical flow is if we find property with a value remove it
       if(resolvedObject!=null) { // could be simplified by just nuking resource without considering if property has a literal or not
           resource.removeAll(currentProperty);
       }
       List<Literal> newLiterals = constructNewLiterals(resource,previewFieldValue);
       newLiterals.forEach(literal ->
               resource.addProperty(currentProperty, literal)
       );
        // we add a new property anyways
}

    private List<Literal> constructNewLiterals(Resource resource, PreviewFieldValue previewFieldValue){
        if (previewFieldValue.getTextValue() != null) {
            return List.of(
                    resource.getModel().createLiteral(previewFieldValue.getTextValue())
            );
        } else if (previewFieldValue.getBooleanValue() != null) {
            return List.of(
                    resource.getModel().createTypedLiteral(previewFieldValue.getBooleanValue())
            );
        } else if (previewFieldValue.getNumberValue() != null) {
            return List.of(
                    resource.getModel().createTypedLiteral(previewFieldValue.getNumberValue())
            );
        } else if (previewFieldValue.getDateValue() != null) {
            return List.of(
                    resource.getModel().createTypedLiteral(previewFieldValue.getDateValue())
            );
        } else if (previewFieldValue.getTextListValue() != null) {
            return previewFieldValue.getTextListValue()
                    .stream()
                    .map(resource.getModel()::createLiteral)
                    .toList();
        } else {
            return List.of();
        }
    }

    private PreviewFieldGroup buildGroup(String code, String label) {
        PreviewFieldGroup group = new PreviewFieldGroup();
        group.setCode(code);
        group.setLabel(label);
        group.setFields(new ArrayList<>());
        return group;
    }

    private PreviewField buildField(String code, String label, PreviewFieldType type, PreviewFieldSource source, boolean required, Supplier<PreviewFieldValue> valueSupplier) {
        PreviewField field = new PreviewField();
        field.setCode(code);
        field.setLabel(label);
        field.setType(type);
        field.setSource(source);
        field.setRequired(required);
        field.setValue(valueSupplier.get());
        field.setBaseValue(valueSupplier.get());
        return field;
    }

    private PreviewFieldValue textValue(String code, String text) {
        PreviewFieldValue value = new PreviewFieldValue();
        value.setCode(code);
        value.setTextValue(text);
        return value;
    }

    private PreviewFieldValue booleanValue(String code, Boolean value) {
        PreviewFieldValue FieldValue = new PreviewFieldValue();
        FieldValue.setCode(code);
        FieldValue.setBooleanValue(value);
        return FieldValue;
    }

    private PreviewFieldValue numberValue(String code, Double value) {
        PreviewFieldValue FieldValue = new PreviewFieldValue();
        FieldValue.setCode(code);
        FieldValue.setNumberValue(value);
        return FieldValue;
    }

    private PreviewFieldValue textListValue(String code, List<String> texts) {
        PreviewFieldValue FieldValue = new PreviewFieldValue();
        FieldValue.setCode(code);
        FieldValue.setTextListValue(texts != null && !texts.isEmpty() ? texts : null);
        return FieldValue;
    }

    private PreviewFieldValue dateValue(String code, String formattedDate) {
        PreviewFieldValue value = new PreviewFieldValue();
        value.setCode(code);
        if (formattedDate != null && !formattedDate.isBlank()) {
            try {
                value.setDateValue(LocalDate.parse(formattedDate).atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (Exception e) {
                // leave the value empty if the mapped date is not in yyyy-MM-dd form
            }
        }
        return value;
    }
    private PreviewField buildTemplateField(String code, String label, PreviewFieldType type) {
        PreviewField field = new PreviewField();
        field.setCode(code);
        field.setLabel(label);
        field.setType(type);
        field.setSource(PreviewFieldSource.Mapped);
        return field;
    }
}

