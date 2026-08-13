package com.example.kdbxspike;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import static org.junit.Assert.*;

/**
 * The library's save() does `doc.cloneNode(true)` on a Document, which Android's
 * Harmony DOM refuses ("Cannot copy node of type 9"). Is there a workaround?
 */
@RunWith(AndroidJUnit4.class)
public class DomCopyTest {

    private static final String TAG = "KDBXSPIKE";
    private static final String XML =
            "<KeePassFile><Root><Group><Entry>"
            + "<String><Key>Password</Key><Value>提示：貓的名字</Value></String>"
            + "</Entry></Group></Root></KeePassFile>";

    private Document parse() throws Exception {
        DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(XML.getBytes("UTF-8")));
    }

    @Test
    public void cloneNodeOnDocumentFailsAsExpected() throws Exception {
        Document doc = parse();
        try {
            doc.cloneNode(true);
            Log.i(TAG, "COPY: cloneNode(Document) unexpectedly SUCCEEDED");
        } catch (Exception ex) {
            Log.i(TAG, "COPY: cloneNode(Document) fails -> " + ex.getMessage()
                    + "   [confirms the bug]");
        }
    }

    @Test
    public void importNodeWorkaroundWorks() throws Exception {
        Document doc = parse();

        // The proposed fix: fresh Document + importNode of the root element
        DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document copy = b.newDocument();
        copy.appendChild(copy.importNode(doc.getDocumentElement(), true));

        // must be a real deep copy: mutating the copy must not touch the original
        copy.getDocumentElement().setAttribute("mutated", "yes");
        assertFalse("copy is not independent",
                doc.getDocumentElement().hasAttribute("mutated"));

        // and it must still serialise
        Transformer t = TransformerFactory.newInstance().newTransformer();
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(copy), new StreamResult(w));
        String out = w.toString();

        Log.i(TAG, "COPY: importNode workaround PASS, deep=" + out.contains("提示：貓的名字")
                + " independent=true");
        assertTrue(out.contains("提示：貓的名字"));
        assertTrue(out.contains("mutated"));
    }
}
