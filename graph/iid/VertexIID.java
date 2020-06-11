/*
 * Copyright (C) 2020 Grakn Labs
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
 *
 */

package hypergraph.graph.iid;

import hypergraph.common.exception.Error;
import hypergraph.common.exception.HypergraphException;
import hypergraph.graph.util.KeyGenerator;
import hypergraph.graph.util.Schema;

import static hypergraph.common.collection.Bytes.DOUBLE_SIZE;
import static hypergraph.common.collection.Bytes.LONG_SIZE;
import static hypergraph.common.collection.Bytes.booleanToByte;
import static hypergraph.common.collection.Bytes.byteToBoolean;
import static hypergraph.common.collection.Bytes.bytesToDateTime;
import static hypergraph.common.collection.Bytes.bytesToDouble;
import static hypergraph.common.collection.Bytes.bytesToLong;
import static hypergraph.common.collection.Bytes.bytesToShort;
import static hypergraph.common.collection.Bytes.bytesToString;
import static hypergraph.common.collection.Bytes.dateTimeToBytes;
import static hypergraph.common.collection.Bytes.doubleToBytes;
import static hypergraph.common.collection.Bytes.join;
import static hypergraph.common.collection.Bytes.longToBytes;
import static hypergraph.common.collection.Bytes.stringToBytes;
import static hypergraph.graph.util.Schema.STRING_ENCODING;
import static hypergraph.graph.util.Schema.STRING_MAX_LENGTH;
import static hypergraph.graph.util.Schema.TIME_ZONE_ID;
import static java.util.Arrays.copyOfRange;

public abstract class VertexIID extends IID {

    VertexIID(byte[] bytes) {
        super(bytes);
    }

    public static class Type extends VertexIID {

        public static final int LENGTH = PrefixIID.LENGTH + 2;

        Type(byte[] bytes) {
            super(bytes);
            assert bytes.length == LENGTH;
        }

        public static Type of(byte[] bytes) {
            return new Type(bytes);
        }

        /**
         * Generate an IID for a {@code TypeVertex} for a given {@code Schema}
         *
         * @param keyGenerator to generate the IID for a {@code TypeVertex}
         * @param schema       of the {@code TypeVertex} in which the IID will be used for
         * @return a byte array representing a new IID for a {@code TypeVertex}
         */
        public static Type generate(KeyGenerator keyGenerator, Schema.Vertex.Type schema) {
            return of(join(schema.prefix().bytes(), keyGenerator.forType(PrefixIID.of(schema.prefix().bytes()))));
        }

        public Schema.Vertex.Type schema() {
            return Schema.Vertex.Type.of(bytes[0]);
        }

        @Override
        public String toString() {
            return "[" + PrefixIID.LENGTH + ": " + schema().toString() + "]" +
                    "[" + (VertexIID.Type.LENGTH - PrefixIID.LENGTH) + ": " + bytesToShort(copyOfRange(bytes, PrefixIID.LENGTH, VertexIID.Type.LENGTH)) + "]";
        }
    }

    public static class Thing extends VertexIID {

        public static final int PREFIX_TYPE_LENGTH = PrefixIID.LENGTH + VertexIID.Type.LENGTH;
        public static final int LENGTH = PREFIX_TYPE_LENGTH + 8;

        public Thing(byte[] bytes) {
            super(bytes);
        }

        /**
         * Generate an IID for a {@code ThingVertex} for a given {@code Schema} and {@code TypeVertex}
         *
         * @param keyGenerator to generate the IID for a {@code ThingVertex}
         * @param typeIID      of the {@code TypeVertex} in which this {@code ThingVertex} is an instance of
         * @return a byte array representing a new IID for a {@code ThingVertex}
         */
        public static Thing generate(KeyGenerator keyGenerator, Type typeIID) {
            return new Thing(join(Schema.Vertex.Thing.of(typeIID.schema()).prefix().bytes(),
                                  typeIID.bytes(),
                                  keyGenerator.forThing(typeIID)));
        }

