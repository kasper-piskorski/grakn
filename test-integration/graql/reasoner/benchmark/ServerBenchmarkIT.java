package grakn.core.graql.reasoner.benchmark;

import com.google.common.collect.Iterables;
import grakn.core.common.util.Profiler;
import grakn.core.concept.Concept;
import grakn.core.concept.ConceptId;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.graql.executor.WriteExecutor;
import grakn.core.graql.reasoner.benchmark.element.Attribute;
import grakn.core.graql.reasoner.benchmark.element.Element;
import grakn.core.graql.reasoner.benchmark.element.Record;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.session.Session;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import graql.lang.pattern.Pattern;
import graql.lang.query.GraqlInsert;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.ClassRule;
import org.junit.Test;

public class ServerBenchmarkIT {

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    private static String generateString(int length) {
        boolean useLetters = true;
        boolean useNumbers = true;
        return RandomStringUtils.random(length, useLetters, useNumbers);
    }

    private static List<Record> generateRecords(int size, int noOfAttributes){
        List<Record> records = new ArrayList<>();
        for(int i = 0 ; i < size ; i++){
            List<Attribute> attributes = new ArrayList<>();
            attributes.add(new Attribute("attribute0", i));
            attributes.add(new Attribute("attribute1", i % 2 ==0? "even" : "odd"));
            for(int j = 2; j < noOfAttributes ; j++){
                attributes.add(new Attribute("attribute" + j, generateString(j+5)));
            }
            records.add(new Record("someEntity", attributes));
        }
        return records;
    }

    public void setup(Session session, int noOfAttributes){
        try(TransactionOLTP tx = session.transaction().write()){
            tx.stream(Graql.parse("match $x isa thing;get;").asGet()).forEach(ans -> ans.get("x").delete());
            EntityType someEntity = tx.putEntityType("someEntity");
            someEntity.has(tx.putAttributeType("attribute0", AttributeType.DataType.INTEGER));
            someEntity.has(tx.putAttributeType("attribute1", AttributeType.DataType.STRING));
            for(int j = 2; j < noOfAttributes ; j++){
                someEntity.has(tx.putAttributeType("attribute" + j, AttributeType.DataType.STRING));
            }
            tx.commit();
        }
    }

    private void executeInsert(List<Pair<Element, Pattern>> elementPatterns, TransactionOLTP tx, Map<String, ConceptId> indexedIds){
        List<graql.lang.statement.Statement> statements = elementPatterns.stream()
                .flatMap(p -> p.getValue().statements().stream())
                .collect(Collectors.toList());
        GraqlInsert insert = Graql.insert(statements);
        if (indexedIds == null) {
            long start = System.currentTimeMillis();
            tx.execute(insert);
            Profiler.updateFromCurrentTime("recordInsert", start);
        } else {
            long start = System.currentTimeMillis();
            Map<Variable, Attribute> varElementMap = elementPatterns.stream()
                    .collect(Collectors.toMap(
                            p -> Iterables.getOnlyElement(p.getValue().statements()).var(),
                            p -> (Attribute) p.getKey())
                    );
            Profiler.updateChildFromCurrentTime("attributeInsert", "mapVarsToElements", start);
            tx.stream(insert)
                    .forEach(ans -> ans.map().forEach((key, value) -> indexedIds.put(varElementMap.get(key).index(), value.id())));
            Profiler.updateFromCurrentTime("attributeInsert", start);
        }
    }

