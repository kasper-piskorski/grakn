package grakn.core.graql.reasoner.state;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import grakn.core.concept.Concept;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.atom.binary.RelationAtom;
import grakn.core.graql.reasoner.cache.IndexedAnswerSet;
import grakn.core.graql.reasoner.explanation.LookupExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.graql.reasoner.utils.TarjanSCC;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.statement.Variable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class TransitiveClosureState extends ResolutionState {

    private final ReasonerAtomicQuery query;
    private final Unifier unifier;
    private final Iterator<AnswerState> answerStateIterator;

    public TransitiveClosureState(ReasonerAtomicQuery q, ConceptMap sub, Unifier u, QueryStateBase parent) {
        super(sub, parent);
        this.query = q;
        this.unifier = u;
        this.answerStateIterator = generateAnswerIterator();
    }

    public static long tarjanTime = 0;

    private Iterator<AnswerState> generateAnswerIterator(){
        long start = System.currentTimeMillis();
        HashMultimap<Concept, Concept> conceptGraph = HashMultimap.create();
        TransactionOLTP tx = query.tx();

        RelationAtom relationAtom = query.getAtom().toRelationAtom();
        Pair<Variable, Variable> varPair = Iterables.getOnlyElement(relationAtom.varDirectionality());
        IndexedAnswerSet answers = tx.queryCache().getEntry(query).cachedElement();
        answers.forEach(ans -> {
            Concept from = ans.get(varPair.getKey());
            Concept to = ans.get(varPair.getValue());
            conceptGraph.put(from, to);
        });

        HashMultimap<Concept, Concept> transitiveClosure = new TarjanSCC<>(conceptGraph).successorMap();
        tarjanTime += System.currentTimeMillis() - start;
        return transitiveClosure.entries().stream()
                .map(e -> new ConceptMap(
                        ImmutableMap.of(varPair.getKey(), e.getKey(), varPair.getValue(), e.getValue()),
                        new LookupExplanation(query.getPattern()))
                )
                .map(ans -> new AnswerState(ans, unifier, getParentState()))
                .iterator();
    }

    @Override
    public ResolutionState generateSubGoal() {
        return answerStateIterator.hasNext()? answerStateIterator.next() : null;
    }
}