        public static Thing of(byte[] bytes) {
            if (Schema.Vertex.Type.of(bytes[PrefixIID.LENGTH]).equals(Schema.Vertex.Type.ATTRIBUTE_TYPE)) {
                return VertexIID.Attribute.of(bytes);
            } else {
                return new VertexIID.Thing(bytes);
            }
        }

        public Type type() {
            return Type.of(copyOfRange(bytes, PrefixIID.LENGTH, PREFIX_TYPE_LENGTH));
        }

        public Schema.Vertex.Thing schema() {
            return Schema.Vertex.Thing.of(bytes[0]);
        }

        public VertexIID.Attribute asAttribute() {
            if (!schema().equals(Schema.Vertex.Thing.ATTRIBUTE)) {
                throw new HypergraphException(Error.ThingRead.INVALID_IID_CASTING.format(VertexIID.Attribute.class.getCanonicalName()));
            }

            return VertexIID.Attribute.of(bytes);
        }

        @Override
        public String toString() {
            return "[" + PrefixIID.LENGTH + ": " + schema().toString() + "]" +
                    "[" + VertexIID.Type.LENGTH + ": " + type().toString() + "]" +
                    "[" + (LENGTH - PREFIX_TYPE_LENGTH) + ": " + bytesToLong(copyOfRange(bytes, PREFIX_TYPE_LENGTH, LENGTH)) + "]";
        }
    }

    public static abstract class Attribute<VALUE> extends Thing {

        static final int VALUE_TYPE_LENGTH = 1;
        static final int VALUE_INDEX = PrefixIID.LENGTH + VertexIID.Type.LENGTH + VALUE_TYPE_LENGTH;
        private final Schema.ValueType valueType;

        Attribute(byte[] bytes) {
            super(bytes);
            valueType = Schema.ValueType.of(bytes[PREFIX_TYPE_LENGTH]);
        }

        Attribute(Schema.ValueType valueType, Type typeIID, byte[] valueBytes) {
            super(join(
                    Schema.Vertex.Thing.ATTRIBUTE.prefix().bytes(),
                    typeIID.bytes(),
                    valueType.bytes(),
                    valueBytes
            ));
            this.valueType = valueType;
        }

        public static VertexIID.Attribute of(byte[] bytes) {
            switch (Schema.ValueType.of(bytes[PREFIX_TYPE_LENGTH])) {
                case BOOLEAN:
                    return new Attribute.Boolean(bytes);
                case LONG:
                    return new Attribute.Long(bytes);
                case DOUBLE:
                    return new Attribute.Double(bytes);
                case STRING:
                    return new Attribute.String(bytes);
                case DATETIME:
                    return new Attribute.DateTime(bytes);
                default:
                    assert false;
                    return null;
            }
        }

        public abstract VALUE value();

        public Schema.ValueType valueType() {
            return valueType;
        }

        public VertexIID.Attribute.Boolean asBoolean() {
            throw new HypergraphException(Error.ThingRead.INVALID_IID_CASTING.format(Boolean.class.getCanonicalName()));
        }

        public VertexIID.Attribute.Long asLong() {
            throw new HypergraphException(Error.ThingRead.INVALID_IID_CASTING.format(Long.class.getCanonicalName()));
        }

        public VertexIID.Attribute.Double asDouble() {
            throw new HypergraphException(Error.ThingRead.INVALID_IID_CASTING.format(Double.class.getCanonicalName()));
        }

        public VertexIID.Attribute.String asString() {
            throw new HypergraphException(Error.ThingRead.INVALID_IID_CASTING.format(String.class.getCanonicalName()));
        }

        public VertexIID.Attribute.DateTime asDateTime() {
            throw new HypergraphException(Error.ThingRead.INVALID_IID_CASTING.format(DateTime.class.getCanonicalName()));
        }

