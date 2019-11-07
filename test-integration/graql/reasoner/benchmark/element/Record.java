package grakn.core.graql.reasoner.benchmark.element;

import com.google.common.collect.ImmutableMap;
import grakn.core.concept.ConceptId;
import grakn.core.graql.reasoner.utils.Pair;
import graql.lang.Graql;
import graql.lang.pattern.Pattern;
import graql.lang.statement.Statement;
import graql.lang.statement.StatementInstance;
import graql.lang.statement.Variable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class Record implements Element{
    final private List<Attribute> attributes;
    final private String type;
    private final ImmutableMap<String, ConceptId> indexToId;

    public Record(String type, Collection<Attribute> attributes){
        this.type = type;
        this.attributes = new ArrayList<>(attributes);
        this.indexToId = null;
    }
    public Record(String type, Collection<Attribute> attributes, Map<String, ConceptId> map){
        this.type = type;
        this.attributes = new ArrayList<>(attributes);
        this.indexToId = ImmutableMap.copyOf(map);
    }

    public Record withIds(Map<String, ConceptId> map){
        return new Record(this.getType(), this.getAttributes(), map);
    }

    public String getType(){ return type;}
    public List<Attribute> getAttributes(){ return attributes;}

    @Override
    public String toString(){ return getType() + ": " + getAttributes();}

    @Override
    public int conceptSize() { return getAttributes().size() + 1; }

    @Override
    public Pattern patternise(Variable var) {
        if (indexToId == null) return patterniseWithoutIds(var);
        return patterniseWithIds(var);
    }

    private Pattern patterniseWithoutIds(Variable var){
        Statement base = Graql.var(var);
        StatementInstance pattern = base.isa(getType());
        for (Attribute attribute : getAttributes()) {
            Object value = attribute.getValue();
            if (value instanceof String) {
                value = ((String) value).replace("\"", "'");
                pattern = pattern.has(attribute.getType(), (String) value);
            } else if (value instanceof Long) {
                pattern = pattern.has(attribute.getType(), (long) value);
            } else if (value instanceof Double) {
                pattern = pattern.has(attribute.getType(), (double) value);
            } else if (value instanceof Integer) {
                pattern = pattern.has(attribute.getType(), (int) value);
            } else {
                throw new IllegalArgumentException();
            }
        }
        return pattern;
    }

    private Pattern patterniseWithIds(Variable var){
        Statement base = Graql.var(var);
        StatementInstance basePattern = base.isa(getType());
        List<Pattern> patterns = new ArrayList<>();

        List<Pair<Variable, ConceptId>> varToId = new ArrayList<>();
        for (Attribute attribute : getAttributes()) {
            Variable attrVar = Graql.var().var().asReturnedVar();
            Statement statement = Graql.var(attrVar);

            ConceptId id = indexToId.get(attribute.index());
            if (id == null){
                System.out.println("id null for key: " + attribute.index());
            } else {
                basePattern = basePattern.has(attribute.getType(), statement);
                varToId.add(new Pair<>(attrVar, id));
            }
        }
        patterns.add(basePattern);
        varToId.forEach(p -> patterns.add(Graql.var(p.getKey()).id(p.getValue().getValue())));
        return Graql.and(patterns);
    }
}
