/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.server.kb.concept;

import com.google.common.collect.ImmutableMap;
import grakn.core.concept.type.AttributeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public abstract class ValueConverter<SOURCE, TARGET>{

    private static Map<AttributeType.DataType<?>, ValueConverter<?, ?>> converters = ImmutableMap.<AttributeType.DataType<?>, ValueConverter<?, ?>>builder()
            .put(AttributeType.DataType.BOOLEAN, new IdentityConverter<Boolean, Boolean>())
            .put(AttributeType.DataType.DATE, new DateConverter())
            .put(AttributeType.DataType.DOUBLE, new DoubleConverter())
            .put(AttributeType.DataType.FLOAT, new FloatConverter())
            .put(AttributeType.DataType.INTEGER, new IntegerConverter())
            .put(AttributeType.DataType.LONG, new LongConverter())
            .put(AttributeType.DataType.STRING, new IdentityConverter<String, String>())
            .build();

    public static <SOURCE, TARGET> ValueConverter<SOURCE, TARGET> of(AttributeType.DataType<TARGET> dataType) {
        ValueConverter<?, ?> converter = converters.get(dataType);
        if (converter == null){
            throw new UnsupportedOperationException("Unsupported DataType: " + dataType.toString());
        }
        return (ValueConverter<SOURCE, TARGET>) converter;
    }

    public abstract TARGET convert(SOURCE value);

    public static class IdentityConverter<SOURCE, TARGET> extends ValueConverter<SOURCE, TARGET> {
        @Override
        public TARGET convert(SOURCE value) { return (TARGET) value;}
    }

    public static class DateConverter extends ValueConverter<Object, LocalDateTime> {

        @Override
        public LocalDateTime convert(Object value) {
            if (value instanceof LocalDateTime){
                return (LocalDateTime) value;
            } else if (value instanceof LocalDate){
                return ((LocalDate) value).atStartOfDay();
            }
            //NB: we are not able to parse ZonedDateTime correctly so leaving that for now
            throw new ClassCastException();
        }
    }

    public static class DoubleConverter extends ValueConverter<Number, Double> {
        @Override
        public Double convert(Number value) {
            return value.doubleValue();
        }
    }

    public static class FloatConverter extends ValueConverter<Number, Float> {
        @Override
        public Float convert(Number value) {
            return value.floatValue();
        }
    }

    public static class IntegerConverter extends ValueConverter<Number, Integer> {
        @Override
        public Integer convert(Number value) {
            if ( value.floatValue() % 1 == 0) return value.intValue();
            throw new ClassCastException();
        }
    }

    public static class LongConverter extends ValueConverter<Number, Long> {
        @Override
        public Long convert(Number value) {
            if ( value.floatValue() % 1 == 0) return value.longValue();
            throw new ClassCastException();
        }
    }
}
