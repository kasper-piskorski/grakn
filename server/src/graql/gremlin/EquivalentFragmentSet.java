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

package grakn.core.graql.gremlin;

import grakn.core.graql.gremlin.fragment.Fragment;
import graql.lang.property.VarProperty;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * a pattern to match in the graph. comprised of {@code Fragments}, each describing one way to represent the traversal,
 * starting from different variables.
 * <p>
 * A {@code EquivalentFragmentSet} may contain only one {@code Fragment} (e.g. checking the 'id' property), while others may
 * be comprised of two fragments (e.g. $x isa $y, which may start from $x or $y).
 *
 */
public abstract class EquivalentFragmentSet implements Iterable<Fragment> {

    @Override
    @CheckReturnValue
    public final Iterator<Fragment> iterator() {
        return stream().iterator();
    }

    public final Stream<Fragment> stream() {
        return fragments().stream();
    }

    /**
     * @return a set of fragments that this EquivalentFragmentSet contains
     */
    public abstract Set<Fragment> fragments();

    @Nullable
    public abstract VarProperty varProperty();

    @Override
    public String toString() {
        return fragments().stream().map(Object::toString).collect(joining(", ", "{", "}"));
    }
}
