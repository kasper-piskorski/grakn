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

package grakn.core.graql.reasoner.tree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A really basic Json-style Key-Value store
 *
 * Converts objects to strings with '.toString()'
 *
 * Can handle:
 *   - keys: object with 'toString()'
 *   - values: lists of objects with 'toString()' (including JsonCompatibleKV)
 *   - values: lists of JsonCompatibleKV

 * Replaces "\n" characters in the data with a hard coded replacement value
 */
public class JsonCompatibleKV {
    Map<Object, Object> data;
    public String NEWLINE_TEMPLATE = "||";
    public JsonCompatibleKV() {
        data = new HashMap<>();
    }

    public void put(Object key, Object value) {
        data.put(key, value);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        int counter = 0;
        for (Object key : data.keySet()) {
            builder.append(cleanAndQuote(key.toString()));
            builder.append(" : ");
            Object value = data.get(key);
            if (value instanceof List) {
                writeList((List)value, builder);
            } else {
                builder.append(cleanAndQuote(data.get(key).toString()));
            }

            counter++;
            if (counter < data.size()) {
                builder.append(", \n");
            }
        }
        builder.append("}\n");
        return builder.toString();
    }

    private void writeList(List<Object> list, StringBuilder builder){
        builder.append("[ ");
        int counter = 0;
        for (Object subValue : list) {
            if (subValue instanceof JsonCompatibleKV) {
                builder.append(subValue.toString());
            } else {
                builder.append(cleanAndQuote(subValue.toString()));
            }
            counter++;
            if (counter < list.size()) {
                builder.append(", \n");
            }
        }

        builder.append(" ] ");
    }


    private String cleanAndQuote(String string) {
        return String.format("\"%s\"", string.replace("\n", NEWLINE_TEMPLATE));
    }
}
