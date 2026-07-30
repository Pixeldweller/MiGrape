package com.pixeldweller.migrape.schema;

import com.pixeldweller.migrape.testsupport.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bildet jeden H2-Typ ueber die <em>echten</em> DatabaseMetaData ab und prueft das erzeugte
 * MariaDB-DDL. Dieser Test ist der Gegenpol zu {@link TypeMapperTest}: dort werden die
 * Metadaten von Hand gesetzt, hier kommen sie aus einer laufenden H2-Instanz. Genau diese
 * Luecke liess sich zuvor nicht bemerken -- z.B. dass ENUM als Types.OTHER und UUID als
 * Types.BINARY gemeldet wird.
 */
class H2MetadataTypeMappingTest {

    private Connection h2;
    private String url;

    @BeforeEach
    void openDatabase() throws SQLException {
        url = "jdbc:h2:mem:typemap-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        h2 = DriverManager.getConnection(url, "sa", "");
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (h2 != null) {
            try (Statement stmt = h2.createStatement()) {
                stmt.execute("SHUTDOWN");
            }
            h2.close();
        }
    }

    @Test
    @DisplayName("Alle H2-Typen werden aus echten Metadaten korrekt nach MariaDB abgebildet")
    void mapsEveryH2TypeFromRealMetadata() throws Exception {
        execute("""
                CREATE TABLE ALL_TYPES (
                    C_BOOLEAN      BOOLEAN,
                    C_TINYINT      TINYINT,
                    C_SMALLINT     SMALLINT,
                    C_INT          INT,
                    C_BIGINT       BIGINT,
                    C_REAL         REAL,
                    C_FLOAT        FLOAT,
                    C_DOUBLE       DOUBLE,
                    C_DECIMAL_SPEC DECIMAL(10,2),
                    C_DECIMAL_ANY  DECIMAL,
                    C_DATE         DATE,
                    C_TIME         TIME,
                    C_TIMESTAMP    TIMESTAMP,
                    C_TIMESTAMP0   TIMESTAMP(0),
                    C_TIMESTAMP_TZ TIMESTAMP WITH TIME ZONE,
                    C_CHAR         CHAR(2),
                    C_VARCHAR      VARCHAR(100),
                    C_VARCHAR_ANY  VARCHAR,
                    C_CLOB         CLOB,
                    C_BLOB         BLOB,
                    C_VARBINARY    VARBINARY(64),
                    C_UUID         UUID,
                    C_ENUM         ENUM('DRAFT', 'PUBLISHED'),
                    C_ARRAY        INT ARRAY
                )
                """);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("C_BOOLEAN", "TINYINT(1)");
        expected.put("C_TINYINT", "TINYINT");
        expected.put("C_SMALLINT", "SMALLINT");
        expected.put("C_INT", "INT");
        expected.put("C_BIGINT", "BIGINT");
        expected.put("C_REAL", "FLOAT");
        // H2 FLOAT ist doppelt genau -- darf nicht auf MariaDB FLOAT (4 Byte) landen
        expected.put("C_FLOAT", "DOUBLE");
        expected.put("C_DOUBLE", "DOUBLE");
        expected.put("C_DECIMAL_SPEC", "DECIMAL(10,2)");
        expected.put("C_DECIMAL_ANY", "DECIMAL(65,0)");
        expected.put("C_DATE", "DATE");
        expected.put("C_TIME", "TIME");
        expected.put("C_TIMESTAMP", "DATETIME(6)");
        expected.put("C_TIMESTAMP0", "DATETIME");
        expected.put("C_TIMESTAMP_TZ", "DATETIME(6)");
        expected.put("C_CHAR", "CHAR(2)");
        expected.put("C_VARCHAR", "VARCHAR(100)");
        // VARCHAR ohne Laenge: TEXT wuerde bei 64 KB abschneiden
        expected.put("C_VARCHAR_ANY", "LONGTEXT");
        expected.put("C_CLOB", "LONGTEXT");
        expected.put("C_BLOB", "LONGBLOB");
        expected.put("C_VARBINARY", "VARBINARY(64)");
        expected.put("C_UUID", "CHAR(36)");
        expected.put("C_ENUM", "ENUM('DRAFT', 'PUBLISHED')");
        expected.put("C_ARRAY", "LONGTEXT");

        TableDefinition table = readTable("ALL_TYPES");

        Map<String, String> actual = new LinkedHashMap<>();
        for (ColumnDefinition col : table.orderedColumns()) {
            actual.put(col.name, TypeMapper.toMariaDbType(col));
        }

        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("ENUM-Labels kommen exakt aus H2, auch mit Sonderzeichen im Label")
    void readsEnumLabelsIncludingQuotes() throws Exception {
        execute("CREATE TABLE E (STATUS ENUM('DRAFT', 'PUB''LISHED', 'Ä-Ö'))");

        ColumnDefinition status = readTable("E").columns.get("STATUS");

        assertEquals(java.util.List.of("DRAFT", "PUB'LISHED", "Ä-Ö"), status.enumValues);
        assertEquals("ENUM('DRAFT', 'PUB''LISHED', 'Ä-Ö')", TypeMapper.toMariaDbType(status));
    }

    private TableDefinition readTable(String tableName) throws Exception {
        return new SchemaReader(h2, TestConfigs.forH2Only(url)).readSchema().get(tableName);
    }

    private void execute(String sql) throws SQLException {
        try (Statement stmt = h2.createStatement()) {
            stmt.execute(sql);
        }
    }
}
