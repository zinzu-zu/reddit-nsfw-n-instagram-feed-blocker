import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Signs an APK with APK Signature Scheme v2 using apksig, then checks the signature
 * and that every stored (uncompressed) entry is 4-byte aligned, which Android requires
 * for resources.arsc. The app needs Android 10 or newer, so the old v1 (JAR) signature
 * isn't needed; this apksig version can't make one on current JDKs anyway.
 *
 * Usage: java -cp apksig.jar:. SignApk keystore.p12 alias in.apk out.apk
 * The keystore password comes from the KEYSTORE_PASS environment variable.
 */
public final class SignApk {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: SignApk keystore.p12 alias in.apk out.apk");
            System.exit(2);
        }
        String pass = System.getenv("KEYSTORE_PASS");
        if (pass == null) {
            System.err.println("Set KEYSTORE_PASS.");
            System.exit(2);
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(args[0])) {
            ks.load(in, pass.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(args[1], pass.toCharArray());
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        for (Certificate c : ks.getCertificateChain(args[1])) certs.add((X509Certificate) c);

        File out = new File(args[3]);
        ApkSigner.SignerConfig signer =
                new ApkSigner.SignerConfig.Builder("CERT", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new File(args[2]))
                .setOutputApk(out)
                .setMinSdkVersion(29)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .build()
                .sign();

        ApkVerifier.Result result = new ApkVerifier.Builder(out).build().verify();
        if (!result.isVerified()) {
            System.err.println("Signature check failed: " + result.getErrors());
            System.exit(1);
        }
        checkAlignment(out);
        if (!result.isVerifiedUsingV2Scheme()) {
            System.err.println("No valid v2 signature.");
            System.exit(1);
        }
        System.out.println("Signed (APK Signature Scheme v2), verified and aligned: " + out);
    }

    /** Fails if any stored entry's data doesn't start on a 4-byte boundary. */
    private static void checkAlignment(File apk) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            long len = f.length();
            int tail = (int) Math.min(len, 65_557);
            byte[] buf = new byte[tail];
            f.seek(len - tail);
            f.readFully(buf);
            ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (b.getInt(i) == 0x06054b50) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) throw new IllegalStateException("No end of central directory");
            int entries = b.getShort(eocd + 10) & 0xffff;
            long cdOffset = b.getInt(eocd + 16) & 0xffffffffL;
            f.seek(cdOffset);
            for (int n = 0; n < entries; n++) {
                byte[] hdr = new byte[46];
                f.readFully(hdr);
                ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
                if (h.getInt(0) != 0x02014b50) throw new IllegalStateException("Bad central directory");
                int method = h.getShort(10) & 0xffff;
                int nameLen = h.getShort(28) & 0xffff;
                int extraLen = h.getShort(30) & 0xffff;
                int commentLen = h.getShort(32) & 0xffff;
                long lfh = h.getInt(42) & 0xffffffffL;
                byte[] name = new byte[nameLen];
                f.readFully(name);
                long next = f.getFilePointer() + extraLen + commentLen;
                if (method == 0) {
                    byte[] l = new byte[30];
                    f.seek(lfh);
                    f.readFully(l);
                    ByteBuffer lb = ByteBuffer.wrap(l).order(ByteOrder.LITTLE_ENDIAN);
                    long data = lfh + 30 + (lb.getShort(26) & 0xffff) + (lb.getShort(28) & 0xffff);
                    if (data % 4 != 0) {
                        throw new IllegalStateException("Stored entry not aligned: "
                                + new String(name, "UTF-8") + " at " + data);
                    }
                }
                f.seek(next);
            }
        }
    }
}
