package grakn.core.graql.analytics;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import grakn.core.concept.answer.Numeric;
import grakn.core.concept.thing.Entity;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.session.SessionImpl;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import graql.lang.query.GraqlDefine;
import graql.lang.query.GraqlStat;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static junit.framework.TestCase.assertEquals;

public class GraqlStatIT {

    public SessionImpl session;

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    @Before
    public void setUp() {
        session = server.sessionWithNewKeyspace();
    }

    @After
    public void closeSession() { session.close(); }

    @Test
    public void testNullResourceDoesNotBreakAnalytics(){
        final long instanceCount = 100;
        try (TransactionOLTP tx = session.transaction().write()) {
            GraqlDefine graqlDefine = Graql.parse("define " +
                    "person sub entity, has name, has personId;" +
                    "name sub attribute, datatype string;" +
                    "personId sub attribute, datatype long;").asDefine();
            tx.execute(graqlDefine);

            EntityType personType = tx.getEntityType("person");
            AttributeType<Object> nameType = tx.getAttributeType("name");
            AttributeType<Object> idType = tx.getAttributeType("personId");
            for (int i = 0 ; i < instanceCount ; i++) {
                Entity person = personType.create();
                person
                        .has(nameType.create(String.valueOf(i)))
                        .has(idType.create(i));
            }
            tx.commit();

        }

        // the null role-player caused analytics to fail at some stage
        try (TransactionOLTP tx = session.transaction().read()) {
            GraqlStat query = Graql.stat().in(Sets.newHashSet("person", "name", "personId"));
            tx.stream(query).forEach( count -> assertEquals(instanceCount, count.number()));
        }
    }
}
