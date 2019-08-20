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

package grakn.core.graql.executor.property;

import com.google.common.collect.ImmutableSet;
import grakn.core.concept.ConceptId;
import grakn.core.graql.executor.WriteExecutor;
import grakn.core.graql.gremlin.EquivalentFragmentSet;
import grakn.core.graql.gremlin.sets.EquivalentFragmentSets;
import grakn.core.graql.reasoner.atom.Atomic;
import grakn.core.graql.reasoner.atom.predicate.IdPredicate;
import grakn.core.graql.reasoner.query.ReasonerQuery;
import graql.lang.property.IdProperty;
import graql.lang.property.VarProperty;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;

import java.util.Set;

public class IdExecutor implements PropertyExecutor.Referrable {

    private final Variable var;
    private final IdProperty property;
    private final ConceptId id;

    IdExecutor(Variable var, IdProperty property) {
        this.var = var;
        this.property = property;
        this.id = ConceptId.of(property.id());
    }

    @Override
    public Set<EquivalentFragmentSet> matchFragments() {
        return ImmutableSet.of(EquivalentFragmentSets.id(property, var, id));
    }

    @Override
    public Atomic atomic(ReasonerQuery parent, Statement statement, Set<Statement> otherStatements) {
        return IdPredicate.create(var, id, parent);
    }

    @Override
    public Referrer referrer() {
        return new IdReferrer();
    }

    // The WriteExecutor for IdExecutor works for Define, Undefine and Insert queries,
    // because it is used for look-ups of a concept
    private class IdReferrer implements Referrer {

        @Override
        public Variable var() {
            return var;
        }

        @Override
        public VarProperty property() {
            return property;
        }

        @Override
        public void execute(WriteExecutor executor) {
            executor.getBuilder(var).id(id);
        }
    }
}
