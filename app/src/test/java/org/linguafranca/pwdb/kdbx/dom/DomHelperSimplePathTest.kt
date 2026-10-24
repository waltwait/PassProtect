package org.linguafranca.pwdb.kdbx.dom

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * [DomHelper.findSimple] 不經 XPath 直接走子節點；它對每一種它接手的路徑，結果都必須跟
 * XPath 一模一樣（同樣的節點、同樣的順序），其餘路徑交回給 XPath。
 */
class DomHelperSimplePathTest {

    private val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
        """
        <KeePassFile><Root><Group><Name>R</Name>
          <Entry>
            <UUID>abc</UUID>
            <Times><LastModificationTime>2026</LastModificationTime></Times>
            <String><Key>Title</Key><Value>a</Value></String>
            <String><Key>UserName</Key><Value>u</Value></String>
            <String><Key><![CDATA[Ti]]>tle2</Key><Value>c</Value></String>
            <String><Key></Key><Value>empty</Value></String>
            <String><Key>Title</Key><Value>dup</Value></String>
          </Entry>
          <Entry><Times/><String><Key>Title</Key><Value>b</Value></String></Entry>
        </Group></Root></KeePassFile>
        """.trimIndent().byteInputStream()
    )
    private val group = doc.getElementsByTagName("Group").item(0) as Element
    private val entry = doc.getElementsByTagName("Entry").item(0) as Element

    init {
        // 編輯過的 DOM 可能有相鄰、沒合併的文字節點；XPath 會把它們當成同一段文字。
        val key = doc.createElement("Key").apply {
            appendChild(doc.createTextNode("Ti"))
            appendChild(doc.createTextNode("tle3"))
        }
        entry.appendChild(doc.createElement("String").apply { appendChild(key) })
    }

    private fun xpath(path: String, parent: Element): List<Element> {
        val nodes = DomHelper.xpath.evaluate(path, parent, XPathConstants.NODESET) as NodeList
        return List(nodes.length) { nodes.item(it) as Element }
    }

    private fun assertSameAsXPath(path: String, parent: Element) {
        val expected = xpath(path, parent)
        val actual = DomHelper.findSimple(path, parent)
        assertEquals(path, expected.size, actual?.size)
        expected.zip(actual!!).forEach { (e, a) -> assert(e === a) { "$path：節點不同" } }
    }

    @Test
    fun keyedFieldsMatchXPath() {
        listOf(
            "String[Key/text()='Title']",
            "String[Key/text()='UserName']",
            "String[Key/text()='Title2']",
            "String[Key/text()='Title3']",
            "String[Key/text()='']",
            "String[Key/text()='Missing']"
        ).forEach { assertSameAsXPath(it, entry) }
    }

    @Test
    fun childPathsMatchXPath() {
        listOf("String", "UUID", "Times/LastModificationTime", "Times/Missing", "Value").forEach { assertSameAsXPath(it, entry) }
        listOf("Entry", "Entry/Times", "Entry/String/Key", "Name").forEach { assertSameAsXPath(it, group) }
    }

    @Test
    fun dotIsTheElementItself() = assertSameAsXPath(".", entry)

    @Test
    fun otherPathsAreLeftToXPath() {
        assertNull(DomHelper.findSimple("//Generator", entry))
        assertNull(DomHelper.findSimple("Binary[@ID='1']", entry))
        assertNull(DomHelper.findSimple("String[Value/text()='a']", entry))
    }
}
