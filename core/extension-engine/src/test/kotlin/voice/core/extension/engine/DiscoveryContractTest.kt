package voice.core.extension.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryContractTest {
  @Test
  fun absentCategoriesUseCompatibilityModeButEmptyCategoriesStayEmpty() {
    assertNull(SourceContract.parseDiscoveryCategories("null"))
    assertEquals(emptyList(), SourceContract.parseDiscoveryCategories("[]"))
  }

  @Test
  fun categoriesRequireIdsAndTitlesAndRemoveDuplicateIds() {
    val result = SourceContract.parseDiscoveryCategories(
      """[{"id":"hot","title":"热榜"},{"id":"hot","title":"重复"}]""",
    )!!
    assertEquals(listOf(DiscoveryCategoryItem("hot", "热榜")), result)
    assertFailsWith<SourceContractException> { SourceContract.parseDiscoveryCategories("""[{"id":"hot"}]""") }
  }

  @Test
  fun discoveryReusesBookContractAndPreservesChapterExtras() {
    val result = SourceContract.parseDiscoveryPage(
      """{"books":[{"id":"7","bookTitle":"书名","albumId":"AL-7"}],"hasMore":true}""",
    )
    assertTrue(result.hasMore)
    assertEquals("书名", result.books.single().title)
    assertEquals("\"AL-7\"", result.books.single().extra["albumId"].toString())
    assertFalse(SourceContract.parseDiscoveryPage("""{"books":[]}""").hasMore)
  }

  @Test
  fun malformedPageFailsRatherThanLookingLikeEndOfResults() {
    listOf("[]", "{}", """{"books":[],"hasMore":"true"}""", """{"books":[{"id":"7"}]}""").forEach {
      assertFailsWith<SourceContractException> { SourceContract.parseDiscoveryPage(it) }
    }
  }
}
