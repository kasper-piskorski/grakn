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

package grakn.core.concept.thing.impl;

import grakn.core.graph.util.Encoding;
import grakn.core.graph.vertex.ThingVertex;

import static java.util.Objects.requireNonNull;

public class RoleImpl {

    final ThingVertex vertex;

    private RoleImpl(ThingVertex vertex) {
        this.vertex = requireNonNull(vertex);
    }

    public static RoleImpl of(ThingVertex vertex) {
        return new RoleImpl(vertex);
    }

    void optimise() {
        ThingVertex relation = vertex.ins().edge(Encoding.Edge.Thing.RELATES).from().next();
        ThingVertex player = vertex.ins().edge(Encoding.Edge.Thing.PLAYS).from().next();
        relation.outs().put(Encoding.Edge.Thing.ROLEPLAYER, player, vertex);
    }

    public void delete() {
        ThingVertex relation = vertex.ins().edge(Encoding.Edge.Thing.RELATES).from().next();
        ThingVertex player = vertex.ins().edge(Encoding.Edge.Thing.PLAYS).from().next();
        relation.outs().edge(Encoding.Edge.Thing.ROLEPLAYER, player, vertex).delete();
        vertex.delete();
    }
}
