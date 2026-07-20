import java.io.Console;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Dependency-free JVM decoder for Tracker's debug research-trace envelope. */
public final class ResearchTraceDecoder {
    private static final byte[] MAGIC = "TRKTRACE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int KDF_PBKDF2_SHA256 = 1;
    private static final int CIPHER_AES_256_GCM = 1;
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 10_000_000;

    private ResearchTraceDecoder() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java ResearchTraceDecoder.java TRACE.trackertrace > trace.ndjson");
            System.exit(2);
        }

        char[] passphrase = readPassphrase();
        try (InputStream input = new FileInputStream(args[0])) {
            decrypt(input, System.out, passphrase);
            System.out.flush();
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    /**
     * Streams authenticated plaintext to {@code plaintext} without creating a temporary file.
     *
     * Authentication completes only when this method reaches EOF successfully. A caller writing to
     * a persistent destination should discard that destination if an exception is thrown.
     */
    public static void decrypt(
            InputStream encrypted,
            OutputStream plaintext,
            char[] passphrase
    ) throws IOException, GeneralSecurityException {
        DataInputStream data = new DataInputStream(encrypted);
        byte[] magic = readExactly(data, MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("Not a Tracker research trace");

        int version = data.readUnsignedByte();
        int kdf = data.readUnsignedByte();
        int cipherId = data.readUnsignedByte();
        int saltLength = data.readUnsignedByte();
        int nonceLength = data.readUnsignedByte();
        int iterations = data.readInt();
        if (version != VERSION || kdf != KDF_PBKDF2_SHA256 || cipherId != CIPHER_AES_256_GCM) {
            throw new IOException("Unsupported research trace envelope");
        }
        if (saltLength != SALT_LENGTH || nonceLength != NONCE_LENGTH) {
            throw new IOException("Invalid research trace salt or nonce length");
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IOException("Unsafe or unreasonable PBKDF2 iteration count");
        }

        byte[] salt = readExactly(data, saltLength);
        byte[] nonce = readExactly(data, nonceLength);
        byte[] header = header(magic, version, kdf, cipherId, salt, nonce, iterations);
        byte[] keyBytes = deriveKey(passphrase, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce)
            );
            cipher.updateAAD(header);
            CipherInputStream decrypted = new CipherInputStream(data, cipher);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = decrypted.read(buffer)) != -1) {
                plaintext.write(buffer, 0, read);
            }
            plaintext.flush();
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static char[] readPassphrase() {
        Console console = System.console();
        if (console != null) {
            char[] value = console.readPassword("Research trace passphrase: ");
            if (value != null && value.length > 0) return value;
        }
        String fromEnvironment = System.getenv("TRACKER_TRACE_PASSPHRASE");
        if (fromEnvironment == null || fromEnvironment.isEmpty()) {
            throw new IllegalArgumentException(
                    "No console available; set TRACKER_TRACE_PASSPHRASE for non-interactive use"
            );
        }
        return fromEnvironment.toCharArray();
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec specification = new PBEKeySpec(passphrase, salt, iterations, AES_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } finally {
            specification.clearPassword();
        }
    }

    private static byte[] header(
            byte[] magic,
            int version,
            int kdf,
            int cipher,
            byte[] salt,
            byte[] nonce,
            int iterations
    ) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.write(magic);
        output.writeByte(version);
        output.writeByte(kdf);
        output.writeByte(cipher);
        output.writeByte(salt.length);
        output.writeByte(nonce.length);
        output.writeInt(iterations);
        output.write(salt);
        output.write(nonce);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] value = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(value, offset, length - offset);
            if (read == -1) throw new IOException("Truncated research trace header");
            offset += read;
        }
        return value;
    }
}
