package grakn.core.graql.reasoner.rule;

import grakn.core.kb.graql.reasoner.unifier.MultiUnifier;
import java.util.Objects;

public class RuleContext {

    private final InferenceRule rule;
    private final MultiUnifier unifier;

    public RuleContext(InferenceRule rule, MultiUnifier unifier){
        this.rule = rule;
        this.unifier = unifier;
    }

    public InferenceRule rule(){ return rule;}
    public MultiUnifier unifier(){ return unifier;}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleContext that = (RuleContext) o;
        return Objects.equals(rule, that.rule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rule);
    }
}
