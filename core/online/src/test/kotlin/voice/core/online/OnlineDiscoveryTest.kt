package voice.core.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineDiscoveryTest {
  @Test
  fun repeatedPagesStopPaginationWithoutDuplicatingBooks() {
    val book = OnlineSearchResult(source = "jdr:a", bookId = "7")
    val result = OnlineDiscovery.append(listOf(book), OnlineDiscoveryPage(listOf(book), true))
    assertEquals(listOf(book), result.books)
    assertFalse(result.hasMore)
  }

  @Test
  fun overlappingPagesKeepSourceIdentityAndOrder() {
    val first = OnlineSearchResult(source = "jdr:a", bookId = "7")
    val otherSource = first.copy(source = "jdr:b")
    val next = first.copy(bookId = "8")
    val result = OnlineDiscovery.append(listOf(first), OnlineDiscoveryPage(listOf(first, otherSource, next), true))
    assertEquals(listOf(first, otherSource, next), result.books)
    assertTrue(result.hasMore)
    assertFalse(OnlineDiscovery.append(result.books, OnlineDiscoveryPage(emptyList(), true)).hasMore)
  }
}
