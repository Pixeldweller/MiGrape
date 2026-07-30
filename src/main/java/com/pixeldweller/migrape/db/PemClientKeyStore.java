package com.pixeldweller.migrape.db;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/** Baut aus einem PEM-Paar (Client-Zertifikat + privater Schluessel) einen PKCS12-Keystore.
 *
 *  MariaDB liefert Client-Zertifikate ueblicherweise als PEM aus (client-cert.pem /
 *  client-key.pem), der JDBC-Treiber kann sie aber nur aus einer Keystore-Datei lesen.
 *  Deshalb wird ein Keystore mit Zufallspasswort in eine temporaere Datei geschrieben, die
 *  beim Beenden der JVM geloescht wird. Die Datei liegt im Temp-Verzeichnis des Benutzers und
 *  wird von {@link Files#createTempFile} mit auf den Benutzer beschraenkten Rechten angelegt.
 *
 *  Wer den privaten Schluessel nicht als temporaere Datei ablegen will, kann stattdessen einen
 *  eigenen Keystore per maria.ssl.keystore angeben. */
final class PemClientKeyStore {

    /** Erzeugter Keystore samt zugehoerigem (nur im Speicher generierten) Passwort. */
    record Result(Path file, String password) {
    }

    private PemClientKeyStore() {
    }

    static Result build(Path certFile, Path keyFile, String keyFilePassword) {
        try {
            Certificate[] chain = readCertificateChain(certFile);
            PrivateKey privateKey = readPrivateKey(keyFile, keyFilePassword);

            String password = new BigInteger(160, new SecureRandom()).toString(36);
            char[] pw = password.toCharArray();

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("client", privateKey, pw, chain);

            Path file = Files.createTempFile("migrape-client-cert", ".p12");
            file.toFile().deleteOnExit();
            try (OutputStream out = Files.newOutputStream(file)) {
                keyStore.store(out, pw);
            }
            return new Result(file, password);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException(
                    "Client-Zertifikat konnte nicht aus " + certFile + " / " + keyFile
                            + " geladen werden: " + e.getMessage(), e);
        }
    }

    private static Certificate[] readCertificateChain(Path certFile)
            throws IOException, GeneralSecurityException {
        try (InputStream in = Files.newInputStream(certFile)) {
            Collection<? extends Certificate> certs =
                    CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certs.isEmpty()) {
                throw new IllegalArgumentException("Keine Zertifikate in " + certFile + " gefunden.");
            }
            return certs.toArray(new Certificate[0]);
        }
    }

    private static PrivateKey readPrivateKey(Path keyFile, String password)
            throws IOException, GeneralSecurityException {
        String pem = Files.readString(keyFile, StandardCharsets.UTF_8);

        if (pem.contains("BEGIN RSA PRIVATE KEY") || pem.contains("BEGIN EC PRIVATE KEY")
                || pem.contains("BEGIN DSA PRIVATE KEY")) {
            throw new IllegalArgumentException(keyFile + " liegt im alten PKCS#1-Format vor. "
                    + "Java liest nur PKCS#8; einmalig konvertieren mit: "
                    + "openssl pkcs8 -topk8 -nocrypt -in " + keyFile.getFileName() + " -out client-key-pkcs8.pem");
        }

        byte[] der = decodePemBody(pem, keyFile);
        KeySpec keySpec;
        if (pem.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            if (password == null) {
                throw new IllegalArgumentException("Der Schluessel in " + keyFile
                        + " ist passwortgeschuetzt -- bitte maria.ssl.client.key.password setzen.");
            }
            EncryptedPrivateKeyInfo encrypted = new EncryptedPrivateKeyInfo(der);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(encrypted.getAlgName());
            Key pbeKey = factory.generateSecret(new PBEKeySpec(password.toCharArray()));
            Cipher cipher = Cipher.getInstance(encrypted.getAlgName());
            cipher.init(Cipher.DECRYPT_MODE, pbeKey, encrypted.getAlgParameters());
            keySpec = encrypted.getKeySpec(cipher);
        } else {
            keySpec = new PKCS8EncodedKeySpec(der);
        }

        // Der PEM-Rahmen nennt das Schluesselverfahren nicht, also durchprobieren.
        for (String algorithm : List.of("RSA", "EC", "DSA", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (GeneralSecurityException ignored) {
                // naechstes Verfahren versuchen
            }
        }
        throw new IllegalArgumentException("Schluesseltyp in " + keyFile
                + " nicht unterstuetzt (erwartet RSA, EC, DSA oder Ed25519 im PKCS#8-Format).");
    }

    private static byte[] decodePemBody(String pem, Path keyFile) {
        StringBuilder base64 = new StringBuilder();
        for (String line : pem.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("-----")) {
                base64.append(trimmed);
            }
        }
        if (base64.length() == 0) {
            throw new IllegalArgumentException("Kein PEM-Inhalt in " + keyFile + " gefunden.");
        }
        try {
            return Base64.getDecoder().decode(base64.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    keyFile + " ist kein gueltiges PEM (Base64 nicht lesbar): " + e.getMessage(), e);
        }
    }
}
