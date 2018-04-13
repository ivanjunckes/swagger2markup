/*
 * Copyright 2017 Robert Winkler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.swagger2markup.internal.adapter;

import io.github.swagger2markup.internal.resolver.DocumentResolver;
import io.github.swagger2markup.internal.type.ArrayType;
import io.github.swagger2markup.internal.type.BasicType;
import io.github.swagger2markup.internal.type.EnumType;
import io.github.swagger2markup.internal.type.MapType;
import io.github.swagger2markup.internal.type.ObjectType;
import io.github.swagger2markup.internal.type.RefType;
import io.github.swagger2markup.internal.type.Type;
import io.github.swagger2markup.markup.builder.MarkupDocBuilder;
import io.swagger.models.properties.AbstractNumericProperty;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.BaseIntegerProperty;
import io.swagger.models.properties.BooleanProperty;
import io.swagger.models.properties.DoubleProperty;
import io.swagger.models.properties.FloatProperty;
import io.swagger.models.properties.IntegerProperty;
import io.swagger.models.properties.LongProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import io.swagger.models.properties.UUIDProperty;
import io.swagger.models.refs.RefFormat;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.RefUtils;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.swagger2markup.internal.utils.RefUtils.computeSimpleRef;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public final class PropertyAdapter {

    private final Schema property;
    private static Logger logger = LoggerFactory.getLogger(PropertyAdapter.class);

    public PropertyAdapter(Schema property) {
        Validate.notNull(property, "property must not be null");
        this.property = property;
    }

    /**
     * Generate a default example value for property.
     *
     * @param property         property
     * @param markupDocBuilder doc builder
     * @return a generated example for the property
     */
    public static Object generateExample(Schema property, MarkupDocBuilder markupDocBuilder) {

        switch (property.getType()) {
            case "integer":
                return 0;
            case "number":
                return 0.0;
            case "boolean":
                return true;
            case "string":
                return "string";
            case "ref":
                if (logger.isDebugEnabled()) { logger.debug("generateExample RefProperty for " + property.getName()); }
                return markupDocBuilder.copy(false).crossReference(property.get$ref()).toString();
            case "array":
                return generateArrayExample(Json.mapper().convertValue(property, ArraySchema.class), markupDocBuilder);
            default:
                return property.getType();
        }
    }

    /**
     * Generate example for an ArrayProperty
     *
     * @param property ArrayProperty to generate example for
     * @param markupDocBuilder MarkupDocBuilder containing all associated settings
     * @return String example
     */
    private static Object generateArrayExample(ArraySchema property, MarkupDocBuilder markupDocBuilder) {
        Schema itemProperty = property.getItems();
        List<Object> exampleArray = new ArrayList<>();

        exampleArray.add(generateExample(itemProperty, markupDocBuilder));
        return exampleArray;
    }

    /**
     * Convert a string {@code value} to specified {@code type}.
     *
     * @param value value to convert
     * @param type  target conversion type
     * @return converted value as object
     */
    public static Object convertExample(String value, String type) {
        if (value == null) {
            return null;
        }

        try {
            switch (type) {
                case "integer":
                    return Integer.valueOf(value);
                case "number":
                    return Float.valueOf(value);
                case "boolean":
                    return Boolean.valueOf(value);
                case "string":
                    return value;
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException(String.format("Value '%s' cannot be converted to '%s'", value, type), e);
        }
    }

    /**
     * Retrieves the type and format of a property.
     *
     * @param definitionDocumentResolver the definition document resolver
     * @return the type of the property
     */
    public Type getType(DocumentResolver definitionDocumentResolver) {
        Type type;
        if (property.get$ref() != null) {
            String ref = computeSimpleRef(property.get$ref());
            type = new RefType(definitionDocumentResolver.apply(ref), new ObjectType(ref, null  /*FIXME, not used for now */));
        } else if (property instanceof ArraySchema) {
            ArraySchema arrayProperty = (ArraySchema) property;
            Schema items = arrayProperty.getItems();
            if (items == null)
                type = new ArrayType(arrayProperty.getTitle(), new ObjectType(null, null)); // FIXME : Workaround for Swagger parser issue with composed models (https://github.com/Swagger2Markup/swagger2markup/issues/150)
            else
                type = new ArrayType(arrayProperty.getTitle(), new PropertyAdapter(items).getType(definitionDocumentResolver));
        } else if (property instanceof MapSchema) {
            MapSchema mapProperty = (MapSchema) property;
            Schema additionalProperties = (Schema) mapProperty.getAdditionalProperties();
            if (additionalProperties == null)
                type = new MapType(mapProperty.getTitle(), new ObjectType(null, null)); // FIXME : Workaround for Swagger parser issue with composed models (https://github.com/Swagger2Markup/swagger2markup/issues/150)
            else
                type = new MapType(mapProperty.getTitle(), new PropertyAdapter(additionalProperties).getType(definitionDocumentResolver));
        } else if (property instanceof StringSchema) {
            StringSchema stringProperty = (StringSchema) property;
            List<String> enums = stringProperty.getEnum();
            if (CollectionUtils.isNotEmpty(enums)) {
                type = new EnumType(stringProperty.getTitle(), enums);
            } else if (isNotBlank(stringProperty.getFormat())) {
                type = new BasicType(stringProperty.getType(), stringProperty.getTitle(), stringProperty.getFormat());
            } else {
                type = new BasicType(stringProperty.getType(), stringProperty.getTitle());
            }
        } else if (property instanceof ObjectSchema) {
            type = new ObjectType(property.getTitle(), ((ObjectSchema) property).getProperties());
        } else {
            if (isNotBlank(property.getFormat())) {
                type = new BasicType(property.getType(), property.getTitle(), property.getFormat());
            } else {
                type = new BasicType(property.getType(), property.getTitle());
            }
        }
        return type;
    }

    /**
     * Retrieves the default value of a property
     *
     * @return the default value of the property
     */
    public Optional<Object> getDefaultValue() {
        return Optional.ofNullable(property.getDefault());
    }

    /**
     * Retrieves the minLength of a property
     *
     * @return the minLength of the property
     */
    public Optional<Integer> getMinlength() {
        return Optional.ofNullable(property.getMinLength());
    }

    /**
     * Retrieves the maxLength of a property
     *
     * @return the maxLength of the property
     */
    public Optional<Integer> getMaxlength() {
        return Optional.ofNullable(property.getMaxLength());
    }

    /**
     * Retrieves the pattern of a property
     *
     * @return the pattern of the property
     */
    public Optional<String> getPattern() {
        return Optional.ofNullable(property.getPattern());
    }

    /**
     * Retrieves the minimum value of a property
     *
     * @return the minimum value of the property
     */
    public Optional<BigDecimal> getMin() {
        return Optional.ofNullable(property.getMinimum());
    }

    /**
     * Retrieves the exclusiveMinimum value of a property
     *
     * @return the exclusiveMinimum value of the property
     */
    public boolean getExclusiveMin() {
        return Optional.ofNullable(property.getExclusiveMinimum()).orElse(false);
    }

    /**
     * Retrieves the minimum value of a property
     *
     * @return the minimum value of the property
     */
    public Optional<BigDecimal> getMax() {
        return Optional.ofNullable(property.getMaximum());
    }

    /**
     * Retrieves the exclusiveMaximum value of a property
     *
     * @return the exclusiveMaximum value of the property
     */
    public boolean getExclusiveMax() {
        return Optional.ofNullable(property.getExclusiveMaximum()).orElse(false);
    }

    /**
     * Return example display string for the given {@code property}.
     *
     * @param generateMissingExamples specifies if missing examples should be generated
     * @param markupDocBuilder        doc builder
     * @return property example display string
     */
    public Optional<Object> getExample(boolean generateMissingExamples, MarkupDocBuilder markupDocBuilder) {
        if (property.getExample() != null) {
            return Optional.ofNullable(property.getExample());
        } else if (property instanceof MapSchema) {
            Schema additionalProperty = (Schema) property.getAdditionalProperties();
            if (additionalProperty.getExample() != null) {
                return Optional.ofNullable(additionalProperty.getExample());
            } else if (generateMissingExamples) {
                Map<String, Object> exampleMap = new HashMap<>();
                exampleMap.put("string", generateExample(additionalProperty, markupDocBuilder));
                return Optional.of(exampleMap);
            }
        } else if (property instanceof ArraySchema) {
            if (generateMissingExamples) {
                Schema itemProperty = ((ArraySchema) property).getItems();
                List<Object> exampleArray = new ArrayList<>();
                exampleArray.add(generateExample(itemProperty, markupDocBuilder));
                return Optional.of(exampleArray);
            }
        } else if (generateMissingExamples) {
            return Optional.of(generateExample(property, markupDocBuilder));
        }

        return Optional.ofNullable(property.getExample());
    }

    /**
     * Checks if a property is read-only.
     *
     * @return true if the property is read-only
     */
    public boolean getReadOnly() {
        return BooleanUtils.isTrue(property.getReadOnly());
    }
}
