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

package grakn.core.server.keyspace;

import grakn.core.server.exception.TransactionException;

import javax.annotation.CheckReturnValue;
import java.io.Serializable;
import java.util.regex.Pattern;


/**
 * An identifier for an isolated scope of a data in the database.
 */
public class Keyspace implements Serializable, Comparable<Keyspace> {
    private static final long serialVersionUID = 2726154016735929123L;
    private static final int MAX_LENGTH = 48;

    private final String name;

    public Keyspace(String name) {
        if (name == null) {
            throw new NullPointerException("Null name");
        }
        this.name = name;
    }

    @CheckReturnValue
    public static Keyspace of(String name) {
        if (!isValidName(name)) {
            throw TransactionException.invalidKeyspaceName(name);
        }
        return new Keyspace(name);
    }

    @CheckReturnValue
    public String name() {
        return name;
    }

    @Override
    public final String toString() {
        return name();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Keyspace that = (Keyspace) o;
        return this.name.equals(that.name());
    }

    @Override
    public int hashCode() {
        int h = 1;
        h *= 1000003;
        h ^= this.name.hashCode();
        return h;
    }

    static boolean isValidName(String name) {
        return Pattern.matches("[a-z_][a-z_0-9]*", name) && name.length() <= MAX_LENGTH;
    }

    @Override
    public int compareTo(Keyspace o) {
        if (equals(o)) return 0;
        return name().compareTo(o.name());
    }
}
