package com.basho.riak.client.api.commands.itest;

import com.basho.riak.client.api.ListException;
import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.commands.buckets.FetchBucketProperties;
import com.basho.riak.client.api.commands.buckets.StoreBucketProperties;
import com.basho.riak.client.api.commands.timeseries.*;
import com.basho.riak.client.core.RiakCluster;
import com.basho.riak.client.core.RiakFuture;
import com.basho.riak.client.core.RiakNode;
import com.basho.riak.client.core.operations.FetchBucketPropsOperation;
import com.basho.riak.client.core.operations.itest.ts.ITestTsBase;
import com.basho.riak.client.core.query.Namespace;
import com.basho.riak.client.core.query.timeseries.*;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.junit.runners.MethodSorters;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Time Series Commands Integration Tests
 *
 * @author Alex Moore <amoore at basho dot com>
 * @author Sergey Galkin <srggal at gmail dot com>
 * @since 2.0.3
 *
 * Schema for the Timeseries table we're using:
 *
 *   CREATE TABLE GeoCheckin&lt;RandomInteger&gt;
 *   (
 *      geohash     varchar   not null,
 *      user        varchar   not null,
 *      time        timestamp not null,
 *      weather     varchar   not null,
 *      temperature double,
 *      uv_index    sint64,
 *      observed    boolean not null,
 *      sensor_data blob,
 *      PRIMARY KEY(
 *          (geohash, user, quantum(time, 15, 'm')),
 *           geohash, user, time)
 *      )
 *   )
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ITestTimeSeries extends ITestTsBase
{
    private final static String tableName = "GeoHash" + new Random().nextInt(Integer.MAX_VALUE);
    private static final String BAD_TABLE_NAME = "GeoChicken";

    private RiakFuture<Void, String> createTableAsync(final RiakClient client, String tableName) throws InterruptedException
    {
        final TableDefinition tableDef = new TableDefinition(tableName, GeoCheckin_1_5_TableDefinition.getFullColumnDescriptions());

        return createTableAsync(client, tableDef);
    }

    @BeforeClass
    public static void SetupCheck()
    {
        assumeTrue("Timeseries 1.5 features not supported in this test environment, skipping tests.", testTs_1_5_Features());
    }

    @Rule
    public ExpectedException thrown= ExpectedException.none();

    @Test
    public void test_a_TestCreateTableAndChangeNVal() throws InterruptedException, ExecutionException
    {
        final RiakClient client = new RiakClient(cluster);
        final RiakFuture<Void, String> resultFuture = createTableAsync(client, tableName);
        resultFuture.await();
        assertFutureSuccess(resultFuture);

        final Namespace namespace = new Namespace(tableName, tableName);
        StoreBucketProperties storeBucketPropsCmd = new StoreBucketProperties.Builder(namespace).withNVal(1).build();
        final RiakFuture<Void, Namespace> storeBucketPropsFuture = client.executeAsync(storeBucketPropsCmd);

        storeBucketPropsFuture.await();
        assertFutureSuccess(storeBucketPropsFuture);

        FetchBucketProperties fetchBucketPropsCmd = new FetchBucketProperties.Builder(namespace).build();
        final RiakFuture<FetchBucketPropsOperation.Response, Namespace> getBucketPropsFuture =
                client.executeAsync(fetchBucketPropsCmd);

        getBucketPropsFuture.await();
        assertFutureSuccess(getBucketPropsFuture);
        assertTrue(1 == getBucketPropsFuture.get().getBucketProperties().getNVal());
    }

    @Test
    public void test_b_TestCreateBadTable() throws InterruptedException
    {
        final RiakClient client = new RiakClient(cluster);
        final RiakFuture<Void, String> resultFuture = createTableAsync(client, tableName);

        resultFuture.await();
        assertFutureFailure(resultFuture);
    }

    @Test
    public void test_c_StoringData() throws ExecutionException, InterruptedException
    {
        RiakClient client = new RiakClient(cluster);

        Store store = new Store.Builder(tableName).withRows(ts_1_5_rows).build();

        RiakFuture<Void, String> execFuture = client.executeAsync(store);

        execFuture.await();
        assertFutureSuccess(execFuture);
    }

    @Test
    public void test_d_TestListingKeysReturnsThem() throws ExecutionException, InterruptedException
    {
        RiakClient client = new RiakClient(cluster);

        ListKeys listKeys = null;
        try
        {
            listKeys = new ListKeys.Builder(tableName).withAllowListing().build();
        }
        catch (ListException ex)
        {
            fail(ex.getMessage());
        }

        final RiakFuture<QueryResult, String> listKeysFuture = client.executeAsync(listKeys);

        listKeysFuture.await();
        assertFutureSuccess(listKeysFuture);

        final QueryResult queryResult = listKeysFuture.get();
        assertTrue(queryResult.getRowsCount() > 0);
    }

    @Test
    public void test_e_QueryingDataNoMatches() throws ExecutionException, InterruptedException
    {
        final String queryText = "select * from " + tableName + " Where time > 1 and time < 10 and user='user1' and geohash='hash1'";
        final QueryResult queryResult = executeQuery(new Query.Builder(queryText));

        assertNotNull(queryResult);
        assertEquals(0, queryResult.getColumnDescriptionsCopy().size());
        assertEquals(0, queryResult.getRowsCount());
    }

    @Test
    public void test_f_QueryingDataWithMinimumPredicate() throws ExecutionException, InterruptedException
    {
        // Timestamp fields lower bounds are inclusive, upper bounds are exclusive
        // Should only return the 2nd row (one from "5 mins ago")
        // If we added 1 to the "now" time, we would get the third row back too.

        final String queryText = "select * from " + tableName + " " +
                "where user = 'user1' and " +
                "geohash = 'hash1' and " +
                "(time > " + tenMinsAgo +" and " +
                "(time < "+ now + ")) ";

        final QueryResult queryResult = executeQuery(new Query.Builder(queryText));

        assertEquals(8, queryResult.getColumnDescriptionsCopy().size());
        assertEquals(1, queryResult.getRowsCount());

        assertRowMatches(ts_1_5_rows.get(1), queryResult.iterator().next());
    }

    @Test
    public void test_g_QueryingDataWithExtraPredicate() throws ExecutionException, InterruptedException
    {
        // Timestamp fields lower bounds are inclusive, upper bounds are exclusive
        // Should only return the 2nd row (one from "5 mins ago")
        // If we added 1 to the "now" time, we would get the third row back too.

        final String queryText = "select * from " + tableName + " " +
                "where user = 'user1' and " +
                "geohash = 'hash1' and " +
                "(time > " + tenMinsAgo +" and " +
                "(time < "+ now + ")) ";

        final QueryResult queryResult = executeQuery(new Query.Builder(queryText));

        assertEquals(8, queryResult.getColumnDescriptionsCopy().size());
        assertEquals(1, queryResult.getRowsCount());

        assertRowMatches(ts_1_5_rows.get(1), queryResult.iterator().next());
    }

    @Test
    public void test_h_QueryingDataAcrossManyQuantum() throws ExecutionException, InterruptedException
    {
        // Timestamp fields lower bounds are inclusive, upper bounds are exclusive
        // Should return the 2nd & 3rd rows. Query should cover 2 quantums at least.

        final String queryText = "select * from " + tableName + " " +
                "where user = 'user1' and " +
                "geohash = 'hash1' and " +
                "time > " + tenMinsAgo +" and " +
                "time < "+ fifteenMinsInFuture + " ";

        final QueryResult queryResult = executeQuery(new Query.Builder(queryText));

        assertEquals(8, queryResult.getColumnDescriptionsCopy().size());
        assertEquals(2, queryResult.getRowsCount());

        final Iterator<? extends Row> itor = queryResult.iterator();
        assertRowMatches(ts_1_5_rows.get(1), itor.next());
        assertRowMatches(ts_1_5_rows.get(2), itor.next());
    }

    @Test
    public void test_i_TestThatNullsAreSavedAndFetchedCorrectly() throws ExecutionException, InterruptedException
    {
        final String queryText = "select temperature from " + tableName + " " +
                "where user = 'user2' and " +
                "geohash = 'hash1' and " +
                "(time > " + (fifteenMinsAgo - 1) +" and " +
                "(time < "+ (now + 1) + ")) ";

        final QueryResult queryResult = executeQuery(new Query.Builder(queryText));

        assertEquals(1, queryResult.getColumnDescriptionsCopy().size());
        assertEquals(ColumnDescription.ColumnType.DOUBLE, queryResult.getColumnDescriptionsCopy().get(0).getType());

        assertEquals(1, queryResult.getRowsCount());
        final Cell resultCell = queryResult.iterator().next().iterator().next();

        assertNull(resultCell);
    }

    @Test
    public void test_j_TestQueryingInvalidTableNameResultsInError() throws ExecutionException, InterruptedException
    {
        RiakClient client = new RiakClient(cluster);

        final String queryText = "select time from GeoChicken";

        Query query = new Query.Builder(queryText).build();
        RiakFuture<QueryResult, String> future = client.executeAsync(query);

        future.await();
        assertFutureFailure(future);
    }

    @Test
    public void test_k_TestStoringDataOutOfOrderResultsInError() throws ExecutionException, InterruptedException
    {
        RiakClient client = new RiakClient(cluster);

        Row row = new Row(Cell.newTimestamp(fifteenMinsAgo), new Cell("hash1"), new Cell("user1"),
                          new Cell("cloudy"), new Cell(79.0));
        Store store = new Store.Builder(BAD_TABLE_NAME).withRow(row).build();

        RiakFuture<Void, String> future = client.executeAsync(store);

        future.await();
        assertFutureFailure(future);
    }

    @Test
    public void test_l_TestFetchingSingleRowsWorks() throws ExecutionException, InterruptedException
    {
        Row expectedRow = ts_1_5_rows.get(7);
        List<Cell> keyCells = expectedRow.getCellsCopy().stream().limit(3).collect(Collectors.toList());

        RiakClient client = new RiakClient(cluster);

        Fetch fetch = new Fetch.Builder(tableName, keyCells).build();

        QueryResult queryResult = client.execute(fetch);

        assertEquals(1, queryResult.getRowsCount());

        Row actualRow = queryResult.getRowsCopy().get(0);
        assertRowMatches(expectedRow, actualRow);
    }

    @Test
    public void test_m_TestFetchingWithNotFoundKeyReturnsNoRows() throws ExecutionException, InterruptedException
    {
        RiakClient client = new RiakClient(cluster);

        final List<Cell> keyCells = Arrays.asList(new Cell("nohash"), new Cell("nouser"),
                                                  Cell.newTimestamp(fifteenMinsAgo));
        Fetch fetch = new Fetch.Builder(tableName, keyCells).build();

        QueryResult queryResult = client.execute(fetch);
        assertEquals(0, queryResult.getRowsCount());
    }

    @Test
    public void test_n_TestDeletingRowRemovesItFromQueries() throws ExecutionException, InterruptedException
    {
        final List<Cell> keyCells = Arrays.asList(new Cell("hash2"), new Cell("user4"),
                                                  Cell.newTimestamp(fiveMinsAgo));

        RiakClient client = new RiakClient(cluster);

        // Assert we have a row
        Fetch fetch = new Fetch.Builder(tableName, keyCells).build();
        QueryResult queryResult = client.execute(fetch);
        assertEquals(1, queryResult.getRowsCount());

        // Delete row
        Delete delete = new Delete.Builder(tableName, keyCells).build();

        final RiakFuture<Void, String> deleteFuture = client.executeAsync(delete);

        deleteFuture.await();
        assertFutureSuccess(deleteFuture);

        // Assert that the row is no longer with us
        Fetch fetch2 = new Fetch.Builder(tableName, keyCells).build();
        QueryResult queryResult2 = client.execute(fetch2);
        assertEquals(0, queryResult2.getRowsCount());
    }

    @Test
    public void test_o_TestDeletingWithNotFoundKeyDoesNotReturnError() throws ExecutionException, InterruptedException
    {
        RiakClient client = new RiakClient(cluster);

        final List<Cell> keyCells = Arrays.asList(new Cell("nohash"), new Cell("nouser"), Cell
                .newTimestamp(fifteenMinsAgo));
        Delete delete = new Delete.Builder(tableName, keyCells).build();

        final RiakFuture<Void, String> deleteFuture = client.executeAsync(delete);

        deleteFuture.await();
        assertFutureFailure(deleteFuture);
    }

    @Test
    public void test_p_TestDescribeTable() throws InterruptedException, ExecutionException
    {
        RiakClient client = new RiakClient(cluster);

        Query query = new Query.Builder("DESCRIBE " + tableName).build();

        final RiakFuture<QueryResult, String> resultFuture = client.executeAsync(query);

        resultFuture.await();
        assertFutureSuccess(resultFuture);

        final QueryResult tableDescription = resultFuture.get();
        assertEquals(8, tableDescription.getRowsCount());
        int numColumnDesc = tableDescription.getColumnDescriptionsCopy().size();
        assertTrue(numColumnDesc == 5 || numColumnDesc == 7 || numColumnDesc == 8);
    }

    @Test
    public void test_q_TestDescribeTableCommand() throws InterruptedException, ExecutionException
    {
        RiakClient client = new RiakClient(cluster);

        DescribeTable describe = new DescribeTable(tableName);

        final RiakFuture<TableDefinition, String> describeFuture = client.executeAsync(describe);

        describeFuture.await();
        assertFutureSuccess(describeFuture);

        final TableDefinition tableDefinition = describeFuture.get();
        final Collection<FullColumnDescription> fullColumnDescriptions = tableDefinition.getFullColumnDescriptions();
        assertEquals(8, fullColumnDescriptions.size());

        TableDefinitionTest.assertFullColumnDefinitionsMatch(GetCreatedTableFullDescriptions(),
                                                             new ArrayList<>(fullColumnDescriptions));
    }

    @Test
    public void test_r_TestDescribeTableCommandForNonExistingTable() throws InterruptedException, ExecutionException
    {
        RiakClient client = new RiakClient(cluster);

        DescribeTable describe = new DescribeTable(BAD_TABLE_NAME);

        final RiakFuture<TableDefinition, String> describeFuture = client.executeAsync(describe);

        describeFuture.await();
        assertFutureFailure(describeFuture);

        final String message = describeFuture.cause().getMessage();
        assertTrue(message.toLowerCase().contains(BAD_TABLE_NAME.toLowerCase()));
        assertTrue(message.toLowerCase().contains("not"));
        assertTrue(message.toLowerCase().contains("active"));
    }

    @Test
    public void test_z_TestPBCErrorsReturnWhenSecurityIsOn() throws InterruptedException, ExecutionException
    {
        assumeTrue(security);

        thrown.expect(ExecutionException.class);
        thrown.expectMessage("Security is enabled, please STARTTLS first");

        // Build connection WITHOUT security
        final RiakNode node = new RiakNode.Builder().withRemoteAddress(hostname).withRemotePort(pbcPort).build();
        final RiakCluster cluster = new RiakCluster.Builder(node).build();
        cluster.start();
        final RiakClient client = new RiakClient(cluster);

        Query query = new Query.Builder("DESCRIBE " + tableName).build();

        client.execute(query);
    }

    private static List<FullColumnDescription> GetCreatedTableFullDescriptions()
    {
        return GeoCheckin_1_5_TableDefinition.getFullColumnDescriptions().stream().collect(Collectors.toList());
    }

    private static <T> List<T> toList(Iterator<T> itor)
    {
        final List<T> r = new LinkedList<>();

        while (itor.hasNext())
        {
            r.add(itor.next());
        }
        return r;
    }

    private static <R1 extends Row, R2 extends Row> void assertRowMatches(R1 expected, R2 actual)
    {
        List<Cell> expectedCells = toList(expected.iterator());
        List<Cell> actualCells = toList(actual.iterator());

        assertEquals(expectedCells.get(0).getVarcharAsUTF8String(), actualCells.get(0).getVarcharAsUTF8String());
        assertEquals(expectedCells.get(1).getVarcharAsUTF8String(), actualCells.get(1).getVarcharAsUTF8String());
        assertEquals(expectedCells.get(2).getTimestamp(),           actualCells.get(2).getTimestamp());
        assertEquals(expectedCells.get(3).getVarcharAsUTF8String(), actualCells.get(3).getVarcharAsUTF8String());

        Cell expectedCell4 = expectedCells.get(4);
        Cell actualCell4 = actualCells.get(4);

        if (expectedCell4 == null)
        {
            assertNull(actualCell4);
        }
        else
        {
            assertEquals(Double.toString(expectedCells.get(4).getDouble()), Double.toString(actualCells.get(4).getDouble()));
        }

        Cell expectedCell5 = expectedCells.get(5);
        Cell actualCell5 = actualCells.get(5);

        if (expectedCell5 == null)
        {
            assertNull(actualCell5);
        }
        else
        {
            assertEquals(expectedCell5.getLong(), actualCell5.getLong());
        }

        assertEquals(expectedCells.get(6).getBoolean(),  actualCells.get(6).getBoolean());

        Cell expectedCell7 = expectedCells.get(7);
        Cell actualCell7 = actualCells.get(7);

        if (expectedCell7 == null)
        {
            assertNull(actualCell7);
        }
        else
        {
            assertEquals(expectedCell7.getVarcharValue(), actualCell7.getVarcharValue());
        }
    }
}
