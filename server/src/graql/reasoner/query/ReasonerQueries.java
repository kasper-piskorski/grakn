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

package grakn.core.graql.reasoner.query;

import com.google.common.collect.Iterables;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.atom.Atom;
import grakn.core.graql.reasoner.atom.Atomic;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.pattern.Conjunction;
import graql.lang.pattern.Pattern;
import graql.lang.statement.Statement;

import java.util.List;
import java.util.Set;

/**
 * Factory for reasoner queries.
 */
public class ReasonerQueries {

    /**
     *
     * @param pattern conjunctive pattern defining the query
     * @param tx corresponding transaction
     * @return a composite reasoner query constructed from provided conjunctive pattern
     */
    public static CompositeQuery composite(Conjunction<Pattern> pattern, TransactionOLTP tx){
        return new CompositeQuery(pattern, tx).inferTypes();
    }

    /**
     *
     * @param conj conjunctive query corresponding to the +ve part of the composite query
     * @param comp set of queries corresponding to the -ve part of the composite query
     * @param tx transaction the query should be defined over
     * @return corresponding composite query
     */
    public static CompositeQuery composite(ReasonerQueryImpl conj, Set<ResolvableQuery> comp, TransactionOLTP tx){
        return new CompositeQuery(conj, comp, tx).inferTypes();
    }

    /**
     * @param pattern conjunctive pattern defining the query
     * @param tx corresponding transaction
     * @return a resolvable reasoner query constructed from provided conjunctive pattern
     */
    public static ResolvableQuery resolvable(Conjunction<Pattern> pattern, TransactionOLTP tx){
        CompositeQuery query = new CompositeQuery(pattern, tx).inferTypes();
        return query.isAtomic()?
                new ReasonerAtomicQuery(query.getAtoms(), tx) :
                query.isPositive()?
                        query.getConjunctiveQuery() : query;
    }

    /**
     * @param q base query for substitution to be attached
     * @param sub (partial) substitution
     * @return resolvable query with the substitution contained in the query
     */
    public static ResolvableQuery resolvable(ResolvableQuery q, ConceptMap sub){
        return q.withSubstitution(sub).inferTypes();
    }

    public static ReasonerQueryImpl createWithoutRoleInference(Conjunction<Statement> pattern, TransactionOLTP tx) {
        return new ReasonerQueryImpl(pattern, tx);
    }

    /**
     * create a reasoner query from a conjunctive pattern with types inferred
     * @param pattern conjunctive pattern defining the query
     * @param tx corresponding transaction
     * @return reasoner query constructed from provided conjunctive pattern
     */
    public static ReasonerQueryImpl create(Conjunction<Statement> pattern, TransactionOLTP tx) {
        ReasonerQueryImpl query = new ReasonerQueryImpl(pattern, tx).inferTypes();
        return query.isAtomic()?
                new ReasonerAtomicQuery(query.getAtoms(), tx) :
                query;
    }

    /**
     * create a reasoner query from provided set of atomics
     * @param as set of atomics that define the query
     * @param tx corresponding transaction
     * @return reasoner query defined by the provided set of atomics
     */
    public static ReasonerQueryImpl create(Set<Atomic> as, TransactionOLTP tx){
        boolean isAtomic = as.stream().filter(Atomic::isSelectable).count() == 1;
        return isAtomic?
                new ReasonerAtomicQuery(as, tx).inferTypes() :
                new ReasonerQueryImpl(as, tx).inferTypes();
    }

    /**
     * create a reasoner query from provided list of atoms
     * NB: atom constraints (types and predicates, if any) will be included in the query
     * @param as list of atoms that define the query
     * @param tx corresponding transaction
     * @return reasoner query defined by the provided list of atoms together with their constraints (types and predicates, if any)
     */
    public static ReasonerQueryImpl create(List<Atom> as, TransactionOLTP tx){
        boolean isAtomic = as.size() == 1;
        return isAtomic?
                new ReasonerAtomicQuery(Iterables.getOnlyElement(as)).inferTypes() :
                new ReasonerQueryImpl(as, tx).inferTypes();
    }

    /**
     * create a reasoner query by combining an existing query and a substitution
     * @param q base query for substitution to be attached
     * @param sub (partial) substitution
     * @return reasoner query with the substitution contained in the query
     */
    public static ReasonerQueryImpl create(ReasonerQueryImpl q, ConceptMap sub){
        return q.withSubstitution(sub).inferTypes();
    }

    /**
     * @param pattern conjunctive pattern defining the query
     * @param tx corresponding transaction
     * @return atomic query defined by the provided pattern with inferred types
     */
    public static ReasonerAtomicQuery atomic(Conjunction<Statement> pattern, TransactionOLTP tx){
        return new ReasonerAtomicQuery(pattern, tx).inferTypes();
    }

    /**
     * create an atomic query from the provided atom
     * NB: atom constraints (types and predicates, if any) will be included in the query
     * @param atom defining the query
     * @return atomic query defined by the provided atom together with its constraints (types and predicates, if any)
     */
    public static ReasonerAtomicQuery atomic(Atom atom){
        return new ReasonerAtomicQuery(atom).inferTypes();
    }

    /**
     * create a reasoner atomic query from provided set of atomics
     * @param as set of atomics that define the query
     * @param tx corresponding transaction
     * @return reasoner query defined by the provided set of atomics
     */
    public static ReasonerAtomicQuery atomic(Set<Atomic> as, TransactionOLTP tx){
        return new ReasonerAtomicQuery(as, tx).inferTypes();
    }

    /**
     * create an atomic query by combining an existing atomic query and a substitution
     * @param q base query for substitution to be attached
     * @param sub (partial) substitution
     * @return atomic query with the substitution contained in the query
     */
    public static ReasonerAtomicQuery atomic(ReasonerAtomicQuery q, ConceptMap sub){
        return q.withSubstitution(sub).inferTypes();
    }


}
