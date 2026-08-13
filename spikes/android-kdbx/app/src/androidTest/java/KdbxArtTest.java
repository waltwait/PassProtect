package com.example.kdbxspike;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.linguafranca.pwdb.kdbx.KdbxCreds;
import org.linguafranca.pwdb.kdbx.KdbxHeader;
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat;
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper;
import org.linguafranca.pwdb.security.Argon2;
import org.linguafranca.pwdb.security.VariantDictionary;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.List;

import javax.crypto.KeyGenerator;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KdbxArtTest {

    private static final String TAG = "KDBXSPIKE";
    private static final byte[] PW = "spike".getBytes();

    private static void log(String s) { Log.i(TAG, s); }

    private static KdbxStreamFormat fmt(long mib, long iter) {
        KdbxHeader h = new KdbxHeader(4);
        h.setKeyDerivationFunction(Argon2.getInstance());
        VariantDictionary d = h.getKdfParameters();
        d.putULong("M", mib * 1024 * 1024);
        d.putULong("I", iter);
        d.putUInt("P", 2);
        return new KdbxStreamFormat(h);
    }

    /** The core question: does DOM/XPath/BouncyCastle actually work on ART? */
    @Test
    public void kdbx4RoundTripOnArt() throws Exception {
        log("=== device: " + Build.MANUFACTURER + " " + Build.MODEL
                + "  API " + Build.VERSION.SDK_INT + "  " + Build.SUPPORTED_ABIS[0] + " ===");

        DomDatabaseWrapper db = new DomDatabaseWrapper();
        var e = db.newEntry();
        e.setTitle("github.com");
        e.setPassword("提示：貓的名字 + 生日");
        e.setProperty("hist.1.at", "2026-01-04T10:00:00Z");
        e.setProperty("hist.1.hint", "舊提示：國中同學的綽號");
        db.getRootGroup().addEntry(e);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        db.save(fmt(32, 2), new KdbxCreds(PW), bos);
        log("saved on device, " + bos.size() + " bytes");

        DomDatabaseWrapper loaded = DomDatabaseWrapper.load(
                new KdbxCreds(PW), new ByteArrayInputStream(bos.toByteArray()));
        var got = loaded.getRootGroup().getEntries().get(0);

        log("title       = " + got.getTitle());
        log("hint        = " + got.getPassword());
        log("hist.1.hint = " + got.getProperty("hist.1.hint"));

        assertEquals("github.com", got.getTitle());
        assertEquals("提示：貓的名字 + 生日", got.getPassword());
        assertEquals("舊提示：國中同學的綽號", got.getProperty("hist.1.hint"));
        log("ART ROUNDTRIP = PASS");
    }

    /** Read a database that KeePassXC created (KDBX 3.1 / AES-KDF). */
    @Test
    public void readsKeePassXcFile() throws Exception {
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream is = testCtx.getAssets().open("xc.kdbx")) {
            DomDatabaseWrapper db = DomDatabaseWrapper.load(
                    new KdbxCreds("xcpass".getBytes()), is);
            List<?> entries = db.findEntries(x -> true);
            log("KeePassXC file opened on device, entries = " + entries.size());
            assertEquals(1, entries.size());
        }
        log("ART READ-KEEPASSXC = PASS");
    }

    /** The number that actually drives the spec: Argon2 cost on real hardware. */
    @Test
    public void argon2CostOnRealDevice() throws Exception {
        log("=== Argon2 sweep on " + Build.MODEL + " ===");
        for (long mib : new long[]{16, 32, 64}) {
            for (long iter : new long[]{2, 4}) {
                long t = System.nanoTime();
                new DomDatabaseWrapper().save(fmt(mib, iter), new KdbxCreds(PW),
                        new ByteArrayOutputStream());
                long ms = (System.nanoTime() - t) / 1_000_000;
                log(String.format("M=%3d MiB  I=%d  ->  %5d ms", mib, iter, ms));
            }
        }
    }

    /** Does the spec's Keystore configuration actually get accepted by this hardware? */
    @Test
    public void strongBoxBiometricBoundKey() throws Exception {
        String alias = "spike_wrapping_key";
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(alias);

        boolean strongBoxOk;
        try {
            buildKey(alias, true);
            strongBoxOk = true;
        } catch (Exception ex) {
            log("StrongBox rejected: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
            strongBoxOk = false;
            buildKey(alias, false);   // fall back to TEE, as the spec requires
        }
        log("StrongBox backed        = " + strongBoxOk);

        KeyStore.Entry entry = ks.getEntry(alias, null);
        log("key present in keystore = " + (entry != null || ks.containsAlias(alias)));
        assertTrue(ks.containsAlias(alias));
        log("ART KEYSTORE = PASS (strongbox=" + strongBoxOk + ")");
        ks.deleteEntry(alias);
    }

    private void buildKey(String alias, boolean strongBox) throws Exception {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        }
        if (strongBox) {
            b.setIsStrongBoxBacked(true);
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        kg.generateKey();
    }
}
