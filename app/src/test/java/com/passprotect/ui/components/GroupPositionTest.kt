package com.passprotect.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** 群組清單每一列該用哪種圓角——只有頭尾兩列有圓角，中間的列是直角接縫。 */
class GroupPositionTest {

    @Test
    fun singleRowIsOnly() = assertEquals(GroupPosition.Only, GroupPosition.of(0, 1))

    @Test
    fun firstOfThree() = assertEquals(GroupPosition.First, GroupPosition.of(0, 3))

    @Test
    fun middleOfThree() = assertEquals(GroupPosition.Middle, GroupPosition.of(1, 3))

    @Test
    fun lastOfThree() = assertEquals(GroupPosition.Last, GroupPosition.of(2, 3))
}
