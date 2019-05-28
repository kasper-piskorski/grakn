package grakn.core.graql.reasoner.state;

import grakn.core.concept.answer.ConceptMap;
import java.util.Iterator;

public class TransitiveClosureState extends ResolutionState {
    
    private final Iterator<AnswerState> answerStateIterator;

    public TransitiveClosureState(ConceptMap sub, QueryStateBase parent) {

        super(sub, parent);
        answerStateIterator = generateAnswerIterator();
    }

    private Iterator<AnswerState> generateAnswerIterator(){
        //TODO
        return null;
    }

    @Override
    public ResolutionState generateSubGoal() {
        //TODO
        return null;
    }
}
