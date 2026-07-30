package com.pixeldweller.migrape.db;

import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.testsupport.TestConfigs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.export.SslMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prueft die SSL-Konfiguration nicht nur gegen sich selbst, sondern laesst die erzeugten
 * Optionen von {@link Configuration#parse} des echten MariaDB-Treibers lesen. Ein Tippfehler
 * in einem Optionsnamen (z.B. "sslmode" statt "sslMode") faellt dadurch hier auf und nicht
 * erst beim Kunden, wo die Verbindung dann still unverschluesselt zustande kaeme.
 *
 * Das Zertifikatsmaterial wird einmalig mit keytool erzeugt, damit echte Dateien geparst werden.
 */
class MariaDbSslConfigTest {

    private static final String URL = "jdbc:mariadb://db.example.com:3306/zieldb";

    @TempDir
    static Path certDir;

    private static Path clientP12;
    private static Path clientCertPem;
    private static Path clientKeyPem;
    private static final String P12_PASSWORD = "changeit";

    @BeforeAll
    static void createCertificateMaterial() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().startsWith("win") ? "keytool.exe" : "keytool");
        assumeTrue(Files.isExecutable(keytool), "keytool aus dem JDK nicht gefunden");

        clientP12 = certDir.resolve("client.p12");
        Process process = new ProcessBuilder(keytool.toString(),
                "-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=migrape-test",
                "-keystore", clientP12.toString(), "-storetype", "PKCS12",
                "-storepass", P12_PASSWORD, "-keypass", P12_PASSWORD)
                .redirectErrorStream(true)
                .start();
        assertEquals(0, process.waitFor(), "keytool fehlgeschlagen: "
                + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(clientP12)) {
            keyStore.load(in, P12_PASSWORD.toCharArray());
        }
        Certificate cert = keyStore.getCertificate("client");
        PrivateKey key = (PrivateKey) keyStore.getKey("client", P12_PASSWORD.toCharArray());

        clientCertPem = writePem(certDir.resolve("client-cert.pem"), "CERTIFICATE", cert.getEncoded());
        // getEncoded() liefert PKCS#8 -- genau das Format, das die Konfiguration erwartet.
        clientKeyPem = writePem(certDir.resolve("client-key.pem"), "PRIVATE KEY", key.getEncoded());
    }

    private static Path writePem(Path file, String type, byte[] der) throws Exception {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        Files.writeString(file, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
        return file;
    }

    private static Properties props(String... keyValuePairs) {
        Properties p = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            p.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return p;
    }

    /** Wendet die Konfiguration an und laesst das Ergebnis vom Treiber interpretieren. */
    private static Configuration driverConfig(Properties configProperties) throws Exception {
        Properties driverProperties = new Properties();
        MariaDbSslConfig.from(configProperties).applyTo(driverProperties);
        return Configuration.parse(URL, driverProperties);
    }

    // ---- Standardfall: nichts konfiguriert ----

    @Test
    @DisplayName("Ohne maria.ssl.* wird keine einzige Treiberoption gesetzt")
    void keineOptionenOhneKonfiguration() {
        Properties driverProperties = new Properties();
        MariaDbSslConfig.from(props("maria.url", URL)).applyTo(driverProperties);
        assertTrue(driverProperties.isEmpty(), "unerwartete Optionen: " + driverProperties);
    }

    @Test
    void beschreibungOhneKonfigurationNenntDieUrl() {
        assertTrue(MariaDbSslConfig.from(new Properties()).describe().contains("maria.url"));
    }

    // ---- Modus ----

    @Test
    void modusWirdAlsSslModeUebergeben() throws Exception {
        assertEquals(SslMode.VERIFY_FULL, driverConfig(props("maria.ssl.mode", "verify-full")).sslMode());
        assertEquals(SslMode.VERIFY_CA, driverConfig(props("maria.ssl.mode", "verify-ca")).sslMode());
        assertEquals(SslMode.TRUST, driverConfig(props("maria.ssl.mode", "trust")).sslMode());
        assertEquals(SslMode.DISABLE, driverConfig(props("maria.ssl.mode", "disable")).sslMode());
    }

    @Test
    @DisplayName("maria.ssl.enabled=true ist die Kurzform fuer verify-full")
    void enabledKurzform() throws Exception {
        assertEquals(SslMode.VERIFY_FULL, driverConfig(props("maria.ssl.enabled", "true")).sslMode());
        assertEquals(SslMode.DISABLE, driverConfig(props("maria.ssl.enabled", "false")).sslMode());
    }

    @Test
    void grossKleinschreibungImModusIstEgal() throws Exception {
        assertEquals(SslMode.VERIFY_FULL, driverConfig(props("maria.ssl.mode", "VERIFY-FULL")).sslMode());
    }

    @Test
    void ungueltigerModusWirdGemeldet() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MariaDbSslConfig.from(props("maria.ssl.mode", "required")));
        assertTrue(e.getMessage().contains("verify-full"), e.getMessage());
    }

    @Test
    void ungueltigesEnabledWirdGemeldet() {
        assertThrows(IllegalArgumentException.class,
                () -> MariaDbSslConfig.from(props("maria.ssl.enabled", "ja")));
    }

    // ---- CA-Zertifikat ----

    @Test
    void caWirdAlsServerSslCertUebergeben() throws Exception {
        Configuration c = driverConfig(props(
                "maria.ssl.mode", "verify-full",
                "maria.ssl.ca", clientCertPem.toString()));
        assertEquals(clientCertPem.toString(), c.serverSslCert());
    }

    @Test
    void classpathUndInlineCaWerdenNichtAlsDateiGeprueft() throws Exception {
        assertEquals("classpath:ca.pem", driverConfig(props(
                "maria.ssl.mode", "verify-ca",
                "maria.ssl.ca", "classpath:ca.pem")).serverSslCert());
    }

    @Test
    void fehlendeCaDateiWirdGemeldet() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MariaDbSslConfig.from(props(
                        "maria.ssl.mode", "verify-ca",
                        "maria.ssl.ca", certDir.resolve("gibtsnicht.pem").toString())));
        assertTrue(e.getMessage().contains("maria.ssl.ca"), e.getMessage());
    }

    // ---- Client-Zertifikat aus einem Keystore ----

    @Test
    void keystoreWirdMitPasswortUndTypUebergeben() throws Exception {
        Properties driverProperties = new Properties();
        MariaDbSslConfig.from(props(
                "maria.ssl.mode", "verify-full",
                "maria.ssl.keystore", clientP12.toString(),
                "maria.ssl.keystore.password", P12_PASSWORD,
                "maria.ssl.keystore.type", "PKCS12",
                "maria.ssl.key.password", P12_PASSWORD)).applyTo(driverProperties);

        Configuration c = Configuration.parse(URL, driverProperties);
        assertEquals(clientP12.toString(), c.keyStore());
        assertEquals(P12_PASSWORD, c.keyStorePassword());
        assertEquals("PKCS12", c.keyStoreType());
        assertEquals(P12_PASSWORD, c.keyPassword());
    }

    @Test
    void fehlendeKeystoreDateiWirdGemeldet() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MariaDbSslConfig.from(props(
                        "maria.ssl.mode", "verify-full",
                        "maria.ssl.keystore", certDir.resolve("gibtsnicht.p12").toString())));
        assertTrue(e.getMessage().contains("maria.ssl.keystore"), e.getMessage());
    }

    // ---- Client-Zertifikat aus PEM-Dateien ----

    @Test
    @DisplayName("PEM-Paar wird in einen PKCS12-Keystore uebersetzt, den der Treiber lesen kann")
    void pemPaarWirdInKeystoreUebersetzt() throws Exception {
        Properties driverProperties = new Properties();
        MariaDbSslConfig.from(props(
                "maria.ssl.mode", "verify-full",
                "maria.ssl.client.cert", clientCertPem.toString(),
                "maria.ssl.client.key", clientKeyPem.toString())).applyTo(driverProperties);

        Configuration c = Configuration.parse(URL, driverProperties);
        assertNotNull(c.keyStore());
        Path generated = Path.of(c.keyStore());
        assertTrue(Files.isReadable(generated), "Keystore-Datei fehlt: " + generated);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(generated)) {
            keyStore.load(in, c.keyStorePassword().toCharArray());
        }
        assertTrue(keyStore.isKeyEntry("client"), "kein Schluesseleintrag im erzeugten Keystore");
        assertNotNull(keyStore.getKey("client", c.keyStorePassword().toCharArray()));
        assertEquals("CN=migrape-test",
                ((java.security.cert.X509Certificate) keyStore.getCertificate("client"))
                        .getSubjectX500Principal().getName());
    }

    @Test
    void pemPaarBrauchtBeideDateien() {
        assertThrows(IllegalArgumentException.class, () -> MariaDbSslConfig.from(props(
                "maria.ssl.mode", "verify-full",
                "maria.ssl.client.cert", clientCertPem.toString())));
        assertThrows(IllegalArgumentException.class, () -> MariaDbSslConfig.from(props(
                "maria.ssl.mode", "verify-full",
                "maria.ssl.client.key", clientKeyPem.toString())));
    }

    @Test
    void keystoreUndPemPaarSchliessenSichAus() {
        assertThrows(IllegalArgumentException.class, () -> MariaDbSslConfig.from(props(
                "maria.ssl.mode", "verify-full",
                "maria.ssl.keystore", clientP12.toString(),
                "maria.ssl.client.cert", clientCertPem.toString(),
                "maria.ssl.client.key", clientKeyPem.toString())));
    }

    @Test
    @DisplayName("PKCS#1-Schluessel wird mit Konvertierungshinweis abgelehnt")
    void pkcs1SchluesselWirdAbgelehnt() throws Exception {
        Path pkcs1 = certDir.resolve("client-key-pkcs1.pem");
        Files.writeString(pkcs1, "-----BEGIN RSA PRIVATE KEY-----\nMIIB\n-----END RSA PRIVATE KEY-----\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MariaDbSslConfig.from(props(
                        "maria.ssl.mode", "verify-full",
                        "maria.ssl.client.cert", clientCertPem.toString(),
                        "maria.ssl.client.key", pkcs1.toString())));
        assertTrue(e.getMessage().contains("openssl pkcs8"), e.getMessage());
    }

    @Test
    void kaputtesPemWirdGemeldet() throws Exception {
        Path broken = certDir.resolve("kaputt.pem");
        Files.writeString(broken, "-----BEGIN PRIVATE KEY-----\nkein base64 !!!\n-----END PRIVATE KEY-----\n");
        assertThrows(IllegalArgumentException.class, () -> MariaDbSslConfig.from(props(
                "maria.ssl.mode", "verify-full",
                "maria.ssl.client.cert", clientCertPem.toString(),
                "maria.ssl.client.key", broken.toString())));
    }

    // ---- Protokolle, Ciphers, freie Optionen ----

    @Test
    void protokolleUndCiphersWerdenUebergeben() throws Exception {
        Configuration c = driverConfig(props(
                "maria.ssl.mode", "verify-full",
                "maria.ssl.protocols", "TLSv1.2,TLSv1.3",
                "maria.ssl.ciphers", "TLS_AES_256_GCM_SHA384"));
        assertEquals("TLSv1.2,TLSv1.3", c.enabledSslProtocolSuites());
        assertEquals("TLS_AES_256_GCM_SHA384", c.enabledSslCipherSuites());
    }

    @Test
    @DisplayName("maria.option.<name> landet unveraendert beim Treiber")
    void freieTreiberOptionen() throws Exception {
        Configuration c = driverConfig(props(
                "maria.ssl.mode", "trust",
                "maria.option.connectTimeout", "9000"));
        assertEquals(9000, c.connectTimeout());
    }

    // ---- Fehlkonfigurationen ----

    @Test
    @DisplayName("Client-Zertifikat bei abgeschaltetem SSL ist ein Konfigurationsfehler")
    void disableMitZertifikatIstFehler() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MariaDbSslConfig.from(props(
                        "maria.ssl.mode", "disable",
                        "maria.ssl.keystore", clientP12.toString())));
        assertTrue(e.getMessage().contains("disable"), e.getMessage());
    }

    @Test
    @DisplayName("Tippfehler in einem maria.ssl.-Schluessel wird gemeldet statt ignoriert")
    void unbekannterSchluesselWirdGemeldet() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MariaDbSslConfig.from(props("maria.ssl.sslmode", "verify-full")));
        assertTrue(e.getMessage().contains("maria.ssl.mode"), e.getMessage());
    }

    @Test
    @DisplayName("Leere Werte gelten als nicht gesetzt")
    void leereWerteWerdenIgnoriert() throws Exception {
        Configuration c = driverConfig(props(
                "maria.ssl.mode", "",
                "maria.ssl.ca", "  ",
                "maria.ssl.keystore", ""));
        assertNull(c.serverSslCert());
        assertNull(c.keyStore());
        assertEquals(SslMode.DISABLE, c.sslMode(), "Treiber-Default bleibt unberuehrt");
    }

    @Test
    @DisplayName("Optionen in maria.url haben Vorrang vor der Konfigurationsdatei")
    void urlOptionenHabenVorrang() throws Exception {
        Properties driverProperties = new Properties();
        MariaDbSslConfig.from(props("maria.ssl.mode", "trust")).applyTo(driverProperties);
        Configuration c = Configuration.parse(URL + "?sslMode=verify-full", driverProperties);
        assertEquals(SslMode.VERIFY_FULL, c.sslMode());
    }

    @Test
    @DisplayName("Die Schluessel kommen aus config.properties bei MigrationConfig an")
    void verdrahtungMitMigrationConfig() throws Exception {
        // In .properties-Dateien ist der Backslash ein Escape-Zeichen -- Windows-Pfade
        // deshalb mit Schraegstrichen schreiben.
        MigrationConfig config = TestConfigs.fromContent("""
                h2.url=jdbc:h2:mem:ssl-verdrahtung
                maria.url=%s
                maria.user=root
                maria.ssl.mode=verify-ca
                maria.ssl.ca=%s
                """.formatted(URL, clientCertPem.toString().replace('\\', '/')));

        Properties driverProperties = new Properties();
        config.mariaSsl.applyTo(driverProperties);
        assertEquals(SslMode.VERIFY_CA, Configuration.parse(URL, driverProperties).sslMode());
        assertTrue(config.mariaSsl.describe().contains("verify-ca"), config.mariaSsl.describe());
    }
}
