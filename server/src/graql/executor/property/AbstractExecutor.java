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
import grakn.core.concept.Concept;
import grakn.core.concept.type.Type;
import grakn.core.graql.exception.GraqlSemanticException;
import grakn.core.graql.executor.WriteExecutor;
import grakn.core.graql.gremlin.EquivalentFragmentSet;
import grakn.core.graql.reasoner.atom.Atomic;
import grakn.core.graql.reasoner.atom.property.IsAbstractAtom;
import grakn.core.graql.reasoner.query.ReasonerQuery;
import graql.lang.property.AbstractProperty;
import graql.lang.property.VarProperty;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;

import java.util.Set;

import static grakn.core.graql.gremlin.sets.EquivalentFragmentSets.isAbstract;

public class AbstractExecutor implements PropertyExecutor.Definable {

    private final Variable var;
    private final AbstractProperty property;

    AbstractExecutor(Variable var, AbstractProperty property) {
        this.var = var;
        this.property = property;
    }

    @Override
    public Set<EquivalentFragmentSet> matchFragments() {
        return ImmutableSet.of(isAbstract(property, var));
    }

    @Override
    public Atomic atomic(ReasonerQuery parent, Statement statement, Set<Statement> otherStatements) {
        return IsAbstractAtom.create(var, parent);
    }

    @Override
    public Set<PropertyExecutor.Writer> defineExecutors() {
        return ImmutableSet.of(new DefineIsAbstract());
    }

    @Override
    public Set<PropertyExecutor.Writer> undefineExecutors() {
        return ImmutableSet.of(new UndefineIsAbstract());
    }

    private abstract class IsAbstractWriter {

        public Variable var() {
            return var;
        }

        public VarProperty property() {
            return property;
        }

        public Set<Variable> requiredVars() {
            return ImmutableSet.of(var);
        }

        public Set<Variable> producedVars() {
            return ImmutableSet.of();
        }
    }

    private class DefineIsAbstract extends IsAbstractWriter implements PropertyExecutor.Writer {

        @Override
        public void execute(WriteExecutor executor) {
            Concept concept = executor.getConcept(var);
            if (concept.isType()) {
                concept.asType().isAbstract(true);
            } else {
                throw GraqlSemanticException.insertAbstractOnNonType(concept.asSchemaConcept());
            }
        }
    }

    private class UndefineIsAbstract extends IsAbstractWriter implements PropertyExecutor.Writer {

        @Override
        public void execute(WriteExecutor executor) {
            Type type = executor.getConcept(var).asType();
            if (!type.isDeleted()) {
                type.isAbstract(false);
            }
        }
    }
}