        @Override
        public java.lang.String toString() {
            Schema.ValueType valueType = Schema.ValueType.of(bytes[VALUE_INDEX - 1]);
            java.lang.String value;
            switch (valueType) {
                case BOOLEAN:
                    value = byteToBoolean(bytes[VALUE_INDEX]).toString();
                    break;
                case LONG:
                    value = "" + bytesToLong(copyOfRange(bytes, VALUE_INDEX, bytes.length));
                    break;
                case DOUBLE:
                    value = "" + bytesToDouble(copyOfRange(bytes, VALUE_INDEX, bytes.length));
                    break;
                case STRING:
                    value = bytesToString(copyOfRange(bytes, VALUE_INDEX, bytes.length), STRING_ENCODING);
                    break;
                case DATETIME:
                    value = bytesToDateTime(copyOfRange(bytes, VALUE_INDEX, bytes.length), TIME_ZONE_ID).toString();
                    break;
                default:
                    value = "";
                    break;
            }

            return "[" + PrefixIID.LENGTH + ": " + Schema.Vertex.Thing.ATTRIBUTE.toString() + "]" +
                    "[" + VertexIID.Type.LENGTH + ": " + type().toString() + "]" +
                    "[" + VALUE_TYPE_LENGTH + ": " + valueType.toString() + "]" +
                    "[" + (bytes.length - VALUE_INDEX) + ": " + value + "]";
        }

        public static class Boolean extends Attribute<java.lang.Boolean> {

            public Boolean(byte[] bytes) {
                super(bytes);
            }

            public Boolean(Type typeIID, boolean value) {
                super(Schema.ValueType.BOOLEAN, typeIID, new byte[]{booleanToByte(value)});
            }

            @Override
            public java.lang.Boolean value() {
                return byteToBoolean(bytes[VALUE_INDEX]);
            }

            @Override
            public Boolean asBoolean() {
                return this;
            }
        }

        public static class Long extends Attribute<java.lang.Long> {

            public Long(byte[] bytes) {
                super(bytes);
            }

            public Long(Type typeIID, long value) {
                super(Schema.ValueType.LONG, typeIID, longToBytes(value));
            }

            @Override
            public java.lang.Long value() {
                return bytesToLong(copyOfRange(bytes, VALUE_INDEX, VALUE_INDEX + LONG_SIZE));
            }

            @Override
            public Long asLong() {
                return this;
            }
        }

        public static class Double extends Attribute<java.lang.Double> {

            public Double(byte[] bytes) {
                super(bytes);
            }

            public Double(Type typeIID, double value) {
                super(Schema.ValueType.DOUBLE, typeIID, doubleToBytes(value));
            }

            @Override
            public java.lang.Double value() {
                return bytesToDouble(copyOfRange(bytes, VALUE_INDEX, VALUE_INDEX + DOUBLE_SIZE));
            }

            @Override
            public Double asDouble() {
                return this;
            }
        }

        public static class String extends Attribute<java.lang.String> {

            public String(byte[] bytes) {
                super(bytes);
            }

            public String(Type typeIID, java.lang.String value) {
                super(Schema.ValueType.STRING, typeIID, stringToBytes(value, STRING_ENCODING));
                assert bytes.length <= STRING_MAX_LENGTH + 1;
            }

            @Override
            public java.lang.String value() {
                return bytesToString(copyOfRange(bytes, VALUE_INDEX, bytes.length), STRING_ENCODING);
            }

            @Override
            public String asString() {
                return this;
            }
        }

        public static class DateTime extends Attribute<java.time.LocalDateTime> {

            public DateTime(byte[] bytes) {
                super(bytes);
            }

            public DateTime(Type typeIID, java.time.LocalDateTime value) {
                super(Schema.ValueType.DATETIME, typeIID, dateTimeToBytes(value, TIME_ZONE_ID));
            }

            @Override
            public java.time.LocalDateTime value() {
                return bytesToDateTime(copyOfRange(bytes, VALUE_INDEX, bytes.length), TIME_ZONE_ID);
            }

            @Override
            public DateTime asDateTime() {
                return this;
            }
        }
    }
}
