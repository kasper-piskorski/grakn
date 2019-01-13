/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2018 Grakn Labs Ltd
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

package grakn.core.graql.internal.reasoner.atom;

import grakn.core.graql.admin.Atomic;
import grakn.core.graql.admin.ReasonerQuery;
import grakn.core.graql.internal.executor.property.PropertyExecutor;
import grakn.core.graql.internal.reasoner.atom.predicate.ValuePredicate;
import grakn.core.graql.query.pattern.Conjunction;
import grakn.core.graql.query.pattern.Statement;
import grakn.core.graql.query.pattern.Variable;
import grakn.core.graql.query.pattern.property.HasAttributeProperty;
import grakn.core.graql.query.pattern.property.ValueProperty;
import grakn.core.graql.query.pattern.property.VarProperty;

import grakn.core.graql.query.predicate.NeqPredicate;
import grakn.core.graql.query.predicate.Predicates;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Factory class for creating {@link Atomic} objects.
 */
public class AtomicFactory {

    /**
     * @param pattern conjunction of patterns to be converted to atoms
     * @param parent query the created atoms should belong to
     * @return set of atoms
     */
    public static Stream<Atomic> createAtoms(Conjunction<Statement> pattern, ReasonerQuery parent) {
        Set<Atomic> atoms = pattern.statements().stream()
                .flatMap(statement -> statement.properties().stream()
                        .map(property -> createAtom(property, statement, pattern.statements(), parent))
                        .filter(Objects::nonNull))
                .collect(Collectors.toSet());

        return atoms.stream()
                .filter(at -> atoms.stream()
                        .filter(Atom.class::isInstance)
                        .map(Atom.class::cast)
                        .flatMap(Atom::getInnerPredicates)
                        .noneMatch(at::equals)
                );
    }

    /**
     * maps a provided var property to a reasoner atom
     *
     * @param property {@link VarProperty} to map
     * @param statement    {@link Statement} this property belongs to
     * @param statements   Vars constituting the pattern this property belongs to
     * @param parent reasoner query this atom should belong to
     * @return created atom
     */
    private static Atomic createAtom(VarProperty property, Statement statement, Set<Statement> statements, ReasonerQuery parent){
        Atomic atomic = PropertyExecutor.create(statement.var(), property)
                .atomic(parent, statement, statements);
        if (atomic == null) return null;
        return statement.isPositive() ? atomic : NegatedAtomic.create(atomic);
    }

    /**
     *
     * @param property
     * @param statement
     * @param parent
     * @return
     */
    public static Atomic valuePredicate(ValueProperty property, Statement statement, ReasonerQuery parent){
        HasAttributeProperty has = statement.getProperties(HasAttributeProperty.class).findFirst().orElse(null);
        Variable var = has != null? has.attribute().var() : statement.var();

        boolean isPositive = !(property.predicate() instanceof NeqPredicate);
        Statement innerStatement = property.predicate().getInnerVar().orElse(null);
        Object value = innerStatement != null? innerStatement : property.predicate().value().orElse(null);

        if (value == null && !isPositive){
            System.out.println();
        }
        Atomic atomic = ValuePredicate.create(var.asUserDefined(), isPositive? property.predicate() : Predicates.eq(value), parent);
        return isPositive? atomic : NegatedAtomic.create(atomic);
    }

    /**
     * looks for appropriate var properties with a specified name among the vars and maps them to ValuePredicates,
     * covers both the case when variable is and isn't user defined
     * @param valueVariable variable name of interest
     * @param valueVar {@link Statement} to look for in case the variable name is not user defined
     * @param vars VarAdmins to look for properties
     * @param parent reasoner query the mapped predicate should belong to
     * @return stream of mapped ValuePredicates
     */
    public static Stream<ValuePredicate> createValuePredicates(Variable valueVariable, Statement valueVar, Set<Statement> vars, ReasonerQuery parent){
        Stream<Statement> sourceVars = valueVar.var().isUserDefinedName()?
                vars.stream().filter(v -> v.var().equals(valueVariable)).filter(v -> v.isPositive() == valueVar.isPositive()) :
                Stream.of(valueVar);
        return sourceVars.flatMap(v -> v.getProperties(ValueProperty.class)
                .map(vp -> createAtom(vp, valueVar, vars, parent))
                .filter(Objects::nonNull)
                .filter(Atomic::isPositive))
                .map( at -> (ValuePredicate) at);
    }

}

