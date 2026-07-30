# Fixture-Datenbanken

## hibernate-shop.mv.db

H2-File-Datenbank, die von `HibernateEntityFixtureTest` mit Hibernate aus dem Entity-Modell in
`src/test/java/com/pixeldweller/migrape/testsupport/hibernate` erzeugt wird (Schema **und**
Daten). Sie ist die Eingabe fuer zwei weitere Testreihen:

1. `HibernateEntityFixtureTest` -- erzeugt die Datei aus den Entities (mit Hibernate).
2. `HibernateFixtureMigrationTest` -- migriert sie nach MariaDB und vergleicht Struktur und
   jeden Wert per JDBC, ohne die Entity-Klassen zu kennen.
3. `MigratedSchemaWithHibernateTest` -- laedt die migrierte MariaDB wieder mit denselben
   Entities und prueft, ob die Anwendung darauf weiterlaufen wuerde.

Die Datei ist absichtlich eingecheckt: nur so kann die Migration gegen ein Schema getestet
werden, das ein ORM erzeugt hat, ohne dass jeder Testlauf von Hibernate abhaengt.

**Nach Aenderungen am Entity-Modell:** `HibernateEntityFixtureTest` ausfuehren (der Test
ueberschreibt die Datei) und die neue Version mit committen. Die erwarteten Tabellen,
Zeilenzahlen und Werte stehen in `testsupport/HibernateFixtureContract.java`.

Nicht von Hand bearbeiten.
