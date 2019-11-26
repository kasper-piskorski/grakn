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

package grakn.core.graph.graphdb.schema;

import com.google.common.base.Preconditions;
import org.apache.commons.lang.StringUtils;


public class SchemaElementDefinition {

    private final String name;
    private final long id;


    public SchemaElementDefinition(String name, long id) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name));
        this.name = name;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public long getLongId() {
        return id;
    }


    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object oth) {
        if (this == oth) {
            return true;
        } else if (!getClass().isInstance(oth)) {
            return false;
        }
        return name.equals(((SchemaElementDefinition) oth).name);
    }

    @Override
    public String toString() {
        return name;
    }

}
