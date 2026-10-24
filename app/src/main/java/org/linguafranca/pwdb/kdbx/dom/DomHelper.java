/*
 * Copyright 2015 Jo Rabin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.linguafranca.pwdb.kdbx.dom;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.linguafranca.pwdb.kdbx.Helpers;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The class contains static helper methods for access to the underlying XML DOM
 *
 * @author jo
 */
public class DomHelper {

    public static XPath xpath = XPathFactory.newInstance().newXPath();

    static final String GROUP_ELEMENT_NAME = "Group";
    static final String ENTRY_ELEMENT_NAME = "Entry";
    static final String ICON_ELEMENT_NAME = "IconID";
    static final String UUID_ELEMENT_NAME = "UUID";
    static final String NAME_ELEMENT_NAME = "Name";
    static final String NOTES_ELEMENT_NAME = "Notes";
    static final String TIMES_ELEMENT_NAME = "Times";
    static final String IS_EXPANDED = "IsExpanded";

    static final String HISTORY_ELEMENT_NAME = "History";

    static final String LAST_MODIFICATION_TIME_ELEMENT_NAME = "Times/LastModificationTime";
    static final String CREATION_TIME_ELEMENT_NAME = "Times/CreationTime";
    static final String LAST_ACCESS_TIME_ELEMENT_NAME = "Times/LastAccessTime";
    static final String EXPIRY_TIME_ELEMENT_NAME = "Times/ExpiryTime";
    static final String EXPIRES_ELEMENT_NAME = "Times/Expires";
    static final String USAGE_COUNT_ELEMENT_NAME = "Times/UsageCount";
    static final String LOCATION_CHANGED = "Times/LocationChanged";

    static final String PROPERTY_ELEMENT_FORMAT = "String[Key/text()='%s']";
    static final String BINARY_PROPERTY_ELEMENT_FORMAT = "Binary[Key/text()='%s']";
    static final String VALUE_ELEMENT_NAME = "Value";

    static final String RECYCLE_BIN_UUID_ELEMENT_NAME = "RecycleBinUuid";
    static final String RECYCLE_BIN_ENABLED_ELEMENT_NAME = "RecycleBinEnabled";
    static final String RECYCLE_BIN_CHANGED_ELEMENT_NAME = "RecycleBinChanged";

    interface ValueCreator {
        String getValue();
    }

    static class ConstantValueCreator implements ValueCreator {
        String value;
        ConstantValueCreator(String value) {
            this.value = value;
        }
        @Override
        public String getValue() {
            return value;
        }
    }

    static class DateValueCreator implements ValueCreator {
        @Override
        public String getValue() {
            return Helpers.fromDate(Date.from(Instant.now()));
        }
    }

    static class UuidValueCreator implements ValueCreator {
        @Override
        public String getValue() {
            return base64RandomUuid();
        }

    }

    static void ensureElements (Element element, Map<String, ValueCreator> childElements) {
        for (Map.Entry<String, ValueCreator> entry: childElements.entrySet()) {
            ensureElementContent(entry.getKey(), element, entry.getValue().getValue());
        }
    }


