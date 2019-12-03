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
 *
 */

package grakn.core.graql.reasoner.atom.predicate;

import grakn.core.kb.concept.api.Concept;
import grakn.core.kb.concept.api.ConceptId;
import grakn.core.kb.concept.api.Label;
import grakn.core.kb.concept.api.SchemaConcept;
import grakn.core.graql.reasoner.ReasonerCheckedException;
import grakn.core.kb.server.exception.GraqlSemanticException;
import grakn.core.kb.graql.reasoner.atom.Atomic;
import grakn.core.kb.graql.reasoner.query.ReasonerQuery;
import grakn.core.kb.server.Transaction;
import graql.lang.Graql;
import graql.lang.property.IdProperty;
import graql.lang.property.ValueProperty;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;

/**
 * Predicate implementation specialising it to be an id predicate. Corresponds to IdProperty.
 */
public class IdPredicate extends Predicate<ConceptId> {

    private IdPredicate(Variable varName, Statement pattern, ReasonerQuery parentQuery, ConceptId predicate) {
        super(varName, pattern, predicate, parentQuery);
    }

    public static IdPredicate create(Statement pattern, ReasonerQuery parent) {
        return new IdPredicate(pattern.var(), pattern, parent, extractPredicate(pattern));
    }

    public static IdPredicate create(Variable varName, Label label, ReasonerQuery parent) {
        return create(createIdVar(varName.asReturnedVar(), label, parent.tx()), parent);
    }

    public static IdPredicate create(Variable varName, ConceptId id, ReasonerQuery parent) {
        return create(createIdVar(varName.asReturnedVar(), id), parent);
    }

    private static IdPredicate create(IdPredicate a, ReasonerQuery parent) {
        return create(a.getPattern(), parent);
    }

    private static ConceptId extractPredicate(Statement var) {
        return var.getProperty(IdProperty.class).map(idProperty -> ConceptId.of(idProperty.id())).orElse(null);
    }

    private static Statement createIdVar(Variable varName, ConceptId typeId) {
        return new Statement(varName).id(typeId.getValue());
    }

    private static Statement createIdVar(Variable varName, Label label, Transaction tx) {
        SchemaConcept schemaConcept = tx.getSchemaConcept(label);
        if (schemaConcept == null) throw GraqlSemanticException.labelNotFound(label);
        return new Statement(varName).id(schemaConcept.id().getValue());
    }

    @Override
    public boolean isAlphaEquivalent(Object obj) {
        if (obj == null || this.getClass() != obj.getClass()) return false;
        if (obj == this) return true;
        Predicate a2 = (Predicate) obj;
        return this.getPredicateValue().equals(a2.getPredicateValue());
    }

    @Override
    public int alphaEquivalenceHashCode() {
        int hashCode = 1;
        hashCode = hashCode * 37 + this.getPredicateValue().hashCode();
        return hashCode;
    }

    @Override
    public boolean isStructurallyEquivalent(Object obj) {
        if (obj == null || this.getClass() != obj.getClass()) return false;
        if (obj == this) return true;
        return true;
    }

    @Override
    public int structuralEquivalenceHashCode() {
        return 1;
    }

    @Override
    public Atomic copy(ReasonerQuery parent) {
        return create(this, parent);
    }

    @Override
    public void checkValid() {
        ConceptId conceptId = getPredicate();
        if (tx().getConcept(conceptId) == null) {
            throw ReasonerCheckedException.idNotFound(conceptId);
        }
    }

    @Override
    public String toString() {
        return "[" + getVarName() + "/" + getPredicateValue() + "]";
    }

    @Override
    public String getPredicateValue() { return getPredicate().getValue();}

    @Override
    public boolean subsumes(Atomic atom){
        return true;
    }

    /**
     * @return corresponding value predicate if transformation exists (id corresponds to an attribute concept)
     */
    public ValuePredicate toValuePredicate() {
        Concept concept = tx().getConcept(this.getPredicate());
        Object value = (concept != null && concept.isAttribute()) ? concept.asAttribute().value() : null;

        if (value != null) {
            return ValuePredicate.create(this.getVarName(),
                                         ValueProperty.Operation.Comparison.of(Graql.Token.Comparator.EQV, value),
                                         this.getParentQuery());
        } else {
            return null;
        }
    }
}
