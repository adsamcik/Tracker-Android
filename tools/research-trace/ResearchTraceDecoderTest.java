import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Standalone contract test; run with javac/java as documented in README.md. */
public final class ResearchTraceDecoderTest {
    public static void main(String[] args) throws Exception {
        char[] passphrase = "correct horse battery staple".toCharArray();
        byte[] plaintext = ("{\"recordType\":\"manifest\",\"schemaVersion\":3,"
                + "\"evidenceCapabilities\":{\"rawPressureEvents\":false,"
                + "\"canonicalSegmentationObservations\":false},"
                + "\"loss\":{\"lossOccurred\":false,\"replayComplete\":false}}\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] envelope = envelope(plaintext, passphrase);

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        ResearchTraceDecoder.decrypt(new ByteArrayInputStream(envelope), decoded, passphrase);
        if (!Arrays.equals(plaintext, decoded.toByteArray())) {
            throw new AssertionError("Research trace decoder round trip failed");
        }

        envelope[envelope.length - 1] ^= 1;
        try {
            ResearchTraceDecoder.decrypt(
                    new ByteArrayInputStream(envelope),
                    new ByteArrayOutputStream(),
                    passphrase
            );
            throw new AssertionError("Tampered research trace was accepted");
        } catch (IOException expected) {
            // CipherInputStream reports a failed GCM tag as an IOException.
        } finally {
            Arrays.fill(passphrase, '\0');
        }
        System.out.println("ResearchTraceDecoderTest: OK");
    }

    private static byte[] envelope(byte[] plaintext, char[] passphrase) throws Exception {
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) (i + 1);
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (32 + i);
        int iterations = 100_000;

        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(headerBytes);
        header.write("TRKTRACE".getBytes(StandardCharsets.US_ASCII));
        header.writeByte(1);
        header.writeByte(1);
        header.writeByte(1);
        header.writeByte(salt.length);
        header.writeByte(nonce.length);
        header.writeInt(iterations);
        header.write(salt);
        header.write(nonce);
        header.flush();

        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .getEncoded();
        spec.clearPassword();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(headerBytes.toByteArray());
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            result.write(headerBytes.toByteArray());
            result.write(encrypted);
            return result.toByteArray();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
