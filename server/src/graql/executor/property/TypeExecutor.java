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
import grakn.core.concept.Label;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.graql.exception.GraqlSemanticException;
import grakn.core.graql.executor.WriteExecutor;
import grakn.core.graql.gremlin.EquivalentFragmentSet;
import grakn.core.graql.gremlin.sets.EquivalentFragmentSets;
import grakn.core.graql.reasoner.atom.Atomic;
import grakn.core.graql.reasoner.atom.predicate.IdPredicate;
import grakn.core.graql.reasoner.query.ReasonerQuery;
import graql.lang.property.TypeProperty;
import graql.lang.property.VarProperty;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;

import java.util.Set;

public class TypeExecutor implements PropertyExecutor.Referrable {

    private final Variable var;
    private final TypeProperty property;

    TypeExecutor(Variable var, TypeProperty property) {
        this.var = var;
        this.property = property;
    }

    @Override
    public Set<EquivalentFragmentSet> matchFragments() {
        return ImmutableSet.of(EquivalentFragmentSets.label(property, var, ImmutableSet.of(Label.of(property.name()))));
    }

    @Override
    public Atomic atomic(ReasonerQuery parent, Statement statement, Set<Statement> otherStatements) {
        SchemaConcept schemaConcept = parent.tx().getSchemaConcept(Label.of(property.name()));
        if (schemaConcept == null) throw GraqlSemanticException.labelNotFound(Label.of(property.name()));
        return IdPredicate.create(var.asReturnedVar(), Label.of(property.name()), parent);
    }

    @Override
    public Referrer referrer() {
        return new TypeReferrer();
    }

    private class TypeReferrer implements Referrer {

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
            executor.getBuilder(var).label(Label.of(property.name()));
        }
    }
}
