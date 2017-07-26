package com.conveyal.gtfs.error;

import com.conveyal.gtfs.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;

/**
 * This is an abstraction for something that stores GTFS loading and validation errors one by one.
 * Currently there's only one implementation, which uses SQL tables.
 * We used to store the errors in plain old Lists, and could make an alternative implementation to do so.
 * We may need to in order to output JSON reports.
 */
public class SQLErrorStorage {

    private static final Logger LOG = LoggerFactory.getLogger(SQLErrorStorage.class);

    private Connection connection; // FIXME should we really be holding on to a single connection from a pool? Pooling prepared statements.

    private int errorCount; // This serves as a unique ID, so it must persist across multiple validator runs.

    private PreparedStatement insertError;
    private PreparedStatement insertRef;
    private PreparedStatement insertInfo;

    // A string to prepend to all table names. This is a unique identifier for the particular feed that is being loaded.
    // Should include any dot or other separator. May also be the empty string if you want no prefix added.
    private String tablePrefix;

    private static final long INSERT_BATCH_SIZE = 500;

    public SQLErrorStorage (DataSource dataSource, String tablePrefix, boolean createTables) {
        // TablePrefix should always be internally generated so doesn't need to be sanitized.
        this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
        errorCount = 0;
        try {
            this.connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        if (createTables) createErrorTables();
        else reconnectErrorTables();
        createPreparedStatements();
    }

    public void storeError (NewGTFSError error) {
        try {
            // Insert one row for the error itself
            insertError.setInt(1, errorCount);
            insertError.setString(2, error.type.name());
            insertError.setString(3, error.badValues);
            insertError.addBatch();
            // Insert rows for informational key-value pairs for this error
            // [NOT IMPLEMENTED, USING STRINGS]
            // Insert rows for entities referenced by this error
            for (NewGTFSError.EntityReference ref : error.referencedEntities) {
                insertRef.setInt(1, errorCount);
                insertRef.setString(2, ref.type.getSimpleName());
                // TODO handle missing (-1?) We generate these so we can safely use negative to mean missing.
                insertRef.setInt(3, ref.lineNumber);
                insertRef.setString(4, ref.id);
                // TODO are seq numbers constrained to be positive? If so we don't need to use objects.
                if (ref.sequenceNumber == null) insertRef.setNull(5, Types.INTEGER);
                else insertRef.setInt(5, ref.sequenceNumber);
                insertRef.addBatch();
            }
            if (errorCount % INSERT_BATCH_SIZE == 0) {
                insertError.executeBatch();
                insertRef.executeBatch();
                insertInfo.executeBatch();
            }
            errorCount += 1;
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    public int getErrorCount () {
        return errorCount;
    }

    public void finish() {
        try {
            // Execute any remaining batch inserts and commit the transaction.
            insertError.executeBatch();
            insertRef.executeBatch();
            insertInfo.executeBatch();
            connection.commit();
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    private void createErrorTables() {
        try {
            Statement statement = connection.createStatement();
            // If tables are dropped, order matters because of foreign keys.
            statement.execute(String.format("create table %serrors (error_id integer primary key, " +
                    "type varchar, problems varchar)", tablePrefix));
            statement.execute(String.format("create table %serror_refs (error_id integer, entity_type varchar, " +
                    "line_number integer, entity_id varchar, sequence_number integer)", tablePrefix));
            statement.execute(String.format("create table %serror_info (error_id integer, key varchar, value varchar)",
                    tablePrefix));
            connection.commit();
            // Keep connection open, closing nulls the wrapped connection and allows it to be reused in the pool.
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    private void createPreparedStatements () {
        try {
            insertError = connection.prepareStatement(
                    String.format("insert into %serrors values (?, ?, ?)", tablePrefix));
            insertRef = connection.prepareStatement(
                    String.format("insert into %serror_refs values (?, ?, ?, ?, ?)", tablePrefix));
            insertInfo = connection.prepareStatement(
                    String.format("insert into %serror_info values (?, ?, ?)", tablePrefix));
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    private void reconnectErrorTables () {
        try {
            Statement statement = connection.createStatement();
            statement.execute(String.format("select max(error_id) from %serrors", tablePrefix));
            ResultSet resultSet = statement.getResultSet();
            resultSet.next();
            errorCount = resultSet.getInt(1);
            LOG.info("Reconnected to errors table, max error ID is {}.", errorCount);
            errorCount += 1; // Error count is zero based, add one to avoid duplicate error key
        } catch (SQLException ex) {
            throw new StorageException("Could not connect to errors table.");
        }
    }

}