    private <T extends Element> Map<String, ConceptId> insertElements(Session session, List<T> elements,
                                                                      int elementsPerQuery, int insertsPerCommit,
                                                                      boolean indexElements){
        TransactionOLTP tx = session.transaction().write();
        //NB: we batch inserts together to minimise roundtrips
        int inserted = 0;
        List<Pair<Element, Pattern>> elementPatterns = new ArrayList<>();
        Map<String, ConceptId> indexedIds = indexElements? new HashMap<>() : null;
        for(int elementId = 0 ; elementId < elements.size(); elementId++){
            T element = elements.get(elementId);
            elementPatterns.add(new Pair<>(element, element.patternise()));
            if (elementId % elementsPerQuery == 0) {
                executeInsert(elementPatterns, tx, indexedIds);
                if (inserted % insertsPerCommit == 0) {
                    tx.commit();
                    inserted = 0;
                    tx = session.transaction().write();
                }
                inserted++;
                elementPatterns.clear();
            }
        }
        if (!elementPatterns.isEmpty()) executeInsert(elementPatterns, tx, indexedIds);
        tx.commit();
        return indexedIds;
    }

    @Test
    public void singleThreadPerformanceTest(){
        Session session = server.sessionWithNewKeyspace();
        final int noOfAttributes = 10;
        setup(session, noOfAttributes);
        System.out.println("session setup");

        final int elementsPerQuery = 5;
        final int insertsPerCommit = 1000;
        final int noOfRecords = 1000;
        List<Record> records = generateRecords(noOfRecords, noOfAttributes);

        long start = System.currentTimeMillis();
        List<Attribute> attributes = records.stream()
                .flatMap(r -> r.getAttributes().stream())
                .distinct().collect(Collectors.toList());
        System.out.println("Inserting " + attributes.size() + " attributes...");
        Map<String, ConceptId> indexedIds = insertElements(session, attributes, elementsPerQuery, insertsPerCommit, true);

        List<Element> recordsWithIds = records.stream()
                .map(record -> {
                    Map<String, ConceptId> idMap = record.getAttributes().stream()
                            .distinct()
                            .filter(attr -> indexedIds.containsKey(attr.index()))
                            .collect(Collectors.toMap(Attribute::index, attr -> indexedIds.get(attr.index())));
                    return record.withIds(idMap);
                })
                .collect(Collectors.toList());
        System.out.println("Inserting " + recordsWithIds.size() + " records with ids...");
        final int recordInsertsPerCommit = elementsPerQuery * insertsPerCommit;
        insertElements(session, recordsWithIds, 1, recordInsertsPerCommit,false);

        TransactionOLTP tx = session.transaction().read();
        final long noOfConcepts = tx.execute(Graql.parse("compute count in thing;").asComputeStatistics()).get(0).number().longValue();
        tx.close();
        final long totalTime = System.currentTimeMillis() - start;
        Profiler.printTimes();
        System.out.println("sort time: " + WriteExecutor.sortedWritersTime);
        System.out.println("Concepts: " + noOfConcepts + " totalTime: " + totalTime + " throughput: " + noOfConcepts*1000*60/(totalTime));

        session.close();
    }

    @Test
    public void singleThreadPerformanceTest_unfilteredAttributes(){
        Session session = server.sessionWithNewKeyspace();
        final int noOfAttributes = 5;
        setup(session, noOfAttributes);
        System.out.println("session setup");

        final int commitSize = 1000;
        final int txs = 10;
        List<Record> records = generateRecords(commitSize * txs, noOfAttributes);

        final long start = System.currentTimeMillis();
        TransactionOLTP tx = session.transaction().write();
        for (int recordId = 0; recordId < records.size(); recordId++) {
            Record record = records.get(recordId);
            List<Statement> statements = record.getAttributes().stream()
                    .flatMap(attr -> attr.patternise().statements().stream())
                    .collect(Collectors.toList());
            long start2 = System.currentTimeMillis();
            GraqlInsert attributeInsert = Graql.insert(statements).asInsert();
            ConceptMap answer = Iterables.getOnlyElement(tx.execute(attributeInsert));
            Profiler.updateFromCurrentTime("attributeInsert", start2);

            Map<String, ConceptId> idMap = new HashMap<>();
            answer.concepts().stream()
                    .filter(Concept::isAttribute)
                    .map(Concept::asAttribute)
                    .forEach(attr -> {
                        String index = Attribute.index(attr.type().label().getValue(), attr.value().toString());
                        idMap.put(index, attr.id());
                    });
            Record recordWithIds = record.withIds(idMap);

            long start3 = System.currentTimeMillis();
            GraqlInsert recordInsert = Graql.insert(recordWithIds.patternise().statements());
            tx.execute(recordInsert);
            Profiler.updateFromCurrentTime("recordInsert", start3);
            statements.clear();

            if (recordId % commitSize == 0){
                long start4 = System.currentTimeMillis();
                tx.commit();
                Profiler.updateFromCurrentTime("commit", start4);
                tx = session.transaction().write();
            }
        }
        tx.commit();

        tx = session.transaction().read();
        final long noOfConcepts = tx.execute(Graql.parse("compute count in thing;").asComputeStatistics()).get(0).number().longValue();
        tx.close();
        final long totalTime = System.currentTimeMillis() - start;
        Profiler.printTimes();
        System.out.println("sort time: " + WriteExecutor.sortedWritersTime);
        System.out.println("Concepts: " + noOfConcepts + " totalTime: " + totalTime + " throughput: " + noOfConcepts*1000*60/(totalTime));

        session.close();
    }