    // PassProtect 加的：簡單的路徑（"Times/LastModificationTime"、"String[Key/text()='Title']"、"."）
    // 直接走 DOM 子節點，不交給 XPath。javax.xml.xpath 每次 evaluate 的成本跟整份文件一樣大，
    // 讀每一筆記錄的每一個欄位都付一次，記錄一多解鎖就是平方成長（電腦上 300 筆 36 秒）。
    // 其他寫法（"//"、屬性、別的條件）照舊交給 XPath。結果必須跟 XPath 一模一樣，見
    // DomHelperSimplePathTest。
    private static final Pattern KEYED_CHILD = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\[Key/text\\(\\)='([^']*)'\\]");
    private static final Pattern CHILD_PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(/[A-Za-z_][A-Za-z0-9_]*)*");

    /** 簡單路徑的結果（文件順序）；不是簡單路徑、或碰到 XPath 會另作解讀的內容時回傳 null。 */
    @Nullable
    static List<Element> findSimple(String path, Element parent) {
        if (".".equals(path)) {
            return Collections.singletonList(parent);
        }
        Matcher keyed = KEYED_CHILD.matcher(path);
        if (keyed.matches()) {
            List<Element> result = new ArrayList<>();
            for (Element child : childElements(parent, keyed.group(1))) {
                for (Element key : childElements(child, "Key")) {
                    // XPath 的 text() 把相鄰的文字／CDATA 節點當成同一段，沒有文字節點就沒有 text()。
                    // Key 底下混了別種節點時，比對方式不一樣，交回給 XPath。
                    if (!hasOnlyText(key)) {
                        return null;
                    }
                    if (key.hasChildNodes() && key.getTextContent().equals(keyed.group(2))) {
                        result.add(child);
                        break;
                    }
                }
            }
            return result;
        }
        if (CHILD_PATH.matcher(path).matches()) {
            List<Element> current = Collections.singletonList(parent);
            for (String name : path.split("/")) {
                List<Element> next = new ArrayList<>();
                for (Element element : current) {
                    next.addAll(childElements(element, name));
                }
                current = next;
            }
            return current;
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(name)) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private static boolean hasOnlyText(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            short type = child.getNodeType();
            if (type != Node.TEXT_NODE && type != Node.CDATA_SECTION_NODE) {
                return false;
            }
        }
        return true;
    }

    @Nullable @Contract("_,_,true -> !null")
    static  Element getElement(String elementPath, Element parentElement, boolean create) {
        try {
            List<Element> simple = findSimple(elementPath, parentElement);
            Element result = simple != null
                    ? (simple.isEmpty() ? null : simple.get(0))
                    : (Element) xpath.evaluate(elementPath, parentElement, XPathConstants.NODE);
            if (result == null && create) {
                result = createHierarchically(elementPath, parentElement);
            }
            return result;
        } catch (XPathExpressionException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean removeElement(String elementPath, Element parentElement) {
        Element toRemove = getElement(elementPath, parentElement, false);
        if (toRemove == null) {
            return false;
        } else {
            toRemove.getParentNode().removeChild(toRemove);
            return true;
        }
    }

    static List<Element> getElements (String elementPath, Element parentElement) {
        List<Element> simple = findSimple(elementPath, parentElement);
        if (simple != null) {
            return simple;
        }
        try {
            NodeList nodes = (NodeList) xpath.evaluate(elementPath, parentElement, XPathConstants.NODESET);
            ArrayList<Element> result = new ArrayList<>(nodes.getLength());
            for (int i = 0; i < nodes.getLength(); i++) {
                result.add(((Element) nodes.item(i)));
            }
            return result;
        } catch (XPathExpressionException e) {
            throw new IllegalStateException(e);
        }
    }

    static int getElementsCount (String elementPath, Element parentElement) {
        List<Element> simple = findSimple(elementPath, parentElement);
        if (simple != null) {
            return simple.size();
        }
        try {
            NodeList nodes = (NodeList) xpath.evaluate(elementPath, parentElement, XPathConstants.NODESET);
            return nodes.getLength();
        } catch (XPathExpressionException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    static Element newElement(String elementName, Element parentElement) {
        Element newElement = parentElement.getOwnerDocument().createElement(elementName);
        parentElement.appendChild(newElement);
        return newElement;
    }

    @Nullable
    public static String getElementContent(String elementPath, Element parentElement) {
        Element result = getElement(elementPath, parentElement, false);
        return (result == null) ? null : result.getTextContent();
    }

    @NotNull
    public static String ensureElementContent(String elementPath, Element parentElement, @NotNull String value) {
        Element result = getElement(elementPath, parentElement, false);
        if (result == null) {
            result = createHierarchically(elementPath, parentElement);
        }
        result.setTextContent(value);
        return result.getTextContent();
    }

    @NotNull
    public static Element setElementContent(String elementPath, Element parentElement, String value) {
        Element result = getElement(elementPath, parentElement, true);
        result.setTextContent(value);
        return result;
    }

    @Nullable
    static byte[] getBinaryElementContent(String elementPath, Element parentElement) {
        Element result = getElement(elementPath, parentElement, false);
        if (result == null) {
            return null;
        }
        String id = result.getAttribute("Ref");
        Element content = getElement("//Binaries/Binary[@ID=" + id + "]", parentElement.getOwnerDocument().getDocumentElement(),false);
        if (content == null) {
            throw new IllegalStateException("Could not find binary content with ID " + id);
        }
        return Helpers.decodeBase64Content(content.getTextContent().getBytes(), content.hasAttribute("Compressed"));
    }

    @NotNull
    static Element setBinaryElementContent(String elementPath, Element parentElement, byte[] value) {
        try {
            String b64 = Helpers.encodeBase64Content(value, true);

            //Find the highest numbered existing content
            String max = xpath.evaluate("//Binaries/Binary/@ID[not(. < ../../Binary/@ID)][1]", parentElement.getOwnerDocument().getDocumentElement());
            if (max.trim().isEmpty()){
                max = "-1";
            }
            Integer newIndex = Integer.valueOf(max) + 1;

            addBinary(parentElement.getOwnerDocument().getDocumentElement(), b64, newIndex);

            Element result = getElement(elementPath, parentElement, true);
            result.setAttribute("Ref", newIndex.toString());


            return result;

        } catch (XPathExpressionException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Add a binary property value to the V3 Meta/Binaries element
     *
     * @param documentElement the document element
     * @param b64 a base64 gzipped encoded representation of the binary content
     * @param index the index by which it is known
     */
    public static void addBinary(Element documentElement, String b64, Integer index) {
        Element binaries = getElement("Meta/Binaries", documentElement,true);
        Element binary = (Element) binaries.appendChild(binaries.getOwnerDocument().createElement("Binary"));
        binary.setTextContent(b64);
        binary.setAttribute("Compressed", "True");
        binary.setAttribute("ID", index.toString());
    }

    public static String getBinary(Element documentElement, Integer index) {
        Element binaries = getElement("Meta/Binaries", documentElement,false);
        if (Objects.isNull(binaries)){
            throw new IllegalArgumentException("No binaries found");
        }
        for (int i = 0; i < binaries.getChildNodes().getLength(); i++){
            if (((Element) binaries.getChildNodes().item(i)).getAttribute("ID").equals(index.toString())) {
                return ((Element) binaries.getChildNodes().item(i)).getTextContent();
            }
        }
        throw new IllegalArgumentException("No binary with that index found");
    }

    public static int getBinaryCount(Element documentElement) {
        Element binaries = getElement("Meta/Binaries", documentElement,false);
        if (Objects.isNull(binaries)) {
            return 0;
        }
        return binaries.getChildNodes().getLength();
    }

    @NotNull
    static Element touchElement(String elementPath, Element parentElement) {
        return setElementContent(elementPath, parentElement, Helpers.fromDate(new Date()));
    }

    private static Element createHierarchically(String elementPath, Element startElement) {
        Element currentElement = startElement;
        for (String elementName : elementPath.split("/")) {
            try {
                List<Element> simple = findSimple(elementName, currentElement);
                Element nextElement = simple != null
                        ? (simple.isEmpty() ? null : simple.get(0))
                        : (Element) xpath.evaluate(elementName, currentElement, XPathConstants.NODE);
                if (nextElement == null) {
                    nextElement = (Element) currentElement.appendChild(currentElement.getOwnerDocument().createElement(elementName));
                }
                currentElement = nextElement;
            } catch (XPathExpressionException e) {
                throw new IllegalStateException(e);
            }
        }
        return currentElement;
    }

    static String base64RandomUuid () {
        return Helpers.base64FromUuid(UUID.randomUUID());
    }

}
