package com.example.kdbxspike;

import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.linguafranca.pwdb.security.Argon2;
import org.linguafranca.pwdb.security.VariantDictionary;

import java.security.MessageDigest;

/** Argon2 cost on real hardware, measured directly (bypasses the broken save path). */
@RunWith(AndroidJUnit4.class)
public class Argon2CostTest {

    private static final String TAG = "KDBXSPIKE";

    @Test
    public void sweep() throws Exception {
        Log.i(TAG, "=== Argon2 on " + Build.MANUFACTURER + " " + Build.MODEL
                + " (API " + Build.VERSION.SDK_INT + ", " + Build.SUPPORTED_ABIS[0] + ") ===");

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("correct horse battery staple".getBytes("UTF-8"));

        // warm up JIT / allocator so the first row isn't misleading
        run(digest, 16, 1);

        for (long mib : new long[]{16, 32, 64, 128}) {
            for (long iter : new long[]{2, 4}) {
                long best = Long.MAX_VALUE;
                for (int rep = 0; rep < 3; rep++) {
                    best = Math.min(best, run(digest, mib, iter));
                }
                Log.i(TAG, String.format("ARGON2  M=%3d MiB  I=%d  ->  %5d ms", mib, iter, best));
            }
        }
    }

    private long run(byte[] digest, long mib, long iter) {
        VariantDictionary vd = Argon2.getInstance().createKdfParameters();
        vd.putULong("M", mib * 1024 * 1024);
        vd.putULong("I", iter);
        vd.putUInt("P", 2);
        long t = System.nanoTime();
        Argon2.getInstance().getTransformedKey(digest, vd);
        return (System.nanoTime() - t) / 1_000_000;
    }
}
