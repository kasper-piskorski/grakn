package grakn.core.graql.reasoner.benchmark.element;

import graql.lang.Graql;
import graql.lang.pattern.Pattern;
import graql.lang.statement.Variable;
import java.util.Objects;

public class Attribute implements Element{
    private final String type;
    private final Object value;

    public Attribute(String type, Object val){
        this.type = type;
        this.value = val;
    }

    public String getType(){ return type;}
    public Object getValue(){ return value;}

    public String index(){
        String index;
        if (value instanceof String) index = type + "-" + ((String) value).replace("\"", "'");
        else index = type + "-" + value;
        return index;
    }

    public static String index(String label, String value){
        return label + "-" + value;
    }

    public static String index(grakn.core.concept.thing.Attribute attr){
        return attr.type().label().getValue() + "-" + attr.value();
    }

    @Override
    public String toString(){ return getType() + ": " + getValue();}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Attribute attribute = (Attribute) o;
        return type.equals(attribute.type) &&
                value.equals(attribute.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public int conceptSize() { return 1; }

    @Override
    public Pattern patternise() {
        return patternise(Graql.var().var().asReturnedVar());
    }

    @Override
    public Pattern patternise(Variable var) {
        Object value = getValue();
        Pattern pattern;
        if (value instanceof String) {
            value = ((String) value).replace("\"", "'");
            pattern = Graql.parsePattern(var + " \"" + value + "\" isa " + type + ";");
        } else {
            pattern = Graql.parsePattern(var + " " + value + " isa " + type + ";");
        }
        return pattern;
    }
}