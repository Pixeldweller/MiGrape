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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prueft das Auslesen der H2-Metadaten gegen eine echte In-Memory-Datenbank. */
class SchemaReaderTest {

    private Connection h2;
    private String url;

    @BeforeEach
    void openDatabase() throws SQLException {
        url = "jdbc:h2:mem:schemaread-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
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
    @DisplayName("Unterstrich im Tabellennamen zieht keine Spalten aehnlich benannter Tabellen an")
    void doesNotLeakColumnsFromSimilarlyNamedTables() throws Exception {
        // getColumns() erwartet ein LIKE-Pattern: unescaped wuerde "USER_DATA" auch
        // "USERADATA" treffen und dessen Spalten in USER_DATA einmischen.
        execute("CREATE TABLE USER_DATA (ID INT PRIMARY KEY, PAYLOAD VARCHAR(50))");
        execute("CREATE TABLE USERADATA (ID INT PRIMARY KEY, FREMDE_SPALTE VARCHAR(50))");

        Map<String, TableDefinition> tables = readSchema();

        assertEquals(List.of("ID", "PAYLOAD"), columnNames(tables.get("USER_DATA")));
        assertEquals(List.of("ID", "FREMDE_SPALTE"), columnNames(tables.get("USERADATA")));
    }

    @Test
    @DisplayName("Prozentzeichen im Tabellennamen wird ebenfalls maskiert")
    void handlesPercentSignInTableName() throws Exception {
        execute("CREATE TABLE \"RATE%\" (ID INT PRIMARY KEY, VALUE_A INT)");
        execute("CREATE TABLE \"RATE%X\" (ID INT PRIMARY KEY, VALUE_B INT)");

        Map<String, TableDefinition> tables = readSchema();

        assertEquals(List.of("ID", "VALUE_A"), columnNames(tables.get("RATE%")));
    }

    @Test
    @DisplayName("Mehrspaltiger Fremdschluessel wird zu einem Constraint zusammengefasst")
    void groupsCompositeForeignKeyIntoSingleConstraint() throws Exception {
        execute("CREATE TABLE PARENT (K1 INT, K2 INT, PRIMARY KEY (K1, K2))");
        execute("""
                CREATE TABLE CHILD (
                    C1 INT, C2 INT,
                    CONSTRAINT FK_CHILD FOREIGN KEY (C1, C2) REFERENCES PARENT (K1, K2)
                )
                """);

        TableDefinition child = readSchema().get("CHILD");

        assertEquals(1, child.foreignKeys.size(), "getImportedKeys liefert zwei Zeilen fuer einen FK");
        TableDefinition.ForeignKey fk = child.foreignKeys.get(0);
        assertEquals("FK_CHILD", fk.fkName());
        assertEquals(List.of("C1", "C2"), fk.fkColumns());
        assertEquals("PARENT", fk.referencedTable());
        assertEquals(List.of("K1", "K2"), fk.referencedColumns());
    }

    @Test
    void readsSelfReferencingForeignKey() throws Exception {
        execute("""
                CREATE TABLE CATEGORY (
                    ID INT PRIMARY KEY,
                    PARENT_ID INT,
                    CONSTRAINT FK_CATEGORY_PARENT FOREIGN KEY (PARENT_ID) REFERENCES CATEGORY (ID)
                )
                """);

        TableDefinition category = readSchema().get("CATEGORY");

        assertEquals(1, category.foreignKeys.size());
        assertEquals("CATEGORY", category.foreignKeys.get(0).referencedTable());
        assertEquals(List.of("PARENT_ID"), category.foreignKeys.get(0).fkColumns());
    }

    @Test
    void readsCompositePrimaryKeyInKeySeqOrder() throws Exception {
        execute("CREATE TABLE REVIEW (BOOK_ID BIGINT, REVIEWER VARCHAR(50), PRIMARY KEY (BOOK_ID, REVIEWER))");

        assertEquals(List.of("BOOK_ID", "REVIEWER"), readSchema().get("REVIEW").primaryKeyColumns);
    }

    @Test
    @DisplayName("Unique-Constraints behalten ihren Namen (nicht den H2-Indexnamen)")
    void readsUniqueConstraintsWithTheirConstraintNames() throws Exception {
        execute("""
                CREATE TABLE ACCOUNT (
                    ID INT PRIMARY KEY,
                    EMAIL VARCHAR(50),
                    A INT, B INT,
                    CONSTRAINT UQ_EMAIL UNIQUE (EMAIL),
                    CONSTRAINT UQ_AB UNIQUE (A, B)
                )
                """);

        TableDefinition account = readSchema().get("ACCOUNT");

        Map<String, List<String>> byName = account.uniqueConstraints.stream()
                .collect(java.util.stream.Collectors.toMap(
                        TableDefinition.UniqueConstraint::name, TableDefinition.UniqueConstraint::columns));
        assertEquals(Map.of("UQ_EMAIL", List.of("EMAIL"), "UQ_AB", List.of("A", "B")), byName);
    }

    @Test
    @DisplayName("Sekundaerindizes werden gelesen, PK- und FK-Indizes aber nicht dupliziert")
    void readsOnlyRealSecondaryIndexes() throws Exception {
        execute("CREATE TABLE P (ID INT PRIMARY KEY)");
        execute("""
                CREATE TABLE T (
                    ID INT PRIMARY KEY,
                    REF INT,
                    TITLE VARCHAR(50),
                    CONSTRAINT FK_T_P FOREIGN KEY (REF) REFERENCES P (ID)
                )
                """);
        execute("CREATE INDEX IDX_TITLE ON T (TITLE)");

        TableDefinition t = readSchema().get("T");

        assertEquals(1, t.indexes.size(), "Erwartet nur IDX_TITLE, war: " + t.indexes);
        assertEquals("IDX_TITLE", t.indexes.get(0).name());
        assertEquals(List.of("TITLE"), t.indexes.get(0).columns());
    }

    @Test
    void readsColumnDefaultsAndIdentityFlag() throws Exception {
        execute("""
                CREATE TABLE DEFAULTS (
                    ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    ACTIVE BOOLEAN NOT NULL DEFAULT TRUE,
                    LABEL VARCHAR(20) DEFAULT 'unbekannt',
                    AMOUNT INT DEFAULT 42,
                    PLAIN INT
                )
                """);

        TableDefinition table = readSchema().get("DEFAULTS");

        assertTrue(table.columns.get("ID").autoIncrement);
        assertEquals("TRUE", table.columns.get("ACTIVE").defaultExpression);
        assertEquals("'unbekannt'", table.columns.get("LABEL").defaultExpression);
        assertEquals("42", table.columns.get("AMOUNT").defaultExpression);
        assertNull(table.columns.get("PLAIN").defaultExpression);
    }

    @Test
    @DisplayName("Nur das konfigurierte Schema wird gelesen, gleichnamige Tabellen kollidieren nicht")
    void readsOnlyTheConfiguredSchema() throws Exception {
        execute("CREATE SCHEMA ARCHIV");
        execute("CREATE TABLE PUBLIC.BOOK (ID INT PRIMARY KEY, TITLE VARCHAR(50))");
        execute("CREATE TABLE ARCHIV.BOOK (ID INT PRIMARY KEY, ALTE_SPALTE VARCHAR(50))");

        Map<String, TableDefinition> tables = readSchema();

        assertEquals(1, tables.size(), "Erwartet nur PUBLIC.BOOK, war: " + tables.keySet());
        assertEquals(List.of("ID", "TITLE"), columnNames(tables.get("BOOK")));
    }

    @Test
    void parsesEnumLabelsFromTypeNameAsFallback() {
        assertEquals(List.of("DRAFT", "PUBLISHED"), SchemaReader.parseEnumValues("ENUM('DRAFT', 'PUBLISHED')"));
        assertEquals(List.of("PUB'LISHED"), SchemaReader.parseEnumValues("ENUM('PUB''LISHED')"));
        assertEquals(List.of(), SchemaReader.parseEnumValues("CHARACTER VARYING"));
        assertEquals(List.of(), SchemaReader.parseEnumValues(null));
    }

    private Map<String, TableDefinition> readSchema() throws Exception {
        return new SchemaReader(h2, TestConfigs.forH2Only(url)).readSchema();
    }

    private static List<String> columnNames(TableDefinition table) {
        return table.orderedColumns().stream().map(c -> c.name).toList();
    }

    private void execute(String sql) throws SQLException {
        try (Statement stmt = h2.createStatement()) {
            stmt.execute(sql);
        }
    }
}
