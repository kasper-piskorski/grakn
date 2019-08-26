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

package grakn.core.graql.reasoner.atom.binary;

import com.google.auto.value.AutoValue;
import grakn.core.concept.ConceptId;
import grakn.core.graql.reasoner.atom.Atomic;
import grakn.core.graql.reasoner.atom.predicate.Predicate;
import grakn.core.graql.reasoner.query.ReasonerQuery;
import graql.lang.property.SubProperty;
import graql.lang.property.VarProperty;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;

import java.util.stream.Collectors;

/**
 *
 * <p>
 * TypeAtom corresponding to graql a SubProperty property.
 * </p>
 *
 *
 */
@AutoValue
public abstract class SubAtom extends OntologicalAtom {

    @Override public abstract Variable getPredicateVariable();
    @Override public abstract Statement getPattern();
    @Override public abstract ReasonerQuery getParentQuery();

    public static SubAtom create(Variable var, Variable pVar, ConceptId predicateId, ReasonerQuery parent) {
        Variable varName = var.asReturnedVar();
        Variable predicateVar = pVar.asReturnedVar();
        return new AutoValue_SubAtom(varName, predicateId, predicateVar, new Statement(varName).sub(new Statement(predicateVar)), parent);
    }

    private static SubAtom create(SubAtom a, ReasonerQuery parent) {
        return create(a.getVarName(), a.getPredicateVariable(), a.getTypeId(), parent);
    }

    @Override
    OntologicalAtom createSelf(Variable var, Variable predicateVar, ConceptId predicateId, ReasonerQuery parent) {
        return create(var, predicateVar, predicateId, parent);
    }

    @Override
    public Atomic copy(ReasonerQuery parent){
        return create(this, parent);
    }

    @Override
    public Class<? extends VarProperty> getVarPropertyClass() {return SubProperty.class;}

    @Override
    public String toString(){
        String typeString = "sub"+ "(" + getVarName() + ", " + getPredicateVariable() +")";
        return typeString + getPredicates().map(Predicate::toString).collect(Collectors.joining(""));
    }
}
