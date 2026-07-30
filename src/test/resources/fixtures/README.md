# Fixture-Datenbanken

## hibernate-shop.mv.db

H2-File-Datenbank, die von `HibernateEntityFixtureTest` mit Hibernate aus dem Entity-Modell in
`src/test/java/com/pixeldweller/migrape/testsupport/hibernate` erzeugt wird (Schema **und**
Daten). `HibernateFixtureMigrationTest` migriert genau diese Datei nach MariaDB, ohne die
Entity-Klassen zu kennen -- so wie das Werkzeug auch bei einer fremden Anwendung arbeitet.

Die Datei ist absichtlich eingecheckt: nur so kann die Migration gegen ein Schema getestet
werden, das ein ORM erzeugt hat, ohne dass jeder Testlauf von Hibernate abhaengt.

**Nach Aenderungen am Entity-Modell:** `HibernateEntityFixtureTest` ausfuehren (der Test
ueberschreibt die Datei) und die neue Version mit committen. Die erwarteten Tabellen,
Zeilenzahlen und Werte stehen in `testsupport/HibernateFixtureContract.java`.

Nicht von Hand bearbeiten.