    @Test
    public void singleThreadPerformanceTest_deduplicatedAttributes(){
        Session session = server.sessionWithNewKeyspace();
        final int noOfAttributes = 5;
        setup(session, noOfAttributes);
        System.out.println("session setup");

        final int commitSize = 1000;
        final int txs = 50;
        List<Record> records = generateRecords(commitSize * txs, noOfAttributes);

        final long start = System.currentTimeMillis();
        TransactionOLTP tx = session.transaction().write();
        Map<String, ConceptId> indexedIds = new HashMap<>();
        for (int recordId = 0; recordId < records.size(); recordId++) {
            Record record = records.get(recordId);
            List<Attribute> attributes = record.getAttributes();

            Map<String, Statement> indexedStatements = attributes.stream()
                    .filter(attr -> !indexedIds.containsKey(attr.index()))
                    .collect(Collectors.toMap(attr -> attr.index(), attr -> Iterables.getOnlyElement(attr.patternise().statements())));
            long start2 = System.currentTimeMillis();
            GraqlInsert attributeInsert = Graql.insert(indexedStatements.values()).asInsert();
            ConceptMap answer = Iterables.getOnlyElement(tx.execute(attributeInsert));
            indexedStatements.forEach((key, value) -> indexedIds.put(key, answer.get(value.var()).id()));
            Profiler.updateFromCurrentTime("attributeInsert", start2);

            Map<String, ConceptId> idMap = new HashMap<>();
            attributes.forEach(attr -> {
                        String index = attr.index();
                        ConceptId id = indexedIds.get(index);
                        idMap.put(index, id != null? id : answer.get(index).id());
                    });
            Record recordWithIds = record.withIds(idMap);

            long start3 = System.currentTimeMillis();
            GraqlInsert recordInsert = Graql.insert(recordWithIds.patternise().statements());
            tx.execute(recordInsert);
            Profiler.updateFromCurrentTime("recordInsert", start3);

            if (recordId % commitSize == 0){
                long start4 = System.currentTimeMillis();
                tx.commit();
                Profiler.updateFromCurrentTime("commit", start4);
                tx = session.transaction().write();
            }
        }
        tx.commit();

        tx = session.transaction().read();
        final long noOfConcepts = tx.execute(Graql.parse("compute count in thing;").asComputeStatistics()).get(0).number().longValue();
        tx.close();
        final long totalTime = System.currentTimeMillis() - start;
        Profiler.printTimes();
        System.out.println("sort time: " + WriteExecutor.sortedWritersTime);
        System.out.println("Concepts: " + noOfConcepts + " totalTime: " + totalTime + " throughput: " + noOfConcepts*1000*60/(totalTime));

        session.close();
    }
}
