package co.streamx.fluent.JPA;

import static co.streamx.fluent.SQL.AggregateFunctions.ROW_NUMBER;
import static co.streamx.fluent.SQL.AggregateFunctions.SUM;
import static co.streamx.fluent.SQL.Directives.aggregateBy;
import static co.streamx.fluent.SQL.Directives.alias;
import static co.streamx.fluent.SQL.Directives.subQuery;
import static co.streamx.fluent.SQL.Library.pick;
import static co.streamx.fluent.SQL.Operators.BETWEEN;
import static co.streamx.fluent.SQL.Operators.lessEqual;
import static co.streamx.fluent.SQL.Oracle.SQL.registerVendorCapabilities;
import static co.streamx.fluent.SQL.SQL.BY;
import static co.streamx.fluent.SQL.SQL.FROM;
import static co.streamx.fluent.SQL.SQL.PARTITION;
import static co.streamx.fluent.SQL.SQL.SELECT;
import static co.streamx.fluent.SQL.SQL.WHERE;
import static co.streamx.fluent.SQL.ScalarFunctions.CASE;
import static co.streamx.fluent.SQL.ScalarFunctions.CURRENT_DATE;
import static co.streamx.fluent.SQL.ScalarFunctions.WHEN;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import co.streamx.fluent.notation.Tuple;
import lombok.Data;
import lombok.Getter;

public class StackOverflow implements CommonTest {

    @BeforeAll
    public static void init() {
        registerVendorCapabilities(FluentJPA::setCapabilities);
    }

    private EntityManager em;

    @Entity
    @Data
    public static class Location {
        @Id
        private int id;
        private String location;

        @ManyToOne
        @JoinColumn(name = "label_id")
        private Translation translation;
    }

    @Entity
    @Data
    public static class Translation {
        @Id
        private int id;
        private String english;
        private String german;
    }

    @Tuple
    @Getter
    public static class TranslatedLocation {
        @Id
        private int id;
        private String location;
        private String label;
    }

    // https://stackoverflow.com/questions/57361456/java-persistence-join-different-columns-dependent-on-runtime-parameter
    @Test
    public void differentColumns() {
        int langCode = 1;
        FluentQuery query = FluentJPA.SQL((Location l,
                                           Translation t) -> {

            String trans = CASE(WHEN(langCode == 1).THEN(t.getEnglish()).ELSE(t.getGerman())).END();
            String label = alias(trans, TranslatedLocation::getLabel);

            SELECT(l.getId(), l.getLocation(), label);
            FROM(l).JOIN(t).ON(l.getTranslation() == t);

        });

        String expected = "SELECT t0.id, t0.location, CASE WHEN (?1 = 1)  THEN t1.english  ELSE t1.german   END   AS label "
                + "FROM Location t0  INNER JOIN Translation t1  ON (t0.label_id = t1.id)";

        assertQuery(query, expected);
    }

    public List<TranslatedLocation> getTranslatedLocations(int langCode) {
        FluentQuery query = FluentJPA.SQL((Location l,
                                           Translation t) -> {

            String trans = CASE(WHEN(langCode == 1).THEN(t.getEnglish()).ELSE(t.getGerman())).END();
            String label = alias(trans, TranslatedLocation::getLabel);

            SELECT(l.getId(), l.getLocation(), label);
            FROM(l).JOIN(t).ON(l.getTranslation() == t);

        });
        query.createQuery(em).executeUpdate();
        return query.createQuery(em, TranslatedLocation.class).getResultList();
    }

    @Entity
    @Data
    @Table(name = "PriceTags")
    public static class PriceTag {
        @Id
        private int id;
        private int goodsId;
        private int price;
        private Date updatedDate;
    }

