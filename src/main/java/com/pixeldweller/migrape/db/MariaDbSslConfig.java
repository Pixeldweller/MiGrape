package com.pixeldweller.migrape.db;

import com.pixeldweller.migrape.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** SSL/TLS-Einstellungen fuer die MariaDB-Verbindung, gelesen aus config.properties
 *  (Schluessel {@code maria.ssl.*} sowie {@code maria.option.*}).
 *
 *  Die Werte werden als JDBC-Optionen an den MariaDB-Treiber weitergegeben. Optionen, die
 *  direkt in {@code maria.url} stehen, haben beim Treiber Vorrang vor den hier gesetzten
 *  Werten -- SSL sollte daher entweder ueber die URL oder ueber diese Schluessel
 *  konfiguriert werden, nicht gemischt.
 *
 *  Ist nichts konfiguriert, wird keine einzige Option gesetzt: der Treiber verhaelt sich
 *  dann genau wie vor Einfuehrung dieser Klasse. */
public final class MariaDbSslConfig {

    private static final String SSL_PREFIX = "maria.ssl.";
    private static final String OPTION_PREFIX = "maria.option.";

    /** Erlaubte Werte fuer maria.ssl.mode -- entsprechen den sslMode-Werten des Treibers. */
    private static final List<String> MODES = List.of("disable", "trust", "verify-ca", "verify-full");

    /** Schluessel hinter {@value #SSL_PREFIX}. Alles andere ist ein Tippfehler und wird gemeldet. */
    private static final Set<String> KNOWN_SSL_KEYS = Set.of(
            "enabled", "mode", "ca",
            "keystore", "keystore.password", "keystore.type",
            "key.password",
            "client.cert", "client.key", "client.key.password",
            "protocols", "ciphers");

    /** sslMode fuer den Treiber, oder null wenn nichts konfiguriert ist. */
    private final String mode;
    /** CA-Zertifikat (PEM) zum Pruefen des Server-Zertifikats -- Treiberoption serverSslCert. */
    private final String serverSslCert;
    private final String keyStore;
    private final String keyStorePassword;
    private final String keyStoreType;
    private final String keyPassword;
    private final String protocols;
    private final String ciphers;
    private final Map<String, String> extraOptions;
    /** true, wenn der Keystore aus einem PEM-Paar erzeugt wurde (nur fuer describe()). */
    private final boolean keyStoreFromPem;

    private MariaDbSslConfig(Properties p) {
        rejectUnknownKeys(p);

        this.mode = readMode(p);
        this.serverSslCert = value(p, "ca");
        this.protocols = value(p, "protocols");
        this.ciphers = value(p, "ciphers");
        this.keyStoreType = value(p, "keystore.type");
        this.keyPassword = value(p, "key.password");
        this.extraOptions = readExtraOptions(p);

        String configuredKeyStore = value(p, "keystore");
        String clientCert = value(p, "client.cert");
        String clientKey = value(p, "client.key");

        if (configuredKeyStore != null && (clientCert != null || clientKey != null)) {
            throw new IllegalArgumentException(
                    "maria.ssl.keystore und maria.ssl.client.cert/-key schliessen sich aus: "
                            + "entweder eine fertige Keystore-Datei oder ein PEM-Paar angeben.");
        }
        if ((clientCert == null) != (clientKey == null)) {
            throw new IllegalArgumentException(
                    "Fuer ein Client-Zertifikat aus PEM-Dateien muessen maria.ssl.client.cert "
                            + "und maria.ssl.client.key beide gesetzt sein.");
        }

        if (clientCert != null) {
            requireFile(clientCert, "maria.ssl.client.cert");
            requireFile(clientKey, "maria.ssl.client.key");
            PemClientKeyStore.Result built = PemClientKeyStore.build(
                    Path.of(clientCert), Path.of(clientKey), value(p, "client.key.password"));
            this.keyStore = built.file().toString();
            this.keyStorePassword = built.password();
            this.keyStoreFromPem = true;
        } else {
            if (configuredKeyStore != null) {
                requireFile(configuredKeyStore, "maria.ssl.keystore");
            }
            this.keyStore = configuredKeyStore;
            this.keyStorePassword = value(p, "keystore.password");
            this.keyStoreFromPem = false;
        }

        if (serverSslCert != null && isFilePath(serverSslCert)) {
            requireFile(serverSslCert, "maria.ssl.ca");
        }
        validateCombination();
    }

    public static MariaDbSslConfig from(Properties p) {
        return new MariaDbSslConfig(p);
    }

    /** Setzt die konfigurierten Treiber-Optionen. Nicht konfigurierte Optionen bleiben aussen vor,
     *  damit die Treiber-Defaults bzw. Angaben in der JDBC-URL greifen. */
    public void applyTo(Properties driverProperties) {
        put(driverProperties, "sslMode", mode);
        put(driverProperties, "serverSslCert", serverSslCert);
        put(driverProperties, "keyStore", keyStore);
        put(driverProperties, "keyStorePassword", keyStorePassword);
        put(driverProperties, "keyStoreType", keyStoreType);
        put(driverProperties, "keyPassword", keyPassword);
        put(driverProperties, "enabledSslProtocolSuites", protocols);
        put(driverProperties, "enabledSslCipherSuites", ciphers);
        extraOptions.forEach(driverProperties::setProperty);
    }

