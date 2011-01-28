package com.akiban.cserver.service.memcache;

import com.akiban.ais.model.TableName;
import com.akiban.cserver.RowData;
import com.akiban.cserver.RowDefCache;
import com.akiban.cserver.api.HapiGetRequest;
import com.akiban.cserver.api.HapiProcessor;
import com.akiban.cserver.api.HapiRequestException;
import com.akiban.cserver.service.session.Session;
import com.akiban.cserver.service.session.SessionImpl;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.Assert.*;

public final class AkibanCommandHandlerTest {
    private static final Charset CHARSET = Charset.forName("UTF-8");

    private static class MockedHapiProcessor implements HapiProcessor
    {
        private final String expectedSchema;
        private final String expectedTable;
        private final String expectedColumn;
        private final String expectedValue;

        private MockedHapiProcessor(String expectedSchema, String expectedTable, String expectedColumn, String expectedValue) {
            this.expectedSchema = expectedSchema;
            this.expectedTable = expectedTable;
            this.expectedColumn = expectedColumn;
            this.expectedValue = expectedValue;
        }

        @Override
        public void processRequest(Session session, HapiGetRequest request,
                                   Outputter outputter, OutputStream outputStream)
                throws HapiRequestException
        {
            assertEquals("schema", expectedSchema, request.getSchema());
            assertEquals("select table", expectedTable, request.getTable());
            assertEquals("using table", new TableName(expectedSchema, expectedTable), request.getUsingTable());
            assertEquals("predicate count", 1, request.getPredicates().size());
            HapiGetRequest.Predicate predicate = request.getPredicates().get(0);
            assertEquals("predicate column", expectedColumn, predicate.getColumnName());
            assertEquals("predicate value", expectedValue, predicate.getValue());

            try {
                outputter.output(null, null, outputStream);
            } catch (IOException e) {
                throw new RuntimeException("unexpected", e);
            }
        }
    }

    private static class MockedOutputter implements HapiProcessor.Outputter
    {
        private final String string;
        private final Charset charset;

        private MockedOutputter(String string) {
            this.string = string;
            this.charset = CHARSET;
        }

        @Override
        public void output(RowDefCache rowDefCache, List<RowData> rows, OutputStream outputStream) throws IOException {
            outputStream.write( string.getBytes(charset) );
        }
    }

    @Test
    public void testTwice() throws HapiRequestException {
        Session session = new SessionImpl();
        testWriteBytes(session, "first test");
        testWriteBytes(session, "second test");
    }

    private static void testWriteBytes(Session session, String testString) throws HapiRequestException {
        final byte[] expectedBytes = testString.getBytes(CHARSET);

        final MockedHapiProcessor processor = new MockedHapiProcessor("schema", "table", "column", "value");
        final HapiProcessor.Outputter outputter = new MockedOutputter(testString);

        final byte[] actualBytes = AkibanCommandHandler.getBytesForGets(
                session, "schema:table:column=value",
                processor, outputter
        );

        assertArrayEquals("bytes", expectedBytes, actualBytes);
    }
}