    @Test
    // https://stackoverflow.com/questions/57351634/is-there-a-way-to-get-the-latest-entry-that-is-less-or-equal-to-the-current-time
    public void latestEntry() {

        FluentQuery query = FluentJPA.SQL((PriceTag tag) -> {

            Long rowNumber = aggregateBy(ROW_NUMBER())
                    .OVER(PARTITION(BY(tag.getGoodsId())).ORDER(BY(tag.getUpdatedDate()).DESC()))
                    .AS();

            SELECT(tag);
            FROM(tag);
            WHERE(lessEqual(tag.getUpdatedDate(), CURRENT_DATE()) && rowNumber == 1);

        });

        String expected = "SELECT t0.* " + "FROM PriceTags t0 "
                + "WHERE ((t0.updated_date <= CURRENT_DATE   ) AND ( ROW_NUMBER()  OVER(PARTITION BY  t0.goods_id   ORDER BY  t0.updated_date  DESC   ) = 1))";

        assertQuery(query, expected);
    }

    @Entity
    @Data // lombok
    public static class PymtEntity {
        @Id
        private int id;
        private int feeAmt;
        private int tranAmt;
        private Timestamp tranCaptrTm;
        private String tranTypeCde;
        private int payeeId;
        private String respCde;
    }

    @Test
    // https://stackoverflow.com/questions/57311620/create-hibernate-query-to-perform-sum-of-two-select-query-columns
    public void sumTwoColumns() {

        Date from = null;
        Date to = null;
        int md = 0;
        String re = null;

        FluentQuery query = FluentJPA.SQL(() -> {
            PymtEntity common = subQuery((PymtEntity pymt) -> {
                SELECT(pymt);
                FROM(pymt);
                WHERE(BETWEEN(pymt.getTranCaptrTm(), from, to) && pymt.getPayeeId() == md && pymt.getRespCde() == re);
            });

            PymtEntity first = subQuery(() -> {
                SELECT(common);
                FROM(common);
                WHERE(common.getTranTypeCde() == "003");
            });

            PymtEntity second = subQuery(() -> {
                SELECT(common);
                FROM(common);
                WHERE(common.getTranTypeCde() != "003");
            });

            SELECT(SUM(pick(first, SUM(first.getFeeAmt() + first.getTranAmt()))
                    - pick(second, SUM(second.getTranAmt() - second.getFeeAmt()))));
        });

        String expected = "SELECT SUM(((SELECT SUM((q3.fee_amt + q3.tran_amt)) " + "FROM (SELECT q2.* "
                + "FROM (SELECT t0.* " + "FROM PymtEntity t0 "
                + "WHERE ((t0.tran_captr_tm BETWEEN ?1 AND ?2 ) AND ((t0.payee_id = ?3) AND (t0.resp_cde = ?4))) ) q2 "
                + "WHERE (q2.tran_type_cde = '003') ) q3 ) - (SELECT SUM((q4.tran_amt - q4.fee_amt)) "
                + "FROM (SELECT q2.* " + "FROM (SELECT t0.* " + "FROM PymtEntity t0 "
                + "WHERE ((t0.tran_captr_tm BETWEEN ?1 AND ?2 ) AND ((t0.payee_id = ?3) AND (t0.resp_cde = ?4))) ) q2 "
                + "WHERE (q2.tran_type_cde <> '003') ) q4 )))";

        assertQuery(query, expected);
    }

    public int sumTwoColumns(Date from,
                             Date to,
                             int md,
                             String re) {
        FluentQuery query = FluentJPA.SQL(() -> {
            PymtEntity common = subQuery((PymtEntity pymt) -> {
                SELECT(pymt);
                FROM(pymt);
                WHERE(BETWEEN(pymt.getTranCaptrTm(), from, to) && pymt.getPayeeId() == md && pymt.getRespCde() == re);
            });

            PymtEntity first = subQuery(() -> {
                SELECT(common);
                FROM(common);
                WHERE(common.getTranTypeCde() == "003");
            });

            PymtEntity second = subQuery(() -> {
                SELECT(common);
                FROM(common);
                WHERE(common.getTranTypeCde() != "003");
            });

            SELECT(SUM(pick(first, SUM(first.getFeeAmt() + first.getTranAmt()))
                    - pick(second, SUM(second.getTranAmt() - second.getFeeAmt()))));
        });

        return query.createQuery(em, Integer.class).getSingleResult();
    }
}