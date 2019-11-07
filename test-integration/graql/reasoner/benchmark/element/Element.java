package grakn.core.graql.reasoner.benchmark.element;

import graql.lang.Graql;
import graql.lang.pattern.Pattern;
import graql.lang.statement.Variable;

public interface Element {

    default Pattern patternise(){ return patternise(Graql.var().var().asReturnedVar());}

    Pattern patternise(Variable var);

    int conceptSize();
}