    /** Kurzbeschreibung fuer das Log -- ohne Passwoerter. */
    public String describe() {
        if (mode == null && serverSslCert == null && keyStore == null && extraOptions.isEmpty()) {
            return "nicht konfiguriert (ggf. ueber Optionen in maria.url gesteuert)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("sslMode=").append(mode == null ? "<aus maria.url/Treiber-Default>" : mode);
        if (serverSslCert != null) {
            sb.append(", CA=").append(isFilePath(serverSslCert) ? serverSslCert : "<inline/classpath>");
        }
        if (keyStore != null) {
            sb.append(", Client-Zertifikat=").append(keyStoreFromPem ? "PEM-Paar" : keyStore);
        }
        if (protocols != null) {
            sb.append(", Protokolle=").append(protocols);
        }
        if (ciphers != null) {
            sb.append(", Ciphers=").append(ciphers);
        }
        if (!extraOptions.isEmpty()) {
            sb.append(", weitere Optionen=").append(new TreeSet<>(extraOptions.keySet()));
        }
        return sb.toString();
    }

    private void validateCombination() {
        boolean tlsMaterialConfigured = serverSslCert != null || keyStore != null
                || protocols != null || ciphers != null;
        if ("disable".equals(mode) && tlsMaterialConfigured) {
            throw new IllegalArgumentException(
                    "SSL ist per maria.ssl.mode=disable abgeschaltet, es sind aber SSL-Einstellungen "
                            + "(CA/Client-Zertifikat/Protokolle) konfiguriert. Bitte den Modus setzen "
                            + "(trust, verify-ca oder verify-full) oder die Einstellungen entfernen.");
        }
        if (mode == null && tlsMaterialConfigured) {
            Log.warn("SSL-Einstellungen fuer MariaDB sind gesetzt, aber maria.ssl.mode fehlt. "
                    + "Der Treiber baut die Verbindung nur verschluesselt auf, wenn sslMode in "
                    + "maria.url steht -- sonst bitte maria.ssl.mode=verify-full setzen.");
        }
    }

    private static String readMode(Properties p) {
        String raw = value(p, "mode");
        if (raw != null) {
            String normalized = raw.toLowerCase(Locale.ROOT);
            if (!MODES.contains(normalized)) {
                throw new IllegalArgumentException("Ungueltiger Wert fuer maria.ssl.mode: '" + raw
                        + "'. Erlaubt sind: " + String.join(", ", MODES));
            }
            return normalized;
        }
        String enabled = value(p, "enabled");
        if (enabled == null) {
            return null;
        }
        // Bequemer Schalter: an = volle Pruefung von Zertifikatskette und Hostname.
        return switch (enabled.toLowerCase(Locale.ROOT)) {
            case "true" -> "verify-full";
            case "false" -> "disable";
            default -> throw new IllegalArgumentException(
                    "maria.ssl.enabled muss true oder false sein (war: '" + enabled + "')");
        };
    }

    private static Map<String, String> readExtraOptions(Properties p) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith(OPTION_PREFIX)) {
                String option = name.substring(OPTION_PREFIX.length()).trim();
                if (option.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Leerer Optionsname: '" + name + "' -- erwartet wird maria.option.<name>");
                }
                options.put(option, p.getProperty(name).trim());
            }
        }
        return options;
    }

    private static void rejectUnknownKeys(Properties p) {
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith(SSL_PREFIX)) {
                String key = name.substring(SSL_PREFIX.length()).toLowerCase(Locale.ROOT);
                if (!KNOWN_SSL_KEYS.contains(key)) {
                    throw new IllegalArgumentException("Unbekannter Konfigurationsschluessel '" + name
                            + "'. Bekannt sind: " + new TreeSet<>(KNOWN_SSL_KEYS).stream()
                            .map(k -> SSL_PREFIX + k).toList()
                            + ". Beliebige weitere Treiber-Optionen gehen ueber maria.option.<name>.");
                }
            }
        }
    }

    private static String value(Properties p, String key) {
        String v = p.getProperty(SSL_PREFIX + key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Der Treiber akzeptiert fuer serverSslCert auch classpath-Verweise und PEM-Inhalt direkt. */
    private static boolean isFilePath(String certValue) {
        return !certValue.startsWith("classpath:") && !certValue.startsWith("-----");
    }

    private static void requireFile(String path, String key) {
        if (!Files.isReadable(Path.of(path))) {
            throw new IllegalArgumentException(
                    "Datei aus " + key + " nicht lesbar: " + path);
        }
    }

    private static void put(Properties props, String option, String value) {
        if (value != null) {
            props.setProperty(option, value);
        }
    }
}